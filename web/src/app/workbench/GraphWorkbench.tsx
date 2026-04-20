import {
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from "react";
import { traceLinkGraph } from "../debug";

const DEFAULT_WORKBENCH_WIDTH = 520;
const MIN_WORKBENCH_WIDTH = 380;
const MIN_STAGE_WIDTH = 420;
const WORKBENCH_GAP_WIDTH = 10;
const WORKBENCH_RESIZER_WIDTH = 14;

interface GraphWorkbenchProps {
  toolbar: ReactNode;
  legend?: ReactNode;
  dialogs?: ReactNode;
  stage: ReactNode;
  workbench: ReactNode;
  propertyDrawer?: ReactNode;
}

interface LayoutMetrics {
  layoutWidth: number;
  maxWorkbenchWidth: number;
  nextWorkbenchWidth: number | null;
}

function buildLayoutMetrics(
  layoutElement: HTMLElement | null,
  clientX?: number,
): LayoutMetrics | null {
  const layoutRect = layoutElement?.getBoundingClientRect();
  if (!layoutRect || layoutRect.width <= 0) {
    return null;
  }
  const layoutWidth = Math.round(layoutRect.width);
  const maxWorkbenchWidth = Math.max(MIN_WORKBENCH_WIDTH, layoutWidth - MIN_STAGE_WIDTH);
  if (clientX == null) {
    return {
      layoutWidth,
      maxWorkbenchWidth,
      nextWorkbenchWidth: null,
    };
  }
  return {
    layoutWidth,
    maxWorkbenchWidth,
    nextWorkbenchWidth: Math.round(
      Math.max(MIN_WORKBENCH_WIDTH, Math.min(maxWorkbenchWidth, layoutRect.right - clientX)),
    ),
  };
}

export function GraphWorkbench({
  toolbar,
  legend = null,
  dialogs = null,
  stage,
  workbench,
  propertyDrawer = null,
}: GraphWorkbenchProps) {
  const layoutRef = useRef<HTMLElement | null>(null);
  const stageRef = useRef<HTMLDivElement | null>(null);
  const workbenchRef = useRef<HTMLElement | null>(null);
  const draggingRef = useRef(false);
  const [workbenchWidth, setWorkbenchWidth] = useState(DEFAULT_WORKBENCH_WIDTH);
  const [layoutWidth, setLayoutWidth] = useState<number | null>(null);
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    function updateDragWidth(clientX: number) {
      if (!draggingRef.current) {
        return;
      }
      const metrics = buildLayoutMetrics(layoutRef.current, clientX);
      if (!metrics || metrics.nextWorkbenchWidth == null) {
        return;
      }
      setLayoutWidth(metrics.layoutWidth);
      setWorkbenchWidth(metrics.nextWorkbenchWidth);
    }

    function stopDragging() {
      draggingRef.current = false;
      setDragging(false);
    }

    function handlePointerMove(event: PointerEvent) {
      updateDragWidth(event.clientX);
    }

    function handleMouseMove(event: MouseEvent) {
      updateDragWidth(event.clientX);
    }

    function handleWindowResize() {
      const metrics = buildLayoutMetrics(layoutRef.current);
      if (!metrics) {
        return;
      }
      setLayoutWidth(metrics.layoutWidth);
      setWorkbenchWidth((current) =>
        Math.max(MIN_WORKBENCH_WIDTH, Math.min(metrics.maxWorkbenchWidth, current)),
      );
    }

    window.addEventListener("pointermove", handlePointerMove);
    window.addEventListener("pointerup", stopDragging);
    window.addEventListener("mousemove", handleMouseMove);
    window.addEventListener("mouseup", stopDragging);
    window.addEventListener("resize", handleWindowResize);
    handleWindowResize();
    return () => {
      window.removeEventListener("pointermove", handlePointerMove);
      window.removeEventListener("pointerup", stopDragging);
      window.removeEventListener("mousemove", handleMouseMove);
      window.removeEventListener("mouseup", stopDragging);
      window.removeEventListener("resize", handleWindowResize);
    };
  }, []);

  useEffect(() => {
    const layoutElement = layoutRef.current;
    const stageElement = stageRef.current;
    const workbenchElement = workbenchRef.current;
    if (!layoutElement || !stageElement || !workbenchElement) {
      return;
    }

    function summarizeRect(element: Element) {
      const rect = element.getBoundingClientRect();
      return {
        width: Math.round(rect.width),
        height: Math.round(rect.height),
        top: Math.round(rect.top),
        left: Math.round(rect.left),
      };
    }

    function emit(reason: string) {
      traceLinkGraph("workbench.layoutMetrics", {
        reason,
        dragging,
        workbenchWidth,
        layoutRect: summarizeRect(layoutElement),
        stageRect: summarizeRect(stageElement),
        workbenchRect: summarizeRect(workbenchElement),
        stageScrollHeight: stageElement.scrollHeight,
        workbenchScrollHeight: workbenchElement.scrollHeight,
        workbenchClientHeight: workbenchElement.clientHeight,
      });
    }

    emit("effect");
    if (typeof ResizeObserver === "undefined") {
      return;
    }
    const observer = new ResizeObserver(() => {
      const metrics = buildLayoutMetrics(layoutElement);
      if (metrics) {
        setLayoutWidth(metrics.layoutWidth);
      }
      emit("resize");
    });
    observer.observe(layoutElement);
    observer.observe(stageElement);
    observer.observe(workbenchElement);
    return () => observer.disconnect();
  }, [dragging, workbenchWidth]);

  function handleResizeKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") {
      return;
    }
    event.preventDefault();
    setWorkbenchWidth((current) => {
      const metrics = buildLayoutMetrics(layoutRef.current);
      const maxWorkbenchWidth = metrics?.maxWorkbenchWidth ?? DEFAULT_WORKBENCH_WIDTH + MIN_STAGE_WIDTH;
      if (metrics) {
        setLayoutWidth(metrics.layoutWidth);
      }
      const delta = event.key === "ArrowLeft" ? 24 : -24;
      return Math.max(MIN_WORKBENCH_WIDTH, Math.min(maxWorkbenchWidth, current + delta));
    });
  }

  function handleResizePointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    const metrics = buildLayoutMetrics(layoutRef.current);
    if (metrics) {
      setLayoutWidth(metrics.layoutWidth);
    }
    draggingRef.current = true;
    setDragging(true);
    event.currentTarget.setPointerCapture?.(event.pointerId);
  }

  function handleResizeMouseDown() {
    const metrics = buildLayoutMetrics(layoutRef.current);
    if (metrics) {
      setLayoutWidth(metrics.layoutWidth);
    }
    draggingRef.current = true;
    setDragging(true);
  }

  const effectiveMaxWorkbenchWidth = layoutWidth == null
    ? DEFAULT_WORKBENCH_WIDTH + MIN_STAGE_WIDTH
    : Math.max(MIN_WORKBENCH_WIDTH, layoutWidth - MIN_STAGE_WIDTH);
  const stageWidth = layoutWidth == null
    ? null
    : Math.max(
      MIN_STAGE_WIDTH,
      layoutWidth - workbenchWidth - WORKBENCH_GAP_WIDTH - WORKBENCH_RESIZER_WIDTH,
    );

  return (
    <div className="app-shell graph-workbench" data-testid="graph-workbench">
      {toolbar}
      {legend}
      {dialogs}

      <main ref={layoutRef} className="workspace-stage workbench-layout">
        <div
          ref={stageRef}
          className="workspace-stage-content"
          style={stageWidth == null ? undefined : { width: `${stageWidth}px` }}
        >
          {stage}
        </div>
        <div
          role="separator"
          tabIndex={0}
          aria-label="调整工作台宽度"
          aria-orientation="vertical"
          aria-valuemin={MIN_WORKBENCH_WIDTH}
          aria-valuemax={effectiveMaxWorkbenchWidth}
          aria-valuenow={workbenchWidth}
          className={dragging ? "workbench-resizer dragging" : "workbench-resizer"}
          onPointerDown={handleResizePointerDown}
          onMouseDown={handleResizeMouseDown}
          onKeyDown={handleResizeKeyDown}
        />
        <aside
          ref={workbenchRef}
          className="workbench-panel"
          aria-label="工作台"
          style={{ width: `${workbenchWidth}px` }}
        >
          {workbench}
        </aside>
      </main>

      {propertyDrawer}
    </div>
  );
}
