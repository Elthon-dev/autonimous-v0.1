// Bro.exe llama.cpp JNI binding.
//
// Uses only stable llama.cpp APIs (no llama.cpp/common helpers) so the binding
// keeps compiling across champagne bumps of the pinned llama.cpp revision.
//
// Contract (proven by scripts/llm_smoke.cpp/.sh on CI):
//   - prompt  = the model's own ChatML template (Qwen2.5-Instruct) rendered via
//     llama_chat_apply_template
//   - tokenize with add_special=false, parse_special=true  (auto-BOS is
//     <|im_start|> on this vocab and DOUBLES the role header -> broken answers;
//     this was the original runtime bug)
//   - sampling: repeat-penalty(1.05, last_n=64) -> greedy. Deliberately
//     deterministic: the same question yields the same spoken answer and CI
//     assertions stay stable. top_k/top_p/temperature are omitted on purpose --
//     in front of a greedy sampler they are no-ops that only mislead readers.
//     The chain is built once per turn and every accepted token is fed back into
//     it, so the penalty is real (it used to be rebuilt per token and never fed,
//     which made it a silent no-op).
//   - stop on EOG (<|im_end|>, <|endoftext|>) and the literal role tokens
//     <|im_start|> / <|im_end|>. <|startoftext|> is a Qwen3 token and is NOT in
//     the Qwen2.5 vocab, so it is not checked.
//   - a  thinking...</think> reasoning block is stripped app-side by
//     voice/ThinkingBlockFilter before TTS; the native layer stays model-agnostic.
//
// JNI entry points mirror the previous ARM sample so app code keeps compiling
// unchanged (see InferenceEngineImpl.kt).

#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <dirent.h>
#include <iomanip>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <vector>
#include <unistd.h>

#include "logging.h"
#include "ggml-backend.h"
#include "llama.h"

// Keep the AI-loading half of this file boring; the spooky action is all in llama.cpp.
// (One gag per task, and it's spent.)
//
// Stage logging: every step that can fault is bracketed with a LOGi/LOGe line so a
// crash on a device we cannot attach a debugger to still tells us *which step* died.
// The lines are deliberately prefixed with "AI ..." so `adb logcat | grep "AI "` is
// enough to reconstruct the whole load sequence from a user-submitted log.

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

#ifndef BROEXE_CTX_SIZE
#define BROEXE_CTX_SIZE 2048
#endif
constexpr int   DEFAULT_CONTEXT_SIZE    = BROEXE_CTX_SIZE;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_PENALTY_REPEAT  = 1.05f;
constexpr int   DEFAULT_PENALTY_LAST_N  = 64;

static llama_model    * g_model;
static llama_context  * g_context;
static const llama_vocab * g_vocab;
static std::string      g_template;   // model chat template (from GGUF metadata)
static std::string      g_sys;        // last system prompt (raw text)
static int              g_n_predict;  // remaining budget for the current turn
static std::string      g_cache;      // incomplete UTF-8 tail buffer
static llama_sampler  * g_smpl;       // per-turn sampler; free with free_sampler()

// Built once per turn (processUserPrompt) and fed every accepted token, so the
// repetition penalty accumulates over the actual conversation instead of being
// rebuilt -- and reset -- on every single token.
static void free_sampler() {
    if (g_smpl) {
        llama_sampler_free(g_smpl);
        g_smpl = nullptr;
    }
}

static bool ends_with(const std::string &s, const char *suffix) {
    const size_t n = strlen(suffix);
    return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
}

// Size of a file in bytes, or -1 when it cannot be stat()'d. Used to prove the
// model file is actually readable before handing the path to llama.cpp.
static long file_size_bytes(const char *path) {
    struct stat st;
    if (path == nullptr || stat(path, &st) != 0) {
        return -1;
    }
    return (long) st.st_size;
}

