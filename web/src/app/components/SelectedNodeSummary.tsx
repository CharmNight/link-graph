import { canNavigateToSource } from "../sourceNavigation";
import type { LinkGraphNode } from "../types";

interface SelectedNodeSummaryProps {
  selectedNode: LinkGraphNode | null;
  onInspectNode: (nodeId: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onRequestAudit: (selectedNodeId?: string) => void;
  onRequestBeautification: (selectedNodeId?: string) => void;
}

function SummaryList({ values, emptyText }: { values: string[]; emptyText: string }) {
  if (values.length === 0) {
    return <span className="muted">{emptyText}</span>;
  }

  return (
    <ul className="selected-summary-list">
      {values.map((value, index) => (
        <li key={`${value}-${index}`}>{value}</li>
      ))}
    </ul>
  );
}

/**
 * 选中摘要只展示“当前需要看清楚的内容”：
 * 完整签名、完整出入参，以及最常用的两个动作。
 */
export function SelectedNodeSummary({
  selectedNode,
  onInspectNode,
  onRequestSourceNavigation,
  onRequestAudit,
  onRequestBeautification,
}: SelectedNodeSummaryProps) {
  if (!selectedNode) {
    return (
      <section className="selected-node-summary">
        <p className="eyebrow">当前选中</p>
        <p className="muted">还没有选中节点。可在画布点击节点，或在代码中右键方法追加节点。</p>
        <div className="panel-actions">
          <button type="button" className="primary-button" onClick={() => onRequestBeautification()}>
            讲解当前链路
          </button>
          <button type="button" className="primary-button" onClick={() => onRequestAudit()}>
            审计当前链路
          </button>
        </div>
      </section>
    );
  }

  const canOpenSource = canNavigateToSource(selectedNode);
  const usesSignatureFallback = !selectedNode.location?.trim() && canOpenSource;
  const flowActionAnchorMethod = selectedNode.type === "FLOW_ACTION"
    ? selectedNode.metadata?.["flow.anchorMethod"]?.trim() ?? ""
    : "";
  const displayDoc = selectedNode.type === "FLOW_ACTION"
    ? "当前方法内部动作"
    : selectedNode.doc;

  return (
    <section className="selected-node-summary">
      <div className="selected-summary-head">
        <div className="selected-summary-heading">
          <p className="eyebrow">当前选中</p>
          <h2>{selectedNode.title}</h2>
        </div>
        <div className="panel-actions">
          <button type="button" className="primary-button" onClick={() => onInspectNode(selectedNode.id)}>
            查看详情
          </button>
          <button
            type="button"
            className="ghost-button"
            disabled={!canOpenSource}
            onClick={() => onRequestSourceNavigation(selectedNode.id)}
          >
            打开源码
          </button>
          <button type="button" className="ghost-button" onClick={() => onRequestBeautification(selectedNode.id)}>
            讲解当前链路
          </button>
          <button type="button" className="ghost-button" onClick={() => onRequestAudit(selectedNode.id)}>
            审计当前节点
          </button>
        </div>
      </div>

      {displayDoc ? <p className="selected-summary-doc">{displayDoc}</p> : null}
      {usesSignatureFallback ? <p className="muted">当前将按方法/类签名在 IDEA 中定位源码。</p> : null}

      <dl className="selected-summary-grid">
        {flowActionAnchorMethod ? (
          <div>
            <dt>所属方法</dt>
            <dd className="selected-summary-code">{flowActionAnchorMethod}</dd>
          </div>
        ) : null}

        <div>
          <dt>签名</dt>
          <dd className="selected-summary-code">{selectedNode.signature ?? "未提供"}</dd>
        </div>

        <div>
          <dt>位置</dt>
          <dd className="selected-summary-code">{selectedNode.location ?? "未提供"}</dd>
        </div>

        <div>
          <dt>输入</dt>
          <dd>
            <SummaryList values={selectedNode.inputs} emptyText="无输入参数" />
          </dd>
        </div>

        <div>
          <dt>输出</dt>
          <dd>
            <SummaryList values={selectedNode.outputs} emptyText="无输出参数" />
          </dd>
        </div>
      </dl>
    </section>
  );
}
