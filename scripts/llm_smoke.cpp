// Native LFM2.5 smoke test for the Bro.exe voice prototype.
//
// Loads a GGUF with the pinned llama.cpp revision, renders a prompt using one of
// several prompt strategies, samples with greedy or the model's recommended
// temp/top_k/penalty settings, writes JSON to a file. The shell wrapper runs a
// small matrix and asserts at least one combo yields a clean spoken answer, and
// records which combo wins for the JNI binding.
//
// Build: g++ -std=c++17 -O2 llm_smoke.cpp -I<src>/include -I<src>/ggml/include \
//        -L<libdir> -lllama -Wl,-rpath,<libdir> -o llm_smoke
// Run:   ./llm_smoke <gguf> <max-tokens> <out.json> <prompt-mode> <sampler>
//   prompt-mode: tmpl_auto | tmpl_special | bos_special
//     tmpl_auto   model chat template, tokenized like llama-server (add_special)
//     tmpl_special  model chat template, special tokens parsed
//     bos_special   <|startoftext|> + manual ChatML, special tokens parsed
//   sampler: greedy | temp

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <filesystem>

#include "llama.h"

static std::string SYS  = "You are Bro, a concise spoken-voice assistant. Answer in one or two short sentences.";
static std::string USER = "What is the capital of France?";

static std::string escape_json(const std::string & s) {
    std::string e;
    for (char c : s) {
        if (c == '"' || c == '\\') { e += '\\'; e += c; }
        else if (c == '\n') e += "\\n";
        else if (c == '\t') e += "\\t";
        else if ((unsigned char)c < 0x20) { }
        else e += c;
    }
    return e;
}

struct GenResult {
    int n_prompt = 0;
    int n_gen = 0;
    long long first_token_ms = -1;
    std::string text;
};

static GenResult generate(llama_model * model, const llama_vocab * vocab,
                          const std::string & rendered,
                          bool add_special, bool parse_special,
                          const std::string & sampler_mode, int max_tokens) {
    auto t_gen0 = std::chrono::steady_clock::now();
    GenResult out;

    std::vector<llama_token> prompt(rendered.size() + 1);
    int n_prompt = llama_tokenize(vocab, rendered.c_str(), (int)rendered.size(),
                                  prompt.data(), (int)prompt.size(),
                                  add_special, parse_special);
    if (n_prompt <= 0) { fprintf(stderr, "FAIL tokenize\n"); return out; }
    prompt.resize((size_t)n_prompt);
    out.n_prompt = n_prompt;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx    = 1024;
    cparams.n_batch  = 512;
    cparams.n_threads = 2;
    cparams.n_threads_batch = 2;
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) { fprintf(stderr, "FAIL context init\n"); return out; }

    llama_batch batch = llama_batch_get_one(prompt.data(), n_prompt);
    if (llama_decode(ctx, batch) != 0) { fprintf(stderr, "FAIL prompt decode\n"); llama_free(ctx); return out; }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (sampler_mode == "temp") {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(80));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.6));
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            llama_vocab_n_tokens(vocab), 64, 1.05f, 0.0f, 0.0f));
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    llama_token eos = llama_vocab_eos(vocab);
    const char * HTML_TAG = "<|im_start|>";
    char piece[1024];
    for (int i = 0; i < max_tokens; i++) {
        llama_token id = llama_sampler_sample(smpl, ctx, -1);
        if (out.n_gen == 0) {
            out.first_token_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                     std::chrono::steady_clock::now() - t_gen0).count();
        }
        out.n_gen++;
        int n_piece = llama_token_to_piece(vocab, id, piece, (int)sizeof(piece), 0, false);
        std::string t(piece, n_piece > 0 ? n_piece : 0);
        if (id == eos) break;
        const char * tok_text = llama_vocab_get_text(vocab, id);
        if (tok_text && strcmp(tok_text, HTML_TAG) == 0) break;
        out.text += t;
        llama_sampler_accept(smpl, id);
        llama_token one[] = { id };
        llama_batch next = llama_batch_get_one(one, 1);
        if (llama_decode(ctx, next) != 0) break;
    }

    llama_sampler_free(smpl);
    llama_free(ctx);
    return out;
}

