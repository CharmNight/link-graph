import type { Certainty, DiffStatus, DraftCompareStatus } from "../types";
import { certaintyLabel, diffStatusLabel, draftCompareStatusLabel } from "../labels";

interface IssueBadgeProps {
  certainty?: Certainty;
  diffStatus?: DiffStatus;
  draftCompareStatus?: DraftCompareStatus;
}

export function IssueBadge({ certainty, diffStatus, draftCompareStatus }: IssueBadgeProps) {
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
