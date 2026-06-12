# Spec: Task Tool & Task Management (subagent spawn lifecycle + work-item board)

Status: awaiting human review (spec-driven-development Phase 1 gate).
Provenance: derived from the maintainer-APPROVED design note
`~/notes/design/rikkahub-task-tool-design.md` (3 analysis parts + "Re-grounding" +
"Keputusan maintainer 2026-06-12"). This spec does not redesign — it transcribes the approved
design into an implementable roadmap, with every code anchor re-verified against current master
`dd41aabe` (the design note's evidence base was stale master `667f178f`).

Prior delivery's spec (CI warning burn-down + quality/perf audit) is DELIVERED on master
(PRs #266/#272); its full text is preserved in git history — `git show 3e53fcff:SPEC.md`.
Per the keep-the-spec-alive rule, this file now carries the current delivery.

## Re-grounding: design-note anchors vs current master (dd41aabe)

Verified in this repo before writing this spec. Implementers MUST re-verify per slice; line
numbers below are current as of `dd41aabe`.

| Design-note claim | Status on current master |
|---|---|
| `ConversationSession.setJob` lacks identity guard (Fase-2 Slice 1) | **ALREADY FIXED** — `compareAndSet(job, null)` guard with rationale comment at `app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt:98-116` (landed via PR #266). Slice 1 is REMOVED from the roadmap. |
| "HookDispatcher/PreToolUse symbols not present" | **STALE — they exist** (PR #264). `ai-runtime/src/main/java/me/rerere/ai/runtime/hooks/HookDispatcher.kt:25`; PreToolUse fire-point `ai-runtime/src/main/java/me/rerere/ai/runtime/ChatTurnRuntime.kt:191-195` and `applyPreToolUseHooks` at `:281`. Better still: `GenerationHandler` already injects the dispatcher into `ChatTurnRuntime` (`app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt:70-89`), and the subagent path runs through `generateText` — so child turns ALREADY get PreToolUse dispatch keyed off the (ephemeral) sub-assistant's hooks. No new hook port is needed; the only requirement is that `TaskCoordinator` keeps driving `generateText` rather than bypassing it. |
| Spawn tool reserves name `task`, strips approval tools | Confirmed — `SPAWN_TOOL_NAME = "task"` at `app/src/main/java/me/rerere/rikkahub/data/ai/subagent/SpawnTool.kt:28`; `.filterNot { it.needsApproval }` in `execute`; `needsApproval = false` on the spawn tool itself. |
| `SubagentRunner` inline, prompt-only, memory-off, no Room, returns final text only | Confirmed — `app/src/main/java/me/rerere/rikkahub/data/ai/subagent/SubagentRunner.kt` (`SUBAGENT_DEFAULT_MAX_STEPS = 64`, returns `extractFinalAssistantText`). This is the product path this delivery replaces with `TaskCoordinator`. |
| `ToolCatalog` / `ToolAssemblyContext` neutral port | Confirmed and RICHER than the note: `TurnMode.Main/Subagent`, `allowApprovalTools`, `includeSpawnTool` already exist — `ai-runtime/src/main/java/me/rerere/ai/runtime/contract/ToolCatalog.kt`. `AppToolCatalog` implements recursion-guard + approval-strip (`app/src/main/java/me/rerere/rikkahub/data/ai/runtime/AppToolCatalog.kt:46-89`) and is constructed per-generation by ChatService (`di/AppModule.kt:102` comment). |
| Subagent primitives reusable | Confirmed — `resolveSubagentModel`, `filterToolsForSubagent`, `extractFinalAssistantText` in `ai-runtime/src/main/java/me/rerere/ai/runtime/subagent/SubagentPrimitives.kt`; property tests at `app/src/test/java/me/rerere/rikkahub/data/ai/subagent/SubagentPrimitivesPropertyTest.kt`. |
| Sealed `UIMessagePart` `@SerialName` registry; no new part in v1 | Confirmed — `ai/src/main/java/me/rerere/ai/ui/Message.kt:384-475` (`"tool"` at 475); roundtrip arb `ai/src/test/java/me/rerere/ai/ui/UIMessageArb.kt`. |
| Tool UI registry for a `task` renderer | Confirmed — `ToolUIRenderer` / `ToolUIRegistry` at `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt:53,88`. |
| Approval keyed by `toolCallId`; `handleToolApproval` | Confirmed — `ChatService.handleToolApproval` at `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:897`; `stopGeneration` at `:1791`. |
| `Assistant.spawnable` / `maxSteps` additive fields | Confirmed — `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt:58-59`; additive-default test precedent `app/src/test/java/me/rerere/rikkahub/data/model/AssistantSubagentFieldsTest.kt`. |
| Room DB version | Current `version = 24` (`app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt:43`). New tables land as migration **24 → 25**. |
| FGS `START_NOT_STICKY`, wake-lock renewal | Confirmed — `app/src/main/java/me/rerere/rikkahub/service/GenerationForegroundService.kt:112,136-142`. |
| Kotest property deps in `:ai-runtime` and `:app` | Confirmed — kotest 6.1.11; `ai-runtime/build.gradle.kts:47`, `app/build.gradle.kts:351`. |

## Objective

Implement two separable but composing capabilities, exactly as the approved design note's
verdicts specify:

1. **Task (subagent-spawn) tool, lifecycle-aware.** Replace the inline final-text-only
   `SubagentRunner` product path with a `TaskCoordinator` backed by a pure `:ai-runtime` task
   state machine: persisted task runs (Room `TaskRunEntity`, summary-only), async spawn
   returning an execution handle, child tool approvals surfaced to the parent via an explicit
   safe-tool allowlist, budgets (steps / wall-time / tokens / concurrency), cancel cascade,
   process-death recovery, and summary-injection resume. The tool keeps its reserved name
   `task` and renders through the existing `Tool` part + `ToolUIRegistry` — **no new
   `UIMessagePart` subtype in v1**.
2. **Task Management board.** A per-conversation, Room-persisted work-item board
   (`task_create/get/list/update` tools + read-write Compose panel) with normalized dependency
   edges, atomic transactional claims with leases, cycle rejection, orphan recovery, and
   retention — usable by the user, the parent agent, and spawned subagents alike. Live
   coroutine handles stay in-memory (`ExecutionHandleRegistry`); board claims/leases persist.

Success looks like: a parent assistant can spawn N subagents that coordinate over one shared
board; the user can watch and edit the board live; killing the app mid-run yields `Interrupted`
tasks that are resumable from their summaries with no duplicated side effects; and every
invariant below is pinned by a kotest property that runs in plain JVM CI.

## Maintainer decisions (BINDING — do not deviate)

1. **Child transcript: summary-only.** `TaskRunEntity` stores event summaries + final answer;
   the full child transcript is never persisted. Therefore "resume" = spawn a NEW handle with
   `TaskSpec.prompt` + the persisted progress-summary injected as context — NOT an exact
   continuation (no provider state exists). State machine: `Interrupted -> Resuming(new handle)`.
2. **Child approval v1: safe subset only.** Only tools on an EXPLICIT allowlist
   (`AgentTypeSpec.toolPolicy`, e.g. `ask_user` and read-only/non-destructive tools) forward to
   the parent's approval surface; everything else auto-denies with the reason recorded in the
   task summary. Allowlist, not heuristics.
3. **Interrupted tasks: resumable.** The resume MECHANISM exists and is tested
   (`TaskCoordinator.resume` + the `claimResume` single-active-handle CAS); resume consumes the
   summary (per #1) and resume never duplicates side effects — one task has at most ONE active
   handle. NOT YET WIRED to the UI end-to-end: the renderer shows only a non-interactive
   "Interrupted — resume available" affordance, and no production caller invokes
   `TaskCoordinator.resume` — see the disposition section (finding S2-#2). **This sub-criterion is
   NOT met by this branch and must not be claimed met.**
4. **Board v1: user-editable.** Read-write board UI (create/edit/status/delete). Consequence:
   ALL board invariants (cycle check, claim atomicity, legal transitions) are enforced in the
   repository/domain layer, caller-agnostic — tool calls and UI go through the same path, never
   tool-handler-only validation.
5. **Claims per subagent: multiple allowed.** No per-owner cap; `SingleOwnerClaim` (one item,
   one owner) still holds; orphan recovery releases ALL claims owned by a dead handle.

## Tech Stack

Kotlin Android multi-module Gradle (JBR 21 pinned), Jetpack Compose + Material 3, Room
(DB v24 → 25), Koin DI, kotlinx.serialization, Kotest 6.1.11 property tests + JUnit JVM.
Modules touched: `:ai-runtime` (neutral domain — CI gates P1–P3/P6 apply), `:app` (concretes).
No new external dependencies expected (ask first if one appears necessary).

## Commands

All from repo root; `:web:buildWebUi` excluded everywhere, mirroring CI.

```bash
# Compile (both app flavors + library modules)
./gradlew compilePlayDebugKotlin compileSideloadDebugKotlin -x :web:buildWebUi --stacktrace

# Unit tests (sideload variant for :app + unflavored aggregate for library modules — keep BOTH)
./gradlew testSideloadDebugUnitTest testDebugUnitTest -x :web:buildWebUi --stacktrace

# Targeted module tests while iterating
./gradlew :ai-runtime:test -x :web:buildWebUi
./gradlew :app:testSideloadDebugUnitTest --tests 'me.rerere.rikkahub.data.*' -x :web:buildWebUi

# Android Lint (baseline-gated)
./gradlew lintSideloadDebug -x :web:buildWebUi --stacktrace
```

## Project Structure (new/changed files)

```
ai-runtime/src/main/java/me/rerere/ai/runtime/
  task/                          → NEW pure task-lifecycle domain
    TaskSpec.kt                  → TaskSpec, TaskBudget, AgentTypeSpec (incl. toolPolicy allowlist)
    TaskState.kt                 → TaskState sealed states + TaskEvent + TaskStateReducer
  board/                         → NEW pure board domain
    WorkItem.kt                  → WorkItemStatus, transition validator, DTOs
    BoardGraph.kt                → dependency-edge cycle checker
    BoardTools.kt                → task_create/get/list/update tool factories (neutral, port-backed)
  contract/
    TaskPorts.kt                 → NEW ports: TaskAgentRegistry, TaskEventSink, TaskApprovalGate,
                                   TaskBudgetClock, TaskBoardPort
ai-runtime/src/test/java/...     → kotest properties for everything above (pure JVM)

app/src/main/java/me/rerere/rikkahub/
  data/db/entity/                → NEW TaskRunEntity, WorkItemEntity, WorkItemDependencyEntity
  data/db/dao/                   → NEW TaskRunDAO, WorkItemDAO
  data/db/AppDatabase.kt         → version 25, additive migration
  data/repository/TaskBoardRepository.kt → NEW transactional claim/update/delete; ALL invariants here
  data/repository/TaskRunRepository.kt   → NEW task-run persistence + recovery scan + retention
  data/ai/task/                  → NEW TaskCoordinator, ExecutionHandleRegistry, board-port adapter,
                                   approval-router adapter (TaskApprovalGate impl)
  data/ai/subagent/SpawnTool.kt  → execute() rewired to TaskCoordinator (name "task" unchanged)
  data/ai/runtime/AppToolCatalog.kt → board tools added to base pool (Main and Subagent modes)
  ui/components/message/tools/TaskToolUI.kt → NEW live task renderer (registered in ToolUIRegistry)
  ui/pages/chat/board/           → NEW board panel (Flow-fed, read-write)
  di/AppModule.kt                → Koin wiring
app/src/test/java/...            → repository/coordinator/recovery tests (JVM; DAO fakes —
                                   see Testing Strategy)
```

`:ai-runtime` continues to depend only on `:ai` + `:common`; nothing in it may name a concrete
tool/source (P6 token gate). All Android/Room/Compose/Koin code stays in `:app`.

## Architecture & Data Structures (from the approved design)

### Pure domain (`:ai-runtime`)

- `TaskSpec(taskId, parentConversationId, parentToolCallId, agentTypeId, prompt, depth,
  parentModelId, budget)`.
- `TaskBudget` defaults: steps `sub.maxSteps ?: 64`, depth hard `1`, per-parent concurrency
  `1`, global task concurrency `2`, wall-time default `10 min`, hard `30 min`; token usage
  tracked from child usage counters.
- `AgentTypeSpec(id, assistantId, displayName, description, defaultBudget, toolPolicy)` —
  derived from `spawnable` Assistants (stable id = assistant UUID). `toolPolicy` carries the
  explicit approval-forward allowlist (decision #2).
- **Task state machine** (reducer = pure function, property-tested):
  `Created -> Queued` (tool execute) `-> Starting` (coordinator claims a slot) `-> Running`
  (first child event); `Running -> WaitingApproval -> Resuming -> Running` (allowlisted child
  approval round-trip); `Running -> Succeeded`; any active state `-> Failed | Cancelled |
  BudgetExhausted | Interrupted`. `Succeeded/Failed/Cancelled/BudgetExhausted` are absorbing;
  `Interrupted -> Resuming(new handle)` is the ONLY edge out of `Interrupted`, taken only via
  explicit user resume (decisions #1/#3).
- **WorkItem state machine**: `Pending -> InProgress -> Completed`;
  `Pending|InProgress|Completed -> Deleted`; `InProgress -> Pending` only via explicit
  release/orphan recovery; `Completed -> Pending` only via explicit reopen. Delete removes the
  item's dependency edges so dependents unblock.
- **ExecutionHandle state machine**: `Created -> Running -> Completed | Failed | Stopped`.

### Persistence (`:app`, Room v25, additive)

- `TaskRunEntity`: task id, parent conversation/toolCall ids, agent type, prompt, latest state,
  budget counters, event-summary list (summary-only — decision #1), final result/error, child
  approval wait, event sequence, timestamps.
- `WorkItemEntity`: `id`, `conversationId` (board scope = per conversation), `subject`,
  `description`, `activeForm?`, `status`, `ownerHandleId?`, `ownerName?`, `metadataJson`,
  timestamps, `leaseExpiresAt?`.
- `WorkItemDependencyEntity`: `(conversationId, blockerId, blockedId)` unique composite —
  normalized edges, NOT serialized arrays.
- Claims are a Room transaction: read item + unresolved blockers + owner + lease, then set
  `ownerHandleId/status` atomically. Cycle check rejects inserting `A blocks B` when a path
  `B -> A` already exists. The repository layer is the single enforcement point (decision #4):
  board tools AND the UI call the same repository methods.

### In-memory (`:app`)

- `ExecutionHandleRegistry`: `id, conversationId, kind=Subagent, status, assistantId,
  workItemIds, Job, cancel fn, output buffer/result, timestamps`. Live coroutine handles are
  never persisted; board claims/leases are. Child task `Job`s are children of the parent
  generation job so `stopGeneration` (`ChatService.kt:1791`) cascades structurally.

### Wire/UI surface

- Spawn stays `Tool(name = "task")` (`SPAWN_TOOL_NAME`); live task state is mirrored as JSON in
  the existing `UIMessagePart.Tool` output and rendered by a registered `TaskToolUI`.
  **A new `UIMessagePart` subtype is FORBIDDEN in v1.**
- Board panel is a chat-side Compose panel fed by Room `Flow` — not a message part.
- Child approvals are namespaced `taskId/childToolCallId`, rendered inside the parent `task`
  tool step, resumed through the existing `handleToolApproval` path. No hidden execution of
  approval-gated child tools; non-allowlisted ones auto-deny with the reason recorded in the
  task summary (decision #2).

### Failure modes (designed outcomes)

| Mode | Outcome |
|---|---|
| Process death | Startup scan marks active task rows `Interrupted` (no silent side-effect replay); parent message shows a resumable summary; orphan recovery releases ALL board claims of dead handles (lease expiry as backstop). |
| Provider error in child | `Failed`; parent `task` output is a structured error (existing tool-error-as-output behavior). |
| Budget exhaustion | `BudgetExhausted`; child stops; parent receives partial summary + counters. |
| Nested spawn | Structurally denied — child pool never contains `task` (existing `filterToolsForSubagent` guard). |
| User stop | Parent job cancellation cascades; pending child tools finalized cancelled/denied; handles -> `Stopped`. |
| Unbounded board | Open items kept indefinitely; completed/deleted retained 30 days or newest 200 per conversation; cascade with conversation cleanup. |

## Code Style

Match existing idiom: KDoc on public contracts explaining WHY + the invariant, 4-space indent,
max line 120, additive `@SerialName`d models with defaults. Example shape (additive Assistant
field + neutral domain reducer):

```kotlin
// :app — additive, defaulted, pinned by an AssistantSubagentFieldsTest-style legacy-decode test:
val subagentApprovalAllowlist: List<String> = emptyList(), // decision #2: explicit allowlist; empty = forward nothing

// :ai-runtime — pure reducer, exhaustive when, no platform imports:
fun reduce(state: TaskState, event: TaskEvent): TaskState = when (state) {
    is TaskState.Running -> when (event) {
        is TaskEvent.ApprovalRequested -> TaskState.WaitingApproval(event.request)
        is TaskEvent.FinalResult -> TaskState.Succeeded(event.summary)
        // … every (state, event) pair handled; illegal edges return `state` unchanged (terminals absorbing)
        else -> state
    }
    // …
}
```

Commit shape: `<area>: <imperative>` with a root-cause/why body; one logical change per commit.

## Testing Strategy

- **Framework/locations**: Kotest property + JUnit JVM. Pure domain properties live in
  `ai-runtime/src/test`; repository/coordinator tests in `app/src/test` (run via
  `testSideloadDebugUnitTest`). CI runs JVM only — no instrumented tests in the gate, so
  repository invariants are tested at the repository/domain seam against DAO fakes; if
  in-memory Room (Robolectric) proves necessary, that is an ask-first dependency decision.
- **Property plan — all 11 named properties are MANDATORY deliverables**, in boundary /
  invariant / metamorphic style per existing precedent (`ToolDeltaInterleavePropertyTest`,
  `SubagentPrimitivesPropertyTest`):
  - Board: `NoDependencyCycles` (generated edge-insert sequences never persist a cycle);
    `SingleOwnerClaim` (a claimed item has exactly one owner — holds under multi-claim per
    owner, decision #5); `TerminalStatusMonotonicity` (`Completed` never regresses without
    explicit reopen); `DeleteUnblocksDependents` (deleting a blocker removes/ignores its
    edges); `InterleavingEquivalence` (any transaction-passing interleaving of
    create/claim/complete equals some sequential merge).
  - Task lifecycle: `TASK_STATE_LEGAL` (only legal transitions; terminals absorbing; terminal
    replay idempotent); `TASK_BUDGET_MONOTONE` (steps/tokens/time never decrease; cap breach =>
    `BudgetExhausted`; lowering a cap cannot turn a failed run into success); `TASK_DEPTH_ONE`
    (no child pool contains `task`; exact-name strip, lookalikes survive); `TASK_APPROVAL_VISIBLE`
    (a pending child approval blocks the task and surfaces parent-visible; no hidden execution
    while pending; approve-then-resume == direct approved execution); `TASK_CANCEL_CASCADES`
    (no child events after parent cancel; covers cancel before start / mid-stream / mid-tool);
    `TASK_SERIALIZATION_ADDITIVE` (legacy JSON without new fields decodes with defaults; new
    task summaries roundtrip; extend `UIMessageArb`/roundtrip tests ONLY if a persisted schema
    actually changes).
  - Delivery-specific additions from the maintainer decisions: resume-single-handle (resuming
    `Interrupted` creates exactly one new active handle; double-resume rejected — decision #3)
    and orphan-release-all (recovery releases EVERY claim of a dead handle — decision #5).
- A failing property is a real bug: fix the code, never weaken the property.
- Full verification per milestone: compile both flavors + both test tasks + lint, all with
  `-x :web:buildWebUi`; `:ai-runtime` boundary gates (P1–P3, P6) and P-FLAVOR stay green.

## Boundaries

- **Always:**
  - Re-verify every design-note `file:line` anchor against master at the start of each slice
    (the note's base was stale once already; the table above is the current ground truth).
  - Keep `:ai-runtime` neutral: no Android/Room/Koin imports, no concrete tool names (P6).
  - Enforce ALL board/task invariants in the repository/domain layer — tool and UI callers
    share one path (decision #4).
  - Additive serialization only: new fields defaulted, new tables via migration 24→25; decode
    tests for legacy payloads in the same commit.
  - Parent child-task `Job`s under the conversation's generation job (cancel cascade).
  - One logical change per commit; tests in the same commit as the behavior they pin.
- **Ask first:**
  - Any new Gradle dependency (incl. Robolectric/in-memory Room for repository tests).
  - Any change to the spawn tool's input schema beyond additive optional args (existing
    `subagent`/`prompt` callers must keep working).
  - Schema changes to `UIMessagePart.Tool` itself (its output JSON content is free-form; the
    part's serialized shape is not).
  - Deviating from the budget defaults (64 steps / depth 1 / 1 per-parent / 2 global /
    10–30 min).
  - Deleting `SubagentRunner` (allowed only after `TaskCoordinator` parity tests pass — design
    note Fase-2 slice-4 condition).
- **Never:**
  - Add a new `UIMessagePart` subtype (v1 prohibition; roundtrip tests enumerate known parts).
  - Persist full child transcripts (decision #1) or live coroutine handles.
  - Auto-resume interrupted tasks or replay side effects on startup (recovery marks
    `Interrupted`; resume is user-explicit).
  - Forward a non-allowlisted child tool to the approval surface, or execute an
    approval-gated child tool hidden (decision #2).
  - Enforce invariants only in tool handlers (decision #4), break the depth-1 guard, change
    existing serialized formats, or break existing callers/tests silently.

## Roadmap

Sequential milestones, each a shippable increment (own PR(s), CI green, mergeable alone).
Together they cover the design note's 8 combined slices IN ORDER: domain → Room → board tools
→ ExecutionHandleRegistry → spawn async → subagent-claims-board → UI panel → recovery/retention
+ integration. (Grouped into 6 milestones; slice boundaries remain separate PRs/commits inside
a milestone.)

- **M1 — Pure domain in `:ai-runtime` (slice 1):** Task lifecycle types + reducer
  (`TaskSpec/TaskBudget/TaskState/TaskEvent`, `AgentTypeSpec.toolPolicy`), board domain
  (status-transition validator, cycle checker, DTOs), ports (`TaskAgentRegistry`,
  `TaskEventSink`, `TaskApprovalGate`, `TaskBudgetClock`, `TaskBoardPort`) and neutral
  board-tool factories — with kotest properties `TASK_STATE_LEGAL`, `TASK_BUDGET_MONOTONE`,
  `NoDependencyCycles`, `TerminalStatusMonotonicity`. Zero `:app` changes; P1–P3/P6 green.
- **M2 — Room persistence + invariant-enforcing repositories (slice 2):** Additive migration
  24→25 (`TaskRunEntity`, `WorkItemEntity`, `WorkItemDependencyEntity` + DAOs), transactional
  claim/update/delete in `TaskBoardRepository` with ALL invariants repository-enforced
  (decision #4); properties `SingleOwnerClaim`, `DeleteUnblocksDependents`,
  `InterleavingEquivalence`, `TASK_SERIALIZATION_ADDITIVE`.
- **M3 — Board tools in the catalog (slice 3):** `task_create/get/list/update` added to the
  `AppToolCatalog` base pool, per-conversation scoped, calling the same repository path as the
  UI will; catalog policy tests stay green.
- **M4 — ExecutionHandleRegistry + async task spawn (slices 4–5):** `ExecutionHandleRegistry`;
  `TaskCoordinator` wrapping `GenerationHandler.generateText` (preserving PreToolUse hook
  dispatch); `buildSpawnTool` internals replaced with task-run creation/event updates while
  still exposing `Tool(name="task")`; budgets/concurrency enforcement; allowlisted
  child-approval router through `handleToolApproval` (namespaced `taskId/childToolCallId`);
  cancel cascade. Properties `TASK_DEPTH_ONE`, `TASK_APPROVAL_VISIBLE`, `TASK_CANCEL_CASCADES`.
  `SubagentRunner` retired only after parity tests pass.
- **M5 — Subagents on the shared board + UI (slices 6–7):** Spawned subagents list/claim/
  complete shared board items (multi-claim allowed, `SingleOwnerClaim` holds — decision #5);
  read-write Compose board panel fed by Room `Flow` (decision #4); `TaskToolUI` live renderer
  registered in `ToolUIRegistry`; no new message part.
- **M6 — Recovery, resume, retention + integration (slice 8):** Startup recovery scan
  (active rows → `Interrupted`; release ALL claims of dead handles), summary-injection resume
  with the single-active-handle invariant (decisions #1/#3), retention sweeper (30 days /
  newest 200 per conversation, cascade with conversation cleanup), and integration tests for
  parent + N subagents sharing one board.

## Success Criteria

1. All 11 named kotest properties (plus resume-single-handle and orphan-release-all)
   implemented and green in plain JVM CI; no property weakened to pass.
2. `Tool(name="task")` spawns asynchronously: the parent receives a handle/live summary instead
   of blocking on final text; the final result lands in the same `UIMessagePart.Tool` output.
3. Child approval round-trip works end-to-end for an allowlisted tool (block → parent-visible
   pending → approve → resume → result) and auto-denies a non-allowlisted tool with the reason
   visible in the task summary.
4. Kill the process mid-task: on restart the task row reads `Interrupted`, its board claims are
   released, the parent message shows resume; resuming spawns exactly one new handle seeded
   with prompt + summary; double-resume is rejected. PARTIALLY met: the restart→`Interrupted`
   scan, the summary-seeded re-spawn, and the double-resume rejection are implemented and tested
   (`TaskRecoveryResumeTest`, `claimResume`), but "the parent message shows resume" is NOT wired —
   the renderer affordance is non-interactive and nothing invokes `TaskCoordinator.resume` in
   production (finding S2-#2 in the disposition section).
5. Board: user edits and tool calls hit the same repository path; cycle insertion is rejected;
   concurrent claims of one item yield exactly one owner; deleting a blocker unblocks
   dependents; one subagent can hold several claims.
6. Serialization: pre-existing conversations/assistants decode unchanged (legacy-JSON decode
   tests); no new `UIMessagePart` `@SerialName` appears in the diff; Room migration 24→25 is
   additive (no altered existing tables).
7. CI fully green: both flavor compiles, both unit-test tasks, lint, `:ai-runtime` boundary
   gates P1–P3/P6, P-FLAVOR — with `SpawnToolTest` / `SubagentRunnerTest` /
   `SubagentPrimitivesPropertyTest` either passing unmodified or explicitly migrated alongside
   the `SubagentRunner` retirement (surfaced in the PR body, not silently rewritten).

## Assumptions

1. **Milestone grouping:** the design note's 8 combined slices are grouped into 6 sequential
   milestones (M4 = slices 4+5, M5 = slices 6+7) to fit the roadmap's milestone budget; slice
   ordering and content are unchanged, and slices remain separate PRs/commits inside a
   milestone.
2. **Agent-type registry = `Assistant.spawnable`** (Fase-2 open question 4, not overridden by
   the maintainer): no separate named-preset system in v1. `AgentTypeSpec` derives from
   spawnable assistants, and the approval allowlist is a new defaulted Assistant field surfaced
   into `AgentTypeSpec.toolPolicy`. Conservative default: empty allowlist = no approval-gated
   child tool forwards (auto-deny), matching today's strip-all behavior.
3. **Global task concurrency = 2, per-parent = 1** (design-note defaults; Fase-2 open question
   5 not overridden). Both live in one place (`TaskBudget` defaults) so a later decision is a
   one-line change.
4. **Board items do NOT appear in exported transcripts** in v1 (Fase-3 open question 2
   unresolved): the board is panel-only; nothing new enters message serialization. This is the
   conservative reading of the no-new-`UIMessagePart` rule.
5. **Board tool names** are `task_create/get/list/update` per the design note; the spawn tool
   keeps the bare reserved name `task`. The recursion guard strips only the exact spawn name,
   so board tools remain available to subagents (required by M5) without weakening
   `TASK_DEPTH_ONE`.
6. **Repository tests run JVM-only** (CI runs no instrumented tests): invariants are tested at
   the repository/domain seam with DAO fakes; adding in-memory Room/Robolectric is ask-first.
7. **Resume keeps the SAME task id** and transitions `Interrupted -> Resuming` with a new
   handle (decision #1's "Interrupted -> Resuming(new handle)"); the single-active-handle
   invariant is enforced in the repository/coordinator (persisted state), not an in-memory
   flag.
8. **Hooks wiring is already sufficient**: PreToolUse dispatch reaches subagent turns through
   `GenerationHandler`/`ChatTurnRuntime` today; this delivery adds no new hook port and only
   guarantees `TaskCoordinator` does not bypass `generateText`.
9. The prior spec (CI warning burn-down) is archived as DELIVERED; its text lives at
   `git show 3e53fcff:SPEC.md` per "update/extend, don't start over".

## Open Questions

1. Is global concurrency 2 acceptable for battery/network on low-end devices, or should v1
   ship at 1? (Assumption 3 defaults to the design note's 2.)
2. Should completed board items ever be exportable into chat transcripts as static summaries
   (Fase-3 OQ2)? v1 assumes no; saying yes later likely reopens the v2 `UIMessagePart`
   discussion.
3. Which concrete tools beyond `ask_user` belong on the DEFAULT approval allowlist shipped
   with the app — or should the default be empty with per-assistant opt-in (v1 assumption)?
4. Should the board panel be reachable outside an open conversation (e.g. a global "all
   boards" screen)? v1 assumes per-conversation panel only.

## Known gaps — deferred to a follow-up slice (maintainer sign-off required)

Two review findings (#1 child-approval round-trip, #5 per-handle board claims) are NOT addressed
in this branch and require an explicit maintainer scope decision before implementation, because
each is a multi-hundred-line change through `ChatService`'s async generation + approval-suspend
machinery and at least one surface this spec flags as **Ask first**. Recording them here (with the
design) rather than shipping a stub, per "say the scope is too large explicitly; don't decide
unilaterally to ship a stub".

### Gap A — child-approval round-trip is non-functional (finding #1; affects Success Criterion #3)

`TaskApprovalRouter` and its `TASK_APPROVAL_VISIBLE` property test exist, but the router is never
wired: `TaskCoordinator` accepts no `TaskApprovalGate`, and the production spawn path strips ALL
`needsApproval` tools from the child pool (`buildSpawnTool`:
`buildSubagentTools(sub).filterNot { it.needsApproval }`) AND the catalog repeats the strip
(`AppToolCatalog`: `recursionGuarded.filterNot { it.needsApproval }`). So no child tool can reach
the gate in production; `pendingApproval` / `WaitingApproval` are effectively dead at runtime.
**Success Criterion #3 is therefore NOT met by this branch and must not be claimed met.**

Design for the follow-up slice (state-machine class — design before patch):
1. Pass allowlisted `needsApproval` child tools THROUGH the child pool instead of stripping them
   unconditionally — gated by the running `AgentTypeSpec.toolPolicy` allowlist, never a heuristic.
   This is an **Ask-first** change: it alters the spawn path's tool-filtering contract (boundary
   "any change to the spawn tool's tool assembly").
2. Thread a `TaskApprovalGate` into `TaskCoordinator` and have the child turn route an
   approval-gated tool call through it. The gate's allowlisted branch calls a
   `ParentApprovalSurface` bound to `ChatService`, which must raise a parent-visible pending
   `UIMessagePart.Tool` namespaced `taskId/childToolCallId` and suspend until `handleToolApproval`
   resolves it — i.e. a new suspend point inside the running child collection, structurally a
   child of the generation job so cancel still cascades. Non-allowlisted tools keep auto-denying
   with the reason recorded in the summary (already implemented in the router).
3. The DEFAULT allowlist contents are **Open Question #3** — unresolved; do not ship a non-empty
   default without the maintainer picking it.

### Gap B — subagent board claims are owned per-conversation, not per-handle (finding #5; decision #5)

`ExecutionHandleRegistry` and `BoardPortAdapter.forHandle` exist and are tested, but the spawn path
never registers a per-child handle: `ChatService` builds the board port with
`actor = BoardActor(handleId = "conversation:$conversationId", …)` for the parent turn, and
`TaskCoordinator` never calls `register` / `forHandle` for a spawned child. So every subagent claim
is owned by the conversation-level id, and `releaseClaimsOf(handleId)` (orphan recovery) would find
nothing to release per dead handle. The decision-#5 "orphan recovery releases ALL claims of a dead
handle" guarantee is therefore not achievable end-to-end yet. (This is why the startup recovery
runner in this branch deliberately does NOT call `releaseClaimsOf` — see `TaskRecoveryRunner` KDoc.)

Design for the follow-up slice:
1. In `TaskCoordinator.run`, `register` an `ExecutionHandle` (child of the parent generation job)
   per spawned child, and build that child's board port via `BoardPortAdapter.forHandle(...)` so
   `ownerHandleId = handle.id` and accepted claims attach to the handle's `workItemIds`.
2. Mark the handle `Running`/`Completed`/`Failed`/`Stopped` along the run's terminals; on process
   death the row's claims are now owned by a real handle id, so the recovery runner can call
   `releaseClaimsOf(handle.id)` for each handle that did not complete.
3. This requires `ChatService` to hand `TaskCoordinator` the parent generation `Job` + the
   `ExecutionHandleRegistry` (a constructor/DI change), and is sequenced with Gap A since both
   touch the same spawn-execution seam.

Both gaps are real and must be tracked as a follow-up slice; this branch fixes findings #2, #3, #4
(startup recovery scan + retention sweep + reachable board panel) and explicitly scopes A and B out.

## Second-round review findings — disposition

A later cross-model review raised four findings (its own numbering, distinct from Gap A/B above):

- **R-#1 — task tool never emitted the `{task:{...}}` envelope.** FIXED for the TERMINAL case:
  `TaskCoordinator.run`/`resume` now return a `TaskRunResult` (terminal `TaskState` + final usage +
  `maxSteps`), and `buildSpawnTool` serializes it into the envelope via `buildTaskEnvelope` so the
  live renderer reads the real terminal status (Succeeded / Failed / BudgetExhausted / Interrupted),
  budget counters, and the resume affordance instead of always falling back to a bare-text "Done".
  REMAINING (tracked): LIVE status WHILE the child runs — emitting intermediate envelopes mid-run —
  still requires the streaming-envelope seam through `ChatService`'s async generation; a synchronous
  `execute` cannot produce them. That portion is sequenced with Gap A (same spawn-execution seam).
- **R-#2 — orphaned parent ids.** `parentConversationId` FIXED: `buildSpawnTool` now takes a
  required conversation id and threads it into `run`, so persisted rows associate with the real
  conversation. `parentToolCallId` REMAINING (tracked): the spawn tool call id is unreachable inside
  `Tool.execute` (`suspend (JsonElement) -> List<…>`, shared engine-wide); passing it needs an ABI
  change to `Tool` and the Ask-first spawn-path tool assembly. Per-parent concurrency grouping
  degrades to the still-enforced global cap until then.
- **R-#3 — budget breach did not stop the child.** FIXED: the breach now throws a `BudgetStop`
  sentinel out of `collect`, cancelling the upstream child flow, instead of a `return@collect` that
  only skipped one chunk.
- **R-#4 — corrupt summaries recovered as empty progress.** FIXED: recovery distinguishes a corrupt
  blob (`decodeEventSummaries() == null`) from an empty history and seeds a non-blank marker so a
  resume does not treat unreadable progress as a clean slate.

## Third-round review findings — disposition

A later cross-model review raised four findings (its own numbering, S2-#1..#4):

- **S2-#1 — spawned subagents never received board tools in production.** FIXED.
  `AppToolCatalog` adds the board tools on a `TurnMode.Subagent` pool, but the production spawn
  path assembles the subagent pool through `buildSpawnTool`'s `buildSubagentTools` lambda and feeds
  it straight to `TaskCoordinator.run` — never the catalog — so that arm was dead for spawned
  children and a subagent could not `task_list` / `task_update` the shared board. The assembly seam
  now appends `subagentBoardTools(...)` (a pure binding of `buildBoardTools` over a
  `BoardPortAdapter` scoped to the parent conversation, owned by a per-subagent `BoardActor`), so a
  spawned subagent coordinates over the one shared board (spec assumption 5 / decision #5).
  Regression: `SubagentBoardToolsTest`. This is the "no board tools at all" gap, distinct from
  Gap B (per-HANDLE claim ownership for orphan release), which stays deferred.
- **S2-#2 — interrupted tasks are not resumable from the UI.** REJECTED for this branch (real, not
  fixed). The renderer shows only a non-interactive `ResumePlaceholder()` and no production caller
  invokes `TaskCoordinator.resume`. A correct user-explicit resume must (a) make the affordance
  interactive, (b) reassemble the subagent spawn context — `sub` Assistant, live `Settings`,
  `parentModelId`, and the full subagent tool pool (`localTools` / `skillManager` / `mcpManager` /
  automation guard) — which only `ChatService` builds per generation, and (c) fold the resumed
  run's terminal `TaskRunResult` back into the ORIGINAL message's `UIMessagePart.Tool` envelope so
  the renderer updates. (c) re-enters `ChatService`'s async generation + streaming-merge seam: the
  renderer reads the message tool envelope, NOT a Room flow, so a coordinator-only resume could run
  side effects but never surface its answer. That is the same multi-hundred-line, Ask-first
  spawn-execution seam Gap A and the R-#1 LIVE-status remainder are sequenced on. Per "design
  before patch for the lifecycle class" and "do not ship a stub / do not decide a large scope split
  unilaterally", it is documented for a follow-up slice rather than half-wired. The false "resume
  is shipped" claims in §Assumptions #3 and Success Criterion #4 have been corrected to "NOT met".

  Design for the follow-up slice (state-machine class — design before patch):
  1. Make the renderer affordance interactive: thread an `onResume(taskId)` callback through
     `ToolUIContext` (or surface a resume action on a task-run list panel backed by
     `TaskRunDAO.listByConversationFlow`), invoked from the chat message/board surface.
  2. Add `ChatService.resumeTask(conversationId, taskId)` that loads the persisted `TaskRunEntity`,
     resolves `agentTypeId` → the `spawnable` `Assistant`, rebuilds the subagent tool pool exactly
     as `buildSubagentTools` does (now including `subagentBoardTools`), and calls
     `TaskCoordinator.resume` inside a generation entry. `claimResume` already guarantees exactly
     one new active handle and rejects a double-resume.
  3. Fold the returned `TaskRunResult` into the original task message's `UIMessagePart.Tool` output
     via `buildTaskEnvelope`, through the same streaming-merge path `handleMessageComplete` uses,
     so the renderer flips `Interrupted` → the new terminal. This is the Ask-first async-generation
     seam; sequence it with Gap A and the R-#1 live-status remainder (all the same seam).
- **S2-#3 — child progress never persisted into `eventSummaries`.** ALREADY FIXED at branch HEAD
  (not a new change): `TaskCoordinator.execute` appends the child's latest assistant text via
  `store.appendEventSummary` on each non-blank progress chunk
  (`TaskCoordinator.kt`, "Persist the child's latest progress as an event summary"), so
  `recoverInterruptedRuns` seeds a resume from real progress rather than an empty summary.
  Regression already present: `TaskCoordinatorTest` — "child progress is persisted as an event
  summary so recovery resumes from real progress". The finding was verified against an earlier tree.
- **S2-#4 — deleting a conversation leaves orphan task/board rows.** ALREADY FIXED at branch HEAD
  (not a new change): `ConversationRepository.deleteConversation` calls
  `deleteConversationTaskArtifacts(taskRunDAO, workItemDAO, conversation.id)` in the SAME
  transaction as the conversation delete (the new entities have no FK to the conversation, so Room
  CASCADE does not reach them). Regression already present: `ConversationTaskArtifactCascadeTest`.
  The finding was verified against an earlier tree.

## Verification (skill gate)

- [x] Six core areas covered (objective, commands, structure, style, testing, boundaries)
- [ ] Human has reviewed and approved the spec  ← PENDING (this gate blocks implementation)
- [x] Success criteria specific and testable
- [x] Boundaries defined (always / ask first / never)
- [x] Spec saved to a file in the repository (`SPEC.md`, uncommitted by instruction)
- [x] Maintainer decisions transcribed verbatim and marked BINDING
- [x] Stale design-note anchors re-grounded against current master (`dd41aabe`) with a
      verification table
