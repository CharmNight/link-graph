import { memo } from "react";
import type { DraftCompareStatus, LinkGraphNode } from "../../../types";
import {
  flowchartKind,
  flowchartKindLabel,
  isInvocationExpansionNode,
  nodeTooltip,
  signaturePreview,
} from "./nodePresentation";
import { NodeCardBase } from "./NodeCardBase";
import { IssueBadge } from "../../IssueBadge";

/** FlowchartNodeCard 组件的入参。 */
interface FlowchartNodeCardProps {
  /** 待渲染的图节点。 */
  node: LinkGraphNode;
  /** 是否被选中。 */
  selected: boolean;
  /** 是否处于讲解聚焦。 */
  explanationFocused?: boolean;
  /** 是否被草稿改动影响。 */
  draftChanged?: boolean;
  /** 草稿比对状态；用于差异视图。 */
  draftCompareStatus?: DraftCompareStatus;
  /** 尺寸测量回调；布局引擎需要。 */
  onMeasure?: (size: { width: number; height: number }) => void;
}

/**
 * 流程图节点卡片。
 *
 * 基于 [NodeCardBase] 渲染：
 * - 头部：流程图种类标签（处理/判断/入口/结束等）+ IssueBadge；
 * - 标题：节点 title；
 * - 签名预览：方法签名 / 表达式等。
 *
 * 使用 memo 包装避免不必要重渲染。
 */
export const FlowchartNodeCard = memo(function FlowchartNodeCard({
  node,
  selected,
  explanationFocused = false,
  draftChanged = false,
  draftCompareStatus,
  onMeasure,
}: FlowchartNodeCardProps) {
  const kind = flowchartKind(node);
  if (kind === "MERGE") {
    return (
      <NodeCardBase
        node={node}
        selected={selected}
        explanationFocused={explanationFocused}
        draftChanged={draftChanged}
        onMeasure={onMeasure}
        measureDeps={[node, onMeasure]}
        variantClassName={["flowchart-node-card", "kind-merge", "is-compact-merge"]}
      >
        <span
          className="flowchart-merge-marker"
          role="img"
          aria-label="分支在此合流"
          title={nodeTooltip(node)}
        />
        <IssueBadge draftCompareStatus={draftCompareStatus} />
      </NodeCardBase>
    );
  }
  const signatureText = signaturePreview(node);
  return (
    <NodeCardBase
      node={node}
      selected={selected}
      explanationFocused={explanationFocused}
      draftChanged={draftChanged}
      onMeasure={onMeasure}
      measureDeps={[node, onMeasure]}
      variantClassName={["flowchart-node-card", `kind-${kind.toLowerCase()}`]}
    >
      <div className="flow-node-head">
        <span className="flowchart-node-kind">{flowchartKindLabel(node)}</span>
        <div className="flow-node-tags">
          {isInvocationExpansionNode(node) ? <span className="flowchart-node-kind is-invocation-expansion">调用展开</span> : null}
          <IssueBadge draftCompareStatus={draftCompareStatus} />
        </div>
      </div>
      <strong className="flowchart-node-title" title={node.title}>
        {node.title}
      </strong>
      {signatureText ? (
        <span className="flowchart-node-detail" title={nodeTooltip(node)}>
          {signatureText}
        </span>
      ) : null}
    </NodeCardBase>
  );
});
