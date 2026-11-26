# LittleBit Chat

Private, on-device AI chat and image editing for Android ARM64. No cloud, no accounts, no telemetry: bring a GGUF model and keep conversations and photos on your phone.

![Android ARM64](https://img.shields.io/badge/Android_9%2B-ARM64-326149) ![Engines](https://img.shields.io/badge/llama.cpp_%2B_stable--diffusion.cpp-on--device-344861) ![UI](https://img.shields.io/badge/Jetpack_Compose-Material_3-624979) [![Download release APK](https://img.shields.io/badge/Download-release_APK-136449)](https://github.com/aj5649169-svg/littlebit-chat/releases/latest)

## Two signed editions

| Edition | Network permission | What it adds |
| --- | --- | --- |
| **Offline** (`com.littlebit.chat`) | None. CI parses the merged release manifest and fails on `INTERNET`/`ACCESS_NETWORK_STATE`. | Chat, Model Hub, Image Studio. Downloads open in your browser; nothing in the app can reach the network. |
| **Standard** (`com.littlebit.chat.standard`) | `INTERNET` only | Everything in Offline plus opt-in, per-message DuckDuckGo or SearXNG Web Search. Installs alongside Offline; searches never run unless you tap the Web toggle for a message. |

Both editions are signed ARM64 APKs of about **41 MB**, bundling two native engines: patched `llama.cpp` (LittleBit `Q1_0` + Arm KleidiAI) and `stable-diffusion.cpp` in `libsd_jni.so`. Model weights are separate downloads and are never bundled.

## Quick start

1. Download the APK for the edition you want from [Releases](https://github.com/aj5649169-svg/littlebit-chat/releases/latest) and open it. If Android asks, allow **Install unknown apps** for the browser or file manager; you can revoke it afterwards.
2. Open **Model Hub** → **Load Local .gguf** and pick a model. Direct loading keeps Android's zero-copy file descriptor: nothing is copied, no extra storage is used, and multi-gigabyte models start immediately. Providers that cannot serve a seekable file offer a one-time **Private copy** instead.
3. Pick **Fast** for short answers or **Think** for reasoning models, then send. If a direct load ever fails, the model error card offers **Import private copy instead** in one tap.
4. For images, open **☰ → Image Studio**, choose a model, and generate or paint a mask over a photo.

## Model Hub and RAM fit

The catalog groups models into **8B spotlight**, **Compact**, **Large 2-bit**, **Image** and **Research**. Badges (`Great fit` / `Tight — use 2K ctx` / `May OOM`) and the **Fits my phone** filter are computed from **total device RAM** minus realistic Android OS headroom — not from the fluctuating `availMem` value — so results stay stable while other apps run.

- **8B spotlight.** Qwen3 8B, DeepSeek R1 0528 Qwen3 8B, Ministral 3 8B, Llama 3.1 8B and Qwen2.5 Coder 7B at roughly 2.8–3.8 GB. A 3.5 GB 2-bit 8B model is `Great fit` on a 12 GB phone and stays visible with **Fits my phone** on.
- **Image.** Verified Hugging Face links that open in the browser, so the Offline edition keeps zero internet permission:
  - **SD 1.5 Inpainting** — recommended for 6–8 GB phones such as the Exynos 1380; brush-mask photo inpainting.
  - **SD-Turbo** — 1–4 step text-to-image at 512 px on 6–8 GB phones.
  - **Qwen-Image-2.1-Turbo (2-Bit)** — `AD-Q2_K`/`Q2_K` denoiser plus a 2-bit Qwen3-VL-8B text encoder, the Qwen Image 2.1 VAE and an optional `mmproj-F16` for photo edits. Experimental low-RAM 2-bit mode with stage-by-stage memory freeing: 8 GB+ recommended, softer detail than 4-bit.

## Image Studio

The bundled `stable-diffusion.cpp` engine runs fully on-device. SD 1.5 / SD-Turbo use a single weights file; Qwen Image 2.1 Turbo takes denoiser, text encoder, VAE and optional vision projector slots. Each provider descriptor stays open until rendering finishes, so FUSE-backed documents load reliably. Results can be saved to Photos or shared; **Cancel** stops a run, and chat RAM is released before generation starts.

## Inference and chat

- **Engines.** Vulkan GPU offload by default, falling back to Arm KleidiAI CPU if initialization fails; tune GPU layers and threads in Settings. Quick Benchmark and **Auto-pick fastest backend** compare backends without clearing the active chat.
- **Streaming.** KV-prefix reuse avoids re-decoding unchanged history, prefill progress is reported per token, and completed Markdown blocks are cached while you scroll manually.
- **Thinking Budget.** Cap reasoning tokens (Off/256/512/1024/Unlimited) for Qwen3 and Gemma 4; Fast mode appends `/no_think`.
- **Chat tools.** Attach text or code, use prompt templates, `/calc`, optional local clock context, system personas, edit-and-resend, regenerate, continue, forks, pin/rename/search chats, and Markdown export.
- **Modern Settings.** Device & memory banner, backend and sampling sliders, appearance (System/Light/Dark/AMOLED, accents, 13/15/17 sp), generation notification, auto-unload policy, and a readable last-crash log with real line breaks.

## Display and navigation fixes in 1.2.1

The Model Hub shows an accurate subtitle describing Direct zero-copy loading versus optional private copies, and the settings memory tiers now use the same names as the catalog (**Compact**, **8B spotlight**, **Large 2-bit**). The last-crash card renders actual newlines. **Image Studio** sits in the **☰** drawer with its own icon, opens without a model loaded, and returns to Chat on system Back.

## Device testing status

Tested on Android 16 with a 12 GB phone and an 8 GB Exynos 1380 device: direct GGUF loading, 2-bit 8B chat, Model Hub RAM hints, Image Studio navigation and background generation. SD 1.5 Inpainting and Qwen Image 2.1 Turbo 2-bit were exercised on the 12 GB device; low-RAM timings and quality on 6 GB phones are still being collected. Report device, RAM, model and quant when filing an issue.

## Build and credits

Clone with `git clone --recurse-submodules https://github.com/aj5649169-svg/littlebit-chat.git`, then run `bash scripts/apply-patches.sh` from the root. It applies `patches/0001-littlebit.diff` (LittleBit `Q1_0`) and `patches/0002-android-saf-fd.diff` (Android `/proc/self/fd/<N>` loading with buffered-read fallback when a FUSE descriptor cannot be mapped) against `llama.cpp` `6aee508`.

The [Android build workflow](.github/workflows/build-android-apk.yml) pins JDK 17, Android SDK/NDK, Gradle 8.10 and the host Vulkan shader compiler, runs the offline and standard unit tests, builds `:app:assembleRelease` for both flavors, verifies the ARM64 APK signatures and checks that `lib/arm64-v8a/libsd_jni.so` is packaged. Pushes to `main` publish a build artifact; version tags publish both APKs with SHA-256 checksums. Release builds need `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS` and `ANDROID_RELEASE_KEY_PASSWORD` as repository secrets; keep the same signing key for updates.

Built on [llama.cpp](https://github.com/ggml-org/llama.cpp) (pinned at `6aee508`), [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp), [Arm KleidiAI](https://github.com/ARM-software/kleidiai), and the LittleBit work from [GeniusDevam/littlebit-gguf](https://github.com/GeniusDevam/littlebit-gguf). The upstream MIT notice is preserved in [LICENSE](LICENSE). Third-party components and separately downloaded model weights retain their respective licenses.
