# LiteRT-LM on Android

Keep GGUF as the default. Evaluate LiteRT-LM in an opt-in ARM64 build flavor
implementing the same blocking generation, cancellation, timing and model-load
contract as the GGUF engine. Cache and draft capabilities need separate backend
implementations. The current APK ships one runtime.

* **Size.** The Android AAR adds native libraries. Measure the signed, stripped
  ARM64 APK delta and installed footprint from two otherwise identical builds.
  There is no measured size or speed advantage for this app yet.
* **Offline and licensing.** The upstream framework is Apache-2.0. Use local
  files, audit transitive binaries and licenses, inspect the merged manifest for
  INTERNET, and test in airplane mode on GrapheneOS without Google Play services.
  Model weights have separate license terms.
* **Model selection.** Label catalog entries and file pickers by runtime.
  LiteRT-LM takes a separately packaged .litertlm model. Existing GGUF files
  should still open with llama.cpp. Never silently download either format.
* **Acceptance.** On the Pixel 6a, compare cold load, peak memory, first-token
  latency and sustained decode with the same conversation and context. Record
  backend, thread count and each model's quantization alongside the results.

[Upstream runtime](https://github.com/google-ai-edge/LiteRT-LM) ·
[Gemma 4 E2B LiteRT model](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
