#include <jni.h>
#include <stable-diffusion.h>
#include <algorithm>
#include <atomic>
#include <cctype>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::mutex lock;
sd_ctx_t *active = nullptr;
std::atomic<bool> cancelled{false};

// One progress channel, three producers. stable-diffusion.cpp@2988060 reuses the
// same sd_progress_cb_t for tensor loading (index / total tensors, ~686 for an
// SD1.5 checkpoint) and for sampling (step / steps). Formatting both as
// "Step n / total" is what produced the "Step 1 / 686" report when Steps = 1.
enum Phase { PHASE_IDLE = 0, PHASE_LOADING = 1, PHASE_SAMPLING = 2, PHASE_VAE = 3, PHASE_DONE = 4 };
std::atomic<int> phase{PHASE_IDLE};
std::atomic<int> step{0};
std::atomic<int> total{0};
std::atomic<int> requested{0};

int percent(int done, int all) {
    if (all <= 0) return 0;
    long long value = static_cast<long long>(done) * 100 / all;
    if (value < 0) return 0;
    if (value > 100) return 100;
    return static_cast<int>(value);
}

std::string label() {
    const int ph = phase.load();
    const int st = step.load();
    const int tt = total.load();
    const int rq = requested.load();
    switch (ph) {
        case PHASE_LOADING:
            return tt > 0 ? "Loading weights… " + std::to_string(percent(st, tt)) + "%" : "Loading weights…";
        case PHASE_SAMPLING: {
            const int denom = rq > 0 ? rq : tt;
            if (denom <= 0) return "Sampling…";
            const int shown = st < 1 ? 1 : (st > denom ? denom : st);
            return "Step " + std::to_string(shown) + " / " + std::to_string(denom);
        }
        case PHASE_VAE:
            return "Decoding VAE…";
        case PHASE_DONE:
            return "Done";
        default:
            return "Ready";
    }
}

void progress(int n, int all, float, void *) {
    const int rq = requested.load();
    step.store(n);
    total.store(all);
    // Sampling reports step/steps where the denominator is exactly the number
    // of steps we asked for; anything else is the tensor loader.
    if (rq > 0 && all == rq) {
        phase.store(n >= all ? PHASE_VAE : PHASE_SAMPLING);
    } else if (phase.load() != PHASE_VAE) {
        phase.store(PHASE_LOADING);
    }
}

std::string str(JNIEnv *env, jstring s) {
    auto p = env->GetStringUTFChars(s, nullptr);
    if (!p) throw std::runtime_error("Could not read image request");
    std::string value(p);
    env->ReleaseStringUTFChars(s, p);
    return value;
}

bool isInpaintingModel(const std::string &path) {
    const auto slash = path.find_last_of("/\\");
    std::string name = slash == std::string::npos ? path : path.substr(slash + 1);
    std::transform(name.begin(), name.end(), name.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return name.find("inpaint") != std::string::npos;
}

std::vector<uint8_t> bytes(JNIEnv *env, jbyteArray array, int count) {
    if (!array) return {};
    if (env->GetArrayLength(array) != count) throw std::runtime_error("Unexpected image dimensions");
    std::vector<uint8_t> v(count);
    env->GetByteArrayRegion(array, 0, count, reinterpret_cast<jbyte *>(v.data()));
    return v;
}
}

extern "C" JNIEXPORT void JNICALL Java_com_littlebit_chat_studio_SdNative_prepare(JNIEnv *, jobject) {
    cancelled.store(false);
    step.store(0);
    total.store(0);
    requested.store(0);
    phase.store(PHASE_LOADING);
}

