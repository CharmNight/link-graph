import type { Certainty, DiffStatus, DraftCompareStatus } from "../types";
import { certaintyLabel, diffStatusLabel, draftCompareStatusLabel } from "../labels";

/**
 * IssueBadge 组件的入参。
 * 三个字段都可空，组件按非空字段决定渲染哪些徽章。
 */
interface IssueBadgeProps {
  /** 节点确定性等级；为空不渲染对应徽章。 */
  certainty?: Certainty;
  /** 差异状态；为空或 MATCHED 时不渲染。 */
  diffStatus?: DiffStatus;
  /** 草稿比对状态；为空不渲染。 */
  draftCompareStatus?: DraftCompareStatus;
}

/**
 * 渲染节点上的"问题徽章"组合。
 *
 * 把 certainty、diffStatus、draftCompareStatus 三类标识合并展示在一个容器里，
 * 每种标识对应一个带 className 的 span，CSS 按类名上色。
 *
 * 三个字段都缺失（或只有 MATCHED）时不渲染任何内容，返回 null。
 */
export function IssueBadge({ certainty, diffStatus, draftCompareStatus }: IssueBadgeProps) {
  // 全部为空（或差异为 MATCHED）时返回 null，避免渲染空容器
  if (!certainty && (!diffStatus || diffStatus === "MATCHED") && !draftCompareStatus) {
    return null;
  }

  return (
    <div className="issue-badges">
      {certainty ? <span className={`badge certainty-${certainty.toLowerCase()}`}>{certaintyLabel(certainty)}</span> : null}
      {diffStatus && diffStatus !== "MATCHED" ? (
        <span className={`badge diff-${diffStatus.toLowerCase()}`}>{diffStatusLabel(diffStatus)}</span>
      ) : null}
      {draftCompareStatus ? (
        <span className={`badge draft-compare-${draftCompareStatus.toLowerCase()}`}>
          {draftCompareStatusLabel(draftCompareStatus)}
        </span>
      ) : null}
    </div>
  );
}
