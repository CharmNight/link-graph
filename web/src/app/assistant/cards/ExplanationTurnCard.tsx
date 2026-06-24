import { useEffect, useMemo, useState, type MouseEvent as ReactMouseEvent } from "react";
import { Button } from "../../components/Button";
import { GraphContextMenu } from "../../components/graph/actions/GraphContextMenu";
import { formatResultEvidenceReference, stepGranularityLabel, stepKindLabel } from "../../labels";
import type { AssistantTurn, ResultEvidenceReference, StepGranularity } from "../../types";
import type { AssistantArtifactAccess } from "../assistantArtifacts";
import { AssistantFailureNotice } from "./AssistantFailureNotice";
import { AssistantPromptDisclosure } from "./AssistantPromptDisclosure";
import { AssistantTurnHeader } from "./AssistantTurnFrame";

/**
 * 讲解轮次卡片的属性集合。
 * 该卡片负责把一轮"对代码/调用链的解释"渲染成可交互的步骤列表，
 * 同时把粒度切换、历史跳转、追问等回调上抛给工作台。
 */
interface ExplanationTurnCardProps extends AssistantArtifactAccess {
  /** 当前轮次的完整数据，包含讲解结果与可能的失败信息。 */
  turn: AssistantTurn;
  /** 当前轮次在整段对话中的序号，仅用于展示。 */
  turnIndex?: number;
  /** 是否为最近一轮，用于驱动头部样式或"最新"标识。 */
  isLatest?: boolean;
  /** 用户点击证据引用时触发，由父级负责跳转到对应代码位置。 */
  onRevealReference: (reference: ResultEvidenceReference) => void;
  /** 当前被选中的步骤 id，用于高亮对应区块。 */
  selectedStepId?: string | null;
  /** 当前生效的讲解粒度，未传则回落到结果自带的粒度。 */
  currentGranularity?: StepGranularity;
  /** 粒度切换请求是否在执行中，用于禁用切换按钮。 */
  granularityRequestRunning?: boolean;
  /** 历史讲解路径标签列表，最后一条会被剔除（视为当前节点）。 */
  historyTrail?: string[];
  /** 上一讲解的展示文案，用于"返回上一讲解"按钮的 aria 描述。 */
  previousSessionLabel?: string | null;
  /** 选中某个步骤时触发，用于联动图谱或详情面板。 */
  onSelectStep?: (stepId: string) => void;
  /** 右键/菜单"定位到图中节点"时触发。 */
  onLocateStepNode?: (stepId: string) => void;
  /** 右键/菜单"编辑节点"时触发。 */
  onInspectStepNode?: (stepId: string) => void;
  /** 鼠标进入步骤时触发，常用于在图谱中高亮节点。 */
  onHoverStep?: (stepId: string) => void;
  /** 鼠标离开步骤时触发，用于清除高亮。 */
  onLeaveStep?: () => void;
  /** 切换讲解粒度时触发，工作台据此追加一轮新的解释。 */
  onGranularityChange?: (granularity: StepGranularity) => void;
  /** 点击"继续追问"时触发，把该步骤的追问问题抛给工作台。 */
  onFollowUpStep?: (stepId: string, question?: string) => void;
  /** 返回上一讲解时触发。 */
  onReturnToPrevious?: () => void;
  /** 点击历史路径中某一项时触发，按索引回溯到对应讲解。 */
  onOpenHistory?: (historyIndex: number) => void;
  /** 是否允许返回上一讲解，控制按钮可见性。 */
  canReturnToPrevious?: boolean;
}

/**
 * 步骤右键菜单的临时状态。
 * 记录被触发的步骤 id 以及鼠标位置，用于浮层定位。
 */
interface StepContextMenuState {
  stepId: string;
  x: number;
  y: number;
}

/**
 * 讲解轮次卡片组件。
 *
 * 渲染流程：
 * - 若没有结果但有失败信息，展示失败提示；
 * - 若结果尚未返回，展示占位文案；
 * - 否则渲染工具区（历史路径、返回、粒度切换）、提示词预览、步骤列表与右键菜单。
 *
 * 步骤支持选中、悬停、右键菜单（定位/编辑节点）以及追问操作。
 */
