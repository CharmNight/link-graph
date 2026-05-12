import { useEffect, useRef, useState } from "react";
import { resolveEffectiveRequestState, AsyncRequestBanner } from "../components/AsyncRequestBanner";
import { traceLinkGraph } from "../debug";
import { deriveConversationInvestigationThreads, deriveInvestigationThreads } from "../investigationThreads";
import { qaModeLabel } from "../labels";
import type {
  AuditConversationMessage,
  AuditWorkbenchState,
  CandidateDraftChange,
  InvestigationThread,
  InvestigationTurnOutcome,
  QaMode,
  SourceSnippetContext,
  WorkbenchSectionId,
  WorkbenchSectionPreferences,
} from "../types";
import { AUDIT_WORKBENCH_SECTION_IDS } from "./workbenchSections";
import { AuditConversation } from "./AuditConversation";
import { CandidateChangeList } from "./CandidateChangeList";
import { InvestigationThreadList } from "./InvestigationThreadList";
import { RequestPromptDisclosure } from "../components/RequestPromptDisclosure";

const DEFAULT_ACTIVE_AUDIT_SECTION: WorkbenchSectionId = "audit.composer";

const AUDIT_SECTION_META: Partial<Record<WorkbenchSectionId, { title: string }>> = {
  "audit.request-status": { title: "请求" },
  "audit.thread": { title: "问答会话" },
  "audit.composer": { title: "提问" },
  "audit.candidate-changes": { title: "待确认变更" },
  "audit.investigation-threads": { title: "风险线程" },
};

function auditSectionTitle(sectionId: WorkbenchSectionId): string {
  return AUDIT_SECTION_META[sectionId]?.title ?? sectionId;
}

interface AuditTabProps {
  state: AuditWorkbenchState;
  onQuestionDraftChange: (value: string) => void;
  onQuestionModeChange?: (value: QaMode) => void;
  onSubmitQuestion: () => void;
  onRetryLastRequest?: () => void;
  onEditFailedRequest?: () => void;
  onSelectChange: (changeId: string) => void;
  onConfirmChange: (changeId: string) => void;
  onSelectThread: (threadId: string) => void;
  onInvestigateThread: (threadId: string) => void;
  onDeferRisk?: (threadId: string) => void;
  onAcceptRisk?: (threadId: string) => void;
  onDismissRisk?: (threadId: string) => void;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
  sectionPreferences?: WorkbenchSectionPreferences | null;
  onSectionPreferenceChange?: (sectionId: WorkbenchSectionId, expanded: boolean) => void;
}

function shouldAutoExpandRequestStatus(requestStatus: ReturnType<typeof resolveEffectiveRequestState>): boolean {
  return requestStatus != null && (
    requestStatus.phase === "RUNNING"
    || requestStatus.phase === "FAILED"
    || requestStatus.phase === "TIMED_OUT"
    || Boolean(requestStatus.previewText?.trim())
  );
}

function resolveVisibleAuditSectionIds(hasChanges: boolean, hasThreads: boolean): WorkbenchSectionId[] {
  return AUDIT_WORKBENCH_SECTION_IDS.filter((sectionId) => {
    if (sectionId === "audit.candidate-changes") {
      return hasChanges;
    }
    if (sectionId === "audit.investigation-threads") {
      return hasThreads;
    }
    return true;
  });
}

function resolveActiveAuditSectionId(args: {
  preferences: WorkbenchSectionPreferences;
  requestStatus: ReturnType<typeof resolveEffectiveRequestState>;
  hasChanges: boolean;
  hasThreads: boolean;
}): WorkbenchSectionId | null {
  const { preferences, requestStatus, hasChanges, hasThreads } = args;
  const visibleSectionIds = resolveVisibleAuditSectionIds(hasChanges, hasThreads);
  const explicitExpandedSectionId = visibleSectionIds.find((sectionId) => preferences[sectionId] === true) ?? null;
  if (shouldAutoExpandRequestStatus(requestStatus) && (
    explicitExpandedSectionId == null
    || explicitExpandedSectionId === "audit.composer"
  )) {
    return "audit.request-status";
  }

  if (explicitExpandedSectionId) {
    return explicitExpandedSectionId;
  }

  const hasExplicitAuditPreference = visibleSectionIds.some((sectionId) => preferences[sectionId] != null);
  if (hasExplicitAuditPreference) {
    return null;
  }

  if (shouldAutoExpandRequestStatus(requestStatus)) {
    return "audit.request-status";
  }

  return DEFAULT_ACTIVE_AUDIT_SECTION;
}

