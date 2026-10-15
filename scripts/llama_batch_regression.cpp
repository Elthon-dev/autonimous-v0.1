// Regression test for the libai-chat.so SIGSEGV (SEGV_MAPERR, fault 0x43) at
// Java_..._InferenceEngineImpl_processUserPrompt (ELF vaddr 0x7ea0).
//
// Root cause: decode_tokens() used llama_batch_get_one(), which returns a batch
// whose `logits` pointer is nullptr, then wrote `batch.logits[n - 1] = last`.
// The store landed at (nullptr + n - 1) == 0x43 (n == 68). A second ownership
// bug called llama_batch_free() on those borrowed batches, which would free()
// memory owned by a std::vector.
//
// This test pins the exact contract the fix relies on:
//   * llama_batch_get_one() borrows the caller's token buffer and leaves every
//     owned pointer (logits/pos/n_seq_id/seq_id/embd) as nullptr -> never write
//     to them, never pass them to llama_batch_free().
//   * llama_batch_init() allocates owned buffers -> must be freed with
//     llama_batch_free().
//   * The fixed decode pattern (chunked get_one + decode, no logits write, no
//     free) works for 1 token, 68 tokens, the batch-size boundary, multi-chunk
//     prompts, repeats, generation and a benchmark-style pass, and still yields
//     usable last-token logits (llama_sampler_sample(..., -1) succeeds).
//
// Build: g++ -std=c++17 -O2 scripts/llama_batch_regression.cpp \
//          -I<src>/include -I<src>/ggml/include -L<libdir> -lllama \
//          -Wl,-rpath,<libdir> -o llama_batch_regression
// Run:   ./llama_batch_regression [model.gguf]
//        (the pure ownership contract runs with or without a model)

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

static int g_failures = 0;

#define CHECK(cond, ...)                                                        \
    do {                                                                        \
        if (!(cond)) {                                                          \
            fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__);                \
            fprintf(stderr, __VA_ARGS__);                                       \
            fprintf(stderr, "\n");                                              \
            g_failures++;                                                       \
        }                                                                       \
    } while (0)

// ---------------------------------------------------------------------------
// 1. Pure API-ownership contract. No model required.
// ---------------------------------------------------------------------------
static void contract_get_one_borrows() {
    llama_token toks[4] = { 1, 2, 3, 4 };

    llama_batch b = llama_batch_get_one(toks, 4);

    CHECK(b.n_tokens == 4,        "get_one n_tokens=%d (want 4)", b.n_tokens);
    CHECK(b.token    == toks,     "get_one token must borrow the caller buffer");
    CHECK(b.logits   == nullptr,  "get_one logits must be nullptr (the crash: writing [n-1])");
    CHECK(b.pos      == nullptr,  "get_one pos must be nullptr (auto-tracked)");
    CHECK(b.n_seq_id == nullptr,  "get_one n_seq_id must be nullptr");
    CHECK(b.seq_id   == nullptr,  "get_one seq_id must be nullptr");
    CHECK(b.embd     == nullptr,  "get_one embd must be nullptr");

    // NOTE: deliberately NO llama_batch_free(b) here. Freeing a borrowed batch
    // is the second bug; doing so would free() a stack/std::vector pointer.
}

static void contract_init_owns() {
    llama_batch b = llama_batch_init(8, /*embd*/ 0, /*n_seq_max*/ 1);

    CHECK(b.token    != nullptr, "init token must be owned (malloc)");
    CHECK(b.pos      != nullptr, "init pos must be owned");
    CHECK(b.n_seq_id != nullptr, "init n_seq_id must be owned");
    CHECK(b.seq_id   != nullptr, "init seq_id must be owned");
    CHECK(b.logits   != nullptr, "init logits must be owned");

    // Correct ownership handling: an init batch is freed with llama_batch_free.
    llama_batch_free(b);
}

// ---------------------------------------------------------------------------
// 2. Decode matrix using the fixed pattern (mirrors decode_tokens/benchModel).
// ---------------------------------------------------------------------------
static const int BATCH_SIZE = 512;

static bool decode_chunked(llama_context * ctx, const std::vector<llama_token> & toks) {
    for (int i = 0; i < (int) toks.size(); i += BATCH_SIZE) {
        const int n = (int) toks.size() - i < BATCH_SIZE ? (int) toks.size() - i : BATCH_SIZE;

        llama_batch b = llama_batch_get_one(
            const_cast<llama_token *>(&toks[i]), n);

        // Fixed contract: never write b.logits (nullptr) and never free b.
        if (b.logits != nullptr) {
            fprintf(stderr, "FAIL: get_one returned non-null logits\n");
            return false;
        }
        if (llama_decode(ctx, b) != 0) {
            fprintf(stderr, "FAIL: llama_decode failed at i=%d n=%d\n", i, n);
            return false;
        }
    }
    return true;
}

