# Spec: User-configurable event-hooks (PreToolUse / UserPromptSubmit / Stop) — Issues #200, #202

> Source of truth: GitHub issue **#200** (arch-design proposal + the owner's design-gate
> comments, `@rdh073`: round-1 reject, round-2 re-gate, reopen note) and the **#202** epic that
> frames execution order/conflict policy. The issue bodies + the owner design-proposal comments ARE the primary
> requirement. This spec reconciles them against the **actual current code**, which has moved
> since the review was written (the tool-exec/approval loop now lives in `ai-runtime`'s
> `ChatTurnRuntime`, not `GenerationHandler.kt:190-302` as the review cites). Stale anchors are
> re-grounded below.

---

## Objective

Add a **user-configurable event-hooks system**: per-Assistant handlers that fire at fixed
agent-loop lifecycle points (**PreToolUse / UserPromptSubmit / Stop**) and can **gate** (deny /
ask / allow a tool), **modify** (rewrite a tool's input before it executes / before approval), or
**inject context**. Reverse-engineered from Claude Code's hooks and inverted for the Android
sandbox: the dangerous shell primitive is replaced by safer in-process primitives.

Who it's for: power users who want to gate or shape the agent loop on a specific Assistant
(block a tool, rewrite its args, inject a reminder on every prompt) without writing app code.

What success looks like:
- A user can attach an `llm` hook to an Assistant on **PreToolUse** that returns `deny` and the
  matched tool is blocked through the **existing** approval path (no new exec path), with a
  visible "blocked by hook" indicator — never silent.
- A PreToolUse `allow` + `updatedInput` rewrites the tool's input **before** the approval prompt
  is shown.
- UserPromptSubmit hooks inject `additionalContext`; Stop hooks can inject context /
  `preventContinuation`.
- Aggregation across multiple matched hooks is **deny > ask > allow** (most-restrictive wins).
- Hooks carried by an **imported** Assistant (SillyTavern import **or backup-restore**) are
  **disabled until the user reviews & grants** (import-trust gate).
- The background `llm` hook call is **genuinely cancellable** (the blocking blocker from the
  round-2 re-gate — see §"The two round-2 blockers").

### v1 scope decision (resolves the design-gate)

The owner **rejected the original js-first v1** and prescribed the v1 shape across two review
rounds. This spec implements exactly that:

> **v1 = LLM hooks only** (Option A, owner-recommended) **+ a cancellation-aware provider call**
> (H1) **+ import-trust covering backup-restore AND AssistantImporter** (H4) **+ pre-approval
> `updatedInput` rewrite** (H2).

**`js` (QuickJS) hooks are explicitly v2** and are NOT implemented here. Reason (owner mustFix,
confirmed against current code): the pinned wrapper `wang.harlon.quickjs:wrapper-android:3.2.3`
exposes **no interrupt API** (`setMemoryLimit`/`evaluate`/`destroy` only), so a synchronous
`while(true){}` inside `evaluate(...)` cannot be preempted by a coroutine `withTimeout`. Running
imported JS with no preemptive timeout is the reject reason. The `{input, console}`-only sandbox
invariant (round-1 correction #3) is therefore **also deferred to v2** with JS (round-2 H3).

`subagent` and `http` hook types remain v2 per the #202 epic (subagent runner already exists on
master, but its hook type is a hooks-v2 that depends on hooks-v1 landing first).

---

## Tech Stack

- Android / Kotlin, multi-module Gradle. JetBrains JDK 21. `play` / `sideload` product flavors.
- Koin DI, `kotlinx.serialization` (JSON, DataStore-backed `Settings`/`Assistant`), Compose UI,
  OkHttp, QuickJS (`wang.harlon.quickjs:wrapper-android:3.2.3` — **not used in v1**).
- Tests: JUnit + AndroidX Test; **property tests use `io.kotest:kotest-property:6.1.11`**
  (`Arb` / `checkAll` + JUnit `assert*`), matching the existing
  `ai/src/test/java/me/rerere/ai/ui/ToolApprovalStateInvariantPropertyTest.kt` style.
- Modules touched: `ai-runtime` (the turn loop + new pure primitives + dispatcher port), `app`
  (executors, Koin wiring, UI, import-trust, backup), `ai` (the cancellation fix in the shared
  `Call.await()` lives in `common`/`ai` — see §Cancellation).

---

## Commands

```bash
./gradlew :ai-runtime:test                       # pure primitives + dispatcher PBT (fast, CI floor)
./gradlew :app:testPlayDebugUnitTest             # app-side unit tests (flavor-qualified)
./gradlew test                                   # all JVM unit tests, all modules
./gradlew :app:assemblePlayDebug                 # build the play-flavor debug APK
./gradlew lint                                   # Android Lint
```

> Note: `app` has product flavors, so the bare `:app:test` task fans out per flavor. Prefer the
> flavor-qualified `:app:testPlayDebugUnitTest` for a fast inner loop; `./gradlew test` for the
> full gate. The pure-JVM hook primitives live in `ai-runtime`, which has no flavor and is the
> cheapest place to run the property tests CI actually gates on.

---

## Project Structure

New + touched files (grounded in the real tree):

```
ai-runtime/src/main/java/me/rerere/ai/runtime/
  hooks/HookConfig.kt          → NEW: @Serializable HookConfig / HookEvent / HookMatcher / HookHandler (sealed: Llm)
  hooks/HookPrimitives.kt      → NEW: pure aggregate() / matches() / matchesIf() / parseHookOutput()
  hooks/HookDispatcher.kt      → NEW: dispatch(event,input,ctx): AggregatedHookResult; executors injected (DIP port)
  hooks/HookExecutor.kt        → NEW: the executor port interface (DIP) — no concrete impl here
  ChatTurnRuntime.kt           → TOUCHED: PreToolUse dispatch + updatedInput rewrite wired into the exec loop

app/src/main/java/me/rerere/rikkahub/
  data/ai/hooks/LlmHookExecutor.kt        → NEW: fastModel single-shot, cancellation-aware, short per-hook timeout
  data/ai/hooks/HookExecutorRegistry.kt   → NEW: Koin composition root binds HookHandler.Llm -> LlmHookExecutor
  service/ChatService.kt                  → TOUCHED: UserPromptSubmit (sendMessage:566) + Stop dispatch fire-points
  data/model/Assistant.kt                 → TOUCHED: additive `val hooks: HookConfig = HookConfig()`
  data/sync/archive/BackupArchiveRestorer.kt → TOUCHED: route restored assistants through import-trust untrust
  ui/pages/assistant/detail/AssistantImporter.kt → TOUCHED: force imported assistants' hooks untrusted (H4)
  ui/pages/assistant/detail/
    AssistantHooksPage.kt                 → NEW: sibling of AssistantMcpPage; editor + import-trust grant + "test hook"
  di/...                                  → TOUCHED: Koin module binds the executor registry + dispatcher

common/src/main/java/me/rerere/common/http/Request.kt → TOUCHED: Call.await() honors coroutine cancellation (H1)

ai-runtime/src/test/java/me/rerere/ai/runtime/hooks/   → NEW: HookPrimitivesPropertyTest.kt, HookAggregatePropertyTest.kt
app/src/test/java/me/rerere/rikkahub/data/ai/hooks/    → NEW: LlmHookExecutorTest.kt, ImportTrustTest.kt
```

### Re-grounding the stale review anchors (read before implementing)

The owner's review cites a pre-refactor layout. The **current** seams are:

| Review's claim (issue #200) | Current reality (verified file:line) |
|---|---|
| approval seam `GenerationHandler.kt:190-228` / execute `:237-302` | Moved to `ai-runtime/.../ChatTurnRuntime.kt:184-238` (approval gate) + `executeTool` at `:481` (the `else -> execute` branch is `:515+`). `GenerationHandler.kt` is now a thin app wrapper delegating to `ChatTurnRuntime` (issue #243 slice 9). |
| `ToolApprovalState` states + no `updatedInput` | Confirmed: `Message.kt:342-363` (`Auto/Pending/Approved/Denied/Answered`); rewrite via `tool.copy(input=…)` — `UIMessagePart.Tool.input` is a raw JSON `String` (`Message.kt:468-476`). |
| per-Assistant additive field, `@Serializable`, no migration | Confirmed: `Assistant` `@Serializable` (`Assistant.kt:16-63`); pattern of additive defaulted fields already present (`localTools:44`, `mcpServers:43`, `enabledSkills:51`, `spawnable:57`, `uiAutomationEnabled:63`). |
| AssistantImporter is the import vector | Confirmed: `app/.../ui/pages/assistant/detail/AssistantImporter.kt` `onImport:(Assistant)->Unit` (`:67`), SillyTavern JSON/PNG. |
| Backup-restore is a SECOND vector (round-2 H4) | Confirmed: `AndroidBackupArchiveEnvironment` `decodeFromString<Settings>` (`:31`) → `Settings.assistants`; `BackupArchiveRestorer.kt:43` `restoreSettingsJson`. |
| LLM call not cancellable (round-2 H1) | Confirmed STILL OPEN: `common/http/Request.kt` `Call.await()` uses `suspendCancellableCoroutine` but registers **no** `invokeOnCancellation { cancel() }`; shared client `readTimeout(10, MINUTES)` (`DataSourceModule.kt:211`). |
| "QuickJS sandboxed by construction" conditional (net bridge exists) | Confirmed: `QuickJSFetch.injectFetch` installs `globalThis.fetch` (`QuickJSFetch.kt:53-107`). Moot for v1 (no JS); re-applies in v2. |

All anchors in this table re-verified against the working tree on 2026-06-12 (branch
`feat/hooks-event-system`, base `667f178f`). Notably `Call.await()`
(`common/src/main/java/me/rerere/common/http/Request.kt:11-27`) still registers no
`invokeOnCancellation` — H1 remains open and is in scope.

---

## Code Style

4-space indent, 120 col (`.editorconfig`). PascalCase classes, `*Test.kt` tests. No new strings
need localization unless the user asks (`AGENTS.md`): UI text may be literal `Text("…")` first.
Additive `@Serializable` fields take a **default** so old JSON decodes (DataStore uses
`explicitNulls=false`).

Config shape (mirrors CC; matches `localTools`/`mcpServers`/`enabledSkills` additive pattern):

```kotlin
// ai-runtime/.../hooks/HookConfig.kt
@Serializable
enum class HookEvent { PreToolUse, UserPromptSubmit, Stop }   // v1 minimal set (open Q #2 → conservative: these 3)

@Serializable
data class HookConfig(
    val hooks: Map<HookEvent, List<HookMatcher>> = emptyMap(),
)

@Serializable
data class HookMatcher(
    val matcher: String? = null,            // null = always matches (tool-name / regex; see Assumptions)
    val handlers: List<HookHandler> = emptyList(),
)

@Serializable
sealed class HookHandler {
    abstract val failClosed: Boolean        // security hooks: error => deny (default below)

    @Serializable @SerialName("llm")
    data class Llm(
        val prompt: String,
        val model: Uuid? = null,            // null => settings.fastModelId
        override val failClosed: Boolean = false,
    ) : HookHandler()
    // Js(code) / Subagent(...) are v2 — intentionally absent so old JSON with no such variant decodes.
}
```

```kotlin
// The aggregated decision the loop consumes — pure, serializable, testable.
data class AggregatedHookResult(
    val decision: HookDecision,                  // Deny(reason) > Ask > Allow  (most-restrictive wins)
    val updatedInput: String? = null,            // PreToolUse: chained rewrite of tool.input (JSON string)
    val additionalContext: String? = null,       // concatenated; UserPromptSubmit/Stop inject this
    val preventContinuation: Boolean = false,    // Stop
)
sealed interface HookDecision { object Allow; object Ask; data class Deny(val reason: String) : HookDecision }
```

---

## The two round-2 blockers (must be implemented — they gate "ready")

### H1 — cancellation-aware provider call (the binding blocker)

`LlmHookExecutor` calls `fastModel` in the background. Its `withTimeoutOrNull` only cancels the
*coroutine*; the OkHttp call keeps running for up to the shared client's 10-min read timeout
because `Call.await()` never cancels on coroutine cancellation. The fix is at the **root cause**,
not a band-aid:

- In `common/http/Request.kt`, make `Call.await()` register
  `continuation.invokeOnCancellation { this@await.cancel() }` so coroutine cancellation cancels the
  HTTP call. This is the place the invariant ("a cancelled coroutine cancels its in-flight I/O")
  was first violated; fixing it here also makes the existing non-stream provider path
  (`ChatCompletionsAPI.kt:98`, `ResponseAPI.kt:104`) correctly cancellable — a strict improvement,
  no behavior regression for the success path.
- `LlmHookExecutor` additionally applies a **short per-hook request timeout** (a dedicated
  short-`callTimeout` OkHttpClient or per-request `callTimeout`), so a hook can never inherit the
  10-minute ceiling regardless of cancellation.

> This is a real edit to a shared seam. It is justified by the owner's H1 finding and is the
> minimal correct fix. Surface it in the PR body as the load-bearing change; it touches a path
> used by non-hook generation, so it gets its own focused commit + regression test.

### H4 — import-trust covers BOTH vectors

A central **import-trust gate**: an Assistant's hooks are **disabled (untrusted)** until the user
reviews them and grants trust. v1 has only `llm` hooks → the review surface shows the prompt text
+ target model + privilege tier (llm = "data egress to provider, medium"). Trust state is a
property of the Assistant record (e.g. `HookConfig.trusted: Boolean = false` defaulting untrusted
on any import path; in-app-authored hooks are created trusted because the user is the author).

Both ingestion paths must mark restored/imported hooks untrusted:
- `AssistantImporter.onImport` (SillyTavern JSON/PNG).
- `BackupArchiveRestorer` / `AndroidBackupArchiveEnvironment.restoreSettingsJson` — after
  decoding `Settings.assistants`, force every assistant's `HookConfig.trusted = false`.

### H2 — pre-approval `updatedInput` rewrite

Approval tracks `toolCallId`, **not** input (`ChatService.kt:753` resolves by `toolCallId`). If we
rewrote input after approval, the user would approve **stale** input — a security hole. Therefore
PreToolUse dispatch runs, and `updatedInput` rewrites `tool.copy(input=…)`, **before** the
approval gate sets `Pending` in `ChatTurnRuntime.kt:184-222`. Wire order at that seam:

1. For each not-yet-executed tool: `dispatch(PreToolUse, toolAsJson, ctx)`.
2. Apply `updatedInput` → `tool.copy(input = updatedInput)` (the rewrite step — a **separate
   explicit step**, NOT folded into `ToolApprovalState`, per round-1 correction #1).
3. Map decision onto the **existing** states: `Deny(reason)` → `ToolApprovalState.Denied(reason)`
   (existing denied path in `executeTool`); `Ask` → `ToolApprovalState.Pending` (existing HITL
   break-and-wait); `Allow` → leave `Auto`/`Approved` so the existing `else -> execute` branch runs.
4. THEN the existing `needsApproval`→`Pending` gate runs on the (possibly rewritten) tool.

No new execution path is introduced — the dispatcher only feeds the existing `ToolApprovalState`
machine + a pre-approval input rewrite.

---

## HookDispatcher (DIP — not the transformer fold)

`HookDispatcher.dispatch(event, input, ctx): AggregatedHookResult`:
registry lookup by `event` → filter `HookMatcher` by `matches(matcher, toolName)` → for each
`HookHandler` look up its executor (injected port `HookExecutor`, bound at the Koin root) →
execute under `withTimeoutOrNull(perHookTimeout)` → `parseHookOutput` → `aggregate` all results.

- **DIP:** the loop calls `dispatch(event)` and never knows individual handlers; executors
  self-register / are injected at the composition root (adding a v2 handler type = additive, no
  edit to the loop). The dispatcher depends on the `HookExecutor` port, not on `LlmHookExecutor`.
- **SoC:** transformers stay `List<UIMessage> -> List<UIMessage>` folds (`Transformer.kt`); the
  dispatcher is decision-shaped (`event,input -> decision`). Distinct concern, distinct module.
- **failClosed:** an executor error (or timeout) on a `failClosed` handler aggregates as
  `Deny`. Non-failClosed error → that handler contributes `Allow` (benign, logged).
- **Event-name validation:** a hook's parsed output that claims a different event than the one
  dispatched is rejected (no spoofing) in `parseHookOutput`.

---

## Testing Strategy

Framework: JUnit + `kotest-property` 6.1.11. Pure primitives live in `ai-runtime` (no flavor,
fast) — **CI gates on `:ai-runtime:test`**. Property tests prioritize Invariant / Boundary /
Metamorphic over example cases, matching `ToolApprovalStateInvariantPropertyTest.kt`.

**`aggregate` (highest value):**
- INVARIANT: `deny > ask > allow` — adding an `Allow` to any set never weakens a `Deny`/`Ask`
  (metamorphic: `aggregate(S + [Allow]).decision == aggregate(S).decision`).
- BOUNDARY: empty set → passthrough (`Allow`, no context, no rewrite).
- CONSERVATION: `additionalContext` is the concatenation of all contributors (order-stable).
- DETERMINISM: `updatedInput` chaining is order-deterministic for a fixed handler order.

**`parseHookOutput`:**
- malformed JSON → error-not-crash (returns a typed failure, never throws to the loop).
- event-name mismatch → **rejected** (no spoofing).
- valid decision roundtrips (Allow/Ask/Deny + updatedInput + additionalContext).

**`matches` / `matchesIf`:**
- null matcher → always matches.
- pattern correctness (tool-name / regex per Assumptions).

**Executor / wiring (app, JVM where possible):**
- `LlmHookExecutorTest`: a cancelled hook coroutine cancels the underlying call (assert
  `Call.cancel()` invoked — fake `Call`); per-hook short timeout honored; `failClosed` error → Deny.
- `ImportTrustTest`: import via `AssistantImporter` AND restore via `BackupArchiveRestorer` both
  yield `HookConfig.trusted == false`; in-app-authored hooks stay trusted.
- Cancellation regression (`common`): `Call.await()` cancels the call on coroutine cancellation
  (failing-before / passing-after).

Every new error/decision branch (`Deny`, `Ask`, `Allow`, failClosed-deny, parse-failure,
event-spoof-reject) gets at least one test.

---

## Boundaries

- **Always:** keep the `hooks` field additive with a default (old JSON must decode — verify against
  `SettingsJsonMigrator.kt`, `PreferenceStoreV3Migration.kt`); run `:ai-runtime:test` + the new
  app tests before each commit; wire decisions through the **existing** `ToolApprovalState` machine
  only; surface a visible "by hook" indicator (no silent action); one logical change per commit
  (the `Call.await()` cancellation fix is its OWN commit).
- **Ask first:** adding a dependency; touching the shared OkHttp client/timeouts beyond the
  `Call.await()` cancellation fix; changing any public signature in `ai`/`ai-runtime`; adding a
  new `HookEvent` beyond the 3 (open Q #2); choosing the `matcher` syntax beyond simple
  tool-name/regex (open Q #3).
- **Never:** implement `js`/QuickJS hooks in v1 (owner mustFix — no preemptive timeout on the
  pinned wrapper); inject any QuickJS bridge; run imported/restored hooks before the import-trust
  grant; rewrite tool input *after* the approval prompt (H2 security hole); swallow an executor
  error on a `failClosed` hook into `Allow`; let a hook spoof a different event; commit secrets;
  remove/skip failing tests without written reason; `git add -A` (stage explicit paths only,
  silently exclude agent-state dirs).

---

## Success Criteria (specific, testable)

1. `Assistant.hooks: HookConfig` is additive + defaulted; an existing assistant JSON (no `hooks`
   field) decodes unchanged — covered by a roundtrip/migration test.
2. `aggregate` satisfies deny>ask>allow + empty-passthrough + context-conservation +
   rewrite-determinism (PBT, green in `:ai-runtime:test`).
3. `parseHookOutput` returns error (not throw) on malformed JSON and **rejects** event-name
   mismatch (PBT).
4. PreToolUse `Deny` blocks the matched tool via `ToolApprovalState.Denied` with a visible
   indicator; `Ask` routes to the existing HITL `Pending`; `Allow`+`updatedInput` rewrites
   `tool.input` **before** the approval gate.
5. A cancelled LLM hook cancels the underlying OkHttp call (no 10-minute hang) — regression test
   green; the per-hook request timeout is short and independent of the shared 10-min ceiling.
6. Hooks on an Assistant arriving via **either** `AssistantImporter` **or** backup-restore are
   `trusted=false` and do not run until the user grants trust on `AssistantHooksPage`.
7. UserPromptSubmit injects `additionalContext`; Stop can `preventContinuation` / inject context.
8. `./gradlew :ai-runtime:test` and the new app unit tests pass; `./gradlew lint` clean for new
   files; `:app:assemblePlayDebug` builds.

---

## Plan (implementation order — sequential, pure-testable first)

Mirrors #200's decomposition, minus the JS PR (v2), plus the two round-2 prerequisites:

1. **Config:** `Assistant.hooks: HookConfig` + `HookEvent`/`HookMatcher`/`HookHandler.Llm`
   (additive, defaulted). Verify migrators don't choke. (1 commit)
2. **Pure primitives + PBT:** `aggregate`, `matches`/`matchesIf`, `parseHookOutput` in
   `ai-runtime/.../hooks/`, with the kotest-property suites. (1 commit)
3. **H1 cancellation fix:** `Call.await()` honors `invokeOnCancellation { cancel() }` +
   regression test. Its OWN commit (shared seam, touches non-hook path). (1 commit)
4. **Executors + dispatcher (DIP):** `LlmHookExecutor` (fastModel single-shot, short per-hook
   timeout, failClosed→Deny) + `HookExecutor` port + `HookDispatcher` + Koin registry. (1 commit)
5. **Wire fire-points:** PreToolUse + `updatedInput` pre-approval rewrite into
   `ChatTurnRuntime` (`:184-238`); UserPromptSubmit (on send) + Stop into `ChatService`. (1 commit)
6. **Import-trust (H4) + UX:** `AssistantHooksPage` (sibling of `AssistantMcpPage`) + test-hook
   button + import-trust grant; force `trusted=false` in `AssistantImporter` and
   `BackupArchiveRestorer`; tool-step "by hook" badge (reuse `ChatMessageToolStep` isDenied/
   isPending). (1 commit)

Branch: already on `feat/hooks-event-system`. **One branch, both issues** per the user's
instruction and #202's serialize-the-tool-exec-block conflict policy.

### PR body Fixes/Refs decision

- **#200** — implements the owner's prescribed **v1** (LLM-only + H1 + H4 + H2). Because js/
  subagent/http hooks and the remaining events are **explicitly v2/deferred** in the issue itself,
  #200 as a whole is not fully closed by this PR. Use **`Refs #200`** (not `Fixes`) unless the
  owner confirms v1 closes the issue. (Conservative default — see Assumptions.)
- **#202** — an epic tracking #200 + #201; #201 is already done, #200 is partially delivered (v1).
  The epic is not fully closed → **`Refs #202`**.

---

## Assumptions

1. **No mid-run interview** → most conservative reading of each open question:
   - **Config home (open Q #1):** per-Assistant only (the issue's "recommended"). No global
     `Settings.hooks` in v1.
   - **v1 events (open Q #2):** exactly `PreToolUse`, `UserPromptSubmit`, `Stop` (the issue's
     proposed minimal set). No PostToolUse in v1 (the issue mentions PostToolUse
     `additionalContext`/`updatedMCPToolOutput` but lists only the 3 events for v1; PostToolUse is
     treated as deferred to avoid scope creep).
   - **`matcher` syntax (open Q #3):** simple tool-name **exact match OR regex** (null = always),
     matching that rikkahub has no permission-rule DSL. No DSL.
2. **v1 = LLM hooks ONLY.** `js`/QuickJS hooks are NOT implemented (owner mustFix — pinned wrapper
   has no interrupt API). The `{input,console}` sandbox invariant is deferred with JS to v2.
3. The **import-trust** trust flag is modeled as part of the Assistant record (e.g.
   `HookConfig.trusted`), defaulting `false` and forced `false` on every import/restore path;
   in-app authoring sets it `true`. (The exact field name/placement is an implementation detail
   chosen at build time; the invariant is what's specified.)
4. The **H1 fix** belongs in `common/http/Request.kt` `Call.await()` (root cause) rather than
   wrapping each provider call, because that is where the cancellation invariant first breaks and
   the fix improves all callers. A short per-hook `callTimeout` is layered on top for defense.
5. `subagent`/`http` hook types and additional events stay **v2** per the #202 epic, even though
   the subagent runner exists on master.
6. Repo is **public** (`rdh073/rikkahub`) → PR/commit text carries no tooling/provenance/infra
   provenance; describe work neutrally.
7. No DataStore migration is needed (additive defaulted field), but the build step must *verify*
   old JSON decodes against `SettingsJsonMigrator`/`PreferenceStoreV3Migration` rather than assume.

## Open Questions

1. **Does v1 (LLM-only + H1 + H4 + H2) close #200, or stay `Refs`?** The issue is
   `status/needs-design` with v2 items explicitly deferred. Default chosen: **`Refs #200`**. If the
   owner considers the prescribed v1 the closing deliverable, switch to `Fixes #200`.
2. **PostToolUse in v1?** The issue's decision protocol §4 describes PostToolUse
   `additionalContext`/`updatedMCPToolOutput`, but the v1 event set is named as the 3 above.
   Confirm whether PostToolUse ships in v1 or defers with JS to v2. (Default: defer.)
3. **Global `Settings.hooks` (open Q #1)?** Per-Assistant only is assumed. Confirm no global hooks
   in v1.
4. **`matcher` regex vs exact-only?** Assumed "exact OR regex". If regex is unwanted in v1 (ReDoS
   surface on imported configs), restrict to exact tool-name match. Confirm.
5. **Trust-flag placement** — `HookConfig.trusted` vs a separate per-Assistant field vs a
   device-local trust store keyed by assistant id. The device-local store is more robust (an
   imported JSON can't carry `trusted=true`), but heavier. Default: in-record `trusted=false`
   forced on import. Confirm acceptable, or require the device-local store.
6. **`AssistantExtensionsPage` already exists** — should the hooks editor be a new
   `AssistantHooksPage` sibling, or a section inside `AssistantExtensionsPage`? Default: new
   sibling page (matches the issue's "sibling of `AssistantMcpPage`"). Confirm.
