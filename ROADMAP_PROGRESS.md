# Roadmap Progress

Strict one-version-at-a-time delivery. Each version gets its own branch from
`origin/main`, its own `versionName`/`versionCode` bump, its own PR, a 100% green
GitHub Actions run, and a clean merge before the next version starts.

## Track split (approved)

- **Track A — `v1.3.3` … `v1.3.9` (OpenCode):** backend/engine/storage/core
  features plus **minimal functional UI hooks** only (one control in an existing
  settings panel or one label in an existing screen). Full pure-JVM unit tests.
- **Track B — `v1.4.0` (Antigravity):** pure flagship Compose/M3 UI overhaul.
  Zero JNI changes, zero SQLite schema rewrites, zero new Gradle dependencies.

| Phase | Branch | Version | Code | Owner | Status |
| --- | --- | --- | --- | --- | --- |
| 0 | `chore/skills-setup` | — | — | OpenCode | **merged** (#7, `9c1e9be`) |
| 1 | `release/v1.3.2-trust-patch` | 1.3.2 | 14 | OpenCode | **merged** (#8, `c0cae93`) |
| 2 | `release/v1.3.3-studio-templates` | 1.3.3 | 15 | OpenCode | **in progress** |
| 3 | `release/v1.3.4-sampler-suite` | 1.3.4 | 16 | OpenCode | not started |
| 4 | `release/v1.3.5-sqlite-storage` | 1.3.5 | 17 | OpenCode | not started |
| 5 | `release/v1.3.6-variants-summaries` | 1.3.6 | 18 | OpenCode | not started |
| 6 | `release/v1.3.7-character-cards` | 1.3.7 | 19 | OpenCode | not started |
| 7 | `release/v1.3.8-lorebooks-prompt` | 1.3.8 | 20 | OpenCode | not started |
| 8 | `release/v1.3.9-perf-contracts` | 1.3.9 | 21 | OpenCode | not started |
| 9 | `release/v1.4.0-ui-overhaul` | 1.4.0 | 22 | **Antigravity** | not started |

`versionCode` map: 14→1.3.2, **15→1.3.3**, 16→1.3.4, 17→1.3.5, 18→1.3.6,
19→1.3.7, 20→1.3.8, 21→1.3.9, 22→1.4.0.

## Baseline

- `main` before Phase 0: `b1d8e8af79290c36b6d9a35bf2f7e374a79d8ff8`
- `main` after Phase 0: `9c1e9be489e994011786faf6c555323b9e994620`
- `main` after v1.3.2 (PR #8): `c0cae93` — `versionCode = 14`,
  `versionName = "1.3.2"`.
- `third_party/llama.cpp` pinned at `6aee508`; `stable-diffusion.cpp` pinned at
  `2988060`. `scripts/apply-patches.sh` is never modified.

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

## Phase 1 — v1.3.2 "Trust & Surface Unfair Advantages" (merged)

Branch `release/v1.3.2-trust-patch`, `versionCode = 14`. Merged as PR #8,
merge commit `c0cae93`.

1. Model Hub ARM `Q4_0` online-repacking guidance.
2. LittleBit `Q1_0` demoted to **Advanced · conversion** (no prebuilt file).
3. `ChatBudget` + `ChatJson` OOM guard: 16 MB ceiling, 300 messages per chat,
   streaming save, recoverable "History too large — export first".
4. Crash log **Copy** / **Report** actions.
5. `ChatImport` + Settings → *Chat history* → **Import chat JSON**.
6. KV cache on by default above 1K tokens, "Resumed in Xs (cache hit)".
7. **Privacy & permissions** row reading the install's real permissions.
8. 19 new JVM tests (`ChatBudgetTest`, `ChatJsonTest`, `ChatImportTest`).

## Phase 2 — v1.3.3 "Image Studio correctness + prompt/engine seams" (code 15)

Live v1.3.2 device bugs fixed here:

- **`Step 1 / 686` when Steps = 1.** `stable-diffusion.cpp@2988060` drives one
  global `sd_progress_cb_t` from two unrelated producers: tensor loading
  (`pretty_bytes_progress`, ~686 tensors for an SD1.5 checkpoint) and sampling
  steps. `sd_jni.cpp` formatted every callback as `"Step n / total"`. Fix:
  atomic `phase/step/total/requested`, phase-aware labels, denominator always
  the requested step count.
- **Hang on `Decoding VAE…` / freeze on `Cancelling…`.** `n == total` was
  treated as VAE for the *loading* callback, and `cancel()` was a no-op while no
  context existed yet. Fix: explicit phase transitions, cancel flag checked
  before/after `new_sd_ctx`, honest "Stopping after the current step" text.
- **`-inpainting` checkpoint without a photo + mask** froze the run. Fix: C++
  guard throws a readable message; Kotlin mirrors it in `ImageModelGuard` and
  the Generate button explains the requirement.
- **Prompt lost on back-navigation during a run.** Prompt/negative/seed/steps/
  strength/brush/shape/slots move from `remember` into `StudioState` owned by
  `ChatViewModel`; leaving the screen no longer cancels the render.

Also shipped in 1.3.3:

- `engine/ChatTemplate.kt` written **fresh** (ChatML, Llama 3, Mistral `[INST]`,
  Gemma, Alpaca, Vicuna, `MODEL_DEFAULT` sentinel, user-editable `CUSTOM`,
  `byId`, `suggestedFor(architecture)`). The stale `6b849f4` sandbox commit is
  **not** cherry-picked.
- `engine/StopStrings.kt` + native `StopFilter` in `runSamplingLoop`
  (byte-prefix tail holdback, ≤8 stops × 64 chars).
- `nativeCountTokens(text, parseSpecial)`; `nativePromptSize` kept as
  `parseSpecial = true`.
- Gemma 4 / Qwen3 thinking-channel normalization, reusing
  `ThinkBlocks.SHORT_OPEN` / `SHORT_CLOSE` so the Kotlin and C++ tags can never
  drift (`<`+`think`+`>` and `<`+`/think`+`>`).
- Settings → *Persona & Prompt*: template selector + custom template.
  Settings → *Advanced*: stop strings, one per line.
- Tests: `ImageProgressTest`, `ImageModelGuardTest`, `ChatTemplateTest`,
  `StopStringsTest`, `ThinkBlocksTest` additions.

## Phase 3 — v1.3.4 "Full sampler suite" (code 16)

Fixed 16-slot `jdoubleArray` sampler contract (index map documented), canonical
chain order: penalties → DRY → top_k → typical → top_n_sigma → top_p → min_p →
XTC → temp_ext → mirostat_v2/dist. `engine/SamplerConfig.kt` (pure) for
round-trip + clamping; mirostat mutually exclusive with top_p/min_p. One
"Sampler suite" group in Advanced settings. **Defaults reproduce v1.3.3 output
exactly.**

## Phase 4 — v1.3.5 "SQLite storage" (code 17)

`SQLiteOpenHelper` + WAL (`synchronous=NORMAL`): `chats`, `messages`,
`sources`, index `(chat_id, ordinal)`. Migration parses `chats.json` in one
transaction, then renames to `chats.json.bak` (kept until 1.3.6 ships).
Pagination `page(chatId, beforeOrdinal, 50)`; `ChatSession.messages` stays a
`MutableList` so the UI is untouched.

## Phase 5 — v1.3.6 "Variants + summaries" (code 18)

`ChatMessage.id`, `variants`, `activeVariant`; DB migration v2; regenerate →
add variant; swipe hook on the last assistant row (keyed by `message.id`).
`ChatSummary` auto-summarises at 80% of context and replaces trimmed turns.

## Phase 6 — v1.3.7 "Character Cards V2 + links" (code 19)

V2/V1 card parsing, PNG `tEXt`/`chara` chunks, macros, bulk import
(SillyTavern JSONL, OpenAI conversations tree → linear). **Character links store
optional `templateId`, `samplerPresetId` and `modelUri` alongside
`linkedCharacterId`, so switching character can auto-apply the chat template,
sampler preset and model.**

## Phase 7 — v1.3.8 "Lorebooks + Author's Note" (code 20)

`Lorebook`, `LoreScanner` (keyword + recursive, depth ≤ 3, token budget by
`insertion_order`), `PromptAssembler` becomes the single prompt path. The KV
cache prompt hash is computed from the **assembled** prompt, not raw messages.

## Phase 8 — v1.3.9 "Perf, estimator, PROCESS_TEXT, state contracts" (code 21)

Single-pass `MarkdownText` scanner with per-line memoization + strikethrough /
nested lists / task lists / autolinks; `TokenEstimator` + debounced composer
counter; `SharedIntents.extract(...)` fixing `ACTION_PROCESS_TEXT` and
`onNewIntent`/`setIntent`; `ChatUiState` + `ChatAction` + `StateFlow` derived
from existing snapshot state (no behaviour change).

## Phase 9 — v1.4.0 "UI overhaul" (code 22, Antigravity, UI only)

Pure flagship Compose/M3 pass over Chat, History drawer, Model Hub, Settings,
Image Studio, Assistants/Characters, Lorebook editor and Prompt inspector.
Every screen reads `ChatUiState`/`StudioState` and dispatches `ChatAction`; no
composable touches `ChatViewModel` fields directly, no JNI, no SQL, no schema
change. Deliverable: `docs/handoff/v1.4.0-ui-spec.md` (tokens, states, actions,
empty/loading/error per screen, 48 dp targets, M3 semantic roles).

## Next action

1. Finish `release/v1.3.3-studio-templates`, open PR to `main`, wait for a
   green GitHub Actions run, merge, record the merge SHA here.
2. Start `release/v1.3.4-sampler-suite` from the merged `main`.
