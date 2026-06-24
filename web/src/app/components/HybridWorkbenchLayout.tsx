import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from "react";

/** HybridWorkbenchLayout 组件的入参。 */
interface HybridWorkbenchLayoutProps {
  /** 大纲内容（左侧）。 */
  outline: ReactNode;
  /**
   * 阶段引导条：放在大纲上方、不受大纲折叠影响，保证工作流导航始终可见。
   */
  stageNav?: ReactNode;
  /** 图谱舞台内容（中部）。 */
  graphStage: ReactNode;
  /** 助理工作台内容（右侧）。 */
  assistantWorkbench: ReactNode;
  /** 大纲是否折叠。 */
  outlineCollapsed?: boolean;
  /** 大纲折叠状态变化回调。 */
  onOutlineCollapsedChange?: (collapsed: boolean) => void;
  /** 助理工作台宽度。 */
  workbenchWidth?: number;
  /** 助理工作台宽度变化回调。 */
  onWorkbenchWidthChange?: (width: number) => void;
  /**
   * 阶段引导条是否收起。与 outlineCollapsed 解耦：
   * - 大纲可能因图谱模式（类图/架构图）被自动折叠，但阶段导航属于工作流层，
   *   不应跟着隐藏。
   * - 仅当用户显式收起阶段条时才隐藏（目前无此入口，默认始终展开）。
   */
  stageNavCollapsed?: boolean;
}

/** 大纲展开时的宽度（像素）。 */
const EXPANDED_OUTLINE_WIDTH = 320;
/** 大纲折叠时的宽度（像素；保留窄条作为视觉锚点）。 */
const COLLAPSED_OUTLINE_WIDTH = 52;
/** 助理工作台默认宽度。 */
const DEFAULT_WORKBENCH_WIDTH = 420;
/** 助理工作台最小宽度。 */
const MIN_WORKBENCH_WIDTH = 320;
/** 助理工作台最大宽度。 */
const MAX_WORKBENCH_WIDTH = 720;

/** 把宽度约束到合法区间并取整。 */
function clampWorkbenchWidth(width: number): number {
  return Math.max(MIN_WORKBENCH_WIDTH, Math.min(MAX_WORKBENCH_WIDTH, Math.round(width)));
}

/** 从 PointerEvent 中安全取出 clientX；非有限数时返回 null。 */
function readClientX(event: PointerEvent | ReactPointerEvent<HTMLButtonElement>): number | null {
  return Number.isFinite(event.clientX) ? event.clientX : null;
}

/**
 * 混合工作台布局：左大纲 + 中图谱 + 右助理，并支持助理宽度可拖拽调整。
 *
 * 布局特点：
 * - 大纲可折叠（折叠后保留窄条 + 展开按钮）；
 * - 阶段引导条放在大纲上方，不受大纲折叠影响；
 * - 助理工作台宽度可拖拽，宽度通过 CSS 变量传给样式层；
 * - 拖拽过程通过全局 pointermove 监听实现，避免鼠标移出按钮后丢失事件。
 */