export function AuditTab({
  state,
  onQuestionDraftChange,
  onQuestionModeChange = () => undefined,
  onSubmitQuestion,
  onRetryLastRequest = () => undefined,
  onEditFailedRequest = () => undefined,
  onSelectChange,
  onConfirmChange,
  onSelectThread,
  onInvestigateThread,
  onDeferRisk = () => undefined,
  onAcceptRisk = () => undefined,
  onDismissRisk = () => undefined,
  resolveArtifactText,
  onRequestArtifact,
  sectionPreferences,
  onSectionPreferenceChange = () => undefined,
}: AuditTabProps) {
  const tabRef = useRef<HTMLElement | null>(null);
  const messages = state.result?.auditSession?.messages ?? [];
  const changes = (state.result?.candidateChanges ?? []).filter((change) => change.status === "PENDING_CONFIRMATION");
  const threads = deriveInvestigationThreads(state.result).filter(
    (thread) => thread.status === "OPEN" || thread.status === "BLOCKED",
  );
  const latestTurnOutcome = state.result?.latestTurnOutcome
    ?? state.result?.recentTurnOutcomes?.[state.result.recentTurnOutcomes.length - 1]
    ?? null;
  const recentTurnOutcomes = state.result?.recentTurnOutcomes
    ?? state.result?.auditSession?.turnOutcomes
    ?? [];
  const conversationTurnOutcomes = state.result?.auditSession?.turnOutcomes
    ?? state.result?.recentTurnOutcomes
    ?? [];
  const conversationThreads = deriveConversationInvestigationThreads(state.result, state.result?.auditSession);
  const scopeLabel = state.scopeLabel ?? "当前链路会话";
  const hasChanges = changes.length > 0;
  const hasThreads = threads.length > 0;
  const hasMessages = messages.length > 0;
  const [localSectionPreferences, setLocalSectionPreferences] = useState<WorkbenchSectionPreferences>(
    () => sectionPreferences ?? {},
  );
  const rawSectionPreferences = sectionPreferences ?? localSectionPreferences;
  const requestStatus = resolveEffectiveRequestState(state.requestState);
  const activeSectionId = resolveActiveAuditSectionId({
    preferences: rawSectionPreferences,
    requestStatus,
    hasChanges,
    hasThreads,
  });
  const visibleSectionIds = resolveVisibleAuditSectionIds(hasChanges, hasThreads);

  useEffect(() => {
    if (sectionPreferences == null) {
      return;
    }
    setLocalSectionPreferences(sectionPreferences);
  }, [sectionPreferences]);

  useEffect(() => {
    traceLinkGraph("workbench.audit.layoutResolved", {
      hasChanges,
      hasThreads,
      hasMessages,
      rawSectionPreferences,
      activeSectionId,
      visibleSectionIds,
    });
  }, [activeSectionId, hasChanges, hasThreads, hasMessages, rawSectionPreferences, visibleSectionIds]);

  function stopComposerBoundaryPropagation(event: {
    stopPropagation: () => void;
  }) {
    event.stopPropagation();
  }

  function syncAuditSectionPreferences(nextActiveSectionId: WorkbenchSectionId | null) {
    const nextAuditPreferences = AUDIT_WORKBENCH_SECTION_IDS.reduce<WorkbenchSectionPreferences>((accumulator, sectionId) => {
      accumulator[sectionId] = nextActiveSectionId === sectionId;
      return accumulator;
    }, {});

    if (sectionPreferences == null) {
      setLocalSectionPreferences((current) => ({
        ...current,
        ...nextAuditPreferences,
      }));
    }

    for (const sectionId of AUDIT_WORKBENCH_SECTION_IDS) {
      const nextExpanded = nextAuditPreferences[sectionId] ?? false;
      const currentExpanded = rawSectionPreferences[sectionId] ?? false;
      if (currentExpanded !== nextExpanded) {
        onSectionPreferenceChange(sectionId, nextExpanded);
      }
    }
  }

  function handleTabSelect(sectionId: WorkbenchSectionId) {
    traceLinkGraph("workbench.audit.tabSelected", {
      sectionId,
      hasChanges,
      activeSectionId,
    });
    syncAuditSectionPreferences(sectionId);
    resetSharedWorkbenchScroll();
  }

  function handleCollapseActivePage() {
    traceLinkGraph("workbench.audit.pageCollapsed", {
      activeSectionId,
      hasChanges,
    });
    syncAuditSectionPreferences(null);
    resetSharedWorkbenchScroll();
  }

  function resetSharedWorkbenchScroll() {
    const scrollRoot = tabRef.current?.closest(".workbench-panel-body") as HTMLElement | null | undefined;
    if (scrollRoot) {
      scrollRoot.scrollTop = 0;
    }
  }

  return (
    <section ref={tabRef} className="workbench-tab audit-tab">
      <div className="workbench-tab-head audit-tab-head">
        <div className="audit-tab-title">
          <p className="eyebrow">问答</p>
          <h2>链路问答</h2>
        </div>
        <span className="workbench-session-label audit-scope-label" title={scopeLabel}>{scopeLabel}</span>
      </div>

      <div className="audit-tab-nav" role="tablist" aria-label="问答页面切换">
        {visibleSectionIds.map((sectionId) => (
          <button
            key={sectionId}
            id={`audit-page-tab-${sectionId}`}
            type="button"
            role="tab"
            aria-label={auditSectionTitle(sectionId)}
            aria-selected={activeSectionId === sectionId}
            aria-controls={`audit-page-panel-${sectionId}`}
            className={activeSectionId === sectionId ? "audit-tab-button active" : "audit-tab-button"}
            onClick={() => handleTabSelect(sectionId)}
          >
            <span>{auditSectionTitle(sectionId)}</span>
            {sectionId === "audit.candidate-changes" ? <span className="badge">{changes.length}</span> : null}
            {sectionId === "audit.investigation-threads" ? <span className="badge">{threads.length}</span> : null}
          </button>
        ))}
      </div>

      <div className="audit-tab-panel workbench-page-flow">
        {activeSectionId ? (
          <AuditPagePanel
            activeSectionId={activeSectionId}
            state={state}
            messages={messages}
            changes={changes}
            threads={threads}
            latestTurnOutcome={latestTurnOutcome}
            recentTurnOutcomes={recentTurnOutcomes}
            conversationTurnOutcomes={conversationTurnOutcomes}
            conversationThreads={conversationThreads}
            requestStatus={requestStatus}
            onQuestionDraftChange={onQuestionDraftChange}
            onQuestionModeChange={onQuestionModeChange}
            onSubmitQuestion={onSubmitQuestion}
            onRetryLastRequest={onRetryLastRequest}
            onEditFailedRequest={onEditFailedRequest}
            onSelectChange={onSelectChange}
            onConfirmChange={onConfirmChange}
            onSelectThread={onSelectThread}
            onInvestigateThread={onInvestigateThread}
            onDeferRisk={onDeferRisk}
            onAcceptRisk={onAcceptRisk}
            onDismissRisk={onDismissRisk}
            resolveArtifactText={resolveArtifactText}
            onRequestArtifact={onRequestArtifact}
            onCollapse={handleCollapseActivePage}
            onStopComposerBoundaryPropagation={stopComposerBoundaryPropagation}
          />
        ) : (
          <div className="audit-page-collapsed-state">
            <p className="muted">当前页面已收起，点击上方标签继续查看。</p>
          </div>
        )}
      </div>
    </section>
  );
}

