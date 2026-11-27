#include <jni.h>
#include <stable-diffusion.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::mutex lock;
sd_ctx_t *active = nullptr;
std::atomic<bool> cancelled{false};
std::string stage;
void status(std::string value) {
    std::lock_guard<std::mutex> guard(lock);
    stage = std::move(value);
}
std::string str(JNIEnv *env, jstring s) {
    auto p = env->GetStringUTFChars(s, nullptr);
    if (!p) throw std::runtime_error("Could not read image request");
    std::string value(p);
    env->ReleaseStringUTFChars(s, p);
    return value;
}
void progress(int n, int total, float, void *) {
    status(n == total ? "Decoding VAE…" : "Step " + std::to_string(n) + " / " + std::to_string(total));
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
    status("Opening weights…");
}
extern "C" JNIEXPORT void JNICALL Java_com_littlebit_chat_studio_SdNative_cancel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(lock);
    cancelled.store(true);
    if (active) sd_cancel_generation(active, SD_CANCEL_ALL);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_littlebit_chat_studio_SdNative_status(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> guard(lock);
    return env->NewStringUTF(stage.c_str());
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
        g.prompt=text.c_str(); g.negative_prompt=neg.c_str(); g.width=w; g.height=h;
        g.seed=seed; g.strength=strength; g.sample_params.sample_steps=steps;
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
        sd_set_progress_callback(progress,nullptr);
        for(int attempt=0; attempt<(preferCpu?1:2) && !cancelled.load(); ++attempt) {
            if(preferCpu || attempt) c.backend="CPU";
            status(attempt ? "Retrying on CPU…" : "Encoding…");
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
            return out;
        }
        throw std::runtime_error(cancelled.load()?"Cancelled":"Image generation failed. Check model slots or reduce resolution.");
    } catch(const std::exception &e) {
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());
        return nullptr;
    }
}