// Count (and log) the .so files in a directory. A missing/empty native library
// directory is the single most common reason dlopen-based backend loading fails.
static int list_shared_libs(const char *dir, const char *suffix) {
    if (dir == nullptr) {
        LOGe("AI list_shared_libs: null directory");
        return -1;
    }
    DIR *d = opendir(dir);
    if (d == nullptr) {
        LOGe("AI list_shared_libs: cannot open %s", dir ? dir : "(null)");
        return -1;
    }
    int n = 0;
    struct dirent *e;
    const size_t sl = strlen(suffix);
    while ((e = readdir(d)) != nullptr) {
        const size_t l = strlen(e->d_name);
        if (l >= sl && strcmp(e->d_name + l - sl, suffix) == 0) {
            LOGi("AI native-lib: %s", e->d_name);
            n++;
        }
    }
    closedir(d);
    return n;
}

// Dump every registered backend and its devices. With GGML_BACKEND_DL=ON the CPU
// backend only exists if a libggml-cpu-*.so module loaded successfully, so this is
// the ground truth for "is there anything capable of running the model?".
static void log_backends() {
    const size_t regs = ggml_backend_reg_count();
    LOGi("AI backends: %zu registries", regs);
    for (size_t i = 0; i < regs; i++) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        const size_t devs = ggml_backend_reg_dev_count(reg);
        LOGi("AI backend[%zu]: %s (%zu devices)", i, ggml_backend_reg_name(reg), devs);
        for (size_t j = 0; j < devs; j++) {
            ggml_backend_dev_t dev = ggml_backend_reg_dev_get(reg, j);
            LOGi("AI   device[%zu.%zu]: %s | %s",
                 i, j, ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));
        }
    }
    ggml_backend_dev_t cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    LOGi("AI backends: CPU device present = %s", cpu ? "yes" : "NO");
}

// Run JNI text through llama's own chat template.
static bool render_chat(const char *system, const char *user, std::string &out) {
    llama_chat_message chat[] = {
        { "system", system },
        { "user",   user },
    };
    const char * tmpl = g_template.empty() ? nullptr : g_template.c_str();
    int need = llama_chat_apply_template(tmpl, chat, 2, true, nullptr, 0);
    if (need <= 0) {
        LOGe("%s: chat template produced no output (need=%d)", __func__, need);
        return false;
    }
    out.resize((size_t)need + 8);
    need = llama_chat_apply_template(tmpl, chat, 2, true, out.data(), (int)out.size());
    if (need < 0) {
        LOGe("%s: chat template failed", __func__);
        return false;
    }
    out.resize((size_t)need);
    return true;
}

static int decode_tokens(const std::vector<llama_token> &tokens) {
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int n = std::min((int) tokens.size() - i, BATCH_SIZE);
        // llama_batch_get_one() returns a batch that *borrows* our token buffer and
        // leaves logits == nullptr. llama.cpp then treats the last token of the batch
        // as the only output (see llama_batch_compat::init), which is exactly the
        // position we sample from. Do NOT touch batch.logits (it is null) and do NOT
        // llama_batch_free() this batch: free() would delete[] memory we still own.
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&tokens[i]), n);
        if (llama_decode(g_context, batch) != 0) {
            LOGe("%s: llama_decode failed", __func__);
            return 1;
        }
    }
    return 0;
}

static void reset_state() {
    if (g_context) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
    free_sampler();
    g_cache.clear();
    g_sys.clear();
    g_n_predict = 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    LOGi("AI init: BEGIN");
    llama_log_set(aichat_android_log_callback, nullptr);

    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("AI init: stage 1/4 nativeLibraryDir=%s", path_to_backend ? path_to_backend : "(null)");
    const int so_count = list_shared_libs(path_to_backend, ".so");
    LOGi("AI init: nativeLibraryDir contains %d shared libraries", so_count);

    LOGi("AI init: stage 2/4 ggml_backend_load_all_from_path()");
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    LOGi("AI init: stage 3/4 enumerating backends");
    log_backends();

    LOGi("AI init: stage 4/4 llama_backend_init()");
    llama_backend_init();
    LOGi("AI init: END");
}

