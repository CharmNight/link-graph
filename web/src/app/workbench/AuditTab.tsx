import { useEffect, useState } from "react";
import { resolveEffectiveRequestState, AsyncRequestBanner } from "../components/AsyncRequestBanner";
import { traceLinkGraph } from "../debug";
import { deriveConversationInvestigationThreads, deriveInvestigationThreads } from "../investigationThreads";
import type {
  AuditConversationMessage,
  AuditWorkbenchState,
  CandidateDraftChange,
  InvestigationThread,
  InvestigationTurnOutcome,
  WorkbenchSectionId,
  WorkbenchSectionPreferences,
} from "../types";
import { AUDIT_WORKBENCH_SECTION_IDS } from "./workbenchSections";
import { AuditConversation } from "./AuditConversation";
import { CandidateChangeList } from "./CandidateChangeList";
import { InvestigationThreadList } from "./InvestigationThreadList";

const DEFAULT_ACTIVE_AUDIT_SECTION: WorkbenchSectionId = "audit.composer";

const AUDIT_SECTION_META: Record<WorkbenchSectionId, { title: string }> = {
  "audit.request-status": { title: "请求状态" },
  "audit.thread": { title: "问答会话" },
  "audit.composer": { title: "继续提问" },
  "audit.candidate-changes": { title: "待确认变更" },
  "audit.investigation-threads": { title: "风险线程" },
};

interface AuditTabProps {
  state: AuditWorkbenchState;
  onQuestionDraftChange: (value: string) => void;
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
  sectionPreferences,
  onSectionPreferenceChange = () => undefined,
}: AuditTabProps) {
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
  }

  function handleCollapseActivePage() {
    traceLinkGraph("workbench.audit.pageCollapsed", {
      activeSectionId,
      hasChanges,
    });
    syncAuditSectionPreferences(null);
  }

  return (
    <section className="workbench-tab audit-tab block overflow-auto m-scrollbar">
      <div className="workbench-tab-head audit-tab-head mb-10px">
        <div className="audit-tab-title">
          <p className="eyebrow">问答</p>
          <h2>链路问答</h2>
        </div>
        <span className="workbench-session-label audit-scope-label" title={scopeLabel}>{scopeLabel}</span>
      </div>

      <div className="audit-tab-nav mb-10px" role="tablist" aria-label="问答页面切换">
        {visibleSectionIds.map((sectionId) => (
          <button
            key={sectionId}
            id={`audit-page-tab-${sectionId}`}
            type="button"
            role="tab"
            aria-label={AUDIT_SECTION_META[sectionId].title}
            aria-selected={activeSectionId === sectionId}
            aria-controls={`audit-page-panel-${sectionId}`}
            className={activeSectionId === sectionId ? "audit-tab-button active" : "audit-tab-button"}
            onClick={() => handleTabSelect(sectionId)}
          >
              <span>{AUDIT_SECTION_META[sectionId].title}</span>
              {sectionId === "audit.candidate-changes" ? <span className="badge">{changes.length}</span> : null}
              {sectionId === "audit.investigation-threads" ? <span className="badge">{threads.length}</span> : null}
            </button>
          ))}
      </div>

      <div className="audit-tab-panel">
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
  onCollapse,
  onStopComposerBoundaryPropagation,
}: AuditPagePanelProps) {
  const pageTitle = AUDIT_SECTION_META[activeSectionId].title;
  const latestQuestion = state.questionDraft.trim() || state.result?.question || "";
  const sourceContext = state.result?.sourceContext ?? [];
  const evidenceTrace = state.result?.evidenceTrace ?? [];
  const requestStillRunning = requestStatus?.phase === "RUNNING";
  const failedRequest = state.qaRequestRecoveryState?.lastFailedRequest ?? null;
  const canRetryFailedRequest = Boolean(
    failedRequest && (requestStatus?.phase === "FAILED" || requestStatus?.phase === "TIMED_OUT"),
  );

  return (
    <section
      id={`audit-page-panel-${activeSectionId}`}
      role="tabpanel"
      aria-labelledby={`audit-page-tab-${activeSectionId}`}
      className="audit-page-panel"
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
              <button type="button" className="primary-button" onClick={onRetryLastRequest}>
                直接重试
              </button>
              <button type="button" className="ghost-button" onClick={onEditFailedRequest}>
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
            <h4>实际附带源码</h4>
            {sourceContext.length > 0 ? (
              <ul className="answer-list">
                {sourceContext.map((snippet) => (
                  <li key={`${snippet.nodeId}-${snippet.filePath}-${snippet.startLine ?? "line"}`}>
                    <strong>{formatSourceSnippetLocation(snippet.filePath, snippet.startLine, snippet.endLine)}</strong>
                    {snippet.snippet?.trim() ? <span className="muted">{snippet.snippet.trim()}</span> : null}
                  </li>
                ))}
              </ul>
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
        <div className="audit-page-body">
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
            <div className="workbench-chat-input-actions">
              <button type="button" className="primary-button" onClick={onSubmitQuestion}>
                发送
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {activeSectionId === "audit.candidate-changes" ? (
        <div className="audit-page-body">
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
