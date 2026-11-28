# LittleBit Chat

Private, on-device AI chat and image generation/editing for Android ARM64. Bring your own GGUF model; inference, chats, and photos stay on your phone. No account or telemetry. The **Offline** edition is built with no `INTERNET` or `ACCESS_NETWORK_STATE` permission, and Settings → **Privacy & permissions** verifies the installed app's actual requested permissions. The **Standard** edition is a separate install with optional per-message web search and cloud providers.

![Android ARM64](https://img.shields.io/badge/Android_9%2B-ARM64-326149) ![Engines](https://img.shields.io/badge/llama.cpp_%2B_stable--diffusion.cpp-on--device-344861) ![Privacy](https://img.shields.io/badge/Offline-zero_network_permissions-2e7d32)

## Editions and quick start

| Edition | Network access | Features |
| --- | --- | --- |
| **Offline** (`com.littlebit.chat`) | No network permissions; CI checks the merged release manifest. | Local chat, Model Hub, Image Studio, offline TTS, assistants, and pinned notes. |
| **Standard** (`com.littlebit.chat.standard`) | Network permission for explicitly enabled features. | Everything in Offline, plus opt-in per-message DuckDuckGo/SearXNG search and OpenAI-compatible cloud APIs. |

Both editions are signed ARM64 APKs. Install from [Releases](https://github.com/aj5649169-svg/littlebit-chat/releases/latest), open **Model Hub → Load Local .gguf**, and choose a model. Seekable documents load directly; if a provider cannot be read directly, the app offers a confirmed private copy. Open **☰ → Image Studio** to generate an image or inpaint a photo.

## Features

### Private local inference

- **Vulkan GPU offload and Arm KleidiAI CPU fallback.** Switch between Vulkan and CPU, tune GPU layers and threads, run a benchmark on a selected backend, or use automatic backend selection.
- **GGUF Model Hub** with model categories, device RAM-fit guidance, and direct loading. Diffusion models are identified and kept out of the chat loader.
- **Automatic KV-cache resume.** Cache persistence is enabled by default for eligible prompts (at least 1,024 tokens); validated cache hits resume saved context and report the restore time. Stale or incompatible caches safely fall back to prefill.
- Streaming chat with context/prefill metrics, Markdown, Fast/Think modes, normalized reasoning display, and reusable prompt context.
- Chat tools: templates, personas and assistants, pinned notes, attachments, calculator, edit/resend, regenerate, continue, fork, search, rename, and Markdown export. Chat history has size/message safeguards and JSON import/export.
- Offline read-aloud using Android text-to-speech.

### Image Studio

On-device `stable-diffusion.cpp` supports **SD 1.5**, **SD-Turbo**, SD 1.5 inpainting with a photo and mask, and experimental Qwen image editing. Weights are staged safely from Android document providers; generation releases chat-model memory first. Phase-aware progress reports sampling steps (not checkpoint tensor loading), cancellation is safe during initialization, and inpainting models require both a photo and mask. Image Studio state survives navigation while generation continues.

### Prompt and sampling controls

- Built-in Chat Templates for ChatML, Llama 3, Mistral, Gemma, Alpaca, and Vicuna, plus a user-editable custom template.
- Configurable stop strings and normalized Gemma 4/Qwen3 thinking channels.
- The full `llama.cpp` sampler suite: temperature, top-k, top-p, min-p, repeat/frequency/presence penalties, DRY, XTC, Top-N-Sigma, Typical-p, DynaTemp, and Mirostat v2. Sampler settings persist together; explicit seed locking supports every uint32 value, including `0xFFFFFFFF`. Mirostat's mutually exclusive sampler rules and canonical sampler order are respected.

### Standard edition connectivity

Web search is opt-in per message. The Standard edition also offers Local | Cloud providers (including OpenRouter, NVIDIA, Groq, Ollama, and custom OpenAI-compatible endpoints). API keys are encrypted with Android Keystore. The Offline edition contains no cloud networking implementation or network permissions.

## Version History

Each entry describes the user-visible changes introduced in that version relative to the preceding version. The first release establishes the initial baseline.

### v1.3.4 — Full sampler suite

- Added the complete `llama.cpp` sampler controls, seed locking, sampler-group settings UI, and persistent sampler configuration with migration from earlier settings.
- Defined and tested the exact 22-slot Kotlin/JNI sampler ABI and canonical chain; Mirostat v2 enforces its sampler exclusions. Defaults preserve v1.3.3 output.
- Improved slider accessibility and touch targets.

### v1.3.3 — Image Studio correctness and prompt/engine controls

- Fixed misleading image progress, cancellation during initialization, SD inpainting without a photo/mask, and lost Studio state on back navigation.
- Added built-in and custom chat templates, configurable stop strings, token counting, and Gemma 4/Qwen3 thinking-channel normalization.

### v1.3.2 — Trust, history safety, and cache resume

- Fixed a v1.3.1 cold-start crash; corrected Model Hub RAM guidance and load recovery, including a model-load lockup.
- Added bounded/streamed chat-history persistence, JSON import, and crash-log Copy/Report actions.
- Enabled eligible KV-cache saves by default, surfaced measured cache-hit resume time, and added the Privacy & permissions inspector.
- Clarified model conversion guidance and Q4_0 speed/RAM trade-offs.

### v1.3.1 — Stability and device-aware controls

- Added pre-load RAM estimates and confirmation choices, a backend/preset benchmark setup, idle model auto-unload, thermal warnings, and context/TTFT display controls.
- Added chat forks and Markdown export; improved offline prompt behavior so static questions receive direct answers.
- **Upgrade note:** this release had a cold-start crash and a model-load lockup on affected paths; v1.3.2 contains the fixes.

### v1.3.0 — Image, chat, and connectivity expansion

- Added safe Image Studio model staging and diffusion-file detection in Model Hub; improved search grounding and query display.
- Added offline TTS, assistants, pinned notes, saved KV cache, richer chat controls, and Standard-only cloud APIs and web search.
- Added the LittleBit low-bit model tier and Android 15/16 16 KiB-aligned ARM64 native libraries.

### v1.2.1 — Real-device loading and image fixes

- Fixed direct Android document-provider model loading and descriptor lifetimes, foreground-service stop behavior, and Model Hub RAM-fit estimates.
- Added the supported SD 1.5 inpainting model entry, image navigation improvements, and clearer crash-log formatting.

### v1.2.0 — Two editions and expanded model/image support

- Introduced separate Offline and Standard editions, per-message web search in Standard, direct GGUF document loading, RAM-aware Model Hub filters, and Image Studio support for inpainting and experimental image editing.
- Improved chat navigation and generation-state handling. **This release had a broken build; use v1.2.1 or later.**

### v1.1.0 — First release in this repository

- Established the ARM64 on-device chat app with the v1.1 inference engine, Vulkan offload with Arm KleidiAI CPU fallback, local GGUF loading, reusable chat context, an on-device Quick Benchmark, and an editorial Material 3 interface.

## Roadmap

- **v1.3.5:** SQLite chat storage with WAL, migration from the current JSON store, and Image Studio TAESD/progress polish.
- **v1.3.6–v1.3.9:** conversation variants and summaries; richer character cards and links; lorebooks and Author's Note; performance, token-estimation, intent, and state-contract improvements.
- **v1.4.0:** Material 3 UI overhaul across Chat, History, Model Hub, Settings, Image Studio, Assistants/Characters, and prompt tools. Roadmap items are planned, not shipped.

## Build and credits

Clone with `git clone --recurse-submodules https://github.com/aj5649169-svg/littlebit-chat.git`, then run `bash scripts/apply-patches.sh` from the repository root. CI builds the signed Offline and Standard ARM64 APKs and checks release artifacts. The app builds on [llama.cpp](https://github.com/ggml-org/llama.cpp), [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp), and [Arm KleidiAI](https://github.com/ARM-software/kleidiai).