interface AuditPagePanelProps {
  activeSectionId: WorkbenchSectionId;
  state: AuditWorkbenchState;
  messages: AuditConversationMessage[];
  changes: CandidateDraftChange[];
  threads: InvestigationThread[];
  latestTurnOutcome: InvestigationTurnOutcome | null;
  recentTurnOutcomes: InvestigationTurnOutcome[];
  conversationTurnOutcomes: InvestigationTurnOutcome[];
  conversationThreads: InvestigationThread[];
  requestStatus: ReturnType<typeof resolveEffectiveRequestState>;
  onQuestionDraftChange: (value: string) => void;
  onQuestionModeChange: (value: QaMode) => void;
  onSubmitQuestion: () => void;
  onRetryLastRequest: () => void;
  onEditFailedRequest: () => void;
  onSelectChange: (changeId: string) => void;
  onConfirmChange: (changeId: string) => void;
  onSelectThread: (threadId: string) => void;
  onInvestigateThread: (threadId: string) => void;
  onDeferRisk: (threadId: string) => void;
  onAcceptRisk: (threadId: string) => void;
  onDismissRisk: (threadId: string) => void;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
  onCollapse: () => void;
  onStopComposerBoundaryPropagation: (event: { stopPropagation: () => void }) => void;
}

function AuditPagePanel({
  activeSectionId,
  state,
  messages,
  changes,
  threads,
  latestTurnOutcome,
  recentTurnOutcomes,
  conversationTurnOutcomes,
  conversationThreads,
  requestStatus,
  onQuestionDraftChange,
  onQuestionModeChange,
  onSubmitQuestion,
  onRetryLastRequest,
  onEditFailedRequest,
  onSelectChange,
  onConfirmChange,
  onSelectThread,
  onInvestigateThread,
  onDeferRisk,
  onAcceptRisk,
  onDismissRisk,
  resolveArtifactText,
  onRequestArtifact,
  onCollapse,
  onStopComposerBoundaryPropagation,
}: AuditPagePanelProps) {
  const pageTitle = auditSectionTitle(activeSectionId);
  const latestQuestion = state.questionDraft.trim() || state.result?.question || "";
  const sourceContext = state.result?.sourceContext ?? [];
  const sourceSnippets = deduplicateSourceSnippets(sourceContext);
  const evidenceTrace = state.result?.evidenceTrace ?? [];
  const requestedMode = state.result?.requestedMode ?? requestStatus?.requestedMode ?? state.selectedMode ?? "AUTO";
  const effectiveMode = state.result?.effectiveMode ?? requestStatus?.effectiveMode ?? null;
  const requestStillRunning = requestStatus?.phase === "RUNNING";
  const hasPromptDisclosure = Boolean(
    state.result?.promptPreview?.trim() ||
      state.result?.promptPreviewArtifactId,
  );
  const failedRequest = state.qaRequestRecoveryState?.lastFailedRequest ?? null;
  const canRetryFailedRequest = Boolean(
    failedRequest && (requestStatus?.phase === "FAILED" || requestStatus?.phase === "TIMED_OUT"),
  );

  return (
    <section
      id={`audit-page-panel-${activeSectionId}`}
      role="tabpanel"
      aria-labelledby={`audit-page-tab-${activeSectionId}`}
      className="audit-page-panel workbench-card-flow stage-workbench-flat-section"
    >
      <div className="audit-page-head">
        <div className="audit-page-title">
          <p className="eyebrow">问答</p>
          <h3>{pageTitle}</h3>
        </div>
        <button type="button" className="ghost-button compact" onClick={onCollapse} aria-label="收起当前页面">
          收起
        </button>
      </div>

      {activeSectionId === "audit.request-status" ? (
        <div className="audit-page-body request-status-section-body">
          {requestStatus ? (
            <AsyncRequestBanner requestState={state.requestState} telemetryCollapsedByDefault />
          ) : (
            <div className="workbench-section-empty-state">
              <p className="muted">当前还没有请求状态。</p>
            </div>
          )}
          {canRetryFailedRequest ? (
            <div className="panel-actions">
              <button
                type="button"
                className="primary-button"
                onClick={() => {
                  traceLinkGraph("workbench.audit.retryLastRequest.clicked", {
                    activeSectionId,
                    requestPhase: requestStatus?.phase ?? null,
                    hasFailedRequest: failedRequest != null,
                  });
                  onRetryLastRequest();
                }}
              >
                直接重试
              </button>
              <button
                type="button"
                className="ghost-button"
                onClick={() => {
                  traceLinkGraph("workbench.audit.editFailedRequest.clicked", {
                    activeSectionId,
                    requestPhase: requestStatus?.phase ?? null,
                    hasFailedRequest: failedRequest != null,
                  });
                  onEditFailedRequest();
                }}
              >
                修改后重试
              </button>
            </div>
          ) : null}
          <section className="workbench-step-section">
            <h4>本次问题</h4>
            <div className="workbench-draft-single-state">
              {latestQuestion || "当前还没有待展示的问题。"}
            </div>
          </section>
          <section className="workbench-step-section">
            <h4>模式</h4>
            <dl className="request-state-meta-grid">
              <div className="request-state-meta-item">
                <dt>请求模式</dt>
                <dd>{qaModeLabel(requestedMode)}</dd>
              </div>
              <div className="request-state-meta-item">
                <dt>实际模式</dt>
                <dd>{qaModeLabel(effectiveMode)}</dd>
              </div>
              <div className="request-state-meta-item">
                <dt>是否附带源码</dt>
                <dd>{sourceSnippets.length > 0 ? "是" : "否"}</dd>
              </div>
            </dl>
          </section>
          {hasPromptDisclosure ? (
            <section className="workbench-step-section">
              <h4>提示词</h4>
              <RequestPromptDisclosure
                promptPreview={state.result?.promptPreview ?? null}
                promptPreviewArtifactId={state.result?.promptPreviewArtifactId ?? null}
                promptPreviewAvailable={hasPromptDisclosure}
                resolveArtifactText={resolveArtifactText}
                onRequestArtifact={onRequestArtifact}
              />
            </section>
          ) : null}
          <section className="workbench-step-section">
            <h4>实际附带源码</h4>
            {sourceSnippets.length > 0 ? (
              <SourceSnippetCards snippets={sourceSnippets} />
            ) : (
              <p className="muted">
                {requestStillRunning
                  ? "请求进行中，结果返回后这里会显示实际附带的源码片段。"
                  : "当前没有回传实际附带的源码片段。"}
              </p>
            )}
          </section>
          <section className="workbench-step-section">
            <h4>取证轨迹</h4>
            {evidenceTrace.length > 0 ? (
              <ul className="answer-list">
                {evidenceTrace.map((trace) => (
                  <li key={`${trace.nodeId}-${trace.filePath}-${trace.reason}`}>
                    <strong>{formatSourceSnippetLocation(trace.filePath, trace.startLine, trace.endLine)}</strong>
                    <span>{trace.reason}</span>
                    {trace.resolvedNodeId && trace.resolvedNodeId !== trace.nodeId ? (
                      <span className="muted">真实节点：{trace.resolvedNodeId}</span>
                    ) : null}
                    {(trace.mappingTrace ?? []).length > 0 ? (
                      <span className="muted">映射轨迹：{(trace.mappingTrace ?? []).join(" / ")}</span>
                    ) : null}
                    <span className="muted">{trace.includedInPrompt ? "已送入本轮 prompt" : "未送入本轮 prompt"}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">
                {requestStillRunning
                  ? "请求进行中，结果返回后这里会显示本轮真实取证轨迹。"
                  : "当前没有回传取证轨迹。"}
              </p>
            )}
          </section>
        </div>
      ) : null}

      {activeSectionId === "audit.thread" ? (
        <div className="audit-page-body">
          <section
            className={[
              "workbench-audit-thread",
              "stage-workbench-flat-block",
              !messages.length ? "is-empty" : "",
            ].join(" ").trim()}
            aria-label="问答会话"
          >
            <div className="workbench-audit-thread-body">
              <AuditConversation
                messages={messages}
                turnOutcomes={conversationTurnOutcomes}
                investigationThreads={conversationThreads}
                requestState={requestStatus}
              />
            </div>
          </section>
        </div>
      ) : null}

      {activeSectionId === "audit.composer" ? (
        <div className="audit-page-body composer-section-body">
          <div
            className="workbench-chat-input p-0 border-0"
            onPointerDownCapture={onStopComposerBoundaryPropagation}
            onMouseDownCapture={onStopComposerBoundaryPropagation}
            onDoubleClickCapture={onStopComposerBoundaryPropagation}
          >
            <label htmlFor="audit-input" className="sr-only">问答输入框</label>
            <textarea
              id="audit-input"
              aria-label="问答输入框"
              value={state.questionDraft}
              onChange={(event) => onQuestionDraftChange(event.target.value)}
              placeholder="围绕当前方法、链路或待确认变更继续提问"
            />
            <div className="workbench-chat-input-actions audit-composer-actions">
              <label className="audit-mode-select-label">
                <span className="audit-mode-select-title">模式</span>
                <span className="audit-mode-select-shell">
                  <select
                    aria-label="问答模式"
                    value={state.selectedMode ?? "AUTO"}
                    onChange={(event) => onQuestionModeChange(event.target.value as QaMode)}
                  >
                    <option value="AUTO">Auto</option>
                    <option value="ANSWER">只回答</option>
                    <option value="REVIEW">审计风险</option>
                    <option value="CHANGE">代码调整</option>
                  </select>
                </span>
              </label>
              <button type="button" className="primary-button audit-send-button" onClick={onSubmitQuestion}>
                发送
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {activeSectionId === "audit.candidate-changes" ? (
        <div className="audit-page-body candidate-changes-section-body">
          <CandidateChangeList
            changes={changes}
            selectedChangeId={state.selectedChangeId ?? null}
            onSelectChange={onSelectChange}
            onConfirmChange={onConfirmChange}
            showTitle={false}
          />
        </div>
      ) : null}

      {activeSectionId === "audit.investigation-threads" ? (
        <div className="audit-page-body">
          <InvestigationThreadList
            threads={threads}
            latestTurnOutcome={latestTurnOutcome}
            recentTurnOutcomes={recentTurnOutcomes}
            selectedThreadId={state.selectedThreadId ?? null}
            onSelectThread={onSelectThread}
            onInvestigateThread={onInvestigateThread}
            onDeferRisk={onDeferRisk}
            onAcceptRisk={onAcceptRisk}
            onDismissRisk={onDismissRisk}
            showTitle={false}
          />
        </div>
      ) : null}
    </section>
  );
}

function SourceSnippetCards({ snippets }: { snippets: SourceSnippetContext[] }) {
  return (
    <div className="source-evidence-list">
      {snippets.map((snippet, index) => {
        const fileName = sourceFileName(snippet.filePath);
        const lineLabel = sourceLineRangeLabel(snippet.startLine, snippet.endLine);
        const code = snippet.snippet?.trim();
        return (
          <article
            key={`${snippet.filePath}-${snippet.startLine ?? "line"}-${snippet.endLine ?? "line"}-${index}`}
            className="source-evidence-card"
            aria-label={`源码片段 ${index + 1} ${fileName}`}
          >
            <div className="source-evidence-card-head">
              <div className="source-evidence-title">
                <strong>{fileName}</strong>
                {lineLabel ? <span className="source-evidence-line-chip">{lineLabel}</span> : null}
              </div>
              <span className="source-evidence-node" title={snippet.nodeId}>{snippet.nodeId}</span>
            </div>
            <div className="source-evidence-path" title={snippet.filePath}>{snippet.filePath}</div>
            {code ? (
              <pre className="source-evidence-code" tabIndex={0}>
                <code>{code}</code>
              </pre>
            ) : (
              <p className="muted">该源码证据没有回传可展示片段。</p>
            )}
          </article>
        );
      })}
    </div>
  );
}

function deduplicateSourceSnippets(snippets: SourceSnippetContext[]): SourceSnippetContext[] {
  const seen = new Set<string>();
  const result: SourceSnippetContext[] = [];
  for (const snippet of snippets) {
    const key = [
      normalizeEvidencePath(snippet.filePath),
      snippet.startLine ?? "",
      snippet.endLine ?? "",
      normalizeSourceSnippetText(snippet.snippet ?? ""),
    ].join("\u0000");
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    result.push(snippet);
  }
  return result;
}

function normalizeEvidencePath(filePath: string): string {
  return filePath.trim().replace(/\\/g, "/");
}

function normalizeSourceSnippetText(snippet: string): string {
  return snippet.trim().replace(/\s+/g, " ");
}

function sourceFileName(filePath: string): string {
  const normalizedPath = normalizeEvidencePath(filePath);
  return normalizedPath.split("/").filter(Boolean).pop() ?? normalizedPath;
}

function sourceLineRangeLabel(
  startLine?: number | null,
  endLine?: number | null,
): string | null {
  if (startLine == null) {
    return null;
  }
  if (endLine != null && endLine !== startLine) {
    return `L${startLine}-L${endLine}`;
  }
  return `L${startLine}`;
}

function formatSourceSnippetLocation(
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
