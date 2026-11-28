// JNI bridge between Kotlin (com.littlebit.chat.engine.LlamaEngine) and the
// repository's patched llama.cpp (LittleBit Q1_0 + Arm KleidiAI).
//
// Built for arm64-v8a only, with GGML_NATIVE=OFF / GGML_CPU_KLEIDIAI=ON /
// GGML_OPENMP=OFF — matching scripts/build-android.sh so in-app inference
// runs at Termux-binary speed without shelling out.

#include <jni.h>
#include <android/log.h>

#include <llama.h>
#include "jinja/parser.h"
#include "json.h"
#include <limits>
#include <string>
#include <cstring>

// Qwen3 channel tokens, built from char codes so no text pipeline can mangle them.
static const std::string kThinkOpen = std::string(1, char(60)) + "think" + char(62);
static const std::string kThinkClose = std::string(1, char(60)) + "/think" + char(62);
#include <cstdio>
#include <cstdarg>
#include <ggml-backend.h>
#include <gguf.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <deque>
#include <mutex>
#include <memory>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

#define LOG_TAG "LittleBitJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM * g_vm = nullptr;
std::once_flag g_init_flag;

// Cached classes / method IDs for the Kotlin callback interfaces
// (com.littlebit.chat.engine.LlamaEngine$ProgressCallback / $TokenCallback).
jclass    g_progress_cb_class    = nullptr;
jmethodID g_progress_on_progress = nullptr;
jclass    g_token_cb_class       = nullptr;
jmethodID g_token_on_token       = nullptr;
jmethodID g_token_on_complete    = nullptr;
jmethodID g_prefill = nullptr;
jmethodID g_token_on_error       = nullptr;

struct NativeEngine {
    llama_model *       model  = nullptr;
    llama_context *     ctx    = nullptr;
    const llama_vocab * vocab  = nullptr;
    std::atomic<bool>   stop{false};
    std::atomic<bool>   in_prefill{false};
    // Number of tokens currently decoded in the KV cache. Tracked across
    // nativeGenerate / nativeContinueGenerate so a speculative pre-warm can
    // be flushed and seamlessly continued without re-processing the prompt.
    int                 n_past = 0;
    std::vector<llama_token> cached_tokens;
    std::chrono::steady_clock::time_point started;
    double ttft_ms = 0, prefill_rate = 0, decode_rate = 0;
    int prompt_tokens = 0;
    double prompt_ms = 0, gen_ms = 0;
    bool thinking = false;
    int thought_tokens = 0;
    llama_token close_thought = -1;
    std::string arch;
    llama_context_params params{};
    NativeEngine * draft = nullptr; // Kotlin holds both handles under the same engine mutex.
    bool owns_model = true;
    int                 gpu_layers = 0;
    // UTF-8 code points may span token-piece boundaries or a speculative
    // generate/continue boundary. Keep incomplete bytes here until complete.
    std::string         pending_utf8;
};

// One small app-private breadcrumb per phase. No model paths, prompts or reply text.
std::mutex diagnostic_mutex;
std::string diagnostic_path;

void recordStage(const char * format, ...) {
    char message[320];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", message);
    std::lock_guard<std::mutex> lock(diagnostic_mutex);
    if (diagnostic_path.empty()) return;
    const auto tmp = diagnostic_path + ".tmp";
    if (FILE * file = fopen(tmp.c_str(), "w")) {
        const bool written = fputs(message, file) >= 0;
        if (fclose(file) == 0 && written) std::rename(tmp.c_str(), diagnostic_path.c_str());
    }
}

void throwIllegalState(JNIEnv * env, const std::string & msg) {
    jclass ex = env->FindClass("java/lang/IllegalStateException");
    if (ex != nullptr) {
        env->ThrowNew(ex, msg.c_str());
        env->DeleteLocalRef(ex);
    }
}

std::string toStdString(JNIEnv * env, jstring s) {
    if (s == nullptr) return std::string();
    const jsize length = env->GetStringLength(s);
    const jchar * chars = env->GetStringChars(s, nullptr);
    if (chars == nullptr) return std::string();
    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length
            && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[++i] - 0xDC00);
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp <= 0x7F) out.push_back(static_cast<char>(cp));
        else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

void logToAndroid(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (level >= GGML_LOG_LEVEL_ERROR && text != nullptr) {
        LOGE("%s", text);
    }
}

jmethodID getMethod(JNIEnv * env, jclass clazz, const char * name, const char * sig) {
    jmethodID mid = env->GetMethodID(clazz, name, sig);
    if (mid == nullptr) {
        throw std::runtime_error(std::string("callback method not found: ") + name + sig);
    }
    return mid;
}

struct ProgressState {
    JNIEnv * env;
    jobject  cb;
    bool cancelled = false;
};

bool progressThunk(float progress, void * user_data) {
    auto * st = static_cast<ProgressState *>(user_data);
    if (st != nullptr && st->cb != nullptr && g_progress_on_progress != nullptr) {
        st->env->CallVoidMethod(st->cb, g_progress_on_progress, progress);
        if (st->env->ExceptionCheck()) { st->env->ExceptionClear(); st->cancelled = true; return false; }
    }
    return true;
}

std::string tokenToPiece(const llama_vocab * vocab, llama_token token) {
    char buf[256];
    int len = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
    if (len >= 0) return std::string(buf, buf + len);
    std::vector<char> big(static_cast<size_t>(-len) + 1);
    len = llama_token_to_piece(vocab, token, big.data(), static_cast<int32_t>(big.size()), 0, false);
    if (len >= 0) return std::string(big.data(), big.data() + len);
    return std::string();
}


