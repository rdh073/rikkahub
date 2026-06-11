# Implementation Plan: User-configurable event-hooks (PreToolUse / UserPromptSubmit / Stop)

Issues: #200 (arch-design + owner design-gate, rounds 1 & 2), #202 (epic / conflict policy).
Branch: `feat/hooks-event-system` (already checked out). One branch, both issues.

## Overview

Add a per-Assistant event-hooks system firing at three agent-loop lifecycle points
(PreToolUse / UserPromptSubmit / Stop). Hooks can **gate** a tool (deny/ask/allow),
**modify** a tool's input before approval, or **inject context**. v1 ships **LLM hooks
only** (owner-prescribed Option A) plus the two round-2 blockers: H1 cancellation-aware
provider call, H4 import-trust over both ingestion vectors, and H2 pre-approval input
rewrite. `js`/QuickJS, `subagent`, `http` hook types and PostToolUse are explicitly v2 —
not implemented here.

## Architecture Decisions

- **Pure-first, bottom-up.** Config types → pure primitives (`aggregate`/`matches`/
  `parseHookOutput`) → cancellation root-cause fix → DIP dispatcher+port → concrete
  executor → loop wiring → import-trust → UI. The pure primitives live in `ai-runtime`
  (no product flavor) where CI gates cheapest.
- **DIP at the dispatcher.** The turn loop calls `dispatch(event,…)` and never names a
  handler; executors are injected at the Koin composition root. Adding a v2 handler type
  is additive — zero edits to the loop or dispatcher.
- **No new exec path.** Decisions map onto the existing `ToolApprovalState` machine
  (`Denied`/`Pending`/`Auto-Approved`). The dispatcher only feeds that machine + a
  separate pre-approval input rewrite step.
- **H1 fix at root cause.** `Call.await()` in `common/http/Request.kt` lacks
  `invokeOnCancellation { cancel() }`; fixing it there makes every provider caller
  cancellable, not just hooks. Its OWN commit — touches a non-hook shared seam.
- **Additive serialization.** `Assistant.hooks: HookConfig = HookConfig()` is defaulted so
  old JSON decodes; `HookConfig.trusted: Boolean = false` defaults untrusted.

## Dependency Graph

```
T1 Config types (Assistant.hooks)
   │
   ├── T2 Pure primitives + PBT (aggregate / matches / parseHookOutput)
   │       │
   │       ├── T4 Dispatcher + executor port + Koin registry (DIP)
   │       │       │
   │       │       └── T5 LlmHookExecutor (concrete, cancellation-aware) ── needs T3
   │       │               │
   │       │               ├── T6 Wire PreToolUse + updatedInput rewrite (ChatTurnRuntime, H2)
   │       │               └── T7 Wire UserPromptSubmit + Stop (ChatService)
   │       │
   │       └── (primitives also consumed by T6/T7)
   │
   ├── T3 H1 cancellation fix (Call.await) — independent, parallel to T2/T4
   │
   └── T8 Import-trust H4 (force trusted=false on both vectors)
           │
           └── T9 AssistantHooksPage UI (editor + grant + test-hook) ── needs T5
                   │
T6,T7,T9 ──────────┴── T10 Tool-step "by hook" indicator (no silent action)
```

## Task List

### Phase 1: Foundation (pure, fast-testable)

- **T1 — Config types: `Assistant.hooks: HookConfig`**
  Add `HookConfig`/`HookEvent`/`HookMatcher`/`HookHandler` (sealed, `Llm` only) +
  `AggregatedHookResult`/`HookDecision`, and the additive defaulted `hooks` field on
  `Assistant`. Verify migrators don't choke on old JSON.
  Files: `ai-runtime/.../hooks/HookConfig.kt` (NEW), `app/.../data/model/Assistant.kt`,
  `app/src/test/.../AssistantHooksSerializationTest.kt` (NEW).
  Acceptance: existing assistant JSON with no `hooks` field decodes unchanged (roundtrip
  test green); `HookConfig.trusted` defaults `false`; `:ai-runtime:test` +
  `:app:testPlayDebugUnitTest` compile and pass. Deps: none. Risk: normal.

- **T2 — Pure primitives + property tests**
  `aggregate()` (deny>ask>allow, context concatenation, updatedInput chaining),
  `matches()`/`matchesIf()` (null=always; exact OR regex), `parseHookOutput()`
  (error-not-throw on malformed JSON; reject event-name mismatch). kotest-property suites.
  Files: `ai-runtime/.../hooks/HookPrimitives.kt` (NEW),
  `ai-runtime/src/test/.../HookPrimitivesPropertyTest.kt`,
  `.../HookAggregatePropertyTest.kt` (NEW).
  Acceptance: PBT green in `:ai-runtime:test` for INVARIANT deny>ask>allow (metamorphic:
  adding Allow never weakens), BOUNDARY empty→passthrough, CONSERVATION context concat,
  DETERMINISM rewrite order, parse malformed→typed failure, event-spoof→rejected.
  Deps: T1. Risk: normal.

