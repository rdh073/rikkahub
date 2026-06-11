# Implementation Plan: User-configurable event-hooks (PreToolUse / UserPromptSubmit / Stop)

Spec: `SPEC.md` (v1 = LLM hooks only + H1 cancellation fix + H4 import-trust + H2
pre-approval rewrite). Issues: Refs #200, Refs #202. Branch: `feat/hooks-event-system`,
base `667f178f`.

## Overview

Add per-Assistant lifecycle hooks that can gate (deny/ask/allow), rewrite tool input
pre-approval, and inject context — all wired through the EXISTING `ToolApprovalState`
machine (no new exec path). v1 ships only the `llm` handler type; `js`/`subagent`/`http`
and PostToolUse are v2 by owner decision. Two round-2 blockers are in scope: H1
(`Call.await()` must honor coroutine cancellation) and H4 (import-trust gate on both
ingestion vectors).

## Architecture Decisions

- Pure decision primitives (`aggregate`, `matches`, `parseHookOutput`) live in
  `ai-runtime` — flavor-free, fast, CI gates on `:ai-runtime:test`.
- DIP: `HookDispatcher` depends on the `HookExecutor` port; `LlmHookExecutor` is bound
  at the Koin composition root. Adding a v2 handler is additive — zero loop edits.
- H2 ordering: PreToolUse dispatch + `updatedInput` rewrite run BEFORE the approval
  gate sets `Pending` (`ChatTurnRuntime.kt:184-222`); approval tracks `toolCallId`, so
  post-approval rewrite would approve stale input.
- H1 fix at root cause: `common/http/Request.kt` `Call.await()` registers
  `invokeOnCancellation { cancel() }`. Own commit — shared seam touching non-hook paths.