/** Decode ordinary UTF-8 token bytes to UTF-16; replace malformed sequences. */
jstring utf8ToJString(JNIEnv * env, const std::string & input) {
    std::vector<jchar> out;
    out.reserve(input.size());
    size_t i = 0;
    while (i < input.size()) {
        const auto a = static_cast<unsigned char>(input[i]);
        uint32_t cp = 0xFFFD;
        size_t n = 1;
        if (a <= 0x7F) cp = a;
        else if (a >= 0xC2 && a <= 0xDF && i + 1 < input.size()) {
            const auto b = static_cast<unsigned char>(input[i + 1]);
            if ((b & 0xC0) == 0x80) { cp = ((a & 0x1F) << 6) | (b & 0x3F); n = 2; }
        } else if (a >= 0xE0 && a <= 0xEF && i + 2 < input.size()) {
            const auto b = static_cast<unsigned char>(input[i + 1]);
            const auto c = static_cast<unsigned char>(input[i + 2]);
            if ((b & 0xC0) == 0x80 && (c & 0xC0) == 0x80
                && !(a == 0xE0 && b < 0xA0) && !(a == 0xED && b >= 0xA0)) {
                cp = ((a & 0x0F) << 12) | ((b & 0x3F) << 6) | (c & 0x3F); n = 3;
            }
        } else if (a >= 0xF0 && a <= 0xF4 && i + 3 < input.size()) {
            const auto b = static_cast<unsigned char>(input[i + 1]);
            const auto c = static_cast<unsigned char>(input[i + 2]);
            const auto d = static_cast<unsigned char>(input[i + 3]);
            if ((b & 0xC0) == 0x80 && (c & 0xC0) == 0x80 && (d & 0xC0) == 0x80
                && !(a == 0xF0 && b < 0x90) && !(a == 0xF4 && b > 0x8F)) {
                cp = ((a & 0x07) << 18) | ((b & 0x3F) << 12) | ((c & 0x3F) << 6) | (d & 0x3F); n = 4;
            }
        }
        i += n;
        if (cp <= 0xFFFF) out.push_back(static_cast<jchar>(cp));
        else {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}


size_t completeUtf8PrefixLength(const std::string & bytes) {
    size_t i = 0;
    while (i < bytes.size()) {
        const auto lead = static_cast<unsigned char>(bytes[i]);
        size_t width = 0;
        if (lead <= 0x7F) width = 1;
        else if (lead >= 0xC2 && lead <= 0xDF) width = 2;
        else if (lead >= 0xE0 && lead <= 0xEF) width = 3;
        else if (lead >= 0xF0 && lead <= 0xF4) width = 4;
        else { ++i; continue; }

        if (bytes.size() - i < width) {
            bool onlyContinuationTail = true;
            for (size_t j = i + 1; j < bytes.size(); ++j) {
                if ((static_cast<unsigned char>(bytes[j]) & 0xC0) != 0x80) {
                    onlyContinuationTail = false;
                    break;
                }
            }
            if (onlyContinuationTail) return i;
            ++i;
            continue;
        }

        bool valid = true;
        for (size_t j = 1; j < width; ++j) {
            if ((static_cast<unsigned char>(bytes[i + j]) & 0xC0) != 0x80) {
                valid = false;
                break;
            }
        }
        if (valid && width == 3) {
            const auto second = static_cast<unsigned char>(bytes[i + 1]);
            valid = !((lead == 0xE0 && second < 0xA0) ||
                      (lead == 0xED && second >= 0xA0));
        } else if (valid && width == 4) {
            const auto second = static_cast<unsigned char>(bytes[i + 1]);
            valid = !((lead == 0xF0 && second < 0x90) ||
                      (lead == 0xF4 && second > 0x8F));
        }
        i += valid ? width : 1;
    }
    return i;
}

bool deliverUtf8(JNIEnv * env, jobject callback, const std::string & bytes) {
    if (bytes.empty()) return true;
    jstring piece = utf8ToJString(env, bytes);
    if (piece == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    env->CallVoidMethod(callback, g_token_on_token, piece);
    const bool failed = env->ExceptionCheck();
    if (failed) env->ExceptionClear();
    env->DeleteLocalRef(piece);
    return !failed;
}

// Mirrors engine/StopStrings.kt byte-for-byte: holds back the final
// (longestStop - 1) bytes so a stop string split across token pieces can still
// be recognised, then releases everything that cannot be a stop prefix.
struct StopFilter {
    std::vector<std::string> stops; // longest first, de-duped, non-empty
    std::string holdback;
    bool matched = false;
    std::string matchedOn;

    explicit StopFilter(JNIEnv * env, jobjectArray jstops) {
        if (!jstops) return;
        jsize n = env->GetArrayLength(jstops);
        std::vector<std::string> tmp;
        tmp.reserve(static_cast<size_t>(n));
        for (jsize i = 0; i < n && i < 8; ++i) {
            jstring s = static_cast<jstring>(env->GetObjectArrayElement(jstops, i));
            if (!s) continue;
            tmp.push_back(toStdString(env, s));
            env->DeleteLocalRef(s);
        }
        std::sort(tmp.begin(), tmp.end());
        tmp.erase(std::unique(tmp.begin(), tmp.end()), tmp.end());
        tmp.erase(std::remove_if(tmp.begin(), tmp.end(),
                    [](const std::string & s) { return s.empty(); }), tmp.end());
        std::sort(tmp.begin(), tmp.end(),
                  [](const std::string & a, const std::string & b) {
                      return a.size() > b.size();
                  });
        stops = std::move(tmp);
    }

    std::string feed(const std::string & piece) {
        if (matched) return "";
        if (stops.empty()) return piece;
        holdback.append(piece);
        size_t bestAt = std::string::npos;
        std::string bestStop;
        for (const auto & stop : stops) {
            size_t at = holdback.find(stop);
            if (at != std::string::npos &&
                (bestAt == std::string::npos || at < bestAt)) {
                bestAt = at;
                bestStop = stop;
            }
        }
        if (bestAt != std::string::npos) {
            matched = true;
            matchedOn = bestStop;
            std::string out = holdback.substr(0, bestAt);
            holdback.clear();
            return out;
        }
        size_t keep = stops.front().size() - 1;
        if (holdback.size() <= keep) return "";
        size_t cut = holdback.size() - keep;
        std::string out = holdback.substr(0, cut);
        holdback.erase(0, cut);
        return out;
    }

    std::string finish() {
        if (matched) return "";
        std::string out = holdback;
        holdback.clear();
        return out;
    }
};

// Return the complete UTF-8 prefix instead of delivering it, so the caller can
// run it through the StopFilter before delivery.
std::string takeUtf8Piece(JNIEnv * env, jobject callback, std::string & pending,
                          const std::string & piece) {
    pending.append(piece);
    const size_t complete = completeUtf8PrefixLength(pending);
    if (complete == 0) return "";
    const std::string ready = pending.substr(0, complete);
    pending.erase(0, complete);
    return ready;
}

std::string takeUtf8Tail(JNIEnv * env, jobject callback, std::string & pending) {
    if (pending.empty()) return "";
    const size_t complete = completeUtf8PrefixLength(pending);
    std::string ready = pending.substr(0, complete);
    if (complete < pending.size()) ready.append("\xEF\xBF\xBD");
    pending.clear();
    return ready;
}

bool hasVulkanComputeDevice() {
    try {
        ggml_backend_reg_t vulkan = ggml_backend_reg_by_name("Vulkan");
        return vulkan != nullptr && ggml_backend_reg_dev_count(vulkan) > 0;
    } catch (const std::exception & e) {
        LOGE("Vulkan device probe failed: %s", e.what());
    } catch (...) {
        LOGE("Vulkan device probe failed with an unknown error");
    }
    return false;
}

// Decide before mapping model weights. A fatal vendor-driver signal cannot
// be caught by the normal context/decode error path.
bool useMaliGemmaCpu(const std::string & path) {
    auto reg = ggml_backend_reg_by_name("Vulkan");
    if (!reg) return false;
    bool mali = false;
    for (size_t i = 0; i < ggml_backend_reg_dev_count(reg); ++i) {
        const char * name = ggml_backend_dev_description(ggml_backend_reg_dev_get(reg, i));
        if (name && (std::strstr(name, "Mali") || std::strstr(name, "Immortalis"))) mali = true;
    }
    if (!mali) return false;
    std::unique_ptr<gguf_context, decltype(&gguf_free)> header(
        gguf_init_from_file(path.c_str(), {true, nullptr}), gguf_free);
    if (!header) return false;
    auto key = gguf_find_key(header.get(), "general.architecture");
    return key >= 0 && gguf_get_kv_type(header.get(), key) == GGUF_TYPE_STRING &&
           !std::strcmp(gguf_get_val_str(header.get(), key), "gemma4");
}

void callOnError(JNIEnv * env, jobject cb, const char * msg) {
    if (g_token_on_error == nullptr) return;
    jstring jmsg = utf8ToJString(env, std::string(msg));
    if (jmsg == nullptr) { if (env->ExceptionCheck()) env->ExceptionClear(); return; }
    env->CallVoidMethod(cb, g_token_on_error, jmsg);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jmsg);
}

constexpr jsize kSamplerSlotCount = 22;

enum SamplerSlot {
    SAMPLER_TEMPERATURE = 0,
    SAMPLER_TOP_K = 1,
    SAMPLER_TOP_P = 2,
    SAMPLER_MIN_P = 3,
    SAMPLER_REPEAT_PENALTY = 4,
    SAMPLER_PENALTY_LAST_N = 5,
    SAMPLER_FREQUENCY_PENALTY = 6,
    SAMPLER_PRESENCE_PENALTY = 7,
    SAMPLER_DRY_MULTIPLIER = 8,
    SAMPLER_DRY_BASE = 9,
    SAMPLER_DRY_ALLOWED_LENGTH = 10,
    SAMPLER_DRY_PENALTY_LAST_N = 11,
    SAMPLER_XTC_PROBABILITY = 12,
    SAMPLER_XTC_THRESHOLD = 13,
    SAMPLER_TOP_N_SIGMA = 14,
    SAMPLER_TYPICAL_P = 15,
    SAMPLER_DYNATEMP_RANGE = 16,
    SAMPLER_DYNATEMP_EXPONENT = 17,
    SAMPLER_MIROSTAT_ENABLED = 18,
    SAMPLER_MIROSTAT_TAU = 19,
    SAMPLER_MIROSTAT_ETA = 20,
    SAMPLER_SEED = 21,
};

struct SamplerOpts {
    double temperature = 0.7;
    int32_t top_k = 40;
    double top_p = 0.90;
    double min_p = 0.05;
    double repeat_penalty = 1.18;
    int32_t penalty_last_n = 64;
    double frequency_penalty = 0.0;
    double presence_penalty = 0.0;
    double dry_multiplier = 0.8;
    double dry_base = 1.75;
    int32_t dry_allowed_length = 2;
    int32_t dry_penalty_last_n = 64;
    double xtc_probability = 0.0;
    double xtc_threshold = 0.10;
    double top_n_sigma = 0.0;
    double typical_p = 1.0;
    double dynatemp_range = 0.0;
    double dynatemp_exponent = 1.0;
    bool mirostat_enabled = false;
    double mirostat_tau = 5.0;
    double mirostat_eta = 0.10;
    int64_t seed = -1;
};

double finiteClamp(double value, double minimum, double maximum, double fallback) {
    if (!std::isfinite(value)) return fallback;
    return std::max(minimum, std::min(maximum, value));
}

int32_t finiteIntClamp(double value, int32_t minimum, int32_t maximum, int32_t fallback) {
    if (!std::isfinite(value)) return fallback;
    return static_cast<int32_t>(std::max(static_cast<double>(minimum),
                                         std::min(static_cast<double>(maximum), value)));
}

// Owns the JNI array view until its values have been copied into SamplerOpts.
// JNI_ABORT discards any hypothetical writes without copying the array back.
class DoubleArrayView {
public:
    DoubleArrayView(JNIEnv * env, jdoubleArray array) : env_(env), array_(array) {
        values_ = env_->GetDoubleArrayElements(array_, nullptr);
    }

    ~DoubleArrayView() {
        if (values_ != nullptr) {
            env_->ReleaseDoubleArrayElements(array_, values_, JNI_ABORT);
        }
    }

    DoubleArrayView(const DoubleArrayView &) = delete;
    DoubleArrayView & operator=(const DoubleArrayView &) = delete;

    const jdouble * values() const { return values_; }

private:
    JNIEnv * env_;
    jdoubleArray array_;
    jdouble * values_ = nullptr;
};

// LLAMA_DEFAULT_SEED (0xFFFFFFFF) is llama.cpp's random-seed sentinel, but the
// app ABI reserves -1 for random and allows every non-negative uint32 seed.
// These small samplers mirror the pinned upstream CPU algorithms while seeding
// std::mt19937 directly, so an explicit 0xFFFFFFFF remains deterministic.
enum class ForcedSamplerKind {
    Dist,
    Xtc,
    MirostatV2,
};

struct ForcedSeedSampler {
    ForcedSamplerKind kind;
    uint32_t seed;
    float probability;
    float threshold;
    float tau;
    float eta;
    float mu;
    std::mt19937 rng;

    ForcedSeedSampler(ForcedSamplerKind kind, uint32_t seed,
                      float probability, float threshold, float tau, float eta)
        : kind(kind), seed(seed), probability(probability), threshold(threshold),
          tau(tau), eta(eta), mu(2.0f * tau), rng(seed) {}
};

const char * forcedSamplerName(const llama_sampler * smpl) {
    const auto * ctx = static_cast<const ForcedSeedSampler *>(smpl->ctx);
    switch (ctx->kind) {
        case ForcedSamplerKind::Dist:      return "dist-forced-seed";
        case ForcedSamplerKind::Xtc:       return "xtc-forced-seed";
        case ForcedSamplerKind::MirostatV2: return "mirostat-v2-forced-seed";
    }
    return "forced-seed";
}

void sortForcedSamplerCandidates(llama_token_data_array * cur_p) {
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sorter(
        llama_sampler_init_top_k(static_cast<int32_t>(cur_p->size)), llama_sampler_free);
    if (!sorter) return;
    llama_sampler_apply(sorter.get(), cur_p);
}

void forcedSamplerSoftmax(llama_token_data_array * cur_p, bool do_sort) {
    if (cur_p == nullptr || cur_p->data == nullptr || cur_p->size == 0) return;

    if (do_sort && !cur_p->sorted) {
        sortForcedSamplerCandidates(cur_p);
    }

    float max_logit = cur_p->data[0].logit;
    if (!cur_p->sorted) {
        for (size_t i = 1; i < cur_p->size; ++i) {
            max_logit = std::max(max_logit, cur_p->data[i].logit);
        }
    }

    float cumulative = 0.0f;
    for (size_t i = 0; i < cur_p->size; ++i) {
        const float probability = std::exp(cur_p->data[i].logit - max_logit);
        cur_p->data[i].p = probability;
        cumulative += probability;
    }
    for (size_t i = 0; i < cur_p->size; ++i) {
        cur_p->data[i].p /= cumulative;
    }
}

void forcedSamplerApply(llama_sampler * smpl, llama_token_data_array * cur_p) {
    auto * ctx = static_cast<ForcedSeedSampler *>(smpl->ctx);
    if (cur_p == nullptr || cur_p->data == nullptr || cur_p->size == 0) return;

    if (ctx->kind == ForcedSamplerKind::Dist) {
        forcedSamplerSoftmax(cur_p, false);
        double cumulative = 0.0;
        for (size_t i = 0; i < cur_p->size; ++i) {
            cumulative += cur_p->data[i].p;
        }

        std::uniform_real_distribution<double> distribution(0.0, 1.0);
        const double target = cumulative * distribution(ctx->rng);
        double running = 0.0;
        bool found = false;
        for (size_t i = 0; i < cur_p->size; ++i) {
            if (!found) {
                running += cur_p->data[i].p;
                if (running >= target) {
                    cur_p->selected = static_cast<int32_t>(i);
                    found = true;
                }
            }
            cur_p->data[i].p /= cumulative;
        }
        if (!found) {
            cur_p->selected = static_cast<int32_t>(cur_p->size - 1);
        }
        return;
    }

    if (ctx->kind == ForcedSamplerKind::Xtc) {
        std::uniform_real_distribution<float> distribution(0.0f, 1.0f);
        if (distribution(ctx->rng) > ctx->probability) return;

        forcedSamplerSoftmax(cur_p, true);
        size_t last_kept = 0;
        for (size_t i = 0; i < cur_p->size; ++i) {
            if (cur_p->data[i].p < ctx->threshold) break;
            last_kept = i;
        }
        if (cur_p->size - last_kept >= 1 && last_kept > 0) {
            cur_p->data += last_kept;
            cur_p->size -= last_kept;
        }
        return;
    }

    forcedSamplerSoftmax(cur_p, true);
    size_t kept = 0;
    while (kept < cur_p->size &&
           !(-std::log2(cur_p->data[kept].p) > ctx->mu)) {
        ++kept;
    }
    cur_p->size = kept;
    if (cur_p->size == 0) cur_p->size = 1;

    forcedSamplerSoftmax(cur_p, true);
    std::vector<float> probabilities;
    probabilities.reserve(cur_p->size);
    for (size_t i = 0; i < cur_p->size; ++i) {
        probabilities.push_back(cur_p->data[i].p);
    }
    std::discrete_distribution<int> distribution(probabilities.begin(), probabilities.end());
    const int selected = distribution(ctx->rng);
    cur_p->selected = selected;

    const float observed_surprise = -std::log2(cur_p->data[selected].p);
    const float error = observed_surprise - ctx->tau;
    ctx->mu = ctx->mu - ctx->eta * error;
}

void forcedSamplerReset(llama_sampler * smpl) {
    auto * ctx = static_cast<ForcedSeedSampler *>(smpl->ctx);
    ctx->rng.seed(ctx->seed);
    ctx->mu = 2.0f * ctx->tau;
}

llama_sampler * forcedSamplerClone(const llama_sampler * smpl);
void forcedSamplerFree(llama_sampler * smpl);

static llama_sampler_i forced_sampler_i = {
    /* .name              = */ forcedSamplerName,
    /* .accept            = */ nullptr,
    /* .apply             = */ forcedSamplerApply,
    /* .reset             = */ forcedSamplerReset,
    /* .clone             = */ forcedSamplerClone,
    /* .free              = */ forcedSamplerFree,
    /* .backend_init      = */ nullptr,
    /* .backend_accept    = */ nullptr,
    /* .backend_apply     = */ nullptr,
    /* .backend_set_input = */ nullptr,
    /* .backend_reset     = */ nullptr,
    /* .copy_state        = */ nullptr,
};

llama_sampler * forcedSamplerClone(const llama_sampler * smpl) {
    const auto * source = static_cast<const ForcedSeedSampler *>(smpl->ctx);
    auto * copy = new ForcedSeedSampler(*source);
    auto * result = llama_sampler_init(&forced_sampler_i, copy);
    if (!result) delete copy;
    return result;
}

void forcedSamplerFree(llama_sampler * smpl) {
    delete static_cast<ForcedSeedSampler *>(smpl->ctx);
}

llama_sampler * initForcedSeedSampler(ForcedSamplerKind kind, uint32_t seed,
                                       float probability, float threshold,
                                       float tau, float eta) {
    auto * ctx = new ForcedSeedSampler(kind, seed, probability, threshold, tau, eta);
    auto * result = llama_sampler_init(&forced_sampler_i, ctx);
    if (!result) delete ctx;
    return result;
}

bool readSamplerOpts(JNIEnv * env, jdoubleArray sampler, SamplerOpts & o) {
    if (sampler == nullptr) throw std::runtime_error("sampler ABI is null");
    if (env->GetArrayLength(sampler) != kSamplerSlotCount) {
        throw std::runtime_error("sampler ABI requires exactly 22 slots");
    }

    DoubleArrayView view(env, sampler);
    if (view.values() == nullptr) return false;
    const jdouble * v = view.values();

    o.temperature = finiteClamp(v[SAMPLER_TEMPERATURE], 0.0, 2.0, o.temperature);
    o.top_k = finiteIntClamp(v[SAMPLER_TOP_K], 0, 1000000, o.top_k);
    o.top_p = finiteClamp(v[SAMPLER_TOP_P], 0.0, 1.0, o.top_p);
    o.min_p = finiteClamp(v[SAMPLER_MIN_P], 0.0, 1.0, o.min_p);
    o.repeat_penalty = finiteClamp(v[SAMPLER_REPEAT_PENALTY], 1.0, 2.0, o.repeat_penalty);
    o.penalty_last_n = finiteIntClamp(v[SAMPLER_PENALTY_LAST_N], 0, 1000000, o.penalty_last_n);
    o.frequency_penalty = finiteClamp(v[SAMPLER_FREQUENCY_PENALTY], -2.0, 2.0, o.frequency_penalty);
    o.presence_penalty = finiteClamp(v[SAMPLER_PRESENCE_PENALTY], -2.0, 2.0, o.presence_penalty);
    o.dry_multiplier = finiteClamp(v[SAMPLER_DRY_MULTIPLIER], 0.0, 2.0, o.dry_multiplier);
    o.dry_base = finiteClamp(v[SAMPLER_DRY_BASE], 1.0, 4.0, o.dry_base);
    o.dry_allowed_length = finiteIntClamp(v[SAMPLER_DRY_ALLOWED_LENGTH], 0, 128, o.dry_allowed_length);
    o.dry_penalty_last_n = finiteIntClamp(v[SAMPLER_DRY_PENALTY_LAST_N], 0, 1000000, o.dry_penalty_last_n);
    o.xtc_probability = finiteClamp(v[SAMPLER_XTC_PROBABILITY], 0.0, 1.0, o.xtc_probability);
    o.xtc_threshold = finiteClamp(v[SAMPLER_XTC_THRESHOLD], 0.0, 0.5, o.xtc_threshold);
    o.top_n_sigma = finiteClamp(v[SAMPLER_TOP_N_SIGMA], 0.0, 100.0, o.top_n_sigma);
    o.typical_p = finiteClamp(v[SAMPLER_TYPICAL_P], 0.0, 1.0, o.typical_p);
    o.dynatemp_range = finiteClamp(v[SAMPLER_DYNATEMP_RANGE], 0.0, 4.0, o.dynatemp_range);
    o.dynatemp_exponent = finiteClamp(v[SAMPLER_DYNATEMP_EXPONENT], 0.01, 4.0, o.dynatemp_exponent);
    o.mirostat_enabled = std::isfinite(v[SAMPLER_MIROSTAT_ENABLED]) && v[SAMPLER_MIROSTAT_ENABLED] != 0.0;
    o.mirostat_tau = finiteClamp(v[SAMPLER_MIROSTAT_TAU], 0.01, 10.0, o.mirostat_tau);
    o.mirostat_eta = finiteClamp(v[SAMPLER_MIROSTAT_ETA], 0.001, 1.0, o.mirostat_eta);
    const double seed = finiteClamp(v[SAMPLER_SEED], -1.0, 4294967295.0, -1.0);
    o.seed = seed < 0.0 ? -1 : static_cast<int64_t>(seed);
    return true;
}

// Canonical chain: penalties -> dry -> top_k -> typical -> top_n_sigma ->
// top_p -> min_p -> xtc -> temp_ext/temp -> mirostat_v2/dist.
llama_sampler * buildSampler(JNIEnv * env, jdoubleArray sampler,
                             const llama_vocab * vocab, int n_vocab) {
    SamplerOpts o;
    if (!readSamplerOpts(env, sampler, o)) return nullptr;

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * chain = llama_sampler_chain_init(sparams);
    if (chain == nullptr) return nullptr;

    auto add = [chain](llama_sampler * node) {
        if (node == nullptr) throw std::runtime_error("could not create sampler");
        llama_sampler_chain_add(chain, node);
    };

    try {
        add(llama_sampler_init_penalties(
                n_vocab, o.penalty_last_n, static_cast<float>(o.repeat_penalty),
                static_cast<float>(o.frequency_penalty), static_cast<float>(o.presence_penalty)));
        if (o.dry_multiplier > 0.0) {
            static const char * breakers[] = {"\n", ":", "\"", "*"};
            add(llama_sampler_init_dry(
                    vocab, static_cast<float>(o.dry_multiplier), static_cast<float>(o.dry_base),
                    o.dry_allowed_length, o.dry_penalty_last_n, breakers, 4));
        }
        if (o.top_k > 0) {
            add(llama_sampler_init_top_k(o.top_k));
        }
        if (!o.mirostat_enabled) {
            if (o.typical_p < 1.0) {
                add(llama_sampler_init_typical(static_cast<float>(o.typical_p), 1));
            }
            if (o.top_n_sigma > 0.0) {
                add(llama_sampler_init_top_n_sigma(static_cast<float>(o.top_n_sigma)));
            }
            if (o.top_p < 1.0) {
                add(llama_sampler_init_top_p(static_cast<float>(o.top_p), 1));
            }
            if (o.min_p > 0.0) {
                add(llama_sampler_init_min_p(static_cast<float>(o.min_p), 1));
            }
        }

        const bool locked_seed = o.seed >= 0;
        const uint32_t useed = locked_seed
            ? static_cast<uint32_t>(o.seed)
            : static_cast<uint32_t>(LLAMA_DEFAULT_SEED);
        const bool force_explicit_seed = locked_seed && useed == LLAMA_DEFAULT_SEED;
        if (!o.mirostat_enabled && o.xtc_probability > 0.0) {
            add(force_explicit_seed
                    ? initForcedSeedSampler(
                        ForcedSamplerKind::Xtc, useed,
                        static_cast<float>(o.xtc_probability),
                        static_cast<float>(o.xtc_threshold), 0.0f, 0.0f)
                    : llama_sampler_init_xtc(
                        static_cast<float>(o.xtc_probability),
                        static_cast<float>(o.xtc_threshold), 1, useed));
        }
        if (o.dynatemp_range > 0.0) {
            add(llama_sampler_init_temp_ext(
                    static_cast<float>(o.temperature), static_cast<float>(o.dynatemp_range),
                    static_cast<float>(o.dynatemp_exponent)));
        } else {
            add(llama_sampler_init_temp(static_cast<float>(o.temperature)));
        }
        if (o.mirostat_enabled) {
            add(force_explicit_seed
                    ? initForcedSeedSampler(
                        ForcedSamplerKind::MirostatV2, useed, 0.0f, 0.0f,
                        static_cast<float>(o.mirostat_tau),
                        static_cast<float>(o.mirostat_eta))
                    : llama_sampler_init_mirostat_v2(
                        useed, static_cast<float>(o.mirostat_tau),
                        static_cast<float>(o.mirostat_eta)));
        } else {
            add(force_explicit_seed
                    ? initForcedSeedSampler(
                        ForcedSamplerKind::Dist, useed, 0.0f, 0.0f, 0.0f, 0.0f)
                    : llama_sampler_init_dist(useed));
        }
    } catch (...) {
        llama_sampler_free(chain);
        throw;
    }
    return chain;
}

// Sampling loop shared by nativeGenerate and nativeContinueGenerate.
// Returns 0 finished, 1 stopped/aborted, 2 context full, -1 error.
// Batch positions are explicit because accepted target tokens can replace a proposed suffix.
bool decodeTokens(NativeEngine * e, const llama_token * tokens, int count, int pos, bool all_logits) {
    std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(
        llama_batch_ext_init(e->ctx), llama_batch_ext_free);
    if (!batch) return false;
    for (int i = 0; i < count; ++i) {
        int idx = llama_batch_ext_add_token(batch.get(), 0, tokens[i]);
        llama_pos at = pos + i;
        if (idx < 0 || !llama_batch_ext_set_pos(batch.get(), idx, &at) ||
            !llama_batch_ext_set_output_logits(batch.get(), idx, all_logits || i == count - 1)) return false;
    }
    return llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) == 0;
}

void clearHistory(NativeEngine * e) {
    llama_memory_clear(llama_get_memory(e->ctx), false);
    e->cached_tokens.clear();
    e->n_past = 0;
}

bool draftGuesses(NativeEngine * target, llama_token confirmed, int limit,
                  bool & synchronized, std::vector<llama_token> & batch) {
    auto * d = target->draft;
    int prefix = 0;
    if (synchronized) {
        // The previous block began with a confirmed target token. All positions
        // before the next mismatch have already been accepted by the target.
        prefix = std::min(d->n_past, target->n_past);
    } else {
        while (prefix < d->n_past && prefix < target->n_past &&
               d->cached_tokens[prefix] == target->cached_tokens[prefix]) ++prefix;
    }
    if (prefix < d->n_past && !llama_memory_seq_rm(llama_get_memory(d->ctx), 0, prefix, -1)) {
        clearHistory(d);
        prefix = 0;
    }
    d->cached_tokens.resize(prefix);
    d->n_past = prefix;
    while (prefix < target->n_past && !target->stop.load()) {
        int n = std::min(64, target->n_past - prefix);
        auto start = target->cached_tokens.data() + prefix;
        if (!decodeTokens(d, start, n, prefix, false)) return false;
        d->cached_tokens.insert(d->cached_tokens.end(), start, start + n);
        prefix += n;
        d->n_past = prefix;
    }
    synchronized = true;
    llama_token token = confirmed;
    for (int i = 0; i < limit && !target->stop.load(); ++i) {
        if (!decodeTokens(d, &token, 1, d->n_past, false)) return false;
        d->cached_tokens.push_back(token);
        ++d->n_past;
        auto logits = llama_get_logits_ith(d->ctx, -1);
        if (!logits) return false;
        token = static_cast<llama_token>(std::max_element(logits, logits + llama_vocab_n_tokens(d->vocab)) - logits);
        if (llama_vocab_is_eog(d->vocab, token)) break;
        batch.push_back(token);
    }
    return true;
}

int runSamplingLoop(JNIEnv * env, NativeEngine * e, jobject cb,
                    llama_sampler * chain, jint max_tokens, jint budget,
                    StopFilter * stopFilter) {
    const int n_ctx   = static_cast<int>(llama_n_ctx(e->ctx));
    const int n_vocab = static_cast<int>(llama_vocab_n_tokens(e->vocab));

    recordStage("token_decode arch=%s layers=%d pos=%d", e->arch.c_str(), e->gpu_layers, e->n_past);
    const auto t0 = std::chrono::steady_clock::now();
    int n_gen = 0;
    int status = 0; // 0 = finished, 1 = stopped/aborted, 2 = context full
    bool reachedEog = false;
    std::vector<llama_token_data> candidates(static_cast<size_t>(n_vocab));

    std::vector<llama_token> verified;
    int next = 0;
    int logits_index = -1;
    bool draft_synced = false;
    while (!e->stop.load()) {
        if (n_gen >= max_tokens) break;
        if (e->n_past >= n_ctx - 1) {
            status = 2;
            callOnError(env, cb, "context window is full — start a new chat");
            break;
        }

        float * logits = llama_get_logits_ith(e->ctx, logits_index);
        if (logits == nullptr) {
            callOnError(env, cb, "no logits available");
            status = -1;
            break;
        }
        for (int v = 0; v < n_vocab; v++) {
            candidates[static_cast<size_t>(v)] = llama_token_data{v, logits[v], 0.0f};
        }
        llama_token_data_array cur{ candidates.data(), static_cast<size_t>(n_vocab), -1, false };
        llama_sampler_apply(chain, &cur);
        if (!cur.data || cur.selected < 0 || static_cast<size_t>(cur.selected) >= cur.size) {
            callOnError(env, cb, "token_decode: sampler returned no valid candidate");
            status = -1;
            break;
        }
        const llama_token tok = budget >= 0 && e->thinking && e->thought_tokens >= budget && e->close_thought >= 0
            ? e->close_thought : cur.data[cur.selected].id;
        llama_sampler_accept(chain, tok);

        if (llama_vocab_is_eog(e->vocab, tok)) {
            reachedEog = true;
            break;
        }

        if (n_gen == 0) e->ttft_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - e->started).count();
        std::string piece = tokenToPiece(e->vocab, tok);
        if (e->arch == "gemma4") {
            const char * raw = llama_vocab_get_text(e->vocab, tok);
            if (raw && !std::strcmp(raw, "<|channel>")) piece = kThinkOpen;
            if (raw && !std::strcmp(raw, "<channel|>")) piece = kThinkClose;
        }
        if (budget >= 0 && e->close_thought >= 0) {
            const char *raw = llama_vocab_get_text(e->vocab, tok);
            if (tok == e->close_thought) { e->thinking = false; piece = kThinkClose; }
            else if (raw && (!std::strcmp(raw, kThinkOpen.c_str()) || !std::strcmp(raw, "<|channel>"))) {
                e->thinking = true; e->thought_tokens = 0; piece = kThinkOpen;
            } else if (e->thinking) ++e->thought_tokens;
        }
        const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        e->decode_rate = seconds > 0 ? (n_gen + 1) / seconds : 0;
        if (!piece.empty()) {
            std::string ready = takeUtf8Piece(env, cb, e->pending_utf8, piece);
            if (!ready.empty()) {
                std::string safe = stopFilter ? stopFilter->feed(ready) : ready;
                if (!safe.empty() && !deliverUtf8(env, cb, safe)) {
                    status = -1;
                    break;
                }
            }
        }
        n_gen++;

        if (next < static_cast<int>(verified.size()) && verified[next] == tok) {
            logits_index = next++;
        } else {
            if (next < static_cast<int>(verified.size()) &&
                !llama_memory_seq_rm(llama_get_memory(e->ctx), 0, e->n_past, -1)) {
                clearHistory(e);
                status = -1;
                break;
            }
            verified.assign(1, tok);
            const int limit = std::min({4, n_ctx - e->n_past - 2, max_tokens - n_gen});
            if (e->draft && limit > 0 && !draftGuesses(e, tok, limit, draft_synced, verified)) {
                clearHistory(e->draft);
                e->draft = nullptr;
                verified.resize(1);
            }
            if (!decodeTokens(e, verified.data(), static_cast<int>(verified.size()), e->n_past, true)) {
                clearHistory(e);
                callOnError(env, cb, "token_decode: backend rejected the token batch");
                status = -1;
                break;
            }
            next = 1;
            logits_index = 0;
        }
        e->cached_tokens.push_back(tok);
        e->n_past++;
    }

    // ContinueGenerate expects the last logits row to describe the committed tail.
    if (status >= 0 && next < static_cast<int>(verified.size()) && e->n_past > 0) {
        int last = e->n_past - 1;
        if (!llama_memory_seq_rm(llama_get_memory(e->ctx), 0, last, -1) ||
            !decodeTokens(e, &e->cached_tokens[last], 1, last, false)) {
            clearHistory(e);
            status = -1;
        }
    }
    if (reachedEog) {
        std::string tail = takeUtf8Tail(env, cb, e->pending_utf8);
        if (!tail.empty()) {
            std::string safe = stopFilter ? stopFilter->feed(tail) : tail;
            if (!safe.empty() && !deliverUtf8(env, cb, safe)) status = -1;
        }
        std::string flushed = stopFilter ? stopFilter->finish() : "";
        if (!flushed.empty() && !deliverUtf8(env, cb, flushed)) status = -1;
    }

    const double elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    e->gen_ms = elapsed * 1000.0;
    if (status >= 0) recordStage("complete arch=%s layers=%d ctx=%d generated=%d",
                                 e->arch.c_str(), e->gpu_layers, n_ctx, n_gen);
    const double tps = elapsed > 0.0 ? static_cast<double>(n_gen) / elapsed : 0.0;
    env->CallVoidMethod(cb, g_token_on_complete, n_gen, tps);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return -1; }
    return status;
}

} // namespace

