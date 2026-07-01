# Invocation Expansion Lanes Design

## Context

The current flowchart can expand a method invocation into the called method's internal flow. The expanded nodes are placed to the side, but the UI does not make two things obvious enough:

- Which invocation produced a specific expanded method flow.
- Which nodes belong to the same expansion result.

The target use case is not a single expansion. Users may inspect multi-level call chains such as `A -> B -> C/D/E`, with several sibling expansions at the same depth. The design therefore uses depth-based lanes instead of a single right-side expansion area.

## Goals

- Make invocation expansion ownership explicit.
- Make expansion boundaries visible.
- Support multiple expansion depths.
- Prevent same-depth sibling expansions from overwhelming the canvas.
- Keep LLM context aligned with what the user is actively reading.
- Preserve existing backend expansion metadata where possible.

## Non-Goals

- Replacing the existing graph extraction or invocation resolution logic.
- Showing every expanded sibling as full internal flow at the same time by default.
- Making collapsed expansions disappear from the user's structural view.
- Sending all expanded content to the LLM by default.

## Layout Model

The flowchart uses depth-based lanes:

- Depth `0` is the current anchor method's main flow.
- Depth `n + 1` contains invocation expansions triggered from nodes in depth `n`.
- Each lane has a header with the depth label, expansion count, and collapsed count.
- Each invocation expansion is rendered as an expansion block, not as loose nodes.

An expansion block has these logical fields:

- `expansionId`: the existing invocation expansion id.
- `sourceInvocationNodeId`: the invocation node that produced this expansion.
- `targetSignature`: the called method signature.
- `depth`: derived by walking parent invocation ownership.
- `parentExpansionId`: derived from the source invocation node's containing expansion, or null for root-level expansions.

For the first implementation, `depth` and `parentExpansionId` should be derived on the frontend or in a view model layer from existing metadata. They should not require new persisted backend fields unless derivation proves unreliable.

## Same-Depth Behavior

Sibling expansion blocks are blocks with the same parent invocation context and the same depth.

- Siblings stack vertically in stable call order.
- For one parent context, only one sibling is fully expanded by default.
- Other siblings are shown as collapsed summary blocks.
- Switching the active sibling changes visual state, not expansion identity.
- Existing block positions are preserved when users switch between siblings.

Example:

```text
Depth 0              Depth 1                  Depth 2
A.login      ->      B.login       ->         C.check   [expanded]
                                             D.load    [collapsed]
                                             E.save    [collapsed]
```

## Expansion States

An expansion block has one of three user-visible states.

### Expanded

The block shows the called method's full internal flow: entry node, control-flow nodes, invocation nodes, returns, and internal edges.

Expanded blocks are eligible to become part of the active reading path and are included as full evidence in the default LLM context when they are on that path.

### Collapsed

The block is visually present as a summary card but does not show full internal flow nodes.

The summary card should include:

- Method title or short signature.
- Source invocation label.
- Node count.
- Branch count.
- Return count.
- Child expansion count, if any.
- A command to restore full expansion.

Collapsed does not delete data. It is a visual and context-scope state.

### Removed

The expansion is removed from the workspace graph. This is different from collapsed.

Removal must cascade through nested expansions. If expansion `B` is removed and `B` contains invocation expansions `C`, `D`, and `E`, those child expansions must also be removed. This avoids orphaned blocks at deeper lanes.

The existing single-expansion removal behavior should be extended or wrapped with an expansion-tree removal rule.

## User Interactions

- Clicking an expandable invocation creates or activates the expansion block in the next depth lane.
- Clicking a collapsed sibling makes it expanded and collapses the previously expanded sibling under the same parent context.
- Clicking a block header activates that block's reading path.
- Clicking collapse converts the block to summary state without removing workspace data.
- Clicking remove deletes the expansion and all nested child expansions.
- Selecting a normal node does not automatically change the active expansion path.

Only these actions should change the active path:

- Expanding an invocation.
- Opening a collapsed expansion block.
- Clicking an expansion block header.
- Choosing an expansion block from an outline or side panel.

