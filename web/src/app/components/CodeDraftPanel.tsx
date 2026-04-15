import type {
  AsyncRequestState,
  CodeEditOperation,
  GeneratedCodeDraft,
  GeneratedCodeDraftWriteReport,
  LlmResultSource,
} from "../types";
import { draftStatusLabel } from "../labels";
import { AsyncRequestBanner, resolveEffectiveRequestState } from "./AsyncRequestBanner";
import { ArtifactTextDisclosure } from "./ArtifactTextDisclosure";

interface CodeDraftPanelProps {
  drafts: GeneratedCodeDraft[];
  warnings: string[];
  requestState?: AsyncRequestState | null;
  isRequesting?: boolean;
  requestError?: string | null;
  source?: LlmResultSource | null;
  promptPreview?: string | null;
  promptPreviewArtifactId?: string | null;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
  writeReport?: GeneratedCodeDraftWriteReport | null;
  hasPlan?: boolean;
  hasConfirmedDraftChanges?: boolean;
  onOpenDraftWorkbench: () => void;
  onRequestPlan: () => void;
  onRequestDrafts: () => void;
  onWriteDrafts: () => void;
  onWriteSingleDraft: (draftId: string) => void;
  onOpenDraft: (targetPath: string) => void;
}

function resolveDraftStatus(
  draft: GeneratedCodeDraft,
  writeReport?: GeneratedCodeDraftWriteReport | null,
): "READY" | "WRITTEN" | "SKIPPED" {
  if (writeReport?.writtenFiles.includes(draft.targetPath)) {
    return "WRITTEN";
  }
  if (writeReport?.skippedFiles.includes(draft.targetPath)) {
    return "SKIPPED";
  }
  return "READY";
}

function statusClassName(status: "READY" | "WRITTEN" | "SKIPPED"): string {
  switch (status) {
    case "WRITTEN":
      return "risk-low";
    case "SKIPPED":
      return "risk-medium";
    default:
      return "risk-low";
  }
}

function codeDraftSourceLabel(source?: LlmResultSource | null): string {
  switch (source) {
    case "REMOTE":
      return "远程 LLM";
    case "DISABLED":
      return "未启用";
    case "MOCK":
    default:
      return "本地规则";
  }
}

function codeEditOperationLabel(kind: CodeEditOperation["kind"]): string {
  switch (kind) {
    case "REPLACE_METHOD_BODY":
      return "替换方法主体";
    case "REPLACE_METHOD_BLOCK":
      return "替换方法代码块";
    case "INSERT_METHOD_AFTER":
      return "在方法后插入代码";
    case "ADD_IMPORT":
      return "新增 import";
    case "ADD_FIELD":
      return "新增字段";
    case "CREATE_FILE":
      return "创建文件";
    default:
      return "结构化改写";
  }
}

function basename(path: string): string {
  const segments = path.split(/[\\/]/).filter(Boolean);
  return segments[segments.length - 1] ?? path;
}

function codeEditOperationSummary(operation: CodeEditOperation, draftTargetPath: string): string {
  const targetName = basename(operation.filePath || draftTargetPath);
  return `${codeEditOperationLabel(operation.kind)} · ${targetName}`;
}

