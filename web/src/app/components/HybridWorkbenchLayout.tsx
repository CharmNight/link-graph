import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from "react";

interface HybridWorkbenchLayoutProps {
  outline: ReactNode;
  graphStage: ReactNode;
  workbench: ReactNode;
  outlineCollapsed?: boolean;
  onOutlineCollapsedChange?: (collapsed: boolean) => void;
  workbenchWidth?: number;
  onWorkbenchWidthChange?: (width: number) => void;
}

const EXPANDED_OUTLINE_WIDTH = 320;
const COLLAPSED_OUTLINE_WIDTH = 52;
const DEFAULT_WORKBENCH_WIDTH = 420;
const MIN_WORKBENCH_WIDTH = 320;
const MAX_WORKBENCH_WIDTH = 720;

function clampWorkbenchWidth(width: number): number {
  return Math.max(MIN_WORKBENCH_WIDTH, Math.min(MAX_WORKBENCH_WIDTH, Math.round(width)));
}

function readClientX(event: PointerEvent | ReactPointerEvent<HTMLButtonElement>): number | null {
  return Number.isFinite(event.clientX) ? event.clientX : null;
}

export function HybridWorkbenchLayout({
  outline,
  graphStage,
  workbench,
  outlineCollapsed = false,
  onOutlineCollapsedChange = () => undefined,
  workbenchWidth = DEFAULT_WORKBENCH_WIDTH,
  onWorkbenchWidthChange = () => undefined,
}: HybridWorkbenchLayoutProps) {
  const [dragging, setDragging] = useState(false);
  const dragStartRef = useRef<{ startX: number; width: number } | null>(null);
  const resolvedOutlineWidth = outlineCollapsed ? COLLAPSED_OUTLINE_WIDTH : EXPANDED_OUTLINE_WIDTH;
  const resolvedWorkbenchWidth = clampWorkbenchWidth(workbenchWidth);
  const layoutStyle = {
    "--outline-width": `${resolvedOutlineWidth}px`,
    "--workbench-width": `${resolvedWorkbenchWidth}px`,
  } as CSSProperties;

  useEffect(() => {
    if (!dragging) {
      return undefined;
    }

    function handlePointerMove(event: PointerEvent) {
      const dragStart = dragStartRef.current;
      if (!dragStart) {
        return;
      }
      const clientX = readClientX(event);
      if (clientX === null) {
        return;
      }
      onWorkbenchWidthChange(clampWorkbenchWidth(dragStart.width + dragStart.startX - clientX));
    }

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
    event.currentTarget.setPointerCapture?.(event.pointerId);
  }

  return (
    <div
      className={outlineCollapsed ? "hybrid-workbench-layout outline-collapsed" : "hybrid-workbench-layout"}
      data-testid="hybrid-workbench-layout"
      style={layoutStyle}
    >
      <div className="hybrid-workbench-outline-slot">
        {outlineCollapsed ? (
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
      <button
        type="button"
        role="separator"
        aria-label="调整阶段工作台宽度"
        aria-orientation="vertical"
        aria-valuemin={MIN_WORKBENCH_WIDTH}
        aria-valuemax={MAX_WORKBENCH_WIDTH}
        aria-valuenow={resolvedWorkbenchWidth}
        className={dragging ? "hybrid-workbench-resizer dragging" : "hybrid-workbench-resizer"}
        onPointerDown={handleResizePointerDown}
      />
      <div className="hybrid-workbench-stage-slot">{workbench}</div>
    </div>
  );
}
