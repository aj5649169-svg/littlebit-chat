# Roadmap Progress

Strict one-version-at-a-time delivery. Each version gets its own branch from
`origin/main`, its own `versionName`/`versionCode` bump, its own PR, a 100%
green GitHub Actions run, and a clean merge before the next version starts.

| Phase | Branch | Version | Status | PR | Merged `main` SHA |
| --- | --- | --- | --- | --- | --- |
| 0 | `chore/skills-setup` | — | in progress | — | — |
| 1 | `release/v1.3.2-trust-patch` | 1.3.2 | not started | — | — |
| 2 | `release/v1.4.0-chatterui-core` | 1.4.0 | not started | — | — |
| 3 | `release/v1.4.1-multimodal` | 1.4.1 | not started | — | — |
| 4 | `release/v1.5.0-breadth` | 1.5.0 | not started | — | — |

## Baseline

- `main` at start of Phase 0: `b1d8e8af79290c36b6d9a35bf2f7e374a79d8ff8`
- PR #6 (`fix/v1.3.1-ram-guard-and-image-studio`) was already merged before
  Phase 0 began.
- `versionName = "1.3.1"`, `versionCode = 13`.

## Phase 0 — skills setup (no app code)

- [x] `android/skills` → `adaptive`, `edge-to-edge`, `testing-setup`
- [x] `chrisbanes/skills` → `compose-performance`, `kotlin-concurrency-and-flow`
- [x] `rcosteira79/android-skills` → `compose`
- [x] `.agents/skills/llama-jni-abi/SKILL.md` (in-repo, Markdown only)
- [x] `ROADMAP_PROGRESS.md`
- [ ] PR green + merged

All skill directories contain `.md` files only. `skills-lock.json` records the
upstream commits for reproducible reinstall.

## Version 1 — v1.3.2 "Trust & Surface Unfair Advantages"

Planned scope:

1. Model Hub ARM `Q4_0` online-repacking guidance.
2. LittleBit `Q1_0` entry demoted to Advanced / no misleading "Exclusive" badge.
3. `ChatStore` OOM guard + recoverable "History too large — export first".
4. Crash log copy + `ACTION_SEND` report.
5. Chat JSON import.
6. KV cache default-on above 1K tokens + "Resumed in Xs (cache hit)".
7. Verified permissions row in Settings → About.

## Version 2 — v1.4.0 "ChatterUI-Killer Core"

Templates, stop strings + full samplers, Character Cards V2, Lorebooks,
Author's Note at depth, `SQLiteOpenHelper` storage v2, swipe variants, chat
summaries.

## Version 3 — v1.4.1 "Multimodal"

Chat-time `mmproj` vision, offline `SpeechRecognizer` STT.

## Version 4 — v1.5.0 "Breadth"

KoboldCPP / Anthropic / custom API templates (standard only), zero-dependency
BM25 document RAG, i18n foundation.

## Next version to execute

`v1.3.2` — branch `release/v1.3.2-trust-patch` from the merged Phase 0 `main`.
