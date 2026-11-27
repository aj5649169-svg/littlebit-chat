# Roadmap Progress

Strict one-version-at-a-time delivery. Each version gets its own branch from
`origin/main`, its own `versionName`/`versionCode` bump, its own PR, a 100%
green GitHub Actions run, and a clean merge before the next version starts.

| Phase | Branch | Version | Status | PR | Merged `main` SHA |
| --- | --- | --- | --- | --- | --- |
| 0 | `chore/skills-setup` | — | **merged** | [#7](https://github.com/aj5649169-svg/littlebit-chat/pull/7) | `9c1e9be489e994011786faf6c555323b9e994620` |
| 1 | `release/v1.3.2-trust-patch` | 1.3.2 | PR open, CI running | [#8](https://github.com/aj5649169-svg/littlebit-chat/pull/8) | — |
| 2 | `release/v1.4.0-chatterui-core` | 1.4.0 | started (stacked, unpushed) | — | — |
| 3 | `release/v1.4.1-multimodal` | 1.4.1 | not started | — | — |
| 4 | `release/v1.5.0-breadth` | 1.5.0 | not started | — | — |

## Baseline

- `main` before Phase 0: `b1d8e8af79290c36b6d9a35bf2f7e374a79d8ff8`
- `main` after Phase 0: `9c1e9be489e994011786faf6c555323b9e994620`
- PR #6 (`fix/v1.3.1-ram-guard-and-image-studio`) was already merged before
  Phase 0 began.
- `v1.3.1` shipped `versionCode = 13`.

## Phase 0 — skills setup (merged, no app code)

- [x] `android/skills` → `adaptive`, `edge-to-edge`, `testing-setup`
- [x] `chrisbanes/skills` → `compose-performance`, `kotlin-concurrency-and-flow`
- [x] `rcosteira79/android-skills` → `compose`
- [x] `.agents/skills/llama-jni-abi/SKILL.md` (in-repo, Markdown only)
- [x] `ROADMAP_PROGRESS.md`
- [x] PR #7 green (no workflow run: the APK workflow's path filter excludes
  `.agents/**` and root Markdown) and merged

All skill directories contain `.md` files only. `skills-lock.json` records the
upstream commits for reproducible reinstall.

## Version 1 — v1.3.2 "Trust & Surface Unfair Advantages" (PR #8)

Branch `release/v1.3.2-trust-patch`, `versionName = 1.3.2`, `versionCode = 14`,
head `badd83176335f2f8cafd11a7c1747afe7a94d7a1`.

Shipped in the PR:

1. Model Hub ARM `Q4_0` online-repacking guidance.
2. LittleBit `Q1_0` demoted to **Advanced · conversion** (no prebuilt file).
3. `ChatBudget` + `ChatJson` OOM guard: 16 MB ceiling, 300 messages per chat,
   streaming save, recoverable "History too large — export first".
4. Crash log **Copy** / **Report** actions.
5. `ChatImport` + Settings → *Chat history* → **Import chat JSON**.
6. KV cache on by default above 1K tokens, "Resumed in Xs (cache hit)".
7. **Privacy & permissions** row reading the install's real permissions.
8. 19 new JVM tests (`ChatBudgetTest`, `ChatJsonTest`, `ChatImportTest`).

Status: GitHub Actions run
[46065331219](https://github.com/aj5649169-svg/littlebit-chat/actions/runs/46065331219)
reached **Build optimized release APK** with every earlier step green. Not yet
merged — merge only after the run reports `success`.

## Version 2 — v1.4.0 "ChatterUI-Killer Core"

Branch `release/v1.4.0-chatterui-core`, stacked on the v1.3.2 head. Do **not**
push or open its PR until PR #8 is merged and this branch is rebased onto
`origin/main`.

Already written on that branch (commit `6b849f4`):

- `engine/ChatTemplate.kt` — ChatML, Llama 3, Mistral `[INST]`, Gemma, Alpaca,
  Vicuna, a `MODEL_DEFAULT` sentinel that keeps using the GGUF template, a
  user-editable `CUSTOM`, `byId`, and `suggestedFor(architecture)`.
- `ChatTemplateTest.kt` — 10 tests covering exact rendered strings.

Confirmed against the pinned `6aee508` `llama.h` (read-only, no local
submodule needed):

- `llama_chat_apply_template(tmpl, …)` accepts a **custom template string**, and
  `llama_chat_builtin_templates()` enumerates the built-ins.
- Available samplers: `init_xtc`, `init_top_n_sigma`, `init_typical`,
  `init_temp_ext`, `init_mirostat_v2`, `init_grammar`, `init_logit_bias`,
  `init_dry`.
- There is **no stop-string sampler** in the public API — stop strings must be
  enforced in our own generation loop (`nativeGenerate`), not by a llama.cpp
  sampler.

Remaining for v1.4.0: stop strings + full sampler wiring (JNI signature change,
atomic with the Kotlin and UI sides), Character Cards V2, Lorebooks, Author's
Note at depth, `SQLiteOpenHelper` storage v2, swipe variants, chat summaries.

## Version 3 — v1.4.1 "Multimodal"

Chat-time `mmproj` vision, offline `SpeechRecognizer` STT.

## Version 4 — v1.5.0 "Breadth"

KoboldCPP / Anthropic / custom API templates (standard only), zero-dependency
BM25 document RAG, i18n foundation.

## Next action

1. Wait for run 46065331219 to finish. If `success`, merge PR #8 and record its
   merge SHA here.
2. If `failure`, fix only the reported failure on `release/v1.3.2-trust-patch`.
3. Rebase `release/v1.4.0-chatterui-core` onto the merged `main`, then continue
   v1.4.0.
