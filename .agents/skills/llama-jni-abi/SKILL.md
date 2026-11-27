---
name: llama-jni-abi
description: Use when editing android-app native code or JNI bindings — llama_jni.cpp, LlamaJni.kt, LlamaEngine.kt, sd_jni.cpp, SdNative.kt, CMakeLists.txt, exports.map, patches/*.diff, or any change to model loading, sampling parameters, KV cache, or packaged .so files. Covers JNI signature parity, engine isolation, 16 KiB ELF alignment and patch idempotency.
---

# llama.cpp / stable-diffusion.cpp ABI rules

Two native engines ship in one APK and must never leak into each other. Every
rule below exists because breaking it produces a green Kotlin build and a
crashing or unshippable release APK.

## 1. Kotlin ↔ C++ signature parity is a hard contract

`LlamaJni.kt` declares `external fun` members; `llama_jni.cpp` implements
`Java_com_littlebit_chat_engine_LlamaJni_<name>`. The linker does not check
these — a mismatch is an `UnsatisfiedLinkError` at first call, on device.

When you change any of these:

| Kotlin (`engine/LlamaJni.kt`) | C++ (`src/main/cpp/llama_jni.cpp`) |
| --- | --- |
| `nativeLoadModel(path, nCtx, nThreads, nBatch, nGpuLayers, flash, progress)` | `JNIEXPORT jlong JNICALL Java_com_littlebit_chat_engine_LlamaJni_nativeLoadModel` |
| `nativeFormatChat(handle, system, roles, contents, thinking)` | `..._nativeFormatChat` |
| `nativeGenerate(handle, prompt, temperature, topK, topP, minP, repeatPenalty, dryMultiplier, maxTokens, seed, thinkingBudget, callback)` | `..._nativeGenerate` |
| `nativeContinueGenerate(...)` | `..._nativeContinueGenerate` |
| `nativeSaveKvCache` / `nativeLoadKvCache` | `..._nativeSaveKvCache` / `..._nativeLoadKvCache` |

Rules:

- Add or remove a parameter on **both sides in the same commit**. Never land a
  Kotlin signature first.
- Keep the JNI name spelling exact: package `com.littlebit.chat.engine`, class
  `LlamaJni`. Underscores in the package become `_` separators, not `.`.
- `LlamaEngine.kt` is the only caller of `LlamaJni`. Do not call `LlamaJni`
  from UI code; it bypasses the serialized inference dispatcher.
- Return codes are part of the ABI: `0` finished, `1` stopped, `2` context
  full, `-1` error (`GenerateResult.STATUS_*`). Never renumber them.
- Callback objects (`TokenCallback`, `ProgressCallback`) must stay method-name
  and arity compatible; the C++ side resolves them with `GetMethodID`.
- `stop()` / `unload()` are called from other threads. Any new native entry
  point must be safe against a concurrent `nativeUnload`.

## 2. Engine isolation

`libllama_jni.so` and `libsd_jni.so` are separate shared objects with separate
CMake targets (`app/src/main/cpp/CMakeLists.txt`,
`image-runtime/src/main/cpp/CMakeLists.txt`).

- `stable-diffusion.cpp` links **only** into `sd_jni`. It must never appear in
  `target_link_libraries(llama_jni ...)`.
- `sd_jni` links with `-Wl,--exclude-libs,ALL`, `-Wl,-Bsymbolic` and
  `-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map`. New exported
  C entry points must be added to `exports.map` or they are hidden and the JNI
  lookup fails at runtime.
- Both targets set `CXX_VISIBILITY_PRESET hidden`. Do not add
  `__attribute__((visibility("default")))` to anything except a JNI export.
- Load `libsd_jni.so` lazily from `SdNative` only. Loading it at process start
  costs RAM that chat needs.
- Both engines use `ggml`. Keeping them in separate `.so` files is what stops
  duplicate-symbol and allocator conflicts; a "simplification" that merges them
  is a regression.

## 3. 16 KiB ELF alignment

Android 15/16 devices with 16 KiB pages refuse to load a `.so` whose `LOAD`
segments are 4 KiB aligned. CI runs `llvm-readelf` against every packaged
library (`libllama_jni.so`, `libsd_jni.so`, `libc++_shared.so`).

- NDK is pinned to `28.2.13676358` in `android-app/app/build.gradle.kts` and in
  the workflow. Do not change it in one place only.
- `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` stays in the Gradle `cppFlags`
  arguments even though r28 defaults it on.
- Never vendor a prebuilt `.so`, add a `jniLibs/` blob, or link a static
  library built outside this toolchain. It will silently reintroduce 4 KiB
  alignment.

## 4. Patches and the pinned submodule

`third_party/llama.cpp` is pinned at `6aee508`. `scripts/apply-patches.sh`
applies `patches/0001-littlebit.diff` (LittleBit `Q1_0`) and
`patches/0002-android-saf-fd.diff` (SAF descriptor loading).

- Never hand-edit the submodule. `git submodule update --force` must be able to
  restore it.
- Both patches must apply and reverse-check cleanly (idempotent). The CMake
  build performs the same probe, so a non-idempotent patch breaks the *second*
  build, not the first.
- Do not renumber or rename patch files; the glob order in both the script and
  CMake matters.
- If upstream changes make a patch fail, rebase the patch — do not bump the
  pin silently. `KvCacheStore.LLAMA_COMMIT` must keep matching the pin, or every
  saved KV cache is invalidated without explanation.

## 5. Before pushing native changes

Static checks only — CI is the compiler (see `AGENTS.md`):

1. `grep -n 'Java_com_littlebit_chat_engine_LlamaJni_' llama_jni.cpp` and
   compare against the `external fun` list in `LlamaJni.kt`, in order.
2. Confirm no new symbol crossed into the wrong CMake target.
3. Confirm `exports.map` covers every new `sd_jni` export.
4. Confirm `patches/*.diff` still apply in order with `git apply --check`.
5. Confirm the Kotlin change compiles in **both** flavors (shared API must not
   reference `src/standard`).
