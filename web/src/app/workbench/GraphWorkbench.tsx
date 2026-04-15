import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { traceLinkGraph } from "../debug";

const DEFAULT_WORKBENCH_WIDTH = 520;
const MIN_WORKBENCH_WIDTH = 380;
const MIN_STAGE_WIDTH = 420;

interface GraphWorkbenchProps {
  toolbar: ReactNode;
  legend?: ReactNode;
  dialogs?: ReactNode;
  stage: ReactNode;
  workbench: ReactNode;
  propertyDrawer?: ReactNode;
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
  const [workbenchWidth, setWorkbenchWidth] = useState(DEFAULT_WORKBENCH_WIDTH);
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    function resolveNextWidth(clientX: number) {
      const layoutRect = layoutRef.current?.getBoundingClientRect();
      if (!layoutRect) {
        return null;
      }
      const maxWidth = Math.max(MIN_WORKBENCH_WIDTH, layoutRect.width - MIN_STAGE_WIDTH);
      const nextWidth = Math.max(MIN_WORKBENCH_WIDTH, Math.min(maxWidth, layoutRect.right - clientX));
      return Math.round(nextWidth);
    }

    function handleMouseMove(event: MouseEvent) {
      if (!dragging) {
        return;
      }
      const nextWidth = resolveNextWidth(event.clientX);
      if (nextWidth != null) {
        setWorkbenchWidth(nextWidth);
      }
    }

    function handleMouseUp() {
      setDragging(false);
    }

    function handleWindowResize() {
      const layoutRect = layoutRef.current?.getBoundingClientRect();
      if (!layoutRect) {
        return;
      }
      const maxWidth = Math.max(MIN_WORKBENCH_WIDTH, layoutRect.width - MIN_STAGE_WIDTH);
      setWorkbenchWidth((current) => Math.max(MIN_WORKBENCH_WIDTH, Math.min(maxWidth, current)));
    }

    window.addEventListener("mousemove", handleMouseMove);
    window.addEventListener("mouseup", handleMouseUp);
    window.addEventListener("resize", handleWindowResize);
    handleWindowResize();
    return () => {
      window.removeEventListener("mousemove", handleMouseMove);
      window.removeEventListener("mouseup", handleMouseUp);
      window.removeEventListener("resize", handleWindowResize);
    };
  }, [dragging]);

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
    const observer = new ResizeObserver(() => emit("resize"));
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
      const delta = event.key === "ArrowLeft" ? 24 : -24;
      const layoutWidth = layoutRef.current?.getBoundingClientRect().width ?? DEFAULT_WORKBENCH_WIDTH + MIN_STAGE_WIDTH;
      const maxWidth = Math.max(MIN_WORKBENCH_WIDTH, layoutWidth - MIN_STAGE_WIDTH);
      return Math.max(MIN_WORKBENCH_WIDTH, Math.min(maxWidth, current + delta));
    });
  }

  return (
    <div className="app-shell graph-workbench" data-testid="graph-workbench">
      {toolbar}
      {legend}
      {dialogs}

      <main ref={layoutRef} className="workspace-stage workbench-layout">
        <div ref={stageRef} className="workspace-stage-content">
          {stage}
        </div>
        <div
          role="separator"
          tabIndex={0}
          aria-label="调整工作台宽度"
          aria-orientation="vertical"
          aria-valuemin={MIN_WORKBENCH_WIDTH}
          aria-valuemax={Math.max(MIN_WORKBENCH_WIDTH, (layoutRef.current?.getBoundingClientRect().width ?? DEFAULT_WORKBENCH_WIDTH + MIN_STAGE_WIDTH) - MIN_STAGE_WIDTH)}
          aria-valuenow={workbenchWidth}
          className={dragging ? "workbench-resizer dragging" : "workbench-resizer"}
          onMouseDown={() => setDragging(true)}
          onKeyDown={handleResizeKeyDown}
        />
        <aside ref={workbenchRef} className="workbench-panel" aria-label="工作台" style={{ width: `${workbenchWidth}px` }}>
          {workbench}
        </aside>
      </main>

      {propertyDrawer}
    </div>
  );
}
