import { useEffect, useState } from "react";
import type {
  AsyncRequestState,
  CodeEditOperation,
  GeneratedCodeDraft,
  GeneratedCodeDraftWriteReport,
  LlmResultSource,
  PreparedCodeEdit,
  StageEligibilityDecision,
} from "../types";
import { draftStatusLabel, normalizeOptionalWorkbenchWording, normalizeWorkbenchWording } from "../labels";
import { AsyncRequestBanner, resolveEffectiveRequestState } from "./AsyncRequestBanner";
import { ArtifactTextDisclosure } from "./ArtifactTextDisclosure";

interface CodeDraftPanelProps {
  drafts: GeneratedCodeDraft[];
  warnings: string[];
  requestState?: AsyncRequestState | null;
  source?: LlmResultSource | null;
  promptPreview?: string | null;
  promptPreviewArtifactId?: string | null;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
  writeReport?: GeneratedCodeDraftWriteReport | null;
  hasPlan?: boolean;
  eligibilityDecision?: StageEligibilityDecision | null;
  draftVersion?: number | null;
  generatedCodeDraftVersion?: number | null;
  onOpenDraftWorkbench: () => void;
  onOpenDraftValidation?: () => void;
  onRequestPlan: () => void;
  onRequestDrafts: () => void;
  onWriteDrafts: () => void;
  onWriteSingleDraft: (draftId: string) => void;
  onOpenNativeDiff?: (draftId: string) => void;
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

function codeDraftSourceLabel(source?: LlmResultSource | null): string | null {
  switch (source) {
    case "REMOTE":
      return "远程 LLM";
    case "DISABLED":
      return "未启用";
    case "MOCK":
      return "本地规则";
    default:
      return null;
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

function preparedEditSummary(edit: PreparedCodeEdit, draftTargetPath: string): string {
  const targetName = basename(edit.filePath || draftTargetPath);
  return `${codeEditOperationLabel(edit.kind)} · ${targetName}`;
}

function draftFileSummary(draft: GeneratedCodeDraft): string {
  if ((draft.editOperations ?? []).length > 0) {
    return `预计改动 ${(draft.editOperations ?? []).length} 处`;
  }
  return "当前仅提供完整文件内容";
}

export function CodeDraftPanel({
  drafts,
  warnings,
  requestState,
  source,
  promptPreview,
  promptPreviewArtifactId,
  resolveArtifactText,
  onRequestArtifact,
  writeReport,
  hasPlan = false,
  eligibilityDecision,
  draftVersion = null,
  generatedCodeDraftVersion = null,
  onOpenDraftWorkbench,
  onOpenDraftValidation,
  onRequestPlan,
  onRequestDrafts,
  onWriteDrafts,
  onWriteSingleDraft,
  onOpenNativeDiff,
  onOpenDraft,
}: CodeDraftPanelProps) {
  const effectiveRequestState = resolveEffectiveRequestState(requestState);
  const sourceLabel = codeDraftSourceLabel(source);
  const drafting = effectiveRequestState?.phase === "RUNNING";
  const draftError = effectiveRequestState?.phase === "FAILED" || effectiveRequestState?.phase === "TIMED_OUT"
    ? effectiveRequestState.errorMessage ?? null
    : null;
  const awaitingEligibilityDecision = eligibilityDecision == null;
  const canRequestDrafts = eligibilityDecision?.allowed === true;
  const blockedByRisk = Boolean(eligibilityDecision && !eligibilityDecision.allowed && eligibilityDecision.blockingThreadIds.length > 0);
  const openDraftValidation = onOpenDraftValidation ?? onOpenDraftWorkbench;
  const emptyMessage = normalizeWorkbenchWording(drafting
    ? "正在生成代码 diff，请稍候。"
    : draftError
      ? "当前请求失败，可查看上方状态并按需重试。"
      : eligibilityDecision?.message
        ?? "代码阶段准入状态尚未就绪。");
  const emptyDetail = normalizeOptionalWorkbenchWording(
    eligibilityDecision?.detailMessage
    ?? (awaitingEligibilityDecision
      ? "请先回到草稿层完成验证状态同步，再决定是否生成代码 diff。"
      : null),
  );
  const staleDrafts = drafts.length > 0
    && draftVersion != null
    && generatedCodeDraftVersion != null
    && generatedCodeDraftVersion < draftVersion;
  const staleMessage = staleDrafts
    ? `当前代码 diff 基于草稿 v${generatedCodeDraftVersion} 生成，当前草稿已更新到 v${draftVersion}，请先重新生成。`
    : null;
  const [selectedDraftId, setSelectedDraftId] = useState<string | null>(drafts[0]?.id ?? null);
  const selectedDraft = drafts.find((draft) => draft.id === selectedDraftId) ?? drafts[0] ?? null;
  const canWrite = !staleDrafts;

  useEffect(() => {
    if (drafts.length === 0) {
      setSelectedDraftId(null);
      return;
    }
    setSelectedDraftId((current) => (current && drafts.some((draft) => draft.id === current) ? current : drafts[0]?.id ?? null));
  }, [drafts]);

  return (
    <section className="side-panel code-draft-panel">
      <div className="preview-head w-full gap-0 block">
        <div>
          <p className="eyebrow">代码 diff</p>
          <h2>代码 diff 工作台</h2>
          {generatedCodeDraftVersion != null ? <p className="muted">基于草稿 v{generatedCodeDraftVersion} 生成</p> : null}
          {sourceLabel ? <p className="muted">来源 {sourceLabel}</p> : null}
          <p className="muted">代码阶段优先展示改了什么，完整正文只作为次级查看。</p>
        </div>
        {drafts.length > 0 ? (
          <div className="panel-actions">
            <button type="button" className="ghost-button" onClick={onRequestDrafts}>
              重新生成 diff
            </button>
            <button type="button" className="primary-button" onClick={onWriteDrafts} disabled={!canWrite}>
              写入全部
            </button>
          </div>
        ) : null}
      </div>

      <div className="side-panel-scroll-body m-scrollbar">
        {warnings.length > 0 ? (
          <div className="warning-list">
            {warnings.map((warning) => (
              <p key={warning} className="muted">
                {warning}
              </p>
            ))}
          </div>
        ) : null}

        {staleMessage ? (
          <article className="preview-card code-diff-stale-banner">
            <p className="muted">{staleMessage}</p>
          </article>
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

        {drafts.length === 0 ? (
          <article className="preview-card">
            <AsyncRequestBanner requestState={effectiveRequestState} telemetryCollapsedByDefault />
            <p className="muted">{emptyMessage}</p>
            {emptyDetail ? <p className="muted">{emptyDetail}</p> : null}
            {!drafting ? (
              <div className="panel-actions">
                {!canRequestDrafts && blockedByRisk ? (
                  <button type="button" className="primary-button" onClick={openDraftValidation}>
                    处理阻塞风险
                  </button>
                ) : !canRequestDrafts && awaitingEligibilityDecision ? (
                  <button type="button" className="primary-button" onClick={openDraftValidation}>
                    打开草稿验证区
                  </button>
                ) : !canRequestDrafts ? (
                  <button type="button" className="primary-button" onClick={onOpenDraftWorkbench}>
                    前往草稿层
                  </button>
                ) : hasPlan ? (
                  <button type="button" className="primary-button" onClick={onRequestDrafts}>
                    {draftError ? "重试生成 diff" : "生成代码 diff"}
                  </button>
                ) : (
                  <button type="button" className="primary-button" onClick={onRequestPlan}>
                    先生成实现建议
                  </button>
                )}
              </div>
            ) : null}
          </article>
        ) : selectedDraft ? (
          <div className="code-diff-layout">
            <div className="code-diff-file-list">
              {drafts.map((draft) => {
                const status = resolveDraftStatus(draft, writeReport);
                const selected = draft.id === selectedDraft.id;
                return (
                  <button
                    key={draft.id}
                    type="button"
                    className={selected ? "code-diff-file-button active" : "code-diff-file-button"}
                    aria-pressed={selected}
                    aria-label={`选择代码 diff 文件：${draft.title}`}
                    onClick={() => setSelectedDraftId(draft.id)}
                  >
                    <span className="code-diff-file-name">{draft.title}</span>
                    <span className="code-diff-file-meta">{draftFileSummary(draft)}</span>
                    <span className={`risk-pill ${statusClassName(status)}`}>{draftStatusLabel(status)}</span>
                  </button>
                );
              })}
            </div>

            <article className="preview-card code-diff-detail">
              <div className="preview-head">
                <strong>{selectedDraft.title}</strong>
                <span className={`risk-pill ${statusClassName(resolveDraftStatus(selectedDraft, writeReport))}`}>
                  {draftStatusLabel(resolveDraftStatus(selectedDraft, writeReport))}
                </span>
              </div>
              <p className="muted">{selectedDraft.targetPath}</p>
              <div className="panel-actions">
                <button
                  type="button"
                  className="primary-button"
                  onClick={() => onWriteSingleDraft(selectedDraft.id)}
                  disabled={!canWrite}
                >
                  写入当前文件
                </button>
                {onOpenNativeDiff ? (
                  <button type="button" className="ghost-button" onClick={() => onOpenNativeDiff(selectedDraft.id)}>
                    查看真实 Diff
                  </button>
                ) : null}
                <button type="button" className="ghost-button" onClick={() => onOpenDraft(selectedDraft.targetPath)}>
                  打开目标文件
                </button>
                <button type="button" className="ghost-button" onClick={onOpenDraftWorkbench}>
                  回到草稿查看业务意图
                </button>
              </div>

              <section className="panel-section">
                <strong>代码 diff</strong>
                {(selectedDraft.preparedEdits ?? []).length > 0 ? (
                  <div className="code-diff-operation-list">
                    {selectedDraft.preparedEdits?.map((edit) => (
                      <div key={edit.operationId} className="code-diff-operation-card">
                        <strong>{preparedEditSummary(edit, selectedDraft.targetPath)}</strong>
                        {edit.targetSymbolSignature ? <p className="muted">{edit.targetSymbolSignature}</p> : null}
                        <strong>修改前</strong>
                        <pre className="prompt-preview code-diff-payload">{edit.beforeText || "(空)"}</pre>
                        <strong>修改后</strong>
                        <pre className="prompt-preview code-diff-payload">{edit.afterText || "(空)"}</pre>
                      </div>
                    ))}
                  </div>
                ) : (selectedDraft.editOperations ?? []).length > 0 ? (
                  <div className="code-diff-operation-list">
                    {selectedDraft.editOperations?.map((operation) => (
                      <div key={operation.operationId} className="code-diff-operation-card">
                        <strong>{codeEditOperationSummary(operation, selectedDraft.targetPath)}</strong>
                        <p className="muted">当前结构化结果尚未准备出本地 patch 预览，请查看告警或直接打开原生 Diff。</p>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="muted">当前结果没有结构化改写操作，完整文件内容在下方按需展开查看。</p>
                )}
              </section>

              <ArtifactTextDisclosure
                buttonLabel="查看完整内容"
                expandedLabel="隐藏完整内容"
                artifactId={selectedDraft.contentArtifactId ?? null}
                text={selectedDraft.content ?? (selectedDraft.contentArtifactId ? resolveArtifactText?.(selectedDraft.contentArtifactId) : null)}
                onRequestArtifact={onRequestArtifact}
              />
            </article>
          </div>
        ) : null}
      </div>
    </section>
  );
}