extern "C" JNIEXPORT void JNICALL Java_com_littlebit_chat_studio_SdNative_cancel(JNIEnv *, jobject) {
    cancelled.store(true);
    // The lock is held only around the pointer check + the flag write inside
    // stable-diffusion.cpp. Nothing here blocks on a running step, so the UI
    // thread cannot be stranded the way an unbounded wait would strand it.
    std::lock_guard<std::mutex> guard(lock);
    if (active) sd_cancel_generation(active, SD_CANCEL_ALL);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_littlebit_chat_studio_SdNative_status(JNIEnv *env, jobject) {
    return env->NewStringUTF(label().c_str());
}

// phase, step, total, requested — read as one snapshot by the UI.
extern "C" JNIEXPORT jintArray JNICALL Java_com_littlebit_chat_studio_SdNative_progress(JNIEnv *env, jobject) {
    const jint values[4] = {phase.load(), step.load(), total.load(), requested.load()};
    jintArray out = env->NewIntArray(4);
    if (out) env->SetIntArrayRegion(out, 0, 4, values);
    return out;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_littlebit_chat_studio_SdNative_render(
    JNIEnv *env, jobject, jobjectArray files, jstring prompt, jstring negative, jint w, jint h,
    jint steps, jfloat strength, jlong seed, jboolean turbo, jbyteArray photo, jbyteArray mask,
    jstring taesdPath) {
    try {
        if (w < 64 || h < 64 || w > 1024 || h > 1024 || w%64 || h%64 || env->GetArrayLength(files)!=4)
            throw std::runtime_error("Invalid image request");
        std::vector<std::string> paths;
        for(int i=0;i<4;i++) {
            auto s = static_cast<jstring>(env->GetObjectArrayElement(files,i));
            paths.push_back(str(env,s)); env->DeleteLocalRef(s);
        }
        const auto text=str(env,prompt), neg=str(env,negative);
        // Optional TAESD fast VAE (madebyollin/taesd). Empty keeps the full VAE.
        const auto taesd=taesdPath?str(env,taesdPath):std::string();
        auto rgb=bytes(env,photo,w*h*3), gray=bytes(env,mask,w*h);
        sd_ctx_params_t c; sd_ctx_params_init(&c);
        const bool qwen=!paths[1].empty();
        // An -inpainting checkpoint has no unconditional path: without a photo
        // and a mask it loads, then stalls in the encoder. Fail fast and say why.
        if(!qwen && rgb.empty() && isInpaintingModel(paths[0]))
            throw std::runtime_error("Inpainting checkpoints need a photo and a painted mask.");
        // A mask without a photo is meaningless; drop it instead of hanging.
        if(!gray.empty() && rgb.empty()) gray.clear();
        if(qwen) {
            c.diffusion_model_path=paths[0].c_str(); c.llm_path=paths[1].c_str();
            c.vae_path=paths[2].c_str(); c.llm_vision_path=paths[3].c_str();
        } else c.model_path=paths[0].c_str();
        // Pinned include/stable-diffusion.h@2988060: enable_mmap keeps weights
        // file-backed, diffusion_flash_attn is the low-RAM attention path, and
        // taesd_path swaps in the 1-3 s decoder when a staged file is supplied.
        // There is no `vae_decode_only` field at this commit: the VAE encoder is
        // skipped automatically because init_image is only set for img2img.
        c.n_threads=4; c.enable_mmap=true; c.diffusion_flash_attn=true;
        if(!taesd.empty()) c.taesd_path=taesd.c_str();
        c.eager_load=false; c.disable_prefetch=true; c.conditioning_cache_size=0;
        c.params_backend="disk";
        std::vector<char> devs(sd_list_devices(nullptr,0)+1);
        sd_list_devices(devs.data(),devs.size());
        const std::string devices(devs.data());
        bool preferCpu=devices.find("Mali")!=std::string::npos || devices.find("Immortalis")!=std::string::npos;
        sd_img_gen_params_t g; sd_img_gen_params_init(&g);
        // Clamp: a 0-step request makes the sampler divide by zero and a 100-step
        // request on a phone just thermal-throttles.
        int want = steps < 1 ? 1 : (steps > 40 ? 40 : static_cast<int>(steps));
        g.prompt=text.c_str(); g.negative_prompt=neg.c_str(); g.width=w; g.height=h;
        g.seed=seed; g.strength=strength; g.sample_params.sample_steps=want;
        // Tiled VAE decode: the full 512x512 decode was the `Decoding VAE...`
        // stall on 12 GB phones; tiling caps the decoder's working set.
        g.vae_tiling_params.enabled=true;
        sd_image_t input{(uint32_t)w,(uint32_t)h,3,rgb.data()};
        if(!rgb.empty()) {
            if(qwen) { g.ref_images=&input; g.ref_images_count=1; }
            else g.init_image=input;
        }
        if(!gray.empty()) g.mask_image={(uint32_t)w,(uint32_t)h,1,gray.data()};
        float sigmas[]={1.f,.978453f,.95418f,.926626f,.89508f,.845148f,.704534f,.414568f,0.f};
        if(turbo) {
            g.sample_params.sample_steps=8; g.sample_params.guidance.txt_cfg=1.f;
            g.sample_params.sample_method=EULER_SAMPLE_METHOD;
            g.sample_params.custom_sigmas=sigmas; g.sample_params.custom_sigmas_count=9;
        }
        // The denominator the UI must show. Anything else reported by the
        // callback is the tensor loader, never a sampling step.
        requested.store(g.sample_params.sample_steps);
        step.store(0); total.store(0); phase.store(PHASE_LOADING);
        sd_set_progress_callback(progress,nullptr);
        for(int attempt=0; attempt<(preferCpu?1:2) && !cancelled.load(); ++attempt) {
            if(preferCpu || attempt) c.backend="CPU";
            std::unique_ptr<sd_ctx_t,decltype(&free_sd_ctx)> ctx(new_sd_ctx(&c),free_sd_ctx);
            if(!ctx) continue;
            { std::lock_guard<std::mutex> guard(lock); if(cancelled.load()) break; active=ctx.get(); }
            struct Reset { ~Reset(){ std::lock_guard<std::mutex> guard(lock); active=nullptr; } } reset;
            sd_image_t *result=nullptr; int n=0;
            bool ok=generate_image(ctx.get(),&g,&result,&n);
            auto cleanup=[n](sd_image_t *p){ if(p) free_sd_images(p,n); };
            std::unique_ptr<sd_image_t,decltype(cleanup)> owned(result,cleanup);
            if(cancelled.load()) break;
            if(!ok || n!=1 || !result || !result->data) continue;
            if(result->width!=(uint32_t)w || result->height!=(uint32_t)h || result->channel!=3)
                throw std::runtime_error("Unexpected output layout");
            auto out=env->NewByteArray(w*h*3);
            if(out) env->SetByteArrayRegion(out,0,w*h*3,reinterpret_cast<jbyte*>(result->data));
            phase.store(PHASE_DONE);
            return out;
        }
        phase.store(PHASE_IDLE);
        throw std::runtime_error(cancelled.load()?"Cancelled":"Image generation failed. Check model slots or reduce resolution.");
    } catch(const std::exception &e) {
        phase.store(PHASE_IDLE);
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());
        return nullptr;
    }
}