export function CodeDraftPanel({
  drafts,
  warnings,
  requestState,
  isRequesting = false,
  requestError,
  source,
  promptPreview,
  promptPreviewArtifactId,
  resolveArtifactText,
  onRequestArtifact,
  writeReport,
  hasPlan = false,
  hasConfirmedDraftChanges = true,
  onOpenDraftWorkbench,
  onRequestPlan,
  onRequestDrafts,
  onWriteDrafts,
  onWriteSingleDraft,
  onOpenDraft,
}: CodeDraftPanelProps) {
  const effectiveRequestState = resolveEffectiveRequestState(requestState, isRequesting, requestError);
  const drafting = effectiveRequestState?.phase === "RUNNING";
  const draftError = effectiveRequestState?.phase === "FAILED" || effectiveRequestState?.phase === "TIMED_OUT"
    ? effectiveRequestState.errorMessage ?? null
    : null;

  return (
    <section className="side-panel code-draft-panel">
      <div className="preview-head">
        <div>
          <p className="eyebrow">代码草稿</p>
          <h2>生成文件</h2>
          <p className="muted">来源 {codeDraftSourceLabel(source)}</p>
          <p className="muted">代码草稿只会生成文件内容，不会自动把结果写回当前画布。</p>
        </div>
        <button type="button" className="primary-button" onClick={onWriteDrafts}>
          写入全部草稿
        </button>
      </div>

      <div className="side-panel-scroll-body">
        {warnings.length > 0 ? (
          <div className="warning-list">
            {warnings.map((warning) => (
              <p key={warning} className="muted">
                {warning}
              </p>
            ))}
          </div>
        ) : null}

        <ArtifactTextDisclosure
          buttonLabel="查看本次生成提示词"
          expandedLabel="隐藏本次生成提示词"
          artifactId={promptPreviewArtifactId}
          text={promptPreview ?? (promptPreviewArtifactId ? resolveArtifactText?.(promptPreviewArtifactId) : null)}
          onRequestArtifact={onRequestArtifact}
        />

        {writeReport ? (
          <div className="warning-list">
            {writeReport.writtenFiles.map((file) => (
              <p key={`written:${file}`} className="muted">
                已写入：{file}
              </p>
            ))}
            {writeReport.skippedFiles.map((file) => (
              <p key={`skipped:${file}`} className="muted">
                已跳过：{file}
              </p>
            ))}
            {writeReport.warnings.map((warning) => (
              <p key={`write-warning:${warning}`} className="muted">
                {warning}
              </p>
            ))}
          </div>
        ) : null}

        <div className="preview-list">
          {drafts.length === 0 ? (
            <article className="preview-card">
              <AsyncRequestBanner requestState={effectiveRequestState} />
              <p className="muted">
                {drafting
                  ? "正在生成代码草稿，请稍候。"
                  : draftError
                    ? "当前请求失败，可查看上方状态并按需重试。"
                    : !hasConfirmedDraftChanges
                      ? "请先确认至少一条草稿变更，再生成代码草稿。"
                      : hasPlan
                      ? "还没有代码草稿。当前已经有实现计划，可以直接继续生成草稿。"
                      : "还没有代码草稿。请先生成计划，再继续生成草稿。"}
              </p>
              {!drafting ? (
                <div className="panel-actions">
                  {!hasConfirmedDraftChanges ? (
                    <button type="button" className="primary-button" onClick={onOpenDraftWorkbench}>
                      前往草稿层
                    </button>
                  ) : hasPlan ? (
                    <button type="button" className="primary-button" onClick={onRequestDrafts}>
                      {draftError ? "重试生成草稿" : "生成草稿"}
                    </button>
                  ) : (
                    <button type="button" className="primary-button" onClick={onRequestPlan}>
                      先生成计划
                    </button>
                  )}
                </div>
              ) : null}
            </article>
          ) : (
            drafts.map((draft) => {
              const status = resolveDraftStatus(draft, writeReport);
              return (
                <article key={draft.id} className="preview-card">
                  <div className="preview-head">
                    <strong>{draft.title}</strong>
                    <span className={`risk-pill ${statusClassName(status)}`}>{draftStatusLabel(status)}</span>
                  </div>
                  <p className="muted">{draft.targetPath}</p>
                  {draft.editOperations?.length ? (
                    <p className="muted">结构化改写 {draft.editOperations.length} 条，写回时会走本地 scope-safe apply。</p>
                  ) : null}
                  <div className="panel-actions">
                    <button type="button" className="primary-button" onClick={() => onWriteSingleDraft(draft.id)}>
                      写入 {draft.title}
                    </button>
                    <button type="button" className="ghost-button" onClick={() => onOpenDraft(draft.targetPath)}>
                      打开 {draft.title}
                    </button>
                  </div>
                  <ArtifactTextDisclosure
                    buttonLabel="查看正文"
                    expandedLabel="隐藏正文"
                    artifactId={draft.contentArtifactId ?? null}
                    text={draft.content ?? (draft.contentArtifactId ? resolveArtifactText?.(draft.contentArtifactId) : null)}
                    onRequestArtifact={onRequestArtifact}
                  />
                  {draft.editOperations?.length ? (
                    <div className="warning-list">
                      {draft.editOperations.map((operation) => (
                        <p key={operation.operationId} className="muted">
                          {codeEditOperationSummary(operation, draft.targetPath)}
                        </p>
                      ))}
                    </div>
                  ) : null}
                </article>
              );
            })
          )}
        </div>
      </div>
    </section>
  );
}
