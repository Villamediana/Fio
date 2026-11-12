// Novelo's on-device models through llama.cpp (libllama + libmtmd), one in memory at a time:
//   transcribe(): LFM3-Omni hears — 16 kHz PCM in, the text that was said out ("Perform ASR");
//   generate():   a rendered chat prompt in (and maybe a photo, at the media marker), text out
//                 (optionally held to a GBNF grammar) — Omni looks at photos, LFM3-1.7B writes;
//   decide():     d1 (a decision model) reads one question's prompt and gives each answer a score
//                 (fio_decide.h).
// Text comes back as UTF-8 bytes (Java's modified UTF-8 can't carry emoji), streamed to a
// TokenSink as it is produced. One call at a time per engine (the Kotlin side holds a mutex).

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "fio_decide.h"

#define TAG "FioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    mtmd_context * mtmd = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_batch = 1024;
    std::vector<llama_logit_bias> text_only; // no switch to speech in a text answer
    std::vector<llama_token_data> cur;       // the candidates of one step (the whole vocabulary)
    std::atomic<bool> cancel{false};
    // The prompt's fixed start (Fio's instructions) already run once: the model's state right after
    // it, put back on the next answer instead of running those ~1,200 tokens again. A whole-state
    // snapshot (not a cut of the cache): LFM's convolution layers can't be cut at a position.
    std::string prefix_text;
    std::vector<uint8_t> prefix_state;
    llama_pos prefix_pos = 0;
    // Where that snapshot is kept on disk (for this model and app version): the instructions run
    // once, ever — not again each time the model was let go or the app opened.
    std::string cache_path;
};

void log_to_android(enum ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

llama_token single_token(const llama_vocab * vocab, const char * piece) {
    llama_token out[4];
    const int n = llama_tokenize(vocab, piece, (int32_t) strlen(piece), out, 4, false, true);
    return n == 1 ? out[0] : LLAMA_TOKEN_NULL;
}

std::string piece_of(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big((size_t) -n, '\0');
        n = llama_token_to_piece(vocab, tok, big.data(), (int32_t) big.size(), 0, false);
        return n > 0 ? big.substr(0, (size_t) n) : std::string();
    }
    return std::string(buf, (size_t) n);
}

/** Bytes of [s] that form whole UTF-8 characters (a token can end in the middle of one). */
size_t utf8_complete(const std::string & s) {
    size_t i = s.size();
    int back = 0;
    while (i > 0 && back < 4) {
        const unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) != 0x80) {
            const int need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
            return (back + 1 >= need) ? s.size() : i - 1;
        }
        --i;
        ++back;
    }
    return s.size();
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    if (arr && !s.empty()) env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

