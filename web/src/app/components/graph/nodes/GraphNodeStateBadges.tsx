/** GraphNodeStateBadges 组件的入参。 */
interface GraphNodeStateBadgesProps {
  /** 节点是否被选中。 */
  selected?: boolean;
  /** 节点是否处于讲解聚焦状态。 */
  explanationFocused?: boolean;
  /** 节点是否被草稿改动影响。 */
  draftChanged?: boolean;
}

/**
 * 节点状态徽章：在节点卡片角落展示"选中""讲解中""已改草稿"等状态。
 *
 * 所有状态都为 false 时不渲染任何内容。
 */
export function GraphNodeStateBadges({
  selected = false,
  explanationFocused = false,
  draftChanged = false,
}: GraphNodeStateBadgesProps) {
  // 全部为 false 时不渲染容器
  if (!selected && !explanationFocused && !draftChanged) {
    return null;
  }

  return (
    <div className="flow-node-state-badges" aria-label="节点状态">
      {selected ? <span className="flow-node-state-badge selected-state">当前选中</span> : null}
      {explanationFocused ? <span className="flow-node-state-badge explanation-focus">讲解中</span> : null}
      {draftChanged ? <span className="flow-node-state-badge draft-change">已改草稿</span> : null}
    </div>
  );
}
