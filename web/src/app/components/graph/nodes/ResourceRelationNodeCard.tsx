import { memo, useLayoutEffect, useRef } from "react";
import { nodeTypeLabel } from "../../../labels";
import { IssueBadge } from "../../IssueBadge";
import type { LinkGraphNode } from "../../../types";
import { nodeTooltip, resourceLane, resourceLaneLabel, signaturePreview } from "./nodePresentation";
import { measureNodeContentBox } from "./measureNodeContentBox";

interface ResourceRelationNodeCardProps {
  node: LinkGraphNode;
  selected: boolean;
  onMeasure?: (size: { width: number; height: number }) => void;
}

export const ResourceRelationNodeCard = memo(function ResourceRelationNodeCard({
  node,
  selected,
  onMeasure,
}: ResourceRelationNodeCardProps) {
  const rootRef = useRef<HTMLDivElement | null>(null);
  const lane = resourceLane(node);

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
        "resource-node-card",
        `lane-${lane.toLowerCase()}`,
        selected ? "is-selected" : "",
      ].join(" ").trim()}
      data-node-id={node.id}
    >
      <div className="flow-node-head">
        <span className="flow-node-doc">{resourceLaneLabel(lane)}</span>
        <div className="flow-node-tags">
          <IssueBadge certainty={undefined} diffStatus={node.diffStatus} />
        </div>
      </div>
      <strong className="flow-node-owner" title={node.title}>
        {node.title}
      </strong>
      <span className="flow-node-signature" title={nodeTooltip(node)}>
        {signaturePreview(node) ?? nodeTypeLabel(node.type)}
      </span>
    </div>
  );
});
