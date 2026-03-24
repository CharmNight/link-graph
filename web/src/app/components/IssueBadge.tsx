import type { Certainty, DiffStatus } from "../types";

interface IssueBadgeProps {
  certainty?: Certainty;
  diffStatus?: DiffStatus;
}

export function IssueBadge({ certainty, diffStatus }: IssueBadgeProps) {
  return (
    <div className="issue-badges">
      {certainty ? <span className={`badge certainty-${certainty.toLowerCase()}`}>{certainty}</span> : null}
      {diffStatus && diffStatus !== "MATCHED" ? (
        <span className={`badge diff-${diffStatus.toLowerCase()}`}>{diffStatus}</span>
      ) : null}
    </div>
  );
}
