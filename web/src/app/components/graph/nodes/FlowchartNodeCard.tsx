import { memo } from "react";
import type { DraftCompareStatus, LinkGraphNode } from "../../../types";
import { flowchartKind, flowchartKindLabel, nodeTooltip, signaturePreview } from "./nodePresentation";
import { NodeCardBase } from "./NodeCardBase";
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
  return (
    <NodeCardBase
      node={node}
      selected={selected}
      explanationFocused={explanationFocused}
      draftChanged={draftChanged}
      onMeasure={onMeasure}
      measureDeps={[node, onMeasure]}
      variantClassName={["flowchart-node-card", `kind-${flowchartKind(node).toLowerCase()}`]}
    >
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
    </NodeCardBase>
  );
});