export function ExplanationTurnCard({
  turn,
  turnIndex,
  isLatest = false,
  onRevealReference,
  selectedStepId,
  currentGranularity,
  granularityRequestRunning = false,
  historyTrail = [],
  previousSessionLabel,
  onSelectStep,
  onLocateStepNode,
  onInspectStepNode,
  onHoverStep,
  onLeaveStep,
  onGranularityChange,
  onFollowUpStep,
  onReturnToPrevious,
  onOpenHistory,
  canReturnToPrevious = false,
  resolveArtifactText,
  onRequestArtifact,
}: ExplanationTurnCardProps) {
  const result = turn.explanation;
  // 步骤右键菜单的临时状态：null 表示菜单关闭
  const [contextMenu, setContextMenu] = useState<StepContextMenuState | null>(null);
  // 实际生效的粒度：优先用外部传入，其次用结果自带，最后回落到业务粒度
  const granularity = currentGranularity ?? result?.granularity ?? "BUSINESS";
  // 历史路径剔除最后一项（当前节点），仅展示可回溯的祖先节点
  const historyItems = historyTrail.slice(0, -1);
  // 可切换的三种粒度选项：业务、方法调用、代码语义
  const granularityOptions: StepGranularity[] = ["BUSINESS", "METHOD_CALL", "CODE_SEMANTIC"];
  // 粒度切换区域的辅助说明文案：生成中提示等待，否则说明切换行为
  const granularityHelpText = granularityRequestRunning
    ? "正在生成回答，完成后可重新选择粒度。"
    : "选择后会重新生成一轮解释，并追加到底部；不会改写当前回答。";

  // 右键菜单打开期间注册全局监听，点击空白处或按 Esc 时关闭菜单
  useEffect(() => {
    if (!contextMenu) {
      return undefined;
    }
    // 点击菜单自身或触发按钮以外区域时关闭，避免误关
    function closeContextMenu(event: PointerEvent) {
      const target = event.target;
      if (
        target instanceof Element &&
        (target.closest(".canvas-context-menu") || target.closest("[data-step-context-trigger='true']"))
      ) {
        return;
      }
      setContextMenu(null);
    }
    // Esc 键关闭菜单，提供键盘可访问性
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setContextMenu(null);
      }
    }
    window.addEventListener("pointerdown", closeContextMenu);
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      window.removeEventListener("pointerdown", closeContextMenu);
      window.removeEventListener("keydown", closeOnEscape);
    };
  }, [contextMenu]);

  // 根据当前菜单状态构建菜单项；菜单关闭时返回空数组以避免渲染
  const contextMenuActions = useMemo(() => {
    if (!contextMenu) {
      return [];
    }
    return [
      {
        id: "locate-explanation-step-node",
        label: "定位到图中节点",
        onSelect: () => {
          onLocateStepNode?.(contextMenu.stepId);
          setContextMenu(null);
        },
      },
      {
        id: "inspect-explanation-step-node",
        label: "编辑节点",
        onSelect: () => {
          onInspectStepNode?.(contextMenu.stepId);
          setContextMenu(null);
        },
      },
    ];
  }, [contextMenu, onInspectStepNode, onLocateStepNode]);

  /**
   * 步骤按钮的右键事件处理：阻止默认菜单并记录触发位置。
   * 当既没有"定位"也没有"编辑"回调时直接返回，不弹自定义菜单。
   */
  function handleStepContextMenu(event: ReactMouseEvent<HTMLButtonElement>, stepId: string) {
    if (!onLocateStepNode && !onInspectStepNode) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    setContextMenu({
      stepId,
      x: event.clientX,
      y: event.clientY,
    });
  }

  return (
    <article className="assistant-turn-card assistant-turn-explanation">
      <AssistantTurnHeader turn={turn} turnIndex={turnIndex} isLatest={isLatest} />
      {!result && turn.failure ? (
        <AssistantFailureNotice failure={turn.failure} />
      ) : !result ? (
        <p className="muted assistant-result-text">讲解结果尚未返回。</p>
      ) : (
        <div className="assistant-card-flow">
          <div className="assistant-explanation-tools">
            {historyItems.length > 0 && onOpenHistory ? (
              <div className="panel-actions" aria-label="讲解历史路径">
                {historyItems.map((label, index) => (
                  <Button
                    key={`${label}-${index}`}
                    compact
                    className="assistant-wrap-token"
                    aria-label={`讲解历史：${label}`}
                    onClick={() => onOpenHistory(index)}
                  >
                    {label}
                  </Button>
                ))}
              </div>
            ) : null}
            {canReturnToPrevious && onReturnToPrevious ? (
              <Button
                compact
                className="assistant-wrap-token"
                aria-label={`返回上一讲解：${previousSessionLabel ?? "上一讲解"}`}
                onClick={onReturnToPrevious}
              >
                返回上一讲解
              </Button>
            ) : null}
            {onGranularityChange ? (
              <section className="assistant-explanation-rerun" aria-label="重新解释粒度">
                <div className="assistant-explanation-rerun-copy">
                  <strong className="assistant-result-text">重新解释粒度</strong>
                  <p className="muted assistant-result-text">{granularityHelpText}</p>
                </div>
                <div className="workbench-granularity-group" aria-label="重新解释粒度选项">
                  {granularityOptions.map((option) => {
                    const label = stepGranularityLabel(option);
                    const isCurrent = granularity === option;
                    return (
                      <button
                        key={option}
                        type="button"
                        aria-label={`按${label}重新解释`}
                        className={isCurrent ? "workbench-granularity active" : "workbench-granularity"}
                        disabled={granularityRequestRunning}
                        onClick={() => onGranularityChange(option)}
                      >
                        <span>{label}</span>
                        {isCurrent ? <span className="workbench-granularity-current">当前</span> : null}
                      </button>
                    );
                  })}
                </div>
              </section>
            ) : null}
          </div>
          <AssistantPromptDisclosure
            promptPreview={result.promptPreview}
            promptPreviewArtifactId={result.promptPreviewArtifactId}
            resolveArtifactText={resolveArtifactText}
            onRequestArtifact={onRequestArtifact}
          />
          {result.steps.map((step) => (
            <section
              key={step.stepId}
              className={selectedStepId === step.stepId ? "assistant-evidence-block active" : "assistant-evidence-block"}
            >
              <button
                type="button"
                data-step-context-trigger="true"
                className="assistant-step-selector"
                onClick={() => onSelectStep?.(step.stepId)}
                onContextMenu={(event) => handleStepContextMenu(event, step.stepId)}
                onMouseEnter={() => onHoverStep?.(step.stepId)}
                onMouseLeave={() => onLeaveStep?.()}
                onFocus={() => onHoverStep?.(step.stepId)}
                onBlur={() => onLeaveStep?.()}
              >
                <strong className="assistant-result-text">{step.title}</strong>
                <span className="badge">{stepKindLabel(step.kind)}</span>
              </button>
              <p className="assistant-result-text">{step.description}</p>
              {step.evidence.length > 0 ? (
                <div className="assistant-evidence-list">
                  {step.evidence.map((finding) => (
                    <div key={finding.id} className="assistant-evidence-item">
                      <strong className="assistant-result-text">{finding.claim}</strong>
                      {finding.references.map((reference, index) => (
                        <Button
                          key={`${finding.id}:${index}`}
                          compact
                          className="assistant-wrap-token"
                          onClick={() => onRevealReference(reference)}
                        >
                          {formatResultEvidenceReference(reference)}
                        </Button>
                      ))}
                    </div>
                  ))}
                </div>
              ) : null}
              {step.followUpQuestions[0] && onFollowUpStep ? (
                <Button
                  compact
                  className="assistant-wrap-token"
                  onClick={() => onFollowUpStep(step.stepId, step.followUpQuestions[0])}
                >
                  继续追问
                </Button>
              ) : null}
            </section>
          ))}
          {contextMenu ? <GraphContextMenu x={contextMenu.x} y={contextMenu.y} actions={contextMenuActions} /> : null}
        </div>
      )}
    </article>
  );
}
