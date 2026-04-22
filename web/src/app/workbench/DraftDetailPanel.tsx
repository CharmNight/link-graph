import type { DraftWorkbenchEntry } from "../types";

interface DraftDetailPanelProps {
  entry: DraftWorkbenchEntry | null;
  compareMode: "after" | "compare";
  resolveNodeTitle: (nodeId: string) => string;
  onLocateChangeNode: (entryId: string) => void;
  onUnconfirmChange: (entryId: string) => void;
  onOpenNote: (entryId: string) => void;
  onLocateNoteNode: (entryId: string) => void;
  showTitle?: boolean;
}

export function DraftDetailPanel({
  entry,
  compareMode,
  resolveNodeTitle,
  onLocateChangeNode,
  onUnconfirmChange,
  onOpenNote,
  onLocateNoteNode,
  showTitle = true,
}: DraftDetailPanelProps) {
  if (!entry) {
    return (
      <section className="workbench-draft-section">
        <div className="workbench-empty-card">
          <strong>当前还没有草稿条目</strong>
          <p className="muted">先在讲解里记为备注，或在问答里确认变更，这里才会出现可操作的草稿内容。</p>
        </div>
      </section>
    );
  }

  const isChange = entry.kind === "CHANGE";
  const targetNodeTitles = entry.targetNodeIds.map((nodeId) => resolveNodeTitle(nodeId));
  const beforeState = normalizeOptionalText(entry.beforeState);
  const afterState = normalizeOptionalText(entry.afterState);
  const hasComparableState = beforeState != null && afterState != null;
  const hasSingleState = afterState != null || beforeState != null;
  const changeStateSection = isChange ? (
    hasComparableState && compareMode === "compare" ? (
      <dl className="workbench-before-after">
        <div>
          <dt>修改前</dt>
          <dd>{beforeState}</dd>
        </div>
        <div>
          <dt>修改后</dt>
          <dd>{afterState}</dd>
        </div>
      </dl>
    ) : hasSingleState ? (
      <section className="workbench-step-section">
        <h4>{afterState != null ? "修改后" : "当前定位源码"}</h4>
        <div className="workbench-draft-single-state">{afterState ?? beforeState}</div>
      </section>
    ) : (
      <section className="workbench-step-section">
        <h4>变更意图</h4>
        <div className="workbench-draft-intent-card">
          <strong>当前阶段已完成源码定位，但还没有生成具体代码 diff。</strong>
          <p className="muted">这条草稿现在表达的是“改哪里、为什么改、允许写回到哪里”，不是最终代码文本。</p>
        </div>
      </section>
    )
  ) : null;

  return (
    <section className="workbench-draft-section">
      {showTitle ? (
        <div className="workbench-section-title-row">
          <h3>{isChange ? "草稿变更详情" : "草稿说明详情"}</h3>
          <span className="badge">{isChange ? "变更" : "说明"}</span>
        </div>
      ) : null}

      <article className={isChange ? "workbench-draft-card change active" : "workbench-draft-card note active"}>
        <div className="workbench-section-title-row">
          <strong>{entry.title}</strong>
          <span className="badge">{isChange ? "已确认" : "讲解备注"}</span>
        </div>

        {changeStateSection}

        <p>{entry.reason}</p>
        {entry.impactSummary ? <p className="muted">影响范围：{entry.impactSummary}</p> : null}

        {targetNodeTitles.length > 0 ? (
          <section className="workbench-step-section">
            <h4>涉及节点</h4>
            <div className="workbench-draft-target-list">
              {targetNodeTitles.map((title) => (
                <span key={title} className="badge">{title}</span>
              ))}
            </div>
          </section>
        ) : null}

        {isChange ? (
          <section className="workbench-step-section">
            <h4>代码写回边界</h4>
            {(entry.editScopes?.length ?? 0) > 0 ? (
              <>
                <p>已授权 {entry.editScopes?.length ?? 0} 个精确写回范围。</p>
                <ul className="answer-list">
                  {entry.editScopes?.map((scope) => (
                    <li key={scope.scopeId}>
                      <strong>{formatEditScopeLocation(scope.filePath, scope.startLine, scope.endLine)}</strong>
                      {scope.symbolSignature ? <span className="workbench-edit-scope-signature">{scope.symbolSignature}</span> : null}
                    </li>
                  ))}
                </ul>
              </>
            ) : (
              <p className="muted">这条草稿只有读证据，尚未授权现有文件精确写回。</p>
            )}
          </section>
        ) : null}

        {entry.targetStepIds.length > 0 ? (
          <section className="workbench-step-section">
            <h4>关联步骤</h4>
            <div className="workbench-draft-target-list">
              {entry.targetStepIds.map((stepId) => (
                <span key={stepId} className="badge">{stepId}</span>
              ))}
            </div>
          </section>
        ) : null}

        {!isChange ? (
          <section className="workbench-step-section">
            <h4>说明内容</h4>
            <div className="workbench-draft-single-state">{entry.afterState ?? entry.reason}</div>
          </section>
        ) : null}

        <div className="panel-actions">
          {isChange ? (
            entry.targetNodeIds.length > 0 ? (
              <button
                type="button"
                className="ghost-button compact"
                aria-label={`定位草稿变更对应节点：${entry.title}`}
                onClick={() => onLocateChangeNode(entry.entryId)}
              >
                定位节点
              </button>
            ) : null
          ) : (
            <>
              <button
                type="button"
                className="ghost-button compact"
                aria-label={`打开草稿说明：${entry.title}`}
                onClick={() => onOpenNote(entry.entryId)}
              >
                回到讲解
              </button>
              {entry.targetNodeIds.length > 0 ? (
                <button
                  type="button"
                  className="ghost-button compact"
                  aria-label={`定位草稿说明对应节点：${entry.title}`}
                  onClick={() => onLocateNoteNode(entry.entryId)}
                >
                  定位节点
                </button>
              ) : null}
            </>
          )}
          {isChange ? (
            <button
              type="button"
              className="ghost-button compact"
              aria-label={`取消确认：${entry.title}`}
              onClick={() => onUnconfirmChange(entry.entryId)}
            >
              取消确认
            </button>
          ) : null}
        </div>
      </article>
    </section>
  );
}

function normalizeOptionalText(value?: string | null): string | null {
  const normalized = value?.trim();
  return normalized ? normalized : null;
}

function formatEditScopeLocation(
  filePath: string,
  startLine?: number | null,
  endLine?: number | null,
): string {
  if (startLine == null) {
    return filePath;
  }
  if (endLine != null && endLine !== startLine) {
    return `${filePath}:${startLine}-${endLine}`;
  }
  return `${filePath}:${startLine}`;
}
