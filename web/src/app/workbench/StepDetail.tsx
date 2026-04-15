import { useEffect, useState } from "react";
import { resultEvidenceLevelLabel, stepKindLabel } from "../labels";
import type { GraphBeautificationStep, ResultEvidenceReference } from "../types";

interface StepDetailProps {
  step: GraphBeautificationStep | null;
  onAddToDraft: (stepId: string) => void;
  onDrillDown: (stepId: string) => void;
  onFollowUp: (stepId: string, question?: string) => void;
  onRevealReference: (reference: ResultEvidenceReference) => void;
  showEyebrow?: boolean;
}

function formatReference(reference: ResultEvidenceReference) {
  if (reference.filePath) {
    const filename = reference.filePath.split("/").filter(Boolean).pop() ?? reference.filePath;
    if (reference.startLine && reference.endLine && reference.startLine !== reference.endLine) {
      return `${filename}:${reference.startLine}-${reference.endLine}`;
    }
    if (reference.startLine) {
      return `${filename}:${reference.startLine}`;
    }
    return filename;
  }
  return reference.nodeId ?? "当前图节点";
}

function describeReferenceNode(reference: ResultEvidenceReference) {
  const nodeId = reference.nodeId ?? "";
  if (nodeId.startsWith("method:")) {
    return "当前方法";
  }
  if (nodeId.startsWith("action:")) {
    return "方法内动作";
  }
  if (nodeId.startsWith("invoke:")) {
    return "调用点";
  }
  if (nodeId.startsWith("class:")) {
    return "关联类";
  }
  if (nodeId.startsWith("sql:")) {
    return "SQL 节点";
  }
  if (nodeId.startsWith("doc:")) {
    return "文档节点";
  }
  return reference.nodeId ? "关联图节点" : null;
}

export function StepDetail({
  step,
  onAddToDraft,
  onDrillDown,
  onFollowUp,
  onRevealReference,
  showEyebrow = true,
}: StepDetailProps) {
  const [snippetExpanded, setSnippetExpanded] = useState(false);
  const [followUpDraft, setFollowUpDraft] = useState("");

  useEffect(() => {
    setSnippetExpanded(false);
  }, [step?.stepId]);

  useEffect(() => {
    setFollowUpDraft(step?.followUpQuestions[0] ?? "");
  }, [step?.stepId, step?.followUpQuestions]);

  const snippet = step?.codeSnippet?.trim() ?? "";
  const snippetLines = snippet ? snippet.split(/\r?\n/) : [];
  const snippetCanCollapse = snippetLines.length > 6 || snippet.length > 280;
  const visibleSnippet = snippetCanCollapse && !snippetExpanded
    ? `${snippetLines.slice(0, 6).join("\n")}\n...`
    : snippet;

  if (!step) {
    return (
      <section className="workbench-step-detail">
        <p className="muted">先从左侧选择一个步骤。</p>
      </section>
    );
  }

  return (
    <section className="workbench-step-detail">
      <div className="workbench-step-detail-head">
        <div>
          {showEyebrow ? <p className="eyebrow">步骤详情</p> : null}
          <h3>{step.title}</h3>
          <p className="workbench-step-meta">{stepKindLabel(step.kind)}</p>
        </div>
        <div className="panel-actions">
          <button type="button" className="ghost-button" onClick={() => onDrillDown(step.stepId)}>
            定位被调方法
          </button>
        </div>
      </div>

      <p className="workbench-step-description">{step.description}</p>

      <section className="workbench-step-section">
        <h4>草稿备注</h4>
        <p className="muted">只会把这一步的讲解说明记入草稿备注，不会直接生成结构变更。</p>
        <div>
          <button type="button" className="ghost-button" onClick={() => onAddToDraft(step.stepId)}>
            记为草稿备注
          </button>
        </div>
      </section>

      <section className="workbench-step-section">
        <h4>对应代码</h4>
        {step.evidence.some((finding) => finding.references.length > 0) ? (
          <div className="workbench-reference-list">
            {step.evidence.flatMap((finding) => finding.references).map((reference, index) => (
              <button
                key={`${reference.nodeId ?? reference.filePath ?? "reference"}-${index}`}
                type="button"
                className="workbench-reference-card"
                onClick={() => onRevealReference(reference)}
              >
                <strong>{formatReference(reference)}</strong>
                {describeReferenceNode(reference) ? <span className="muted">{describeReferenceNode(reference)}</span> : null}
              </button>
            ))}
          </div>
        ) : (
          <p className="muted">当前步骤还没有命中具体代码位置。</p>
        )}
      </section>

      <section className="workbench-step-section">
        <h4>代码片段</h4>
        {snippet ? (
          <div className="workbench-code-snippet-shell">
            {snippetCanCollapse ? (
              <div className="workbench-code-snippet-toolbar">
                <span className="muted">
                  {snippetExpanded ? `已展开 ${snippetLines.length} 行` : `已展示 6 / ${snippetLines.length} 行`}
                </span>
                <button
                  type="button"
                  className="ghost-button compact"
                  onClick={() => setSnippetExpanded((current) => !current)}
                >
                  {snippetExpanded ? "收起代码片段" : `展开全部 ${snippetLines.length} 行`}
                </button>
              </div>
            ) : null}
            <pre className={snippetCanCollapse && !snippetExpanded ? "workbench-code-snippet is-collapsed" : "workbench-code-snippet"}>
              {visibleSnippet}
            </pre>
          </div>
        ) : (
          <p className="muted">当前步骤还没有可展示的源码片段。</p>
        )}
      </section>

      <section className="workbench-step-section">
        <h4>证据</h4>
        {step.evidence.length > 0 ? (
          <ul className="workbench-bullet-list">
            {step.evidence.map((finding) => (
              <li key={finding.id}>
                <strong>{resultEvidenceLevelLabel(finding.evidenceLevel)}</strong>
                <span>{finding.claim}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">当前步骤还没有补充证据。</p>
        )}
      </section>

      <section className="workbench-step-section">
        <h4>追问这一步</h4>
        <p className="muted">围绕当前步骤继续追问，下一轮讲解会只展开这一段的条件、代码位置和下一跳。</p>
        {step.followUpQuestions.length > 0 ? (
          <div className="workbench-follow-up-suggestions" aria-label="讲解追问建议">
            {step.followUpQuestions.map((question) => (
              <button
                key={question}
                type="button"
                className="ghost-button compact"
                onClick={() => setFollowUpDraft(question)}
              >
                {question}
              </button>
            ))}
          </div>
        ) : null}
        <label htmlFor="explanation-follow-up" className="sr-only">追问这一步输入框</label>
        <textarea
          id="explanation-follow-up"
          aria-label="追问这一步输入框"
          value={followUpDraft}
          onChange={(event) => setFollowUpDraft(event.target.value)}
          placeholder="输入你想继续追问的问题"
          className="workbench-follow-up-textarea"
        />
        <div className="panel-actions">
          <button
            type="button"
            className="primary-button"
            onClick={() => onFollowUp(step.stepId, followUpDraft.trim())}
          >
            围绕这一步继续讲解
          </button>
        </div>
      </section>
    </section>
  );
}
