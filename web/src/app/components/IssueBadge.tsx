import type { Certainty, DiffStatus } from "../types";
import { certaintyLabel, diffStatusLabel } from "../labels";

interface IssueBadgeProps {
  certainty?: Certainty;
  diffStatus?: DiffStatus;
}

export function IssueBadge({ certainty, diffStatus }: IssueBadgeProps) {
  if (!certainty && (!diffStatus || diffStatus === "MATCHED")) {
    return null;
  }

  return (
    <div className="issue-badges">
      {certainty ? <span className={`badge certainty-${certainty.toLowerCase()}`}>{certaintyLabel(certainty)}</span> : null}
      {diffStatus && diffStatus !== "MATCHED" ? (
        <span className={`badge diff-${diffStatus.toLowerCase()}`}>{diffStatusLabel(diffStatus)}</span>
      ) : null}
    </div>
  );
}
