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
  - **SD 1.5 Inpainting** — recommended for 6–8 GB phones such as the Exynos 1380; brush-mask photo inpainting. Ships as a single 1.9 GB `Q8_0` **`.gguf`** for the bundled stable-diffusion.cpp (no unsupported `.ckpt` checkpoint).
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

## What changed in 1.2.1

- **16 KiB ELF alignment (Android 15–16).** Both editions build with **NDK r28c (`28.2.13676358`)**, which links arm64 shared libraries with 16 KiB `LOAD` alignment. CI verifies **both** the Offline and Standard release APKs with `unzip -t`, `zipalign -c -P 16 4`, `apksigner verify --verbose`, `aapt2 dump badging` and an `llvm-readelf` 16 KiB `LOAD` alignment check on every packaged `.so`.
- **No leaked SAF descriptors.** Provider `ParcelFileDescriptor`s are closed on load failure, model replacement and unload, and the draft-model descriptor is released before a replacement is opened. Only a directly loaded model keeps its document descriptor open, for as long as the engine maps it.
- **Foreground-service stop race.** A rapid start→stop burst can no longer end the service before `startForeground()` completes, and a stale stop intent cannot tear down a newer notification: stop intents are revision-gated and only the latest start epoch may stop the service.
- **No stale private-copy prompts.** The one-tap **Import private copy instead** recovery now appears only for the document that actually failed, and is cleared on unload, unlink, dismissal or after a successful copy. Direct-loadable files always read **Load directly · 0 B copied**.
- **Honest RAM estimates.** The usable budget is derived from the measured `totalMem` instead of the rounded advertised tier, so a "12 GB" phone is planned at ~9.5 GB and an "8 GB" phone at ~5.9 GB rather than 6.5 GB. 2-bit 8B models classify **Great fit** on 8–12 GB phones and **Tight — use 2K ctx** on 6 GB.
- **Supported inpainting download.** The SD 1.5 Inpainting catalog entry points at a public `Q8_0` `.gguf` (1.9 GB) rather than an unsupported `.ckpt` checkpoint.
- **Crash-log normalization.** Literal `\n`, `\r\n` and `\t` escapes written by v1.2.0 and real newlines are both normalized, so the Settings crash card renders real lines for old and new logs.
- **Model Hub and navigation.** The subtitle describes Direct zero-copy loading versus optional private copies, the settings memory tiers use the catalog names (**Compact**, **8B spotlight**, **Large 2-bit**), and **Image Studio** sits in the **☰** drawer with its own icon, opens without a model loaded, and returns to Chat on system Back.

## Device testing status

Tested on Android 16 with a 12 GB phone and an 8 GB Exynos 1380 device: direct GGUF loading, 2-bit 8B chat, Model Hub RAM hints, Image Studio navigation and background generation. SD 1.5 Inpainting and Qwen Image 2.1 Turbo 2-bit were exercised on the 12 GB device; low-RAM timings and quality on 6 GB phones are still being collected. Report device, RAM, model and quant when filing an issue.

## Build and credits

Clone with `git clone --recurse-submodules https://github.com/aj5649169-svg/littlebit-chat.git`, then run `bash scripts/apply-patches.sh` from the root. It applies `patches/0001-littlebit.diff` (LittleBit `Q1_0`) and `patches/0002-android-saf-fd.diff` (Android `/proc/self/fd/<N>` loading with buffered-read fallback when a FUSE descriptor cannot be mapped) against `llama.cpp` `6aee508`.

The [Android build workflow](.github/workflows/build-android-apk.yml) pins JDK 17, Android SDK, **NDK r28c (`28.2.13676358`)** for 16 KiB-aligned arm64 libraries, Gradle 8.10 and the host Vulkan shader compiler, runs the offline and standard unit tests, builds `:app:assembleRelease` for both flavors, then verifies **each** APK with `unzip -t`, `zipalign -c -P 16 4`, `apksigner verify --verbose`, `aapt2 dump badging` and a 16 KiB ELF `LOAD` alignment check on every packaged `.so` (`libllama_jni.so`, `libsd_jni.so`, `libc++_shared.so`). Pushes to `main` publish a build artifact; version tags publish both APKs with SHA-256 checksums. Release builds need `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS` and `ANDROID_RELEASE_KEY_PASSWORD` as repository secrets; keep the same signing key for updates.

Built on [llama.cpp](https://github.com/ggml-org/llama.cpp) (pinned at `6aee508`), [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp), [Arm KleidiAI](https://github.com/ARM-software/kleidiai), and the LittleBit work from [GeniusDevam/littlebit-gguf](https://github.com/GeniusDevam/littlebit-gguf). The upstream MIT notice is preserved in [LICENSE](LICENSE). Third-party components and separately downloaded model weights retain their respective licenses.