export function HybridWorkbenchLayout({
  outline,
  stageNav = null,
  graphStage,
  assistantWorkbench,
  outlineCollapsed = false,
  onOutlineCollapsedChange = () => undefined,
  workbenchWidth = DEFAULT_WORKBENCH_WIDTH,
  onWorkbenchWidthChange = () => undefined,
  stageNavCollapsed = false,
}: HybridWorkbenchLayoutProps) {
  // 是否正在拖拽助理宽度
  const [dragging, setDragging] = useState(false);
  // 拖拽起点信息：开始时的 X 坐标 + 开始时的宽度
  const dragStartRef = useRef<{ startX: number; width: number } | null>(null);
  const resolvedOutlineWidth = outlineCollapsed ? COLLAPSED_OUTLINE_WIDTH : EXPANDED_OUTLINE_WIDTH;
  const resolvedWorkbenchWidth = clampWorkbenchWidth(workbenchWidth);
  // 通过 CSS 变量把宽度传给样式层
  const layoutStyle = {
    "--outline-width": `${resolvedOutlineWidth}px`,
    "--workbench-width": `${resolvedWorkbenchWidth}px`,
  } as CSSProperties;

  // 拖拽期间注册全局 pointermove/up 监听
  useEffect(() => {
    if (!dragging) {
      return undefined;
    }

    /** 全局 pointermove：根据位移计算新宽度。 */
    function handlePointerMove(event: PointerEvent) {
      const dragStart = dragStartRef.current;
      if (!dragStart) {
        return;
      }
      const clientX = readClientX(event);
      if (clientX === null) {
        return;
      }
      // 鼠标向左移动 = clientX 减小 = 助理变宽（拖拽手柄在助理左边缘）
      onWorkbenchWidthChange(clampWorkbenchWidth(dragStart.width + dragStart.startX - clientX));
    }

    /** 全局 pointerup：结束拖拽。 */
    function handlePointerUp() {
      dragStartRef.current = null;
      setDragging(false);
    }

    window.addEventListener("pointermove", handlePointerMove);
    window.addEventListener("pointerup", handlePointerUp);
    return () => {
      window.removeEventListener("pointermove", handlePointerMove);
      window.removeEventListener("pointerup", handlePointerUp);
    };
  }, [dragging, onWorkbenchWidthChange]);

  /** 拖拽手柄 pointerdown：记录起点并捕获指针。 */
  function handleResizePointerDown(event: ReactPointerEvent<HTMLButtonElement>) {
    const startX = readClientX(event);
    if (startX === null) {
      return;
    }
    event.preventDefault();
    dragStartRef.current = {
      startX,
      width: resolvedWorkbenchWidth,
    };
    setDragging(true);
    // 捕获指针确保 move/up 事件不丢失
    event.currentTarget.setPointerCapture?.(event.pointerId);
  }

  return (
    <div
      className={[
        "hybrid-workbench-layout",
        outlineCollapsed ? "outline-collapsed" : "",
      ].filter(Boolean).join(" ")}
      data-testid="hybrid-workbench-layout"
      style={layoutStyle}
    >
      <div className="hybrid-workbench-outline-slot">
        {/* 阶段引导条优先渲染（位于大纲上方） */}
        {stageNav && !stageNavCollapsed ? (
          <div className="hybrid-workbench-stage-nav-slot">{stageNav}</div>
        ) : null}
        {outlineCollapsed ? (
          // 折叠状态：渲染窄条 + 展开按钮
          <div className="hybrid-workbench-outline-rail" aria-label="链路大纲已折叠">
            <button
              type="button"
              className="hybrid-workbench-outline-toggle"
              aria-label="展开链路大纲"
              onClick={() => onOutlineCollapsedChange(false)}
            >
              <span aria-hidden="true">&gt;</span>
            </button>
          </div>
        ) : (
          // 展开状态：渲染折叠按钮 + 大纲内容
          <>
            <button
              type="button"
              className="hybrid-workbench-outline-collapse"
              aria-label="折叠链路大纲"
              onClick={() => onOutlineCollapsedChange(true)}
            >
              <span aria-hidden="true">&lt;</span>
            </button>
            {outline}
          </>
        )}
      </div>
      <div className="hybrid-workbench-graph-slot">{graphStage}</div>
      {/* 拖拽手柄：作为 separator 角色，支持键盘可访问性 */}
      <button
        type="button"
        role="separator"
        aria-label="调整 AI 工作台宽度"
        aria-orientation="vertical"
        aria-valuemin={MIN_WORKBENCH_WIDTH}
        aria-valuemax={MAX_WORKBENCH_WIDTH}
        aria-valuenow={resolvedWorkbenchWidth}
        className={dragging ? "hybrid-workbench-resizer dragging" : "hybrid-workbench-resizer"}
        onPointerDown={handleResizePointerDown}
      />
      <div className="hybrid-workbench-assistant-slot">{assistantWorkbench}</div>
    </div>
  );
}