/** Streams the text so far to sink.onText(byte[]) (whole characters only); false = stop. */
struct Sink {
    JNIEnv * env;
    jobject obj;
    jmethodID method = nullptr;
    size_t sent = 0;
    Sink(JNIEnv * e, jobject o) : env(e), obj(o) {
        if (obj) {
            jclass cls = env->GetObjectClass(obj);
            method = env->GetMethodID(cls, "onText", "([B)Z");
            env->DeleteLocalRef(cls);
        }
    }
    bool push(const std::string & all) {
        if (!method) return true;
        const size_t upto = utf8_complete(all);
        if (upto <= sent) return true;
        jbyteArray arr = to_bytes(env, all.substr(sent, upto - sent));
        sent = upto;
        const jboolean go = env->CallBooleanMethod(obj, method, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return go == JNI_TRUE;
    }
};

/** Every token with the logits of the last position, the switch to speech ruled out by [bias]. */
llama_token_data_array candidates(Engine * e, llama_sampler * bias) {
    const float * logits = llama_get_logits_ith(e->ctx, -1);
    const int n = llama_vocab_n_tokens(e->vocab);
    e->cur.resize((size_t) n);
    for (int i = 0; i < n; i++) e->cur[(size_t) i] = llama_token_data{ (llama_token) i, logits[i], 0.0f };
    llama_token_data_array arr{ e->cur.data(), e->cur.size(), -1, false };
    if (bias) llama_sampler_apply(bias, &arr);
    return arr;
}

/** Index of the highest finite logit (greedy, what the model card recommends for text); -1 if none. */
int64_t best_of(const llama_token_data_array & arr) {
    int64_t best = -1;
    for (size_t i = 0; i < arr.size; i++) {
        if (!std::isfinite(arr.data[i].logit)) continue;
        if (best < 0 || arr.data[i].logit > arr.data[best].logit) best = (int64_t) i;
    }
    return best;
}

/**
 * The next token, held to [grammar] the cheap way: the model's own pick is checked first (it nearly
 * always fits); only when it doesn't is the grammar run over the whole vocabulary. Running it over
 * every token at every step (~125k for LFM3) was what made organizing on the phone take minutes.
 */
llama_token pick(Engine * e, llama_sampler * bias, llama_sampler * grammar) {
    llama_token_data_array arr = candidates(e, bias);
    int64_t best = best_of(arr);
    if (best < 0) return LLAMA_TOKEN_NULL;
    const llama_token tok = arr.data[best].id;
    if (!grammar) return tok;
    llama_token_data one{ tok, 0.0f, 0.0f };
    llama_token_data_array single{ &one, 1, -1, false };
    llama_sampler_apply(grammar, &single);
    if (std::isfinite(single.data[0].logit)) return tok;
    arr = candidates(e, bias);
    llama_sampler_apply(grammar, &arr);
    best = best_of(arr);
    return best < 0 ? LLAMA_TOKEN_NULL : arr.data[best].id;
}

/** Samples from the logits already computed for position n_past-1 until end, cancel or max. */
std::string sample_loop(Engine * e, llama_sampler * bias, llama_sampler * grammar, llama_pos n_past, int max_tokens, Sink & sink) {
    std::string out;
    for (int i = 0; i < max_tokens && !e->cancel.load(); i++) {
        const llama_token tok = pick(e, bias, grammar);
        if (tok == LLAMA_TOKEN_NULL || llama_vocab_is_eog(e->vocab, tok)) break;
        if (grammar) llama_sampler_accept(grammar, tok);
        if (!llama_vocab_is_control(e->vocab, tok)) {
            out += piece_of(e->vocab, tok);
            if (!sink.push(out)) break;
        }
        llama_token next = tok;
        llama_batch b = llama_batch_get_one(&next, 1);
        if (llama_decode(e->ctx, b) != 0) {
            LOGE("decode failed at %d", (int) n_past);
            break;
        }
        n_past++;
    }
    return out;
}

/** The switch to speech ruled out (a text answer never turns into audio); null when there is nothing to rule out. */
llama_sampler * make_bias(Engine * e) {
    if (e->text_only.empty()) return nullptr;
    return llama_sampler_init_logit_bias(llama_vocab_n_tokens(e->vocab), (int32_t) e->text_only.size(), e->text_only.data());
}

/** The GBNF [grammar] (the brain's JSON), or null without one (or if llama.cpp refuses it). */
llama_sampler * make_grammar(Engine * e, const char * grammar) {
    if (!grammar || !*grammar) return nullptr;
    llama_sampler * g = llama_sampler_init_grammar(e->vocab, grammar, "root");
    if (!g) LOGE("grammar rejected");
    return g;
}

void free_samplers(llama_sampler * bias, llama_sampler * grammar) {
    if (bias) llama_sampler_free(bias);
    if (grammar) llama_sampler_free(grammar);
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return std::string();
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string jbytes(JNIEnv * env, jbyteArray a) {
    if (!a) return std::string();
    const jsize n = env->GetArrayLength(a);
    std::string out((size_t) n, '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

/** [prompt] without the media marker (a photo that could not be read: the text alone goes on). */
std::string without_marker(std::string prompt) {
    const std::string m = mtmd_default_marker();
    for (size_t at = prompt.find(m); at != std::string::npos; at = prompt.find(m)) prompt.erase(at, m.size());
    return prompt;
}

/** [text]'s tokens, the special ones read as such (the prompt writes them out); empty on failure. */
std::vector<llama_token> tokenize(Engine * e, const std::string & text) {
    std::vector<llama_token> toks(text.size() + 16);
    int n = llama_tokenize(e->vocab, text.c_str(), (int32_t) text.size(), toks.data(), (int32_t) toks.size(), false, true);
    if (n < 0) {
        toks.resize((size_t) -n);
        n = llama_tokenize(e->vocab, text.c_str(), (int32_t) text.size(), toks.data(), (int32_t) toks.size(), false, true);
    }
    if (n <= 0) return {};
    toks.resize((size_t) n);
    return toks;
}

/** [toks] decoded after what is already in seq 0, in batches; false on failure or cancel. */
bool decode_all(Engine * e, const std::vector<llama_token> & toks) {
    for (size_t i = 0; i < toks.size(); i += (size_t) e->n_batch) {
        if (e->cancel.load()) return false;
        const int len = (int) std::min((size_t) e->n_batch, toks.size() - i);
        llama_batch b = llama_batch_get_one(const_cast<llama_token *>(toks.data()) + i, len);
        if (llama_decode(e->ctx, b) != 0) { LOGE("decode failed"); return false; }
    }
    return true;
}

/** The snapshot of [prefix] from disk into [e] (only when it is of that very text); false if none. */
bool read_cache(Engine * e, const std::string & prefix) {
    if (e->cache_path.empty()) return false;
    FILE * f = std::fopen(e->cache_path.c_str(), "rb");
    if (!f) return false;
    bool ok = false;
    char magic[4];
    uint64_t text_size = 0, state_size = 0;
    int32_t pos = 0;
    if (std::fread(magic, 1, 4, f) == 4 && std::memcmp(magic, "FIO1", 4) == 0 &&
        std::fread(&text_size, sizeof(text_size), 1, f) == 1 && text_size == prefix.size()) {
        std::string text((size_t) text_size, '\0');
        if (std::fread(text.data(), 1, text.size(), f) == text.size() && text == prefix &&
            std::fread(&pos, sizeof(pos), 1, f) == 1 && std::fread(&state_size, sizeof(state_size), 1, f) == 1 &&
            pos > 0 && state_size > 0 && state_size < (1ull << 31)) {
            std::vector<uint8_t> state((size_t) state_size);
            if (std::fread(state.data(), 1, state.size(), f) == state.size()) {
                e->prefix_state.swap(state);
                e->prefix_text = prefix;
                e->prefix_pos = pos;
                ok = true;
            }
        }
    }
    std::fclose(f);
    return ok;
}

/** The snapshot in [e] to disk (written aside, then put in place: never half a file). */
void write_cache(Engine * e) {
    if (e->cache_path.empty() || e->prefix_state.empty()) return;
    const std::string tmp = e->cache_path + ".tmp";
    FILE * f = std::fopen(tmp.c_str(), "wb");
    if (!f) return;
    const uint64_t text_size = e->prefix_text.size(), state_size = e->prefix_state.size();
    const int32_t pos = (int32_t) e->prefix_pos;
    bool ok = std::fwrite("FIO1", 1, 4, f) == 4 &&
        std::fwrite(&text_size, sizeof(text_size), 1, f) == 1 &&
        std::fwrite(e->prefix_text.data(), 1, e->prefix_text.size(), f) == e->prefix_text.size() &&
        std::fwrite(&pos, sizeof(pos), 1, f) == 1 &&
        std::fwrite(&state_size, sizeof(state_size), 1, f) == 1 &&
        std::fwrite(e->prefix_state.data(), 1, e->prefix_state.size(), f) == e->prefix_state.size();
    ok = std::fclose(f) == 0 && ok;
    if (ok) std::rename(tmp.c_str(), e->cache_path.c_str()); else std::remove(tmp.c_str());
}

/**
 * Seq 0 holding exactly [prefix]: put back from the snapshot when it is of the same text (from
 * memory, or from disk), else run now and kept (on disk too). The position after it; -1 on failure
 * (memory left empty).
 */
llama_pos start_from(Engine * e, const std::string & prefix) {
    llama_memory_t mem = llama_get_memory(e->ctx);
    llama_memory_clear(mem, true);
    if (prefix.empty()) return 0;
    if (e->prefix_text != prefix) read_cache(e, prefix);
    if (!e->prefix_state.empty() && e->prefix_text == prefix) {
        const size_t read = llama_state_seq_set_data(e->ctx, e->prefix_state.data(), e->prefix_state.size(), 0);
        if (read > 0 && llama_memory_seq_pos_max(mem, 0) == e->prefix_pos - 1) return e->prefix_pos;
        // Never trust a half put-back state: run it again.
        LOGE("prefix snapshot not restored (%zu bytes read); running it again", read);
        llama_memory_clear(mem, true);
        e->prefix_state.clear();
        e->prefix_text.clear();
    }
    const std::vector<llama_token> toks = tokenize(e, prefix);
    if (toks.empty() || !decode_all(e, toks)) { llama_memory_clear(mem, true); return -1; }
    const llama_pos pos = (llama_pos) toks.size();
    const size_t size = llama_state_seq_get_size(e->ctx, 0);
    std::vector<uint8_t> state(size);
    const size_t wrote = size > 0 ? llama_state_seq_get_data(e->ctx, state.data(), size, 0) : 0;
    if (wrote > 0) {
        state.resize(wrote);
        e->prefix_state.swap(state);
        e->prefix_text = prefix;
        e->prefix_pos = pos;
        write_cache(e);
    }
    return pos;
}

/** The photo and the prompt (with the media marker where the photo goes) through the projector, after [from] tokens already in; -1 on failure. */
llama_pos eval_with_image(Engine * e, const std::string & prompt, const std::string & image, llama_pos from = 0) {
    mtmd_helper_bitmap_wrapper w = mtmd_helper_bitmap_init_from_buf(
        e->mtmd, reinterpret_cast<const unsigned char *>(image.data()), image.size(), false, mtmd_helper_init_opt_default());
    if (!w.bitmap) { LOGE("image decode failed"); return -1; }
    mtmd_input_text text{ prompt.c_str(), prompt.size(), false, true };
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[] = { w.bitmap };
    llama_pos n_past = -1;
    int32_t rc = mtmd_tokenize(e->mtmd, chunks, &text, bitmaps, 1);
    if (rc == 0) {
        const int ctx_size = (int) llama_n_ctx(e->ctx);
        const int n = (int) mtmd_helper_get_n_tokens(chunks);
        if (from + n + 16 >= ctx_size) {
            LOGE("prompt with image too long: %d tokens (ctx %d)", (int) from + n, ctx_size);
        } else {
            llama_pos past = from;
            rc = mtmd_helper_eval_chunks(e->mtmd, e->ctx, chunks, from, 0, e->n_batch, true, &past);
            if (rc == 0) n_past = past; else LOGE("image eval failed: %d", rc);
        }
    } else {
        LOGE("tokenize with image failed: %d", rc);
    }
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(w.bitmap);
    return n_past;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_villamediana_fio_local_LocalEngine_nativeLoad(JNIEnv * env, jobject, jstring jmodel, jstring jmmproj, jint threads, jint n_ctx, jint n_batch, jstring jcache) {
    llama_log_set(log_to_android, nullptr);
    mtmd_helper_log_set(log_to_android, nullptr);
    llama_backend_init();

    const std::string model_path = jstr(env, jmodel);
    const std::string mmproj_path = jstr(env, jmmproj);

    auto * e = new Engine();
    e->cache_path = jstr(env, jcache);
    // A decision model reads its whole prompt in one pass: its batch is as big as its context.
    if (n_batch > 0) e->n_batch = n_batch;
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    e->model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!e->model) { LOGE("model load failed: %s", model_path.c_str()); delete e; return 0; }
    e->vocab = llama_model_get_vocab(e->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = (uint32_t) e->n_batch;
    cp.n_ubatch = (uint32_t) e->n_batch;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = true;
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) { LOGE("context init failed"); llama_model_free(e->model); delete e; return 0; }

    if (!mmproj_path.empty()) {
        mtmd_context_params mtp = mtmd_context_params_default();
        mtp.use_gpu = false;
        mtp.n_threads = threads;
        mtp.print_timings = false;
        mtp.warmup = false;
        e->mtmd = mtmd_init_from_file(mmproj_path.c_str(), e->model, mtp);
        if (!e->mtmd) LOGE("audio projector failed: %s", mmproj_path.c_str());
    }

    for (const char * p : { "<|audio_start|>", "<|mixed_start|>", "<|audio_out|>" }) {
        const llama_token t = single_token(e->vocab, p);
        if (t != LLAMA_TOKEN_NULL) e->text_only.push_back({ t, -INFINITY });
    }
    LOGI("loaded: audio=%d threads=%d ctx=%d", e->mtmd && mtmd_support_audio(e->mtmd), threads, n_ctx);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jboolean JNICALL
Java_com_villamediana_fio_local_LocalEngine_nativeHearsAudio(JNIEnv *, jobject, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    return (e && e->mtmd && mtmd_support_audio(e->mtmd)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_villamediana_fio_local_LocalEngine_nativeSampleRate(JNIEnv *, jobject, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    return (e && e->mtmd) ? mtmd_get_audio_sample_rate(e->mtmd) : 16000;
}

JNIEXPORT void JNICALL
Java_com_villamediana_fio_local_LocalEngine_nativeCancel(JNIEnv *, jobject, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (e) e->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_villamediana_fio_local_LocalEngine_nativeFree(JNIEnv *, jobject, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) return;
    if (e->mtmd) mtmd_free(e->mtmd);
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

/**
 * [prompt] is the whole rendered chat (system + user turn with the media marker + "assistant"
 * opening), as UTF-8 bytes. [pcm] is mono float PCM at the projector's sample rate.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_villamediana_fio_local_LocalEngine_nativeTranscribe(JNIEnv * env, jobject, jlong h, jfloatArray jpcm, jbyteArray jprompt, jint max_tokens, jobject jsink) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e || !e->mtmd) return nullptr;
    e->cancel.store(false);
    llama_memory_clear(llama_get_memory(e->ctx), true);

    const jsize n = env->GetArrayLength(jpcm);
    std::vector<float> pcm((size_t) n);
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());

    const std::string prompt = jbytes(env, jprompt);
    mtmd_bitmap * audio = mtmd_bitmap_init_from_audio(pcm.size(), pcm.data());
    if (!audio) return nullptr;
    mtmd_input_text text{ prompt.c_str(), prompt.size(), false, true };
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[] = { audio };
    int32_t rc = mtmd_tokenize(e->mtmd, chunks, &text, bitmaps, 1);
    std::string out;
    if (rc == 0) {
        llama_pos n_past = 0;
        rc = mtmd_helper_eval_chunks(e->mtmd, e->ctx, chunks, 0, 0, e->n_batch, true, &n_past);
        if (rc == 0 && !e->cancel.load()) {
            llama_sampler * bias = make_bias(e);
            Sink sink(env, jsink);
            out = sample_loop(e, bias, nullptr, n_past, max_tokens, sink);
            free_samplers(bias, nullptr);
        } else if (rc != 0) {
            LOGE("audio eval failed: %d", rc);
        }
    } else {
        LOGE("tokenize failed: %d", rc);
    }
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(audio);
    if (rc != 0) return nullptr;
    return to_bytes(env, out);
}

/**
 * Gets the instructions' snapshot ready ([prefix]: from disk, or run now and kept) while she is
 * still talking, so the answer after starts from there. True when it is ready.
 */
JNIEXPORT jboolean JNICALL
Java_com_villamediana_fio_local_LocalEngine_nativePrime(JNIEnv * env, jobject, jlong h, jbyteArray jprefix) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) return JNI_FALSE;
    const std::string prefix = jbytes(env, jprefix);
    if (prefix.empty()) return JNI_FALSE;
    if (e->prefix_text == prefix && !e->prefix_state.empty()) return JNI_TRUE;
    if (read_cache(e, prefix)) return JNI_TRUE;
    e->cancel.store(false);
    const llama_pos pos = start_from(e, prefix);
    llama_memory_clear(llama_get_memory(e->ctx), true);
    return pos > 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_villamediana_fio_local_LocalEngine_nativeGenerate(JNIEnv * env, jobject, jlong h, jbyteArray jprompt, jint jkeep, jstring jgrammar, jbyteArray jimage, jint max_tokens, jobject jsink) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) return nullptr;
    e->cancel.store(false);

    const std::string prompt = jbytes(env, jprompt);
    const std::string grammar = jstr(env, jgrammar);
    const int ctx_size = (int) llama_n_ctx(e->ctx);
    // The prompt's first [jkeep] bytes are the same every time (the instructions): run once, then put back.
    const size_t keep = (size_t) std::max(0, std::min((int) jkeep, (int) prompt.size()));
    const std::string head = prompt.substr(0, keep);
    const std::string rest = prompt.substr(keep);
    llama_pos n_past = -1;

    // A photo goes in through the projector when the model sees; otherwise the text alone.
    const std::string image = jbytes(env, jimage);
    if (!image.empty() && e->mtmd && mtmd_support_vision(e->mtmd)) {
        const llama_pos from = start_from(e, head);
        if (from >= 0) n_past = eval_with_image(e, rest, image, from);
    }
    if (n_past < 0) {
        if (e->cancel.load()) return to_bytes(env, std::string());
        const llama_pos from = start_from(e, head);
        if (from < 0) return e->cancel.load() ? to_bytes(env, std::string()) : nullptr;
        const std::vector<llama_token> toks = tokenize(e, without_marker(rest));
        if (toks.empty()) return nullptr;
        if (from + (int) toks.size() + 16 >= ctx_size) { LOGE("prompt too long: %d tokens (ctx %d)", (int) (from + toks.size()), ctx_size); return nullptr; }
        if (!decode_all(e, toks)) return e->cancel.load() ? to_bytes(env, std::string()) : nullptr;
        n_past = from + (llama_pos) toks.size();
    }
    if (e->cancel.load()) return to_bytes(env, std::string());

    llama_sampler * bias = make_bias(e);
    llama_sampler * gram = make_grammar(e, grammar.c_str());
    Sink sink(env, jsink);
    const int room = std::max(16, std::min((int) max_tokens, ctx_size - (int) n_past - 8));
    std::string out = sample_loop(e, bias, gram, n_past, room, sink);
    free_samplers(bias, gram);
    return to_bytes(env, out);
}

/**
 * One question to the decision model: [jprompt] rendered (UTF-8), [jgroups] one string per answer
 * with the spellings of its label separated by U+0001 ("yes\u0001Yes\u0001YES"). Each answer's
 * score (the best logit of its label's tokens); null when it couldn't be done.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_villamediana_fio_local_LocalEngine_nativeDecide(JNIEnv * env, jobject, jlong h, jbyteArray jprompt, jobjectArray jgroups) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) return nullptr;
    std::vector<std::vector<llama_token>> groups;
    const jsize n = env->GetArrayLength(jgroups);
    for (jsize i = 0; i < n; i++) {
        auto js = (jstring) env->GetObjectArrayElement(jgroups, i);
        const std::string all = jstr(env, js);
        env->DeleteLocalRef(js);
        std::vector<std::string> forms;
        size_t from = 0;
        for (size_t at = all.find('\x01'); ; at = all.find('\x01', from)) {
            forms.push_back(all.substr(from, at == std::string::npos ? std::string::npos : at - from));
            if (at == std::string::npos) break;
            from = at + 1;
        }
        groups.push_back(fio::label_group(e->vocab, forms));
    }
    const std::vector<float> scores = fio::decide(e->ctx, e->vocab, jbytes(env, jprompt), groups);
    if (scores.empty()) { LOGE("decision failed"); return nullptr; }
    jfloatArray out = env->NewFloatArray((jsize) scores.size());
    if (out) env->SetFloatArrayRegion(out, 0, (jsize) scores.size(), scores.data());
    return out;
}

/** The control tokens' texts (what she can't write without breaking them), separated by NUL, as UTF-8. */
JNIEXPORT jbyteArray JNICALL
Java_com_villamediana_fio_local_LocalEngine_nativeSpellings(JNIEnv * env, jobject, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) return nullptr;
    std::string all;
    for (const auto & s : fio::control_spellings(e->vocab)) { all += s; all += '\0'; }
    return to_bytes(env, all);
}

/** A value of the model's metadata ("lfm3.decision.temperature.noul"); null when it has none. */
JNIEXPORT jstring JNICALL
Java_com_villamediana_fio_local_LocalEngine_nativeMeta(JNIEnv * env, jobject, jlong h, jstring jkey) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) return nullptr;
    char buf[512];
    const int n = llama_model_meta_val_str(e->model, jstr(env, jkey).c_str(), buf, sizeof(buf));
    return n < 0 ? nullptr : env->NewStringUTF(buf);
}

} // extern "C"
