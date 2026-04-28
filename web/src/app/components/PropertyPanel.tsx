import { useEffect, useId, useRef, useState } from "react";
import type { SyntheticEvent, WheelEvent as ReactWheelEvent } from "react";
import { bindingStatusLabel, certaintyLabel, nodeTypeLabel } from "../labels";
import { canNavigateToSource } from "../sourceNavigation";
import type { LinkGraphNode } from "../types";

function joinLines(values: string[]): string {
  return values.join("\n");
}

function parseLines(value: string): string[] {
  return value
    .split(/[\r\n,]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

function parseMetadataLines(value?: string): string[] {
  if (!value) {
    return [];
  }
  return value
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

function anchorResolutionStateLabel(state?: string): string | null {
  switch (state) {
    case "AMBIGUOUS":
      return "当前引用命中多个候选方法。";
    case "NOT_FOUND":
      return "当前引用未找到可绑定的目标方法。";
    case "NO_EXACT_MATCH":
      return "当前引用未命中精确的方法签名。";
    default:
      return state?.trim() ? state : null;
  }
}

function flowScopeKindLabel(kind?: string): string {
  switch (kind) {
    case "LAMBDA":
      return "Lambda 作用域";
    case "IF":
      return "条件分支";
    case "FOREACH":
      return "For-Each 作用域";
    case "FOR":
      return "For 循环";
    case "WHILE":
      return "While 循环";
    case "DO_WHILE":
      return "Do-While 循环";
    default:
      return "流程作用域";
  }
}

function nodeDraftSignature(node: LinkGraphNode | null | undefined): string {
  if (!node) {
    return "";
  }
  return JSON.stringify({
    id: node.id,
    title: node.title,
    location: node.location ?? "",
    signature: node.signature ?? "",
    inputs: node.inputs,
    outputs: node.outputs,
    doc: node.doc ?? "",
    metadata: node.metadata ?? {},
  });
}

function nodeSourceFieldLabel(node: LinkGraphNode): string {
  if (node.type === "FLOW_SCOPE") {
    return "流程摘要";
  }
  if (node.type === "FLOW_ACTION") {
    return "动作表达式";
  }
  return "符号签名";
}

function ReadOnlyField({
  label,
  value,
  code = false,
}: {
  label: string;
  value?: string;
  code?: boolean;
}) {
  if (!value?.trim()) {
    return null;
  }
  return (
    <div className="form-stack">
      <p className="eyebrow">{label}</p>
      <p className={code ? "selected-summary-code" : undefined}>{value}</p>
    </div>
  );
}

interface PropertyPanelProps {
  selectedNode: LinkGraphNode | null;
  onUpdateNode: (node: LinkGraphNode) => void;
  onDeleteNode: (nodeId: string) => void;
  onDeleteNodeSubtree?: (nodeId: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onClose: () => void;
}

export function PropertyPanel({
  selectedNode,
  onUpdateNode,
  onDeleteNode,
  onDeleteNodeSubtree = () => undefined,
  onRequestSourceNavigation,
  onClose,
}: PropertyPanelProps) {
  const [draft, setDraft] = useState<LinkGraphNode | null>(selectedNode);
  const [inputText, setInputText] = useState(() => joinLines(selectedNode?.inputs ?? []));
  const [outputText, setOutputText] = useState(() => joinLines(selectedNode?.outputs ?? []));
  const titleId = useId();
  const titleInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!selectedNode) {
      setDraft(null);
      setInputText("");
      setOutputText("");
      return;
    }
    const draftMatchesSelected = nodeDraftSignature(draft) === nodeDraftSignature(selectedNode);
    if (!draft || draft.id !== selectedNode.id || draftMatchesSelected) {
      setDraft(selectedNode);
      setInputText(joinLines(selectedNode.inputs ?? []));
      setOutputText(joinLines(selectedNode.outputs ?? []));
    }
  }, [selectedNode, draft]);

  useEffect(() => {
    if (!draft) {
      return undefined;
    }
    const timerId = window.setTimeout(() => {
      titleInputRef.current?.focus();
      titleInputRef.current?.select();
    }, 0);
    return () => window.clearTimeout(timerId);
  }, [draft?.id]);

  useEffect(() => {
    if (!draft) {
      return undefined;
    }
    const previousBodyOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousBodyOverflow;
    };
  }, [draft]);

  useEffect(() => {
    if (!draft) {
      return undefined;
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [draft, onClose]);

  if (!draft) {
    return null;
  }

  const stopBoundaryPropagation = (event: SyntheticEvent) => {
    event.stopPropagation();
  };

  const swallowBackdropWheel = (event: ReactWheelEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
  };

  const canOpenSource = canNavigateToSource(draft);
  const usesSignatureFallback = !draft.location?.trim() && canOpenSource;
  const anchorResolutionState = draft.metadata?.["linkGraph.anchorResolutionState"];
  const anchorResolutionHint = draft.metadata?.["linkGraph.anchorResolutionHint"]?.trim() ?? "";
  const anchorCandidates = parseMetadataLines(draft.metadata?.["linkGraph.anchorCandidates"]);
  const anchorResolutionStateText = anchorResolutionStateLabel(anchorResolutionState);
  const isFlowScope = draft.type === "FLOW_SCOPE";
  const isFlowAction = draft.type === "FLOW_ACTION";
  const flowKindLabel = flowScopeKindLabel(draft.metadata?.["flow.kind"]);
  const actionAnchorMethod = draft.metadata?.["flow.anchorMethod"]?.trim() ?? "";
  const actionStartOffset = draft.metadata?.["source.startOffset"]?.trim() ?? "";
  const actionEndOffset = draft.metadata?.["source.endOffset"]?.trim() ?? "";
  const sourceFieldLabel = nodeSourceFieldLabel(draft);

  return (
    <div
      className="property-drawer-backdrop"
      onClick={onClose}
      onPointerDownCapture={stopBoundaryPropagation}
      onWheelCapture={swallowBackdropWheel}
      onTouchMoveCapture={stopBoundaryPropagation}
    >
      <aside
        className="property-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(event) => event.stopPropagation()}
        onPointerDownCapture={stopBoundaryPropagation}
        onWheelCapture={stopBoundaryPropagation}
        onTouchMoveCapture={stopBoundaryPropagation}
      >
        <div className="drawer-header">
          <div>
            <p className="eyebrow">编辑节点</p>
            <h2 id={titleId}>编辑节点</h2>
          </div>
          <button type="button" className="ghost-button" onClick={onClose}>
            收起
          </button>
        </div>

        <div className="drawer-meta">
          <span className="node-type-chip">类型：{nodeTypeLabel(draft.type)}</span>
          <span className="node-type-chip">代码状态：{bindingStatusLabel(draft.bindingStatus)}</span>
          <span className="node-type-chip">证据：{certaintyLabel(draft.certainty)}</span>
        </div>

        <label>
          标题
          <input
            ref={titleInputRef}
            aria-label="标题"
            value={draft.title}
            onChange={(event) => setDraft({ ...draft, title: event.target.value })}
          />
        </label>

        <section className="panel-section" aria-label="源码锚点">
          <p className="eyebrow">源码锚点</p>
          <ReadOnlyField label="源码位置" value={draft.location} code />
          <ReadOnlyField label={sourceFieldLabel} value={draft.signature} code />
        </section>

        {!canOpenSource ? <p className="muted">该节点当前没有可跳转的源码位置。</p> : null}
        {usesSignatureFallback ? <p className="muted">当前将按方法/类签名在 IDEA 中定位源码。</p> : null}

        {anchorResolutionStateText || anchorResolutionHint || anchorCandidates.length > 0 ? (
          <section className="panel-section" aria-label="解析提示">
            <p className="eyebrow">解析提示</p>
            {anchorResolutionStateText ? <p className="muted">{anchorResolutionStateText}</p> : null}
            {anchorResolutionHint ? <p>{anchorResolutionHint}</p> : null}
            {anchorCandidates.length > 0 ? (
              <div className="form-stack">
                <p className="eyebrow">候选方法</p>
                <ul className="selected-summary-list">
                  {anchorCandidates.map((candidate) => (
                    <li key={candidate} className="selected-summary-code">
                      {candidate}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </section>
        ) : null}

        {isFlowScope ? (
          <section className="panel-section" aria-label="流程说明">
            <p className="eyebrow">流程说明</p>
            <p className="muted">该节点不是独立方法，而是当前方法里的流程作用域容器。</p>
            <div className="form-stack">
              <p className="eyebrow">作用域类型</p>
              <p>{flowKindLabel}</p>
            </div>
          </section>
        ) : null}

        {isFlowAction ? (
          <section className="panel-section" aria-label="动作说明">
            <p className="eyebrow">动作说明</p>
            <p className="muted">该节点表示当前方法中的内部执行动作，用来补齐代码阅读顺序，不等同于独立方法定义。</p>
            {actionAnchorMethod ? (
              <div className="form-stack">
                <p className="eyebrow">所属方法</p>
                <p className="selected-summary-code">{actionAnchorMethod}</p>
              </div>
            ) : null}
            {actionStartOffset || actionEndOffset ? (
              <div className="form-stack">
                <p className="eyebrow">源码偏移</p>
                <p className="selected-summary-code">
                  {actionStartOffset || "?"} - {actionEndOffset || "?"}
                </p>
              </div>
            ) : null}
          </section>
        ) : null}

        {!isFlowScope && !isFlowAction ? (
          <label>
            输入
            <textarea
              aria-label="输入"
              value={inputText}
              onChange={(event) => {
                const nextValue = event.target.value;
                setInputText(nextValue);
                setDraft({ ...draft, inputs: parseLines(nextValue) });
              }}
            />
          </label>
        ) : null}

        {!isFlowScope && !isFlowAction ? (
          <label>
            输出
            <textarea
              aria-label="输出"
              value={outputText}
              onChange={(event) => {
                const nextValue = event.target.value;
                setOutputText(nextValue);
                setDraft({ ...draft, outputs: parseLines(nextValue) });
              }}
            />
          </label>
        ) : null}

        <label>
          注释
          <textarea
            aria-label="注释"
            value={draft.doc ?? ""}
            onChange={(event) => setDraft({ ...draft, doc: event.target.value })}
          />
        </label>

        <div className="panel-actions">
          <button type="button" className="primary-button" onClick={() => onUpdateNode(draft)}>
            保存修改
          </button>
          <button
            type="button"
            className="ghost-button"
            disabled={!canOpenSource}
            onClick={() => onRequestSourceNavigation(draft.id)}
          >
            打开源码
          </button>
          <button type="button" className="ghost-button" onClick={() => onDeleteNode(draft.id)}>
            删除节点
          </button>
          <button type="button" className="ghost-button" onClick={() => onDeleteNodeSubtree(draft.id)}>
            删除节点及子节点
          </button>
        </div>
      </aside>
    </div>
  );
}
