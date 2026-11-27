> **Before coding:** read `.agents/skills/using-superpowers/SKILL.md` and follow it. `.agents/skills/` holds Markdown-only guidance (`obra/superpowers`, GSD, Context7, `material-design-3-ui`, `mobile-android-design`); keep it free of scripts and `.git` folders.

# LittleBit Chat — permanent engineering rules

These rules apply to every change in this repository. They exist because the
Android native engines are expensive to build and the offline edition makes a
hard privacy promise.

## Builds: GitHub Actions only

- Never install a JDK, Android SDK, NDK, CMake or the Gradle wrapper in a cloud
  sandbox, and never run `./gradlew` there. The sandbox has no Android toolchain
  and a partial install produces misleading results.
- Rely on `.github/workflows/build-android-apk.yml` for unit tests,
  `assembleRelease` for both flavors, the offline manifest gate, the 16 KiB ELF
  `LOAD` alignment check, `apksigner verify` and `aapt2 dump badging`.
- Verify Kotlin/C++ changes statically (imports, signatures, JNI names, XML
  references, flavor parity) before pushing, then let CI be the compiler.

## CI: never sleep

- Never run `sleep` loops while a workflow is running. Audit untouched modules
  instead: Compose stability, lifecycle and descriptor leaks, flavor isolation,
  JNI signatures, manifest rules, ProGuard keep rules.
- Check the active run only between audit passes, and wait for it to finish
  before pushing the next commit so runs are never cancelled by a new push.

## Engines

- Keep `third_party/llama.cpp` pinned at `6aee508`. `bash scripts/apply-patches.sh`
  must apply `patches/0001-littlebit.diff` (LittleBit `Q1_0`) and
  `patches/0002-android-saf-fd.diff` (Android SAF descriptor loading) cleanly and
  idempotently. Never hand-edit the submodule.
- Keep `stable-diffusion.cpp` isolated in `libsd_jni.so`: built with
  `-fvisibility=hidden`, a linker version script, and lazily loaded from
  `SdNative`. It must never be linked into `libllama_jni.so`.
- Keep NDK r28c (`28.2.13676358`) and 16 KiB ELF `LOAD` alignment for every
  packaged arm64 library.

## Flavors

- `offline` is strictly zero-internet: no `INTERNET`, no
  `ACCESS_NETWORK_STATE`, no networking code, no cloud/API UI. All networking
  lives under `android-app/app/src/standard/`.
- `CloudFactory` and `Search` exist in both flavor source sets with identical
  signatures; the offline versions are minimal no-ops.
- Every new shared API must compile in both flavors.

## Security and storage

- Never use `androidx.security:security-crypto` / `EncryptedSharedPreferences`
  (deprecated). Use the built-in `AndroidKeyStore` AES-GCM helper in
  `src/standard` for API keys.
- Never log, copy or commit API keys. Encrypted key files and staged image
  models are excluded from backup rules.
- Never open `/proc/self/fd/<N>` by path from Kotlin. Read SAF documents through
  `contentResolver.openInputStream(uri)` or a `ParcelFileDescriptor`; only pass
  `/proc/self/fd/<N>` to native engines that explicitly duplicate the descriptor.
- Never commit `.jks`, `.keystore`, `.p12`, `keystore.properties`,
  `local.properties`, `.env*` (except `.env.example`), or temp SDK/JDK folders.

## Dependencies

- Do not add third-party Gradle dependencies. Use the libraries already in
  `android-app/app/build.gradle.kts` plus Android SDK APIs (`TextToSpeech`,
  `HttpURLConnection`, `org.json`, `AndroidKeyStore`).

## Skills

- `.agents/skills/` contains Markdown-only design guidance
  (`mobile-android-design`, `material-design-3-ui`). Apply it to Compose work:
  semantic theme roles, 48 dp touch targets, real states, accessible sliders,
  sheets and drawers. Keep those directories free of scripts and `.git` folders.