// Ground-truth backend report for the in-app diagnostics panel: which backends and
// devices this process actually managed to register, after init() ran.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_backendReport(JNIEnv *env, jobject /*unused*/) {
    std::string s;
    const size_t regs = ggml_backend_reg_count();
    for (size_t i = 0; i < regs; i++) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        s += ggml_backend_reg_name(reg);
        const size_t devs = ggml_backend_reg_dev_count(reg);
        for (size_t j = 0; j < devs; j++) {
            ggml_backend_dev_t dev = ggml_backend_reg_dev_get(reg, j);
            s += "|";
            s += ggml_backend_dev_name(dev);
            s += ":";
            s += ggml_backend_dev_description(dev);
        }
        s += ";";
    }
    if (s.empty()) {
        s = "(no backends registered)";
    }
    return env->NewStringUTF(s.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only on the Oppo A18

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    const long sz = file_size_bytes(model_path);
    LOGi("AI load: path=%s size=%ld bytes (readable=%s)",
         model_path ? model_path : "(null)", sz, (sz >= 0) ? "yes" : "NO");

    // Refuse to hand an unreadable path to llama.cpp; it would fail cleanly anyway,
    // but we want a *distinct* report before any native tensor work begins.
    if (sz < 0) {
        LOGe("AI load: model file missing/unreadable, aborting before llama_model_load_from_file()");
        env->ReleaseStringUTFChars(jmodel_path, model_path);
        return 2;
    }

    LOGi("AI load: llama_model_load_from_file() BEGIN");
    auto *model = llama_model_load_from_file(model_path, model_params);
    LOGi("AI load: llama_model_load_from_file() END (%s)", model ? "ok" : "NULL");
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        LOGe("%s: failed to load model", __func__);
        return 1;
    }
    g_model = model;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    if (!g_model) {
        LOGe("%s: model not loaded", __func__);
        return 1;
    }

    // Thread budget: leave headroom for the OS + mic service.
    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                      (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                      N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);

    llama_context_params ctx_params = llama_context_default_params();
    const int trained_ctx = llama_model_n_ctx_train(g_model);
    ctx_params.n_ctx = std::min(DEFAULT_CONTEXT_SIZE, trained_ctx);
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    LOGi("AI prepare: trained_ctx=%d n_ctx=%u n_batch=%u n_ubatch=%u threads=%d",
         trained_ctx, ctx_params.n_ctx, ctx_params.n_batch, ctx_params.n_ubatch, n_threads);
    LOGi("AI prepare: llama_init_from_model() BEGIN");
    g_context = llama_init_from_model(g_model, ctx_params);
    LOGi("AI prepare: llama_init_from_model() END (%s)", g_context ? "ok" : "NULL");
    if (!g_context) {
        LOGe("%s: llama_init_from_model() returned null", __func__);
        return 1;
    }
    g_vocab = llama_model_get_vocab(g_model);

    // Pull the model's own chat template; fall back to manual ChatML.
    std::vector<char> buf(65536);
    int tmpl_len = llama_model_meta_val_str(g_model, "tokenizer.chat_template",
                                            buf.data(), (int) buf.size());
    if (tmpl_len > 0) {
        g_template.assign(buf.data(), (size_t) tmpl_len > buf.size() ? buf.size() : (size_t) tmpl_len);
        LOGi("%s: using model chat template (%zu bytes)", __func__, g_template.size());
    } else {
        g_template.clear();
        LOGw("%s: no chat template in GGUF, falling back to raw ChatML", __func__);
    }

    reset_state();
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    if (!g_context || !g_model) {
        return env->NewStringUTF("no model loaded");
    }

    const auto t_start = std::chrono::steady_clock::now();
    llama_memory_clear(llama_get_memory(g_context), false);

    double pp_tps = 0.0, tg_tps = 0.0;
    const int n_pp = std::max(1, (int) pp);
    std::vector<llama_token> pad((size_t) n_pp, 0);

    for (int r = 0; r < nr; r++) {
        // prompt processing
        auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < n_pp; i += BATCH_SIZE) {
            const int n = std::min(n_pp - i, BATCH_SIZE);
            // get_one borrows `pad` and leaves logits == nullptr; never write/free it.
            llama_batch b = llama_batch_get_one(&pad[i], n);
            llama_decode(g_context, b);
        }
        auto t1 = std::chrono::steady_clock::now();
        const double pp_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        if (pp_ms > 0) pp_tps += (double) n_pp / (pp_ms / 1000.0);

        // text generation
        t0 = std::chrono::steady_clock::now();
        const int n_tg = std::max(1, (int) tg);
        for (int i = 0; i < n_tg; i++) {
            llama_batch b = llama_batch_get_one(&pad[0], 1);
            llama_decode(g_context, b);
        }
        t1 = std::chrono::steady_clock::now();
        const double tg_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        if (tg_ms > 0) tg_tps += (double) n_tg / (tg_ms / 1000.0);
    }
    if (nr > 0) { pp_tps /= nr; tg_tps /= nr; }

    const double el_ms = std::chrono::duration<double, std::milli>(
                             std::chrono::steady_clock::now() - t_start).count();
    char desc_buf[256];
    const char * model_desc = llama_model_desc(g_model, desc_buf, sizeof(desc_buf));
    std::ostringstream out;
    out << std::fixed << std::setprecision(1)
        << (model_desc && model_desc[0] ? model_desc : "unknown model")
        << " | pp " << n_pp << " | " << pp_tps << " t/s | tg "
        << std::max(1, (int) tg) << " | " << tg_tps << " t/s | wall " << el_ms << " ms";
    return env->NewStringUTF(out.str().c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    reset_state();
    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    g_sys = system_prompt;
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);
    LOGd("%s: system prompt cached (%zu bytes)", __func__, g_sys.size());
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    if (!g_context || !g_model || !g_vocab) {
        LOGe("%s: model not ready (ctx=%p model=%p vocab=%p)", __func__,
             (void*)g_context, (void*)g_model, (void*)g_vocab);
        return 1;
    }

    if (juser_prompt == nullptr) {
        LOGe("%s: user prompt is null", __func__);
        return 1;
    }
    const auto *user = env->GetStringUTFChars(juser_prompt, nullptr);
    if (user == nullptr) {
        LOGe("%s: failed to get user prompt chars", __func__);
        return 1;
    }
    std::string user_s(user);
    env->ReleaseStringUTFChars(juser_prompt, user);

    std::string rendered;
    const char *sys_prompt = g_sys.empty() ? "You are a concise spoken-voice assistant." : g_sys.c_str();
    if (!render_chat(sys_prompt, user_s.c_str(), rendered)) {
        return 2;
    }
    LOGd("%s: rendered prompt (%zu bytes)", __func__, rendered.size());

    // Contract: add_special=false, parse_special=true (auto-BOS corrupts role header).
    std::vector<llama_token> tokens((size_t) rendered.size() + 4);
    const int n_tok = llama_tokenize(g_vocab, rendered.c_str(), (int) rendered.size(),
                                     tokens.data(), (int) tokens.size(),
                                     /* add_special */ false, /* parse_special */ true);
    if (n_tok <= 0) {
        LOGe("%s: tokenize failed (n_tok=%d)", __func__, n_tok);
        return 3;
    }
    tokens.resize((size_t) n_tok);

    // Fresh turn: clear KV and re-decode the full rendered prompt.
    llama_memory_clear(llama_get_memory(g_context), false);
    if (decode_tokens(tokens)) {
        LOGe("%s: prompt decode failed", __func__);
        return 4;
    }

    // Per-turn sampler: penalty -> greedy, seeded with the prompt so the first
    // generated tokens are penalised against what the prompt actually said.
    free_sampler();
    g_smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_smpl, llama_sampler_init_penalties(
        llama_vocab_n_tokens(g_vocab), DEFAULT_PENALTY_LAST_N,
        DEFAULT_PENALTY_REPEAT, 0.0f, 0.0f));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_greedy());
    for (int i = 0; i < (int) tokens.size(); i++) {
        llama_sampler_accept(g_smpl, tokens[i]);
    }

    g_cache.clear();
    g_n_predict = n_predict > 0 ? n_predict : 64;
    LOGd("%s: decoded %d prompt tokens, budget=%d", __func__, n_tok, g_n_predict);
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }
    const auto *bytes = (const unsigned char *) string;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) { num = 1; }
        else if ((*bytes & 0xE0) == 0xC0) { num = 2; }
        else if ((*bytes & 0xF0) == 0xE0) { num = 3; }
        else if ((*bytes & 0xF8) == 0xF0) { num = 4; }
        else { return false; }
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) { return false; }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    if (!g_context || !g_vocab || !g_model || g_n_predict <= 0) {
        LOGe("%s: not ready (ctx=%p vocab=%p model=%p n_predict=%d)", __func__,
             (void*)g_context, (void*)g_vocab, (void*)g_model, g_n_predict);
        return nullptr;
    }

    // Reuse the per-turn sampler built in processUserPrompt so the repetition
    // penalty accumulates across the turn. Deterministic chain: repeat-penalty ->
    // greedy (top_k/top_p/temperature would be no-ops in front of greedy).
    if (!g_smpl) {
        LOGw("%s: sampler missing; rebuilding a minimal greedy chain", __func__);
        g_smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(g_smpl, llama_sampler_init_greedy());
    }

    const llama_token id = llama_sampler_sample(g_smpl, g_context, -1);
    g_n_predict--;

    const bool is_eog = llama_vocab_is_eog(g_vocab, id);
    const char * piece_txt = llama_vocab_get_text(g_vocab, id);
    const bool role_stop = piece_txt && (strcmp(piece_txt, "<|im_start|>") == 0 ||
                                         strcmp(piece_txt, "<|im_end|>") == 0);

    if (is_eog || role_stop) {
        LOGd("%s: stop (eog=%d role_stop=%d text=%s)", __func__, is_eog, role_stop,
             piece_txt ? piece_txt : "");
        return nullptr;
    }

    // Feed the accepted token back into the sampler so its penalty history stays
    // in sync with the tokens actually generated this turn.
    llama_sampler_accept(g_smpl, id);

    // Feed the sampled token back into the KV cache.
    // get_one borrows `one` and leaves logits == nullptr; the single token is the
    // last (and only) output, so llama.cpp computes its logits by default. Never
    // write next.logits or llama_batch_free() a get_one batch.
    llama_token one[] = { id };
    llama_batch next = llama_batch_get_one(one, 1);
    if (llama_decode(g_context, next) != 0) {
        LOGw("%s: decode of sampled token failed", __func__);
        return nullptr;
    }

    // Convert to text; hold incomplete UTF-8 sequences for the next call.
    char piece[1024];
    const int n_piece = llama_token_to_piece(g_vocab, id, piece, (int) sizeof(piece), 0, false);
    g_cache.append(piece, n_piece > 0 ? n_piece : 0);

    jstring result = nullptr;
    if (is_valid_utf8(g_cache.c_str())) {
        result = env->NewStringUTF(g_cache.c_str());
        g_cache.clear();
    }
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    reset_state();
    llama_free(g_context);
    g_context = nullptr;
    llama_model_free(g_model);
    g_model = nullptr;
    g_vocab = nullptr;
    g_template.clear();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}