## Active Path

The active path is the chain from the depth `0` anchor method to the currently active expanded block.

For example, if the user is reading `A -> B -> D`, then the active path contains:

- The source invocation node in `A`.
- The `B` expansion block.
- The source invocation node in `B`.
- The `D` expansion block.
- The cross-lane call edges connecting the chain.

The active path receives stronger visual emphasis:

- Source invocation nodes are highlighted.
- Cross-lane call edges are highlighted.
- Active expansion block borders and headers are emphasized.
- The active block's entry node is highlighted.

Non-active branches remain visible as structure but are visually de-emphasized.

## Visual Rules

- Every expansion block title must identify its source, for example `Expanded from LoginService.login`.
- Cross-lane connections must remain visible even when the target block is collapsed.
- Collapsed siblings should keep readable titles and compact metrics.
- Parent collapse hides child lanes for that parent path, but the parent summary shows that child expansions exist.
- Restoring a parent expansion restores the last known child state where possible.
- Lane and block ordering must be stable across activation changes.

## LLM Context Scope

The default LLM context should follow the active reading path, not every expansion that has ever been opened.

The initial context mode is:

```text
ACTIVE_CHAIN
```

In `ACTIVE_CHAIN` mode:

- The anchor method main flow is included.
- Expanded blocks on the active path are included as full evidence.
- Collapsed sibling blocks are included only as summaries.
- Non-active descendants under collapsed parents are not included as full evidence.
- Removed expansions are not included.

Future context modes can be added without changing the visual model:

- `CURRENT_DEPTH`: include the active depth's expanded block plus sibling summaries.
- `ALL_EXPANDED`: include all currently expanded blocks.
- `SELECTED_NODE`: include a selected node neighborhood.

LLM input must distinguish full evidence from collapsed summaries. Summaries must not be presented as complete code evidence.

Example:

```text
Context scope: ACTIVE_CHAIN

Full evidence:
AController.login
LoginService.login
UserRepository.find

Collapsed summaries:
EmailService.sendCode: folded sibling under LoginService.login, 5 nodes hidden
AuditService.record: folded sibling under LoginService.login, 3 nodes hidden
```

## Implementation Notes

The current backend already records useful expansion metadata:

- `linkGraph.expansion.id`
- `linkGraph.expansion.sourceInvocationNodeId`
- `linkGraph.expansion.rootNodeId`
- `linkGraph.expansion.targetSignature`

The first implementation should build an expansion tree from those fields:

1. Group nodes by `linkGraph.expansion.id`.
2. Resolve each group source via `sourceInvocationNodeId`.
3. Find whether the source node belongs to another expansion group.
4. Derive parent-child expansion relationships.
5. Derive depth from the parent chain.

Collapsed state and active path state are UI/session state. They should not be written into semantic graph metadata. If they must travel through the backend for LLM tooling, use scene state or `ui.` metadata with clear filtering behavior.

The LLM graph builder should not blindly re-include all nodes sharing a visible `expansionId` when the UI state marks that expansion as collapsed. It should include full nodes only for active-path expanded blocks and summary records for collapsed siblings.

## Risks

- If parent-child expansion derivation is based only on visible nodes, collapsed parents could make children look orphaned. Derivation must use the workspace graph or a stable expansion registry.
- If removal is not cascaded, nested expansions can remain in deeper lanes without their source invocation.
- If all expanded groups remain full LLM evidence, answers may drift toward inactive sibling branches.
- If normal node selection changes the active path, the canvas will feel unstable during inspection.

## Acceptance Criteria

- A user can identify the source invocation for every expansion block.
- A user can distinguish expanded, collapsed, and removed states.
- `A -> B -> C/D/E` displays by depth, with only one sibling under `B` fully expanded by default.
- Switching from `C` to `D` preserves lane and block ordering.
- Removing `B` also removes nested `C/D/E` expansions.
- Default LLM context includes the active chain as full evidence and sibling expansions as summaries.
