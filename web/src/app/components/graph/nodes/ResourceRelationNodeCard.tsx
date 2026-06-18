import { memo } from "react";
import { nodeTypeLabel } from "../../../labels";
import { IssueBadge } from "../../IssueBadge";
import type { DraftCompareStatus, LinkGraphNode } from "../../../types";
import { nodeTooltip, resourceLane, resourceLaneLabel, signaturePreview } from "./nodePresentation";
import { NodeCardBase } from "./NodeCardBase";

interface ResourceRelationNodeCardProps {
  node: LinkGraphNode;
  selected: boolean;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: { width: number; height: number }) => void;
}

export const ResourceRelationNodeCard = memo(function ResourceRelationNodeCard({
  node,
  selected,
  explanationFocused = false,
  draftChanged = false,
  draftCompareStatus,
  onMeasure,
}: ResourceRelationNodeCardProps) {
  const lane = resourceLane(node);

  return (
    <NodeCardBase
      node={node}
      selected={selected}
      explanationFocused={explanationFocused}
      draftChanged={draftChanged}
      onMeasure={onMeasure}
      measureDeps={[node, onMeasure]}
      variantClassName={["resource-node-card", `lane-${lane.toLowerCase()}`]}
    >
      <div className="flow-node-head">
        <span className="flow-node-doc">{resourceLaneLabel(lane)}</span>
        <div className="flow-node-tags">
          <IssueBadge certainty={undefined} diffStatus={node.diffStatus} draftCompareStatus={draftCompareStatus} />
        </div>
      </div>
      <strong className="flow-node-owner" title={node.title}>
        {node.title}
      </strong>
      <span className="flow-node-signature" title={nodeTooltip(node)}>
        {signaturePreview(node) ?? nodeTypeLabel(node.type)}
      </span>
    </NodeCardBase>
  );
});