jint JNI_OnLoad(JavaVM * vm, void * /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeInit(JNIEnv * env, jobject) {
    std::call_once(g_init_flag, [&]() {
        llama_log_set(logToAndroid, nullptr);
        llama_backend_init();

        jclass local = env->FindClass("com/littlebit/chat/engine/LlamaEngine$ProgressCallback");
        if (local == nullptr) return;
        g_progress_cb_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);

        local = env->FindClass("com/littlebit/chat/engine/LlamaEngine$TokenCallback");
        if (local == nullptr) return;
        g_token_cb_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);

        try {
            g_progress_on_progress = getMethod(env, g_progress_cb_class, "onProgress", "(F)V");
            g_token_on_token       = getMethod(env, g_token_cb_class, "onToken", "(Ljava/lang/String;)V");
            g_token_on_complete    = getMethod(env, g_token_cb_class, "onComplete", "(ID)V");
            g_prefill = getMethod(env, g_token_cb_class, "onPrefill", "(II)V");
            g_token_on_error       = getMethod(env, g_token_cb_class, "onError", "(Ljava/lang/String;)V");
        } catch (const std::exception & e) {
            LOGE("callback init failed: %s", e.what());
        }
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeSetDiagnosticPath(JNIEnv *env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(diagnostic_mutex);
    diagnostic_path = toStdString(env, path);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeLoadModel(
        JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads, jint n_batch,
        jint n_gpu_layers, jboolean flash, jobject jprogress) {
    try {
        recordStage("model_load layers=%d ctx=%d threads=%d", n_gpu_layers, n_ctx, n_threads);
        const std::string path = toStdString(env, jpath);
        auto engine = std::make_unique<NativeEngine>();

        jobject progress_ref = nullptr;
        std::unique_ptr<ProgressState> pst;
        if (jprogress != nullptr) {
            progress_ref = env->NewGlobalRef(jprogress);
            if (progress_ref == nullptr) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                throw std::runtime_error("could not retain model-load progress callback");
            }
            pst = std::make_unique<ProgressState>(ProgressState{env, progress_ref});
        }

        const int requested_gpu_layers = std::max(0, static_cast<int>(n_gpu_layers));
        const bool vulkan_ready = requested_gpu_layers > 0 && hasVulkanComputeDevice() && !useMaliGemmaCpu(path);
        if (requested_gpu_layers > 0 && !vulkan_ready) {
            LOGE("Vulkan offload requested without a usable Vulkan compute device; falling back to CPU/KleidiAI");
        }

        const int attempts[] = { vulkan_ready ? requested_gpu_layers : 0, 0 };
        const size_t attempt_count = vulkan_ready ? 2 : 1;
        std::string last_error = "unknown model load failure";
        bool loaded = false;

        for (size_t attempt = 0; attempt < attempt_count; ++attempt) {
            const int layers = attempts[attempt];
            try {
                llama_model_params mparams = llama_model_default_params();
                // Zero GPU layers alone still lets AUTO inspect GPU devices
                // and disable mmap when a device lacks mmap support.
                static ggml_backend_dev_t cpu_only[] = {nullptr};
                if (layers == 0) {
                    mparams.devices = cpu_only;
                    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
                }
                mparams.n_gpu_layers = layers;
                mparams.progress_callback = pst ? progressThunk : nullptr;
                mparams.progress_callback_user_data = pst.get();

                engine->model = llama_model_load_from_file(path.c_str(), mparams);
                if (engine->model == nullptr) {
                    throw std::runtime_error("model_load: llama_model_load_from_file returned null");
                }
                char arch[128] = {};
                llama_model_meta_val_str(engine->model, "general.architecture", arch, sizeof(arch));
                engine->arch = arch;
                const bool gemma4 = engine->arch == "gemma4";
                engine->vocab = llama_model_get_vocab(engine->model);

                llama_context_params cparams = llama_context_default_params();
                cparams.n_ctx           = std::clamp(n_ctx > 0 ? n_ctx : 2048, 128, 16384);
                cparams.n_batch         = std::clamp(n_batch > 0 ? n_batch : 512, 1, std::min(512, static_cast<int>(cparams.n_ctx)));
                cparams.n_ubatch        = std::min(cparams.n_batch, gemma4 ? 32u : (layers > 0 ? 128u : 64u));
                cparams.op_offload = layers > 0;
                cparams.offload_kqv = layers > 0;
                if (layers == 0) {
                    cparams.abort_callback = [](void * data) {
                        auto * e = static_cast<NativeEngine *>(data);
                        return e->in_prefill.load() && e->stop.load();
                    };
                    cparams.abort_callback_data = engine.get();
                }
                cparams.n_threads       = n_threads;
                cparams.n_threads_batch = n_threads;

                recordStage("ctx_init arch=%s layers=%d ctx=%u ubatch=%u", arch, layers, cparams.n_ctx, cparams.n_ubatch);
                if (layers > 0 && flash && !gemma4) {
                    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
                    cparams.type_k = cparams.type_v = GGML_TYPE_Q8_0;
                    engine->ctx = llama_init_from_model(engine->model, cparams);
                }
                if (!engine->ctx) {
                    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
                    cparams.type_k = cparams.type_v = GGML_TYPE_F16;
                    engine->ctx = llama_init_from_model(engine->model, cparams);
                }
                if (engine->ctx == nullptr) {
                    throw std::runtime_error("ctx_init: failed to create llama context");
                }
                engine->params = cparams;
                engine->gpu_layers = layers;
                // Exercise the actual GPU graph before declaring the model ready.
                if (layers > 0) {
                    llama_token probe = llama_vocab_bos(engine->vocab);
                    if (probe == LLAMA_TOKEN_NULL) probe = 0;
                    recordStage("prefill_decode startup_probe arch=%s layers=%d ctx=%u", arch, layers, cparams.n_ctx);
                    const llama_token pair[] = {probe, probe};
                    if (!decodeTokens(engine.get(), pair, 2, 0, false) ||
                        !decodeTokens(engine.get(), &probe, 1, 2, false)) {
                        throw std::runtime_error("prefill_decode/token_decode: Vulkan startup probe failed");
                    }
                    clearHistory(engine.get());
                }
                loaded = true;
                break;
            } catch (const std::exception & e) {
                last_error = e.what();
            } catch (...) {
                last_error = "unknown exception while initializing model/backend";
            }

            if (engine->ctx != nullptr) {
                llama_free(engine->ctx);
                engine->ctx = nullptr;
            }
            if (engine->model != nullptr) {
                llama_model_free(engine->model);
                engine->model = nullptr;
            }
            engine->vocab = nullptr;
            if (pst && pst->cancelled) break;
            if (layers > 0) {
                LOGE("Vulkan model/context initialization failed (%s); retrying CPU/KleidiAI",
                     last_error.c_str());
            }
        }

        if (progress_ref != nullptr) env->DeleteGlobalRef(progress_ref);
        pst.reset();

        if (!loaded) {
            throw std::runtime_error("failed to load model " + path + ": " + last_error);
        }
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception & e) {
        throwIllegalState(env, e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeModelDesc(JNIEnv * env, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->model == nullptr) return nullptr;
    char buf[256];
    llama_model_desc(e->model, buf, sizeof(buf));
    return utf8ToJString(env, std::string(buf));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeArchitecture(JNIEnv *env, jobject, jlong handle) {
    return env->NewStringUTF(reinterpret_cast<NativeEngine *>(handle)->arch.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeModelGpuLayers(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    return e != nullptr ? e->gpu_layers : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeModelSizeBytes(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->model == nullptr) return 0;
    return static_cast<jlong>(llama_model_size(e->model));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeModelFtype(JNIEnv * env, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->model == nullptr) return nullptr;
    return env->NewStringUTF(llama_ftype_name(llama_model_ftype(e->model)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeFormatChat(
        JNIEnv * env, jobject, jlong handle, jstring jsystem, jobjectArray jroles, jobjectArray jcontents, jboolean thinking) {
    try {
        auto * e = reinterpret_cast<NativeEngine *>(handle);
        if (e == nullptr || e->model == nullptr) throw std::runtime_error("no model loaded");

        std::deque<std::string> storage; // stable references while we build the message array
        std::vector<llama_chat_message> msgs;
        std::string system_prompt;

        if (jsystem != nullptr) {
            system_prompt = toStdString(env, jsystem);
            msgs.push_back({"system", system_prompt.c_str()});
        }
        const jsize n = env->GetArrayLength(jroles);
        if (env->GetArrayLength(jcontents) != n) throw std::runtime_error("template_apply: mismatched messages");
        for (jsize i = 0; i < n; i++) {
            auto * jr = static_cast<jstring>(env->GetObjectArrayElement(jroles, i));
            auto * jc = static_cast<jstring>(env->GetObjectArrayElement(jcontents, i));
            storage.emplace_back(toStdString(env, jr));
            const char * role = storage.back().c_str();
            storage.emplace_back(toStdString(env, jc));
            const char * content = storage.back().c_str();
            if (jr != nullptr) env->DeleteLocalRef(jr);
            if (jc != nullptr) env->DeleteLocalRef(jc);
            msgs.push_back({role, content});
        }

        // Prefer the GGUF-embedded chat template, but NEVER let it crash a turn:
        // llama.cpp's --jinja path can throw std::out_of_range when prior
        // assistant turns contain <think> blocks (multi-turn crash on Qwen3).
        // Kotlin strips those blocks before formatting; the ChatML fallback
        // below is the second line of defence so multi-turn NEVER crashes.
        recordStage("template_apply arch=%s layers=%d ctx=%u", e->arch.c_str(), e->gpu_layers, llama_n_ctx(e->ctx));
        const char * tmpl = llama_model_chat_template(e->model, nullptr);
        if (e->arch == "gemma4") {
            if (!tmpl) throw std::runtime_error("template_apply: missing Gemma 4 chat template");
            try {
                jinja::lexer lexer;
                auto lexed = lexer.tokenize(tmpl);
                auto program = jinja::parse_from_tokens(lexed);
                jinja::context ctx(lexed.source);
                auto rows = common_json::array();
                for (const auto & m : msgs) rows.push_back(common_json{{"role", m.role}, {"content", m.content}});
                auto special = [&](llama_token id) {
                    return id == LLAMA_TOKEN_NULL ? "" : llama_vocab_get_text(e->vocab, id);
                };
                common_json vars{{"messages", rows}, {"add_generation_prompt", true},
                    {"enable_thinking", bool(thinking)}, {"bos_token", special(llama_vocab_bos(e->vocab))},
                    {"eos_token", special(llama_vocab_eos(e->vocab))}};
                jinja::global_from_json(ctx, vars, false);
                jinja::runtime runtime(ctx);
                return utf8ToJString(env, jinja::runtime::gather_string_parts(runtime.execute(program))->as_string().str());
            } catch (const std::exception & err) {
                throw std::runtime_error(std::string("template_apply: Gemma 4: ") + err.what());
            }
        }
        if (tmpl != nullptr) {
            try {
                int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, nullptr, 0);
                if (len < 0) throw std::runtime_error("chat template failed");
                std::vector<char> buf(static_cast<size_t>(len) + 16);
                len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                                buf.data(), static_cast<int32_t>(buf.size()));
                if (len < 0 || static_cast<size_t>(len) >= buf.size()) throw std::runtime_error("chat template length changed");
                return utf8ToJString(env, std::string(buf.data(), static_cast<size_t>(len)));
            } catch (const std::exception & ex) {
                LOGE("chat template failed (%s); falling back to ChatML", ex.what());
            }
        }

        // Safe ChatML fallback: <|im_start|>role\ncontent<|im_end|>\n ...
        std::string out;
        for (const auto & m : msgs) {
            out += "<|im_start|>" + std::string(m.role) + "\n" + m.content + "<|im_end|>\n";
        }
        out += "<|im_start|>assistant\n";
        return utf8ToJString(env, out);
    } catch (const std::exception & e) {
        throwIllegalState(env, e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jstring jprompt, jdoubleArray sampler,
        jint max_tokens, jint budget, jobjectArray jstopStrings, jobject jcallback) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->ctx == nullptr) {
        throwIllegalState(env, "no model loaded");
        return -1;
    }
    if (jcallback == nullptr) {
        throwIllegalState(env, "callback is null");
        return -1;
    }
    StopFilter stopFilter(env, jstopStrings);
    jobject cb = env->NewGlobalRef(jcallback);
    if (cb == nullptr) { if (env->ExceptionCheck()) env->ExceptionClear(); throwIllegalState(env, "could not retain generation callback"); return -1; }

    try {
        e->started = std::chrono::steady_clock::now();
        e->ttft_ms = 0;
        e->decode_rate = 0;
        e->prompt_tokens = 0; e->prompt_ms = 0; e->gen_ms = 0;
        e->stop.store(false);
        // A fresh prompt owns a fresh byte stream. Only a same-turn
        // nativeContinueGenerate may finish a code point from the prior call.
        e->pending_utf8.clear();
        const std::string prompt = toStdString(env, jprompt);
        e->thinking = false;
        e->thought_tokens = 0;
        e->close_thought = -1;
        if (budget >= 0 && (e->arch.find("qwen3") == 0 || e->arch == "gemma4")) {
            const char *close = e->arch == "gemma4" ? "<channel|>" : "</think>";
            llama_token candidate;
            if (llama_tokenize(e->vocab, close, std::strlen(close), &candidate, 1, false, true) == 1)
                e->close_thought = candidate;
            const auto last = prompt.find_last_not_of(" \r\n\t");
            if (last != std::string::npos && last >= 6)
                e->thinking = prompt.compare(last - 6, 7, "<think>") == 0;
        }
        const int n_ctx   = static_cast<int>(llama_n_ctx(e->ctx));
        const int logical_batch = static_cast<int>(llama_n_batch(e->ctx));
        const int n_batch = std::min(static_cast<int>(llama_n_ubatch(e->ctx)), logical_batch);
        const int n_vocab = static_cast<int>(llama_vocab_n_tokens(e->vocab));

        // Tokenize the fully-templated prompt (special tokens parsed, BOS left to the template).
        if (prompt.size() > static_cast<size_t>(INT32_MAX - 8)) throw std::runtime_error("template_apply: prompt too large");
        std::vector<llama_token> prompt_tokens(prompt.size() + 8);
        int n_prompt = llama_tokenize(e->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                      prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()),
                                      false, true);
        if (n_prompt < 0) {
            if (n_prompt == INT32_MIN) throw std::runtime_error("template_apply: token count overflow");
            prompt_tokens.resize(static_cast<size_t>(-n_prompt));
            n_prompt = llama_tokenize(e->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                      prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()),
                                      false, true);
        }
        if (n_prompt <= 0) {
            callOnError(env, cb, "failed to tokenize prompt");
            env->DeleteGlobalRef(cb);
            return -1;
        }
        prompt_tokens.resize(static_cast<size_t>(n_prompt));
        if (n_prompt > n_ctx - 4) {
            callOnError(env, cb, "prompt is too long for the configured context size");
            env->DeleteGlobalRef(cb);
            return -1;
        }

        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> chain(
            buildSampler(env, sampler, e->vocab, n_vocab), llama_sampler_free);
        if (!chain) {
            env->DeleteGlobalRef(cb);
            if (!env->ExceptionCheck()) throwIllegalState(env, "could not create sampler");
            return -1;
        }

        bool failed = false;

        // Re-evaluate the final prompt token even for an exact match: the
        // sampling loop needs logits for that position, and a restored KV cache
        // that already covers the whole prompt must not skip the only decode
        // that produces them.
        int lcp = 0;
        while (lcp < n_prompt - 1 && lcp < e->n_past &&
               e->cached_tokens[lcp] == prompt_tokens[lcp]) ++lcp;
        if (n_prompt > 0) lcp = std::min(lcp, n_prompt - 1);
        auto mem = llama_get_memory(e->ctx);
        if ((lcp == 0 && e->n_past > 0) || (lcp < e->n_past && (llama_memory_seq_pos_min(mem, 0) > 0 ||
                !llama_memory_seq_rm(mem, 0, lcp, -1)))) {
            llama_memory_clear(mem, false);
            lcp = 0;
        }
        e->cached_tokens.resize(lcp);
        e->n_past = lcp;
        const auto prefill_start = std::chrono::steady_clock::now();
        recordStage("prefill_decode arch=%s layers=%d ctx=%d batch=%d ubatch=%d", e->arch.c_str(), e->gpu_layers, n_ctx, logical_batch, n_batch);
        e->in_prefill.store(true);
        env->CallVoidMethod(cb, g_prefill, lcp, n_prompt);
        if (env->ExceptionCheck()) {
            e->in_prefill.store(false);
            env->DeleteGlobalRef(cb);
            return -1;
        }
        for (int i0 = lcp; i0 < n_prompt && !e->stop.load(); i0 += n_batch) {
            const int chunk = std::min(n_batch, n_prompt - i0);
            llama_batch_ext * b = llama_batch_ext_init(e->ctx);
            if (b == nullptr) { callOnError(env, cb, "could not allocate prompt batch"); failed = true; break; }
            for (int i = 0; i < chunk; i++) {
                const llama_pos pos = e->n_past + i;
                const int idx = llama_batch_ext_add_token(b, 0, prompt_tokens[static_cast<size_t>(i0 + i)]);
                if (idx < 0 || !llama_batch_ext_set_pos(b, idx, &pos) ||
                    (i == chunk - 1 && !llama_batch_ext_set_output_logits(b, idx, true))) {
                    failed = true;
                    break;
                }
            }
            if (failed) {
                llama_batch_ext_free(b);
                callOnError(env, cb, "failed to build decode batch");
                break;
            }
            const int rc = llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, b);
            llama_batch_ext_free(b);
            if (rc != 0) {
                auto detail = "prefill_decode: rc=" + std::to_string(rc) + " chunk=" + std::to_string(chunk);
                callOnError(env, cb, detail.c_str());
                failed = true;
                break;
            }
            e->cached_tokens.insert(e->cached_tokens.end(), prompt_tokens.begin() + i0,
                                    prompt_tokens.begin() + i0 + chunk);
            e->n_past += chunk;
            env->CallVoidMethod(cb, g_prefill, e->n_past, n_prompt);
            if (env->ExceptionCheck()) {
                e->in_prefill.store(false);
                env->DeleteGlobalRef(cb);
                return -1;
            }
        }

        e->in_prefill.store(false);
        const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - prefill_start).count();
        e->prompt_tokens = e->n_past - lcp;
        e->prompt_ms = secs * 1000.0;
        e->prefill_rate = secs > 0 ? (e->n_past - lcp) / secs : 0;
        if (failed) {
            llama_memory_clear(mem, false);
            e->cached_tokens.clear();
            e->n_past = 0;
        }
        int status = 0;
        if (!failed && !e->stop.load()) {
            status = runSamplingLoop(env, e, cb, chain.get(), max_tokens, budget, &stopFilter);
        }

        env->DeleteGlobalRef(cb);

        if (e->stop.load()) return 1;
        if (failed) return -1;
        return status;
    } catch (const std::exception & ex) {
        callOnError(env, cb, ex.what());
        env->DeleteGlobalRef(cb);
        return -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeContinueGenerate(
        JNIEnv * env, jobject, jlong handle, jdoubleArray sampler,
        jint max_tokens, jint budget, jobjectArray jstopStrings, jobject jcallback) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->ctx == nullptr) {
        throwIllegalState(env, "no model loaded");
        return -1;
    }
    if (jcallback == nullptr) {
        throwIllegalState(env, "callback is null");
        return -1;
    }
    StopFilter stopFilter(env, jstopStrings);
    jobject cb = env->NewGlobalRef(jcallback);
    if (cb == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        throwIllegalState(env, "could not retain generation callback");
        return -1;
    }

    // Continue sampling from the current KV state (speculative pre-warm flush):
    // no prompt re-processing, logits of the last decoded token are reused.
    try {
        e->started = std::chrono::steady_clock::now();
        e->ttft_ms = 0;
        e->decode_rate = 0;
        e->prompt_tokens = 0; e->prompt_ms = 0; e->gen_ms = 0;
        e->stop.store(false);
        const int n_vocab = static_cast<int>(llama_vocab_n_tokens(e->vocab));

        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> chain(
            buildSampler(env, sampler, e->vocab, n_vocab), llama_sampler_free);
        if (!chain) {
            env->DeleteGlobalRef(cb);
            if (!env->ExceptionCheck()) throwIllegalState(env, "could not create sampler");
            return -1;
        }

        const int status = runSamplingLoop(env, e, cb, chain.get(), max_tokens, budget, &stopFilter);
        env->DeleteGlobalRef(cb);

        if (e->stop.load()) return 1;
        return status;
    } catch (const std::exception & ex) {
        callOnError(env, cb, ex.what());
        env->DeleteGlobalRef(cb);
        return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeStop(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e != nullptr) e->stop.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeSetThreads(JNIEnv *, jobject, jlong handle, jint threads) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e != nullptr && e->ctx != nullptr && threads > 0) {
        llama_set_n_threads(e->ctx, threads, threads);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeUnload(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr) return;
    if (e->ctx != nullptr) llama_free(e->ctx);
    if (e->owns_model && e->model != nullptr) llama_model_free(e->model);
    delete e;
}

extern "C" JNIEXPORT void JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeReset(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    llama_memory_clear(llama_get_memory(e->ctx), false);
    e->cached_tokens.clear();
    e->pending_utf8.clear();
    e->n_past = 0;
}

// --------------------------------------------------------------------------
// Opt-in KV cache persistence (settings: Save KV Cache on Disk).
// The state file is written beside the final path and renamed only after
// llama_state_save_file() succeeded, so a killed process cannot leave a
// half-written sidecar. loadKvCache bounds the token buffer to n_ctx and
// resets the engine on any failure; nativeGenerate then re-prefills from the
// longest common token prefix, so a stale state is only a cache miss.
// --------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeSaveKvCache(JNIEnv * env, jobject, jlong handle, jstring jpath) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->ctx == nullptr || e->cached_tokens.empty()) return -1;
    const std::string path = toStdString(env, jpath);
    if (path.empty()) return -1;
    const std::string partial = path + ".partial";
    try {
        const bool ok = llama_state_save_file(e->ctx, partial.c_str(), e->cached_tokens.data(), e->cached_tokens.size());
        if (!ok) {
            std::remove(partial.c_str());
            return -1;
        }
        if (std::rename(partial.c_str(), path.c_str()) != 0) {
            std::remove(partial.c_str());
            return -1;
        }
        return e->n_past > 0 ? static_cast<jint>(e->n_past) : static_cast<jint>(e->cached_tokens.size());
    } catch (...) {
        std::remove(partial.c_str());
        return -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeLoadKvCache(JNIEnv * env, jobject, jlong handle, jstring jpath) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->ctx == nullptr) return -1;
    const std::string path = toStdString(env, jpath);
    if (path.empty()) return -1;
    const size_t capacity = static_cast<size_t>(llama_n_ctx(e->ctx));
    if (capacity == 0) return -1;
    std::vector<llama_token> tokens(capacity);
    size_t count = 0;
    bool ok = false;
    try {
        ok = llama_state_load_file(e->ctx, path.c_str(), tokens.data(), tokens.size(), &count);
    } catch (...) {
        ok = false;
    }
    if (!ok || count == 0 || count > capacity) {
        llama_memory_clear(llama_get_memory(e->ctx), false);
        e->cached_tokens.clear();
        e->pending_utf8.clear();
        e->n_past = 0;
        return -1;
    }
    tokens.resize(count);
    e->cached_tokens = tokens;
    e->n_past = static_cast<int>(count);
    e->pending_utf8.clear();
    return static_cast<jint>(count);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativePromptSize(JNIEnv * env, jobject, jlong handle, jstring prompt) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    const auto text = toStdString(env, prompt);
    return -llama_tokenize(e->vocab, text.data(), text.size(), nullptr, 0, false, true);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeCountTokens(JNIEnv * env, jobject, jlong handle, jstring jtext, jboolean parseSpecial) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    if (e == nullptr || e->vocab == nullptr) return 0;
    const auto text = toStdString(env, jtext);
    if (text.empty()) return 0;
    return -llama_tokenize(e->vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, false, parseSpecial);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeMetrics(JNIEnv * env, jobject, jlong handle) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    const double values[] = {e->ttft_ms, e->prefill_rate, static_cast<double>(e->n_past),
                             static_cast<double>(llama_n_ctx(e->ctx)), e->decode_rate,
                             static_cast<double>(e->prompt_tokens), e->prompt_ms, e->gen_ms};
    auto result = env->NewDoubleArray(8);
    if (result) env->SetDoubleArrayRegion(result, 0, 8, values);
    return result;
}

// The caller holds the parent engine mutex until this context is destroyed.
extern "C" JNIEXPORT jlong JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeBenchmarkContext(JNIEnv *env, jobject, jlong handle) {
    auto * parent = reinterpret_cast<NativeEngine *>(handle);
    try {
        auto isolated = std::make_unique<NativeEngine>();
        isolated->arch = parent->arch;
        isolated->model = parent->model;
        isolated->vocab = parent->vocab;
        isolated->owns_model = false;
        isolated->gpu_layers = parent->gpu_layers;
        auto params = parent->params;
        params.n_ctx = std::min(params.n_ctx, 512u);
        if (params.abort_callback) params.abort_callback_data = isolated.get();
        isolated->ctx = llama_init_from_model(parent->model, params);
        if (!isolated->ctx) throw std::runtime_error("Insufficient memory for benchmark context");
        return reinterpret_cast<jlong>(isolated.release());
    } catch (const std::exception &error) {
        throwIllegalState(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_littlebit_chat_engine_LlamaJni_nativeAttachDraft(JNIEnv *, jobject, jlong handle, jlong draft) {
    auto * e = reinterpret_cast<NativeEngine *>(handle);
    auto * d = reinterpret_cast<NativeEngine *>(draft);
    e->draft = nullptr;
    if (!d) return true;
    for (auto * model : {e->model, d->model}) {
        if (llama_model_is_recurrent(model) || llama_model_is_hybrid(model) || llama_model_n_swa(model)) return false;
    }
    if (llama_n_ctx(d->ctx) < llama_n_ctx(e->ctx) ||
        llama_vocab_type(e->vocab) != llama_vocab_type(d->vocab) ||
        llama_vocab_n_tokens(e->vocab) != llama_vocab_n_tokens(d->vocab) ||
        llama_vocab_bos(e->vocab) != llama_vocab_bos(d->vocab) ||
        llama_vocab_eos(e->vocab) != llama_vocab_eos(d->vocab)) return false;
    for (int i = 0; i < llama_vocab_n_tokens(e->vocab); ++i) {
        if (tokenToPiece(e->vocab, i) != tokenToPiece(d->vocab, i) ||
            llama_vocab_get_attr(e->vocab, i) != llama_vocab_get_attr(d->vocab, i) ||
            llama_vocab_is_eog(e->vocab, i) != llama_vocab_is_eog(d->vocab, i)) return false;
    }
    e->draft = d;
    return true;
}