- Trust flag in-record (`HookConfig.trusted = false` default), forced `false` on both
  import paths; in-app authoring sets `true` (spec Assumption 3, open Q #5 default).
- Matcher: exact tool-name OR regex; `null` = always (open Q #3 conservative default).

## Task List

Each task = one commit. T2/T3 split the spec's plan-step 2 to keep slices thin; T7/T8
split step 5 by module; T9/T10 split step 6 into enforcement vs UI.

### Phase 1: Foundation (pure, no behavior change)

#### T1: Hook config types + additive `Assistant.hooks` field
**Description:** `HookConfig`/`HookEvent`/`HookMatcher`/`HookHandler.Llm` (sealed,
`@SerialName("llm")`, `failClosed`, `trusted: Boolean = false` on `HookConfig`) in
`ai-runtime/.../hooks/HookConfig.kt`; additive defaulted `val hooks: HookConfig =
HookConfig()` on `Assistant`.
**Acceptance criteria:**
- [ ] Old assistant JSON (no `hooks` field) decodes unchanged — roundtrip test; verified
      against `SettingsJsonMigrator` / `PreferenceStoreV3Migration` (not assumed).
- [ ] `HookConfig` with an `llm` handler serializes/deserializes round-trip.
- [ ] Only the `Llm` variant exists (no `Js`/`Subagent` in v1).
**Verification:** `./gradlew :ai-runtime:test :app:testPlayDebugUnitTest`
**Dependencies:** None
**Files:** `ai-runtime/src/main/java/me/rerere/ai/runtime/hooks/HookConfig.kt` (NEW),
`app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`, decode-roundtrip test
**Scope:** S | **Risk:** normal

#### T2: Pure primitives — `aggregate` + `matches`/`matchesIf` + PBT
**Description:** `AggregatedHookResult`, `HookDecision`, `aggregate()`, `matches()`,
`matchesIf()` in `HookPrimitives.kt` + kotest-property 6.1.11 suites (style of
`ToolApprovalStateInvariantPropertyTest.kt`).
**Acceptance criteria:**
- [ ] PBT green: deny>ask>allow invariant; metamorphic
      `aggregate(S+[Allow]).decision == aggregate(S).decision`; empty set → Allow
      passthrough; `additionalContext` concatenation order-stable; `updatedInput`
      chaining order-deterministic.
- [ ] `matches`: null matcher always matches; exact tool-name and regex both covered.
**Verification:** `./gradlew :ai-runtime:test`
**Dependencies:** T1
**Files:** `ai-runtime/.../hooks/HookPrimitives.kt` (NEW),
`ai-runtime/src/test/java/me/rerere/ai/runtime/hooks/HookAggregatePropertyTest.kt`,
`.../HookPrimitivesPropertyTest.kt`
**Scope:** S | **Risk:** normal

#### T3: Pure primitives — `parseHookOutput` + PBT
**Description:** `parseHookOutput()` returning a typed result (never throws to the
loop), with event-name validation (spoof rejection), + property tests.
**Acceptance criteria:**
- [ ] Malformed JSON → typed failure, never an exception (PBT over garbage inputs).
- [ ] Output claiming a different event than dispatched is rejected.
- [ ] Valid Allow/Ask/Deny + `updatedInput` + `additionalContext` roundtrip (PBT).
**Verification:** `./gradlew :ai-runtime:test`
**Dependencies:** T1
**Files:** `ai-runtime/.../hooks/HookPrimitives.kt` (extend),
`ai-runtime/src/test/.../HookPrimitivesPropertyTest.kt` (extend)
**Scope:** XS | **Risk:** normal

#### T4: H1 — `Call.await()` cancellation fix + regression test
**Description:** Register `continuation.invokeOnCancellation { this@await.cancel() }`
in `common/http/Request.kt:11-27`. Own commit; surfaced in the PR body as the
load-bearing change (touches non-hook provider paths `ChatCompletionsAPI.kt:98`,
`ResponseAPI.kt:104` — strict improvement, no success-path change).
**Acceptance criteria:**
- [ ] Regression test failing-before/passing-after: cancelling the awaiting coroutine
      invokes `Call.cancel()` (fake/mock `Call`).
- [ ] Success and failure paths of `await()` behave exactly as before.
**Verification:** `./gradlew :common:test` (module test task) then `./gradlew test`
**Dependencies:** None (parallel-safe with T1-T3)
**Files:** `common/src/main/java/me/rerere/common/http/Request.kt`, new `common` test
**Scope:** XS | **Risk:** normal

### Checkpoint A (after T1-T4)
- [ ] `./gradlew :ai-runtime:test` and `./gradlew test` green; zero app behavior change.

### Phase 2: Dispatch machinery

#### T5: `HookExecutor` port + `HookDispatcher` (DIP)
**Description:** Port interface + dispatcher in `ai-runtime`: registry lookup by event
→ matcher filter → executor lookup via injected port →
`withTimeoutOrNull(perHookTimeout)` → `parseHookOutput` → `aggregate`.
**Acceptance criteria:**
- [ ] `dispatch(event, input, ctx)` returns `AggregatedHookResult`; the loop never sees
      individual handlers (unit test with fake executors).
- [ ] failClosed executor error/timeout aggregates as Deny; non-failClosed error
      contributes Allow (logged, not swallowed silently).
- [ ] Untrusted `HookConfig` (`trusted=false`) dispatches as passthrough — no executor
      invoked (test).
**Verification:** `./gradlew :ai-runtime:test`
**Dependencies:** T2, T3
**Files:** `ai-runtime/.../hooks/HookExecutor.kt` (NEW),
`ai-runtime/.../hooks/HookDispatcher.kt` (NEW), dispatcher unit test
**Scope:** S | **Risk:** normal

#### T6: `LlmHookExecutor` + Koin registry binding
**Description:** fastModel single-shot executor (model = handler's or
`settings.fastModelId`), short per-hook `callTimeout` independent of the shared 10-min
client ceiling, cancellation-aware (rides T4); `HookExecutorRegistry` bound at the Koin
composition root.
**Acceptance criteria:**
- [ ] `LlmHookExecutorTest`: cancelled hook coroutine cancels the underlying call
      (assert `Call.cancel()` via fake); short per-hook timeout honored; failClosed
      error → Deny.
- [ ] Koin module resolves `HookDispatcher` with the `Llm → LlmHookExecutor` binding.
**Verification:** `./gradlew :app:testPlayDebugUnitTest`
**Dependencies:** T4, T5
**Files:** `app/src/main/java/me/rerere/rikkahub/data/ai/hooks/LlmHookExecutor.kt` (NEW),
`app/.../data/ai/hooks/HookExecutorRegistry.kt` (NEW), `app/.../di/...` (Koin module),
`app/src/test/.../data/ai/hooks/LlmHookExecutorTest.kt`
**Scope:** M | **Risk:** normal

### Phase 3: Fire-points (behavior goes live)

#### T7: Wire PreToolUse + pre-approval `updatedInput` rewrite (H2)
**Description:** In `ChatTurnRuntime.kt:184-238`, for each not-yet-executed tool:
dispatch PreToolUse → apply `tool.copy(input = updatedInput)` as a separate explicit
step (NOT folded into `ToolApprovalState`) → map Deny→`Denied(reason)`, Ask→`Pending`,
Allow→leave `Auto`/`Approved` → THEN the existing `needsApproval` gate runs on the
rewritten tool. Re-verify the line anchors against the working tree first.
**Acceptance criteria:**
- [ ] Deny blocks via existing `ToolApprovalState.Denied` with the hook's reason (test).
- [ ] Ask routes to the existing HITL `Pending` (test).
- [ ] Allow + `updatedInput` rewrites `tool.input` BEFORE the approval gate — the
      approval prompt sees the rewritten input (test asserts ordering).
- [ ] No hooks configured → loop behavior unchanged (all existing tests still green).
**Verification:** `./gradlew :ai-runtime:test` then `./gradlew test`
**Dependencies:** T5
**Files:** `ai-runtime/.../ChatTurnRuntime.kt`, runtime wiring test
**Scope:** M | **Risk:** normal

#### T8: Wire UserPromptSubmit + Stop into `ChatService`
**Description:** Dispatch UserPromptSubmit at the send seam (`ChatService.kt:566`)
injecting `additionalContext`; dispatch Stop at turn end honoring
`preventContinuation` + context injection.
**Acceptance criteria:**
- [ ] UserPromptSubmit `additionalContext` is appended to the outgoing turn (test).
- [ ] Stop `preventContinuation=true` stops continuation; Stop context injection works
      (test).
- [ ] No hooks configured → send/stop paths unchanged.
**Verification:** `./gradlew :app:testPlayDebugUnitTest`
**Dependencies:** T6, T7
**Files:** `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`, app unit test
**Scope:** S | **Risk:** normal

### Checkpoint B (after T5-T8)
- [ ] End-to-end: an `llm` PreToolUse hook on a trusted Assistant denies/rewrites a
      tool through the existing approval machine; `./gradlew test` green.

### Phase 4: Import-trust + UX

#### T9: H4 — import-trust enforcement on BOTH vectors
**Description:** Force `HookConfig.trusted = false` in `AssistantImporter.onImport`
(SillyTavern JSON/PNG, `:67`) and in `BackupArchiveRestorer` /
`AndroidBackupArchiveEnvironment.restoreSettingsJson` (after `Settings.assistants`
decode). In-app-authored hooks are created `trusted = true`.
**Acceptance criteria:**
- [ ] `ImportTrustTest`: import via `AssistantImporter` yields `trusted == false` even
      when the imported JSON carries `trusted: true`.
- [ ] Backup-restore forces `trusted == false` on every restored assistant.
- [ ] In-app-authored hooks remain trusted.
**Verification:** `./gradlew :app:testPlayDebugUnitTest`
**Dependencies:** T1 (runtime enforcement of untrusted = T5's passthrough)
**Files:** `app/.../ui/pages/assistant/detail/AssistantImporter.kt`,
`app/.../data/sync/archive/BackupArchiveRestorer.kt`,
`app/src/test/.../data/ai/hooks/ImportTrustTest.kt`
**Scope:** S | **Risk:** normal

#### T10: `AssistantHooksPage` editor + trust grant + "by hook" indicator
**Description:** New page, sibling of `AssistantMcpPage` (open Q #6 default): list/edit
`llm` hooks (prompt, model, failClosed, matcher, event); import-trust review surface
(prompt text + target model + privilege tier "llm = data egress to provider, medium")
with grant action; "test hook" button; tool-step "blocked by hook" badge reusing
`ChatMessageToolStep` isDenied/isPending — never silent.
**Acceptance criteria:**
- [ ] User can author/edit/delete an `llm` hook per event with matcher; authored hooks
      are trusted.
- [ ] Untrusted hooks render a review+grant flow; granting flips `trusted=true`.
- [ ] A hook-denied tool shows a visible "blocked by hook" indicator with the reason.
- [ ] "Test hook" runs the handler once and shows the parsed result.
**Verification:** `./gradlew :app:testPlayDebugUnitTest :app:assemblePlayDebug` +
manual check of page navigation and badge
**Dependencies:** T7, T9
**Files:** `app/.../ui/pages/assistant/detail/AssistantHooksPage.kt` (NEW), navigation
registration, tool-step badge in chat UI
**Scope:** M | **Risk:** normal

#### T11: Full verification gate
**Description:** Run the complete gate from the spec's Commands section; fix lint
findings in new files; confirm all 8 spec success criteria.
**Acceptance criteria:**
- [ ] `./gradlew test` green (all modules); `./gradlew :ai-runtime:test` green.
- [ ] `./gradlew lint` clean for new files; `./gradlew :app:assemblePlayDebug` builds.
- [ ] Spec Success Criteria 1-8 each verified and checked off.
**Verification:** the commands above
**Dependencies:** T8, T10
**Files:** none new (fixes only if the gate fails)
**Scope:** XS | **Risk:** normal

### Checkpoint: Complete
- [ ] All acceptance criteria met; PR body uses `Refs #200` / `Refs #202` (not Fixes,
      per spec) and surfaces T4's shared-seam change as load-bearing; no
      tooling/provenance text (public repo).

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| T4 touches the shared non-stream provider path | Med | Own commit + failing-before regression test; success path asserted unchanged; scheduled early (fail fast) |
| `ChatTurnRuntime` seam drift vs spec line anchors | Med | Re-verify `:184-238` / `executeTool:481` before T7; anchors re-grounded 2026-06-12 |
| Old JSON decode breakage from new fields | Med | Additive defaulted fields only; T1 roundtrip test against both migrators |
| Regex matcher ReDoS on imported configs (open Q #4) | Low | Untrusted-until-granted (T9) bounds exposure; flag in PR if owner wants exact-only |
| In-record `trusted` flag spoofable in imported JSON | Med | T9 forces `false` on both vectors regardless of payload; test asserts override of `trusted:true` |

## Open Questions (defaults chosen, flagged in PR)

Spec §Open Questions 1-6 — all resolved with the spec's conservative defaults:
Refs-not-Fixes; no PostToolUse; per-Assistant only; exact-or-regex matcher; in-record
trust flag forced on import; new sibling `AssistantHooksPage`.

## Parallelization

T4 is independent of T1-T3 (safe in parallel). T2 and T3 are parallel after T1.
T5 onward is sequential — shared `ChatTurnRuntime`/`ChatService` seams per #202's
serialize-the-tool-exec-block conflict policy.