- **T3 — H1: cancellation-aware `Call.await()`**
  Register `continuation.invokeOnCancellation { this@await.cancel() }` in
  `common/http/Request.kt`. Failing-before/passing-after regression test asserting a
  cancelled coroutine cancels the in-flight call. OWN commit (shared seam, non-hook path).
  Files: `common/.../http/Request.kt`,
  `common/src/test/.../http/CallAwaitCancellationTest.kt` (NEW).
  Acceptance: test fails on the pre-fix code and passes after; success-path behavior
  unchanged (no regression in non-stream provider callers). Deps: none. Risk: normal.

### Checkpoint A — Foundation
- [ ] `:ai-runtime:test` green (config roundtrip + all primitive PBT)
- [ ] `common` cancellation regression green
- [ ] No public signature change in `ai`/`ai-runtime`

### Phase 2: Dispatch & execution (DIP)

- **T4 — Dispatcher + executor port + Koin registry (DIP)**
  `HookExecutor` port interface, `HookDispatcher.dispatch(event,input,ctx)` (registry
  lookup → matcher filter → per-handler executor under `withTimeoutOrNull(perHookTimeout)`
  → `parseHookOutput` → `aggregate`), `failClosed` error→Deny, Koin
  `HookExecutorRegistry`. No concrete executor here.
  Files: `ai-runtime/.../hooks/HookExecutor.kt`, `.../hooks/HookDispatcher.kt` (NEW),
  `app/.../data/ai/hooks/HookExecutorRegistry.kt` (NEW), `app/.../di/*` (Koin),
  `ai-runtime/src/test/.../HookDispatcherTest.kt` (NEW, fake executor).
  Acceptance: dispatcher resolves handlers only via injected port (no `LlmHookExecutor`
  import); failClosed executor error/timeout → aggregated Deny; non-failClosed error →
  Allow (logged); test with fake executor green. Deps: T2. Risk: normal.

- **T5 — LlmHookExecutor (concrete, cancellation-aware)**
  `fastModel` single-shot; dedicated short per-hook `callTimeout` OkHttpClient (never
  inherits the 10-min ceiling); cancellation propagates to the call (relies on T3);
  `failClosed` error→Deny.
  Files: `app/.../data/ai/hooks/LlmHookExecutor.kt` (NEW), Koin binding update,
  `app/src/test/.../LlmHookExecutorTest.kt` (NEW, fake `Call`).
  Acceptance: a cancelled hook coroutine invokes `Call.cancel()` (asserted on fake Call);
  per-hook short timeout honored independent of shared 10-min readTimeout; failClosed
  error→Deny; default model resolves to `settings.fastModelId` when `model == null`.
  Deps: T4, T3. Risk: normal.

### Checkpoint B — Dispatch
- [ ] Dispatcher + executor unit tests green; DIP verified (loop/dispatcher import no concrete)
- [ ] `:app:testPlayDebugUnitTest` green

### Phase 3: Loop wiring & fire-points

- **T6 — Wire PreToolUse + pre-approval `updatedInput` rewrite (H2)**
  In `ChatTurnRuntime` (~`:184-238`): for each not-yet-executed tool dispatch PreToolUse,
  apply `updatedInput` via `tool.copy(input=…)` as a SEPARATE explicit step (not folded
  into `ToolApprovalState`), then map decision: `Deny→Denied(reason)`, `Ask→Pending`,
  `Allow→Auto/Approved`; THEN the existing `needsApproval→Pending` gate runs on the
  rewritten tool. No new exec path.
  Files: `ai-runtime/.../ChatTurnRuntime.kt`,
  `ai-runtime/src/test/.../ChatTurnRuntimePreToolUseTest.kt` (NEW).
  Acceptance: `Deny` → tool reaches `ToolApprovalState.Denied`; `Ask` → `Pending`;
  `Allow`+`updatedInput` rewrites `tool.input` BEFORE the approval gate sets `Pending`
  (assert order); no behavior change when no hooks match. Deps: T5. Risk: normal.

- **T7 — Wire UserPromptSubmit (on send) + Stop dispatch**
  Fire UserPromptSubmit on send in `ChatService` injecting `additionalContext`; fire Stop
  injecting context / honoring `preventContinuation`.
  Files: `app/.../service/ChatService.kt`,
  `app/src/test/.../ChatServiceHookFirePointsTest.kt` (NEW).
  Acceptance: UserPromptSubmit `additionalContext` is injected into the outgoing turn;
  Stop `preventContinuation=true` halts continuation; Stop `additionalContext` injected.
  Deps: T5. Risk: normal.

