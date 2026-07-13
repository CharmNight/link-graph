import { useEffect, useId, useRef, useState } from "react";
import type { SyntheticEvent, WheelEvent as ReactWheelEvent } from "react";
import { bindingLabel, confidenceLabel, nodeTypeLabel } from "../labels";
import { canNavigateToSource } from "../sourceNavigation";
import type { LinkGraphNode } from "../types";
import { Button } from "./Button";

/** 把字符串数组拼接为多行文本（用于 textarea 显示）。 */
function joinLines(values: string[]): string {
  return values.join("\n");
}

/**
 * 把多行/逗号分隔的文本解析回字符串数组。
 * 同时支持换行与逗号作为分隔符，便于用户输入。
 */
function parseLines(value: string): string[] {
  return value
    .split(/[\r\n,]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

/** 解析元数据中的多行字符串字段；空输入返回空数组。 */
function parseMetadataLines(value?: string): string[] {
  if (!value) {
    return [];
  }
  return value
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

/** 把锚点解析状态代码转为面向用户的中文说明。 */
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

/** 把 flow.kind 元数据转换为可读的作用域种类标签。 */
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

/**
 * 计算节点的"草稿签名"——把节点可编辑字段序列化为 JSON 字符串。
 *
 * 用于 useEffect 中判断"用户输入与最新选中节点是否一致"，
 * 避免选中节点变化时覆盖用户正在编辑的内容。
 */
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

/** 根据节点类型决定"符号签名"字段的展示标签。 */
function nodeSourceFieldLabel(node: LinkGraphNode): string {
  if (node.type === "FLOW_SCOPE") {
    return "流程摘要";
  }
  if (node.type === "FLOW_ACTION") {
    return "动作表达式";
  }
  return "符号签名";
}

/**
 * 只读字段展示组件。
 * 值为空时不渲染，避免出现空字段。
 */
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

/** PropertyPanel 组件的入参。 */
interface PropertyPanelProps {
  /** 当前选中节点；为 null 时不显示面板。 */
  selectedNode: LinkGraphNode | null;
  /** 保存节点修改的回调。 */
  onUpdateNode: (node: LinkGraphNode) => void;
  /** 删除节点的回调。 */
  onDeleteNode: (nodeId: string) => void;
  /** 删除节点及其子树的回调。 */
  onDeleteNodeSubtree?: (nodeId: string) => void;
  /** 请求跳转到节点源码的回调。 */
  onRequestSourceNavigation: (nodeId: string) => void;
  /** 关闭面板的回调。 */
  onClose: () => void;
}

/**
 * 节点属性编辑面板。
 *
 * 用模态抽屉形态展示选中节点的字段，允许用户编辑标题、输入、输出、注释等可写字段。
 * 源码位置、签名等只读字段只展示不可编辑，避免用户误改导致状态不一致。
 *
 * 交互细节：
 * - 选中节点切换时自动同步表单（除非用户有未保存修改）；
 * - 打开后自动聚焦标题输入框；
 * - 按 Escape 关闭面板；
 * - 阻止事件冒泡到背景，避免误关闭。
 */
export function PropertyPanel({
  selectedNode,
  onUpdateNode,
  onDeleteNode,
  onDeleteNodeSubtree = () => undefined,
  onRequestSourceNavigation,
  onClose,
}: PropertyPanelProps) {
  // 表单状态：draft 是当前编辑中的节点副本
  const [draft, setDraft] = useState<LinkGraphNode | null>(selectedNode);
  // 输入/输出用文本形式编辑（textarea），保存时再拆分为数组
  const [inputText, setInputText] = useState(() => joinLines(selectedNode?.inputs ?? []));
  const [outputText, setOutputText] = useState(() => joinLines(selectedNode?.outputs ?? []));
  // 标题输入的 ref 与 useId，用于自动聚焦与无障碍关联
  const titleId = useId();
  const titleInputRef = useRef<HTMLInputElement | null>(null);
  const draftId = draft?.id ?? null;

  // 选中节点变化时同步表单；但若用户有未保存修改则保留 draft
  useEffect(() => {
    if (!selectedNode) {
      setDraft(null);
      setInputText("");
      setOutputText("");
      return;
    }
    // 通过草稿签名判断"用户是否修改过"
    const draftMatchesSelected = nodeDraftSignature(draft) === nodeDraftSignature(selectedNode);
    // 同步条件：无 draft、ID 不一致、或 draft 与 selectedNode 完全一致（说明用户未修改）
    if (!draft || draft.id !== selectedNode.id || draftMatchesSelected) {
      setDraft(selectedNode);
      setInputText(joinLines(selectedNode.inputs ?? []));
      setOutputText(joinLines(selectedNode.outputs ?? []));
    }
  }, [selectedNode, draft]);

  // draft 变化时（特别是 ID 变化）自动聚焦标题输入
  useEffect(() => {
    if (!draftId) {
      return undefined;
    }
    // 用 setTimeout 让聚焦在渲染后发生，避免与 React 的 commit 阶段冲突
    const timerId = window.setTimeout(() => {
      titleInputRef.current?.focus();
      titleInputRef.current?.select();
    }, 0);
    return () => window.clearTimeout(timerId);
  }, [draftId]);

  // 全局 Escape 关闭面板
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

  // 无草稿（未选中节点）：不渲染
  if (!draft) {
    return null;
  }

  /** 阻止事件冒泡；用于面板内部事件不冒泡到背景层。 */
  const stopBoundaryPropagation = (event: SyntheticEvent) => {
    event.stopPropagation();
  };

  /** 吞掉背景层的滚轮事件，避免面板出现时画布跟着滚动。 */
  const swallowBackdropWheel = (event: ReactWheelEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
  };

  const canOpenSource = canNavigateToSource(draft);
  // 缺少 location 但仍可跳转（用签名兜底）
  const usesSignatureFallback = !draft.location?.trim() && canOpenSource;
  // 锚点解析相关元数据
  const anchorResolutionState = draft.metadata?.["linkGraph.anchorResolutionState"];
  const anchorResolutionHint = draft.metadata?.["linkGraph.anchorResolutionHint"]?.trim() ?? "";
  const anchorCandidates = parseMetadataLines(draft.metadata?.["linkGraph.anchorCandidates"]);
  const anchorResolutionStateText = anchorResolutionStateLabel(anchorResolutionState);
  // 流程相关元数据
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
        className="property-drawer modal-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        // 点击面板内部不关闭（避免误触）
        onClick={(event) => event.stopPropagation()}
        onPointerDownCapture={stopBoundaryPropagation}
        onWheelCapture={stopBoundaryPropagation}
        onTouchMoveCapture={stopBoundaryPropagation}
      >
        <div className="drawer-header modal-header">
          <div>
            <p className="eyebrow">编辑节点</p>
            <h2 id={titleId}>编辑节点</h2>
          </div>
          <Button onClick={onClose}>
            收起
          </Button>
        </div>

        <div className="modal-body">
          {/* 元数据 chip 行：类型 / 代码状态 / 证据 */}
          <div className="drawer-meta">
            <span className="status-pill">类型：{nodeTypeLabel(draft.type)}</span>
            <span className="status-pill">代码状态：{bindingLabel(draft.binding)}</span>
            <span className="status-pill">证据：{confidenceLabel(draft.confidence)}</span>
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

          {/* 源码锚点段：只读 */}
          <section className="panel-section" aria-label="源码锚点">
            <p className="eyebrow">源码锚点</p>
            <ReadOnlyField label="源码位置" value={draft.location} code />
            <ReadOnlyField label={sourceFieldLabel} value={draft.signature} code />
          </section>

          {/* 不可跳转且无签名兜底时给出说明 */}
          {!canOpenSource && !usesSignatureFallback ? <p className="muted">该节点当前没有可跳转的源码位置。</p> : null}

          {/* 锚点解析提示：候选方法、解析状态等 */}
          {anchorResolutionStateText || anchorResolutionHint || anchorCandidates.length > 0 ? (
            <section className="panel-section" aria-label="解析提示">
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

          {/* 流程作用域段 */}
          {isFlowScope ? (
            <section className="panel-section" aria-label="流程作用域">
              <div className="form-stack">
                <p className="eyebrow">作用域类型</p>
                <p>{flowKindLabel}</p>
              </div>
            </section>
          ) : null}

          {/* 动作节点段 */}
          {isFlowAction ? (
            <section className="panel-section" aria-label="动作节点">
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

          {/* 输入/输出：流程节点不显示（流程节点没有可编辑的输入/输出） */}
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

          {/* 注释：所有节点都可编辑 */}
          <label>
            注释
            <textarea
              aria-label="注释"
              value={draft.doc ?? ""}
              onChange={(event) => setDraft({ ...draft, doc: event.target.value })}
            />
          </label>
        </div>

        <div className="panel-actions modal-footer">
          <Button variant="primary" onClick={() => onUpdateNode(draft)}>
            保存修改
          </Button>
          <Button
            disabled={!canOpenSource}
            onClick={() => onRequestSourceNavigation(draft.id)}
          >
            打开源码
          </Button>
          <Button onClick={() => onDeleteNode(draft.id)}>
            删除节点
          </Button>
          <Button onClick={() => onDeleteNodeSubtree(draft.id)}>
            删除节点及子节点
          </Button>
        </div>
      </aside>
    </div>
  );
}