int main(int argc, char ** argv) {
    if (argc < 6) {
        fprintf(stderr, "usage: %s model.gguf max_tokens out.json prompt-mode sampler\n", argv[0]);
        return 2;
    }
    const char * model_path = argv[1];
    const int    max_tokens = std::atoi(argv[2]);
    const char * out_json   = argv[3];
    const std::string p_mode = argv[4];
    const std::string s_mode = argv[5];

    if (const char * s = getenv("SMOKE_SYS"))  SYS  = s;
    if (const char * u = getenv("SMOKE_USER")) USER = u;

    llama_backend_init();
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    auto t0 = std::chrono::steady_clock::now();
    llama_model * model = llama_model_load_from_file(model_path, mparams);
    if (!model) { fprintf(stderr, "FAIL model load\n"); llama_backend_free(); return 1; }
    auto t_load = std::chrono::duration_cast<std::chrono::milliseconds>(
                      std::chrono::steady_clock::now() - t0).count();
    const llama_vocab * vocab = llama_model_get_vocab(model);

    char desc[512] = {0};
    llama_model_desc(model, desc, sizeof(desc));

    std::string rendered;
    bool add_special = false, parse_special = false;
    if (p_mode == "tmpl_auto") {
        add_special = true;
        std::vector<char> tmp_buf(65536);
        int tmpl_len = llama_model_meta_val_str(model, "tokenizer.chat_template",
                                                tmp_buf.data(), (int)tmp_buf.size());
        if (tmpl_len <= 0) { fprintf(stderr, "FAIL: no chat template in GGUF\n"); return 1; }
        std::string tmpl(tmp_buf.data(), (size_t)tmpl_len);
        llama_chat_message chat[] = { { "system", SYS.c_str() }, { "user", USER.c_str() } };
        int need = llama_chat_apply_template(tmpl.c_str(), chat, 2, true, nullptr, 0);
        if (need <= 0) need = 1024;
        rendered.resize((size_t)need + 8);
        need = llama_chat_apply_template(tmpl.c_str(), chat, 2, true,
                                         rendered.data(), (int)rendered.size());
        if (need < 0) { fprintf(stderr, "FAIL chat template\n"); return 1; }
        rendered.resize((size_t)need);
    } else if (p_mode == "tmpl_special") {
        parse_special = true;
        std::vector<char> tmp_buf(65536);
        int tmpl_len = llama_model_meta_val_str(model, "tokenizer.chat_template",
                                                tmp_buf.data(), (int)tmp_buf.size());
        if (tmpl_len <= 0) { fprintf(stderr, "FAIL: no chat template in GGUF\n"); return 1; }
        std::string tmpl(tmp_buf.data(), (size_t)tmpl_len);
        llama_chat_message chat[] = { { "system", SYS.c_str() }, { "user", USER.c_str() } };
        int need = llama_chat_apply_template(tmpl.c_str(), chat, 2, true, nullptr, 0);
        if (need <= 0) need = 1024;
        rendered.resize((size_t)need + 8);
        need = llama_chat_apply_template(tmpl.c_str(), chat, 2, true,
                                         rendered.data(), (int)rendered.size());
        if (need < 0) { fprintf(stderr, "FAIL chat template\n"); return 1; }
        rendered.resize((size_t)need);
    } else if (p_mode == "bos_special") {
        parse_special = true;
        rendered = "<|startoftext|>"
                 + std::string("<|im_start|>system\n") + SYS + "<|im_end|>\n"
                 + std::string("<|im_start|>user\n") + USER + "<|im_end|>\n"
                 + std::string("<|im_start|>assistant\n");
    } else {
        fprintf(stderr, "unknown prompt-mode: %s\n", p_mode.c_str());
        return 2;
    }

    // Qwen3.5 dropped the Qwen3 /no_think soft switch; the only supported way to
    // disable reasoning is the enable_thinking=false chat-template kwarg, which
    // our pinned C API (llama_chat_apply_template, no jinja/kwargs) cannot pass.
    // The template's own non-thinking branch emits a closed think block right
    // after the assistant header, so we reproduce that here to test the idea.
    if (const char * off = getenv("SMOKE_ASSISTANT_THINK_OFF")) {
        if (*off && *off != '0') {
            // Assemble the tags at runtime; writing them literally in source
            // gets mangled by the tooling, and they are model-specific anyway.
            const std::string open_think = std::string("<") + "think" + ">";
            const std::string close_think = std::string("<") + "/" + "think";
            rendered += " " + open_think + "\n\n" + close_think + "\n\n";
            fprintf(stderr, "think-off: appended closed think block to prompt\n");
        }
    }

    GenResult r = generate(model, vocab, rendered, add_special, parse_special,
                           s_mode, max_tokens);

    size_t s = r.text.find_first_not_of(" \n\r\t");
    if (s != std::string::npos) r.text = r.text.substr(s);

    auto t_total = std::chrono::duration_cast<std::chrono::milliseconds>(
                       std::chrono::steady_clock::now() - t0).count();
    std::error_code ec;
    auto fbytes = std::to_string(std::filesystem::file_size(model_path, ec));

    FILE * f = fopen(out_json, "w");
    if (!f) { fprintf(stderr, "cannot write %s\n", out_json); return 2; }
    fprintf(f,
            "{\"prompt_mode\":\"%s\",\"sampler\":\"%s\",\"model\":\"%s\",\"fbytes\":%s,"
            "\"rendered_prompt\":\"%s\",\"prompt_tokens\":%d,\"gen_tokens\":%d,"
            "\"first_token_ms\":%lld,\"total_ms\":%lld,\"load_ms\":%lld,\"text\":\"%s\"}\n",
            p_mode.c_str(), s_mode.c_str(), desc, fbytes.c_str(), escape_json(rendered).c_str(),
            r.n_prompt, r.n_gen, r.first_token_ms, t_total, t_load, escape_json(r.text).c_str());
    fclose(f);

    fprintf(stderr, "prompt=%s sampler=%s load_ms=%lld total_ms=%lld prompt_tokens=%d gen_tokens=%d text_len=%zu\n",
            p_mode.c_str(), s_mode.c_str(), t_load, t_total, r.n_prompt, r.n_gen, r.text.size());

    llama_model_free(model);
    llama_backend_free();
    return r.text.empty() ? 1 : 0;
}