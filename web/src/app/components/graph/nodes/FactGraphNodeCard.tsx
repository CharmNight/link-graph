import { memo, useLayoutEffect, useRef } from "react";
import { nodeTypeLabel } from "../../../labels";
import type { LinkGraphNode } from "../../../types";
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
import { measureNodeContentBox } from "./measureNodeContentBox";
import { GraphNodeStateBadges } from "./GraphNodeStateBadges";

interface FactGraphNodeCardProps {
  node: LinkGraphNode;
  selected: boolean;
  collapsed: boolean;
  collapsedCount?: number;
  explanationFocused?: boolean;
  draftChanged?: boolean;
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
  onMeasure,
  onExpandOverflow,
}: FactGraphNodeCardProps) {
  const rootRef = useRef<HTMLDivElement | null>(null);
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

  useLayoutEffect(() => {
    const size = measureNodeContentBox(rootRef.current);
    if (!size) {
      return;
    }
    onMeasure?.(size);
  }, [node, collapsed, collapsedCount, onMeasure]);

  return (
    <div
      ref={rootRef}
      className={[
        "flow-node-card",
        selected ? "is-selected" : "",
        node.type === "FLOW_SCOPE" ? "is-flow-scope" : "",
        isFlowActionNode(node) ? "is-flow-action" : "",
        isDecisionFlowScope(node) ? "is-flow-decision" : "",
        `direction-${directionClassName}`,
      ].join(" ").trim()}
      data-node-id={node.id}
    >
      <GraphNodeStateBadges selected={selected} explanationFocused={explanationFocused} draftChanged={draftChanged} />
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
          <IssueBadge certainty={certainty} diffStatus={node.diffStatus} />
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
    </div>
  );
});
