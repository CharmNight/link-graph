interface GraphNodeStateBadgesProps {
  selected?: boolean;
  explanationFocused?: boolean;
  draftChanged?: boolean;
}

export function GraphNodeStateBadges({
  selected = false,
  explanationFocused = false,
  draftChanged = false,
}: GraphNodeStateBadgesProps) {
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