### Checkpoint C — Core flow end-to-end
- [ ] PreToolUse deny/ask/allow + rewrite proven in `:ai-runtime:test`
- [ ] UserPromptSubmit + Stop proven in app tests

### Phase 4: Import-trust & UX

- **T8 — Import-trust H4: force `trusted=false` on both vectors**
  Route restored assistants through untrust in `BackupArchiveRestorer` /
  `AndroidBackupArchiveEnvironment.restoreSettingsJson` (force every assistant's
  `HookConfig.trusted=false` after decode) AND in `AssistantImporter.onImport`
  (SillyTavern JSON/PNG). In-app-authored hooks stay trusted.
  Files: `app/.../data/sync/archive/BackupArchiveRestorer.kt`,
  `app/.../ui/pages/assistant/detail/AssistantImporter.kt`,
  `app/src/test/.../ImportTrustTest.kt` (NEW).
  Acceptance: assistants from `AssistantImporter` AND backup-restore both yield
  `HookConfig.trusted == false`; in-app-authored hooks remain `trusted == true`;
  untrusted hooks do NOT run in the dispatcher. Deps: T8 wiring depends on T1; trust gate
  honored by T4. Deps: T4. Risk: normal.

- **T9 — AssistantHooksPage (editor + import-trust grant + test-hook)**
  New sibling of `AssistantMcpPage`: per-event hook editor (prompt + target model),
  import-trust review surface (prompt text + model + privilege tier "data egress, medium")
  with a Grant button, and a "test hook" button that runs the executor once.
  Files: `app/.../ui/pages/assistant/detail/AssistantHooksPage.kt` (NEW),
  navigation/registration wiring.
  Acceptance: page edits `Assistant.hooks`; untrusted hooks show the review surface and a
  Grant action flips `trusted=true`; test-hook button invokes `LlmHookExecutor` and shows
  its decision; in-app-created hooks are trusted on creation. Deps: T5, T8. Risk: normal.

- **T10 — Tool-step "by hook" indicator (no silent action)**
  Surface a visible "blocked/modified by hook" badge by reusing `ChatMessageToolStep`
  `isDenied`/`isPending` rendering, so hook-driven deny/ask is never silent.
  Files: `app/.../ui/components/chat/ChatMessageToolStep.kt` (or sibling),
  related rendering.
  Acceptance: a hook-denied tool renders a visible "by hook" indicator; a hook-asked tool
  shows the HITL pending UI; manual visual check in the play-debug build. Deps: T6, T7, T9.
  Risk: normal.

### Checkpoint D — Complete
- [ ] All success criteria (§Spec) met
- [ ] `./gradlew :ai-runtime:test` + new app unit tests pass
- [ ] `./gradlew lint` clean for new files; `:app:assemblePlayDebug` builds
- [ ] PR body uses `Refs #200` / `Refs #202` (conservative; v2 items deferred), names the
      H1 `Call.await()` fix as the load-bearing shared-seam change

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| H1 `Call.await()` fix regresses non-hook provider callers | Med | Own commit + failing-before/passing-after test; success path asserted unchanged (T3) |
| `updatedInput` rewrite applied after approval (security hole) | High-if-wrong | T6 enforces rewrite-before-gate ordering with an explicit order assertion |
| Imported config carries `trusted=true` and runs unreviewed | High-if-wrong | T8 forces `false` on BOTH vectors; dispatcher refuses untrusted (T4) |
| Regex matcher ReDoS on imported configs | Med | Default exact-OR-regex per Assumptions; open Q #4 flags restricting to exact if owner prefers |
| Stale review anchors drift further | Low | Spec re-grounds anchors to current `file:line`; T6/T7 re-verify at build time |

## Open Questions (carry to PR / owner)

1. Does v1 (LLM-only + H1 + H4 + H2) close #200 or stay `Refs`? Default: `Refs #200`.
2. PostToolUse in v1? Default: defer to v2.
3. Global `Settings.hooks`? Default: per-Assistant only.
4. `matcher` regex vs exact-only (ReDoS surface)? Default: exact OR regex.
5. Trust-flag placement — in-record `HookConfig.trusted` vs device-local store? Default: in-record.
6. Hooks editor as new `AssistantHooksPage` sibling vs section in `AssistantExtensionsPage`?
   Default: new sibling.

## Notes on scope

- No high-risk tasks: no auth/permission system changes, no destructive migration (additive
  defaulted field only), no payments/deletions/secrets/deploys. Import-trust (T8) ADDS a
  restriction (untrust-by-default) and is fully git-revertable — classified normal.
- `js`/QuickJS/`subagent`/`http`/PostToolUse are v2 and intentionally absent.
