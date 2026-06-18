import { memo } from "react";
import { nodeTypeLabel } from "../../../labels";
import type { DraftCompareStatus, LinkGraphNode } from "../../../types";
import { IssueBadge } from "../../IssueBadge";
import {
  factNodeDocText,
  hierarchyDirection,
  hierarchyDirectionLabel,
  hierarchyLabel,
  isDecisionFlowScope,
  isFlowActionNode,
  nodeSourceBadge,
  nodeTooltip,
  overflowPresentation,
  ownerPreview,
  signaturePreview,
} from "./nodePresentation";
import { NodeCardBase } from "./NodeCardBase";

interface FactGraphNodeCardProps {
  node: LinkGraphNode;
  selected: boolean;
  collapsed: boolean;
  collapsedCount?: number;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: { width: number; height: number }) => void;
  onExpandOverflow?: () => void;
}

export const FactGraphNodeCard = memo(function FactGraphNodeCard({
  node,
  selected,
  collapsed,
  collapsedCount,
  explanationFocused = false,
  draftChanged = false,
  draftCompareStatus,
  onMeasure,
  onExpandOverflow,
}: FactGraphNodeCardProps) {
  const overflow = overflowPresentation(node);
  const sourceBadge = overflow?.expandable ? null : nodeSourceBadge(node);
  const metaText = overflow
    ? overflow.metaLine
    : collapsed
      ? `已折叠下游${collapsedCount && collapsedCount > 0 ? ` ${collapsedCount} 个节点` : "子树"}，右键可重新展开`
      : hierarchyLabel(node);
  const directionText = overflow?.expandable ? null : hierarchyDirectionLabel(node);
  const certainty = !overflow && node.type === "UNCERTAIN_LINK" && node.certainty !== "PROVEN" ? node.certainty : undefined;
  const directionClassName = hierarchyDirection(node)?.toLowerCase() ?? "unknown";
  const signatureText = overflow?.signatureLine ?? signaturePreview(node) ?? nodeTypeLabel(node.type);
  const ownerText = overflow?.ownerLine ?? ownerPreview(node);

  return (
    <NodeCardBase
      node={node}
      selected={selected}
      explanationFocused={explanationFocused}
      draftChanged={draftChanged}
      onMeasure={onMeasure}
      measureDeps={[node, collapsed, collapsedCount, onMeasure]}
      variantClassName={[
        node.type === "FLOW_SCOPE" ? "is-flow-scope" : "",
        isFlowActionNode(node) ? "is-flow-action" : "",
        isDecisionFlowScope(node) ? "is-flow-decision" : "",
        `direction-${directionClassName}`,
      ]}
    >
      <div className="flow-node-head">
        <span className="flow-node-doc" title={node.doc ?? factNodeDocText(node)}>
          {factNodeDocText(node)}
        </span>
        <div className="flow-node-tags">
          {sourceBadge ? (
            <span className="badge source-tag-badge" title={sourceBadge.title}>
              {sourceBadge.text}
            </span>
          ) : null}
          {directionText ? <span className="badge hierarchy-badge">{directionText}</span> : null}
          <IssueBadge certainty={certainty} diffStatus={node.diffStatus} draftCompareStatus={draftCompareStatus} />
        </div>
      </div>
      <strong className="flow-node-owner" title={ownerText}>
        {ownerText}
      </strong>
      <span className="flow-node-signature" title={nodeTooltip(node)}>
        {signatureText}
      </span>
      {metaText ? <span className={`flow-node-meta ${collapsed ? "is-warning" : ""}`}>{metaText}</span> : null}
      {overflow?.expandable && overflow.expandActionLabel ? (
        <button
          type="button"
          className="flow-node-expand-button"
          onClick={(event) => {
            event.preventDefault();
            event.stopPropagation();
            onExpandOverflow?.();
          }}
          onDoubleClick={(event) => {
            event.preventDefault();
            event.stopPropagation();
          }}
          onContextMenu={(event) => {
            event.preventDefault();
            event.stopPropagation();
          }}
        >
          {overflow.expandActionLabel}
        </button>
      ) : null}
    </NodeCardBase>
  );
});