static bool sample_once(llama_context * ctx, const llama_vocab * vocab) {
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    const llama_token id = llama_sampler_sample(smpl, ctx, -1);
    llama_sampler_free(smpl);
    // Last-token logits must exist and map to a valid token id.
    return id >= 0 && id < llama_vocab_n_tokens(vocab);
}

static void run_decode_matrix(llama_model * model, const llama_vocab * vocab) {
    std::vector<llama_token> base(64);
    const char * seed = "The quick brown fox jumps over the lazy dog. Hello, how are you today?";
    int nb = llama_tokenize(vocab, seed, (int) strlen(seed),
                            base.data(), (int) base.size(),
                            /*add_special*/ false, /*parse_special*/ false);
    CHECK(nb > 0, "tokenize seed failed");
    if (nb <= 0) return;
    base.resize((size_t) nb);
    for (int k = 0; k < (int) nb; k++) {
        CHECK(base[k] >= 0 && base[k] < llama_vocab_n_tokens(vocab), "seed token out of range");
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx    = 1024;
    cparams.n_batch  = BATCH_SIZE;
    cparams.n_threads = 2;
    cparams.n_threads_batch = 2;

    llama_context * ctx = llama_init_from_model(model, cparams);
    CHECK(ctx != nullptr, "context init failed");
    if (!ctx) return;

    // Prompt lengths: reported crash (68) and the batch-size boundary.
    const int lengths[] = { 1, 68, 511, 512, 513 };

    for (int L : lengths) {
        std::vector<llama_token> toks;
        toks.reserve((size_t) L);
        while ((int) toks.size() < L) {
            const int take = (int) toks.size() + (int) base.size() <= L
                                 ? (int) base.size()
                                 : L - (int) toks.size();
            toks.insert(toks.end(), base.begin(), base.begin() + take);
        }

        // Repeated processing: same length twice, clearing memory between turns.
        for (int rep = 0; rep < 2; rep++) {
            llama_memory_clear(llama_get_memory(ctx), false);
            if (!decode_chunked(ctx, toks)) {
                g_failures++;
                continue;
            }
            CHECK(sample_once(ctx, vocab),
                  "last-token logits unusable after prompt L=%d rep=%d", L, rep);
        }
    }

    // generateNextToken() pattern: sample then decode one token via get_one.
    {
        std::vector<llama_token> toks(base.begin(), base.begin() + 68);
        llama_memory_clear(llama_get_memory(ctx), false);
        if (decode_chunked(ctx, toks)) {
            for (int step = 0; step < 8; step++) {
                llama_sampler * smpl =
                    llama_sampler_chain_init(llama_sampler_chain_default_params());
                llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
                llama_token id = llama_sampler_sample(smpl, ctx, -1);
                llama_sampler_free(smpl);
                CHECK(id >= 0 && id < llama_vocab_n_tokens(vocab), "sample step %d invalid", step);

                llama_token one[] = { id };
                llama_batch next = llama_batch_get_one(one, 1);
                CHECK(next.logits == nullptr, "generation get_one logits not null");
                if (llama_decode(ctx, next) != 0) {
                    fprintf(stderr, "FAIL: generation decode step %d\n", step);
                    g_failures++;
                    break;
                }
            }
        }
    }

    // benchModel() pattern: chunked get_one decode over a fixed pad, repeated.
    {
        std::vector<llama_token> pad(128, 0);
        for (int r = 0; r < 3; r++) {
            llama_memory_clear(llama_get_memory(ctx), false);
            decode_chunked(ctx, pad);
        }
    }

    llama_free(ctx);
}

int main(int argc, char ** argv) {
    // Contract checks always run (no model needed).
    contract_get_one_borrows();
    contract_init_owns();

    if (argc >= 2) {
        llama_backend_init();
        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = 0;
        llama_model * model = llama_model_load_from_file(argv[1], mparams);
        if (!model) {
            fprintf(stderr, "FAIL: model load: %s\n", argv[1]);
            llama_backend_free();
            return 1;
        }
        run_decode_matrix(model, llama_model_get_vocab(model));
        llama_model_free(model);
        llama_backend_free();
    } else {
        printf("no model given: ran ownership contract only\n");
    }

    if (g_failures != 0) {
        fprintf(stderr, "BATCH REGRESSION FAIL (%d checks)\n", g_failures);
        return 1;
    }
    printf("BATCH REGRESSION PASS\n");
    return 0;
}
