import { useEffect, useRef, type RefObject } from "react";
import type { ReactFlowInstance } from "@xyflow/react";
import { traceLinkGraph } from "../debug";
import type { LinkGraphNode } from "../types";
import { VIEWPORT_TRANSITION } from "./graphFlowInteractionModel";
import {
  invocationExpansionViewportTarget,
  type InvocationExpansionViewportFocus,
} from "./graphFlowViewportModel";
import { FIT_VIEW_DELAY_MS, READABLE_FIT_MIN_ZOOM } from "./viewportConfig";

interface InvocationExpansionViewportFocusOptions {
  focus: InvocationExpansionViewportFocus | null;
  flowInstance: ReactFlowInstance | null;
  nodes: LinkGraphNode[];
  nodeViewportSize: (node: LinkGraphNode) => { width: number; height: number };
  canvasShellRef: RefObject<HTMLDivElement | null>;
  onUnavailable: () => void;
}

/** 调度新调用展开的局部视口，并把缺失端点安全回退给常规视口策略。 */
export function useInvocationExpansionViewportFocus({
  focus,
  flowInstance,
  nodes,
  nodeViewportSize,
  canvasShellRef,
  onUnavailable,
}: InvocationExpansionViewportFocusOptions): boolean {
  const handledExpansionIdRef = useRef<string | null>(null);
  const pending = focus != null && focus.expansionId !== handledExpansionIdRef.current;

  useEffect(() => {
    if (!focus) {
      handledExpansionIdRef.current = null;
      return;
    }
    if (!pending || !flowInstance) {
      return;
    }
    const timer = window.setTimeout(() => {
      const shellRect = canvasShellRef.current?.getBoundingClientRect();
      const target = invocationExpansionViewportTarget({
        focus,
        nodes,
        nodeViewportSize,
        viewportSize: { width: shellRect?.width ?? 0, height: shellRect?.height ?? 0 },
        minZoom: READABLE_FIT_MIN_ZOOM,
      });
      handledExpansionIdRef.current = focus.expansionId;
      if (!target) {
        traceLinkGraph("graphFlowSurface.viewport.invocationExpansion.skipped", {
          expansionId: focus.expansionId,
          sourceNodeId: focus.sourceNodeId,
          rootNodeId: focus.rootNodeId,
        });
        onUnavailable();
        return;
      }
      traceLinkGraph("graphFlowSurface.viewport.invocationExpansion.apply", {
        expansionId: focus.expansionId,
        sourceNodeId: focus.sourceNodeId,
        rootNodeId: focus.rootNodeId,
        bounds: target.bounds,
        center: { x: target.centerX, y: target.centerY },
        zoom: target.zoom,
      });
      flowInstance.setCenter(target.centerX, target.centerY, {
        zoom: target.zoom,
        ...VIEWPORT_TRANSITION,
      });
    }, FIT_VIEW_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [canvasShellRef, flowInstance, focus, nodeViewportSize, nodes, onUnavailable, pending]);

  return pending;
}
