import type { AssistantTurn } from "../../types";
import { AssistantFailureNotice } from "./AssistantFailureNotice";
import { AssistantTurnHeader } from "./AssistantTurnFrame";

interface CodeDraftTurnCardProps {
  turn: AssistantTurn;
  turnIndex?: number;
  isLatest?: boolean;
  onRequestCodeDrafts: () => void;
  onWriteCodeDrafts: () => void;
  onWriteSingleCodeDraft?: (draftId: string) => void;
  onOpenNativeDiff?: (draftId: string) => void;
  onOpenDraft?: (targetPath: string) => void;
}

export function CodeDraftTurnCard({
  turn,
  turnIndex,
  isLatest = false,
  onRequestCodeDrafts,
  onWriteCodeDrafts,
  onWriteSingleCodeDraft,
  onOpenNativeDiff,
  onOpenDraft,
}: CodeDraftTurnCardProps) {
  const drafts = turn.codeDrafts ?? [];
  const warnings = Array.from(new Set([
    ...(turn.codeDraftWarnings ?? []),
    ...drafts.flatMap((draft) => draft.warnings ?? []),
  ].filter((warning) => warning.trim().length > 0)));
  return (
    <article className="assistant-turn-card assistant-turn-code-draft">
      <AssistantTurnHeader turn={turn} turnIndex={turnIndex} isLatest={isLatest} />
      {drafts.length === 0 && turn.failure ? (
        <AssistantFailureNotice failure={turn.failure} />
      ) : drafts.length === 0 ? (
        <div className="assistant-card-flow">
          <p className="muted assistant-result-text">还没有代码草稿。先基于实现建议生成代码 diff。</p>
          <button type="button" className="primary-button" onClick={onRequestCodeDrafts}>
            生成代码 diff
          </button>
        </div>
      ) : (
        <div className="assistant-card-flow">
          <div className="preview-head">
            <strong>代码草稿</strong>
            <button type="button" className="primary-button" onClick={onWriteCodeDrafts}>
              写入全部
            </button>
          </div>
          {warnings.length > 0 ? (
            <div className="assistant-evidence-list" aria-label="代码草稿警告">
              {warnings.map((warning) => (
                <p key={warning} className="muted assistant-result-text">{warning}</p>
              ))}
            </div>
          ) : null}
          {drafts.map((draft) => (
            <div key={draft.id} className="assistant-evidence-item">
              <strong className="assistant-result-text">{draft.title}</strong>
              <p className="muted assistant-result-text">{draft.targetPath}</p>
              <div className="panel-actions">
                {onWriteSingleCodeDraft ? (
                  <button
                    type="button"
                    className="ghost-button compact assistant-wrap-token"
                    onClick={() => onWriteSingleCodeDraft(draft.id)}
                  >
                    写入
                  </button>
                ) : null}
                {onOpenNativeDiff ? (
                  <button
                    type="button"
                    className="ghost-button compact assistant-wrap-token"
                    onClick={() => onOpenNativeDiff(draft.id)}
                  >
                    打开 diff
                  </button>
                ) : null}
                {onOpenDraft ? (
                  <button
                    type="button"
                    className="ghost-button compact assistant-wrap-token"
                    onClick={() => onOpenDraft(draft.targetPath)}
                  >
                    打开源码
                  </button>
                ) : null}
              </div>
            </div>
          ))}
          <button type="button" className="ghost-button compact assistant-wrap-token" onClick={onRequestCodeDrafts}>
            重新生成代码 diff
          </button>
        </div>
      )}
    </article>
  );
}
