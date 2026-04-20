import { memo, useLayoutEffect, useRef } from "react";
import type { DraftCompareStatus, LinkGraphNode } from "../../../types";
import { flowchartKind, flowchartKindLabel, nodeTooltip, signaturePreview } from "./nodePresentation";
import { measureNodeContentBox } from "./measureNodeContentBox";
import { GraphNodeStateBadges } from "./GraphNodeStateBadges";
import { IssueBadge } from "../../IssueBadge";

interface FlowchartNodeCardProps {
  node: LinkGraphNode;
  selected: boolean;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: { width: number; height: number }) => void;
}

export const FlowchartNodeCard = memo(function FlowchartNodeCard({
  node,
  selected,
  explanationFocused = false,
  draftChanged = false,
  draftCompareStatus,
  onMeasure,
}: FlowchartNodeCardProps) {
  const rootRef = useRef<HTMLDivElement | null>(null);

  useLayoutEffect(() => {
    const size = measureNodeContentBox(rootRef.current);
    if (size) {
      onMeasure?.(size);
    }
  }, [node, onMeasure]);

  return (
    <div
      ref={rootRef}
      className={[
        "flow-node-card",
        "flowchart-node-card",
        `kind-${flowchartKind(node).toLowerCase()}`,
        selected ? "is-selected" : "",
      ].join(" ").trim()}
      data-node-id={node.id}
    >
      <GraphNodeStateBadges selected={selected} explanationFocused={explanationFocused} draftChanged={draftChanged} />
      <div className="flow-node-head">
        <span className="flowchart-node-kind">{flowchartKindLabel(node)}</span>
        <div className="flow-node-tags">
          <IssueBadge draftCompareStatus={draftCompareStatus} />
        </div>
      </div>
      <strong className="flowchart-node-title" title={node.title}>
        {node.title}
      </strong>
      {signaturePreview(node) ? (
        <span className="flowchart-node-detail" title={nodeTooltip(node)}>
          {signaturePreview(node)}
        </span>
      ) : null}
    </div>
  );
});
