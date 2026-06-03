import { useEffect, useRef, type MutableRefObject } from "react";
import {
  measureDuration,
  measureStart,
  summarizeGraph,
  traceLinkGraph,
} from "../debug";
import { sameNodeIdList } from "../graphState";
import { canNavigateToSource } from "../sourceNavigation";
import type {
  GraphPosition,
  LinkGraphEdge,
  LinkGraphNode,
  SourceNavigationState,
} from "../types";

interface UseInteractionProbeControllerArgs {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  nodesRef: MutableRefObject<LinkGraphNode[]>;
  selectionGroupNodeIds: string[];
  detailNodeId: string | null;
  detailNode: LinkGraphNode | null;
  sourceNavigationState: SourceNavigationState;
  fallbackDesignPosition: (index: number) => GraphPosition;
  handleSelectionGroupChange: (nodeIds: string[]) => void;
  handleInspectNode: (nodeId: string) => void;
  handleMoveNode: (nodeId: string, position: GraphPosition) => void;
  handleRequestSourceNavigation: (nodeId: string) => void;
}

export function useInteractionProbeController(args: UseInteractionProbeControllerArgs) {
  const interactionProbeRef = useRef<{
    scheduled: boolean;
    selection: { ids: string[]; startedAt: number } | null;
    inspect: { nodeId: string; startedAt: number } | null;
    move: { nodeId: string; position: GraphPosition; startedAt: number } | null;
    source: { nodeId: string; startedAt: number } | null;
  }>({
    scheduled: false,
    selection: null,
    inspect: null,
    move: null,
    source: null,
  });
  const interactionProbeTimerIdsRef = useRef<number[]>([]);

  useEffect(() => {
    const pendingSource = interactionProbeRef.current.source;
    if (!pendingSource || args.sourceNavigationState.nodeId !== pendingSource.nodeId) {
      return;
    }
    if (args.sourceNavigationState.phase === "IDLE" || args.sourceNavigationState.phase === "RUNNING") {
      return;
    }
    traceLinkGraph("probe.app.sourceNavigation.completed", {
      nodeId: pendingSource.nodeId,
      phase: args.sourceNavigationState.phase,
      result: args.sourceNavigationState.result ?? null,
      targetPath: args.sourceNavigationState.targetPath ?? null,
      errorMessage: args.sourceNavigationState.errorMessage ?? null,
      durationMs: measureDuration(pendingSource.startedAt),
    });
    interactionProbeRef.current.source = null;
  }, [args.sourceNavigationState]);

  useEffect(() => {
    const pendingSelection = interactionProbeRef.current.selection;
    if (!pendingSelection || !sameNodeIdList(args.selectionGroupNodeIds, pendingSelection.ids)) {
      return;
    }
    traceLinkGraph("probe.app.selection.completed", {
      nodeIds: pendingSelection.ids,
      durationMs: measureDuration(pendingSelection.startedAt),
    });
    interactionProbeRef.current.selection = null;
  }, [args.selectionGroupNodeIds]);

  useEffect(() => {
    const pendingInspect = interactionProbeRef.current.inspect;
    if (!pendingInspect || args.detailNodeId !== pendingInspect.nodeId || args.detailNode?.id !== pendingInspect.nodeId) {
      return;
    }
    traceLinkGraph("probe.app.inspect.completed", {
      nodeId: pendingInspect.nodeId,
      durationMs: measureDuration(pendingInspect.startedAt),
    });
    interactionProbeRef.current.inspect = null;
  }, [args.detailNode, args.detailNodeId]);

  useEffect(() => {
    const pendingMove = interactionProbeRef.current.move;
    if (!pendingMove) {
      return;
    }
    const movedNode = args.nodes.find((node) => node.id === pendingMove.nodeId);
    const position = movedNode?.position;
    if (!position) {
      return;
    }
    if (Math.abs(position.x - pendingMove.position.x) > 0.5 || Math.abs(position.y - pendingMove.position.y) > 0.5) {
      return;
    }
    traceLinkGraph("probe.app.move.completed", {
      nodeId: pendingMove.nodeId,
      position,
      durationMs: measureDuration(pendingMove.startedAt),
    });
    interactionProbeRef.current.move = null;
  }, [args.nodes]);

  useEffect(() => {
    return () => {
      interactionProbeTimerIdsRef.current.forEach((timerId) => window.clearTimeout(timerId));
      interactionProbeTimerIdsRef.current = [];
    };
  }, []);

  useEffect(() => {
    if (typeof window === "undefined" || window.__linkGraphInteractionProbe !== true) {
      return;
    }
    if (interactionProbeRef.current.scheduled || args.nodes.length === 0) {
      return;
    }
    const targetNode = args.nodes.find((node) => canNavigateToSource(node)) ?? args.nodes[0];
    if (!targetNode) {
      return;
    }
    interactionProbeRef.current.scheduled = true;
    traceLinkGraph("probe.app.start", {
      graph: summarizeGraph({ nodes: args.nodes, edges: args.edges }),
      targetNodeId: targetNode.id,
    });

    interactionProbeTimerIdsRef.current.forEach((timerId) => window.clearTimeout(timerId));
    interactionProbeTimerIdsRef.current = [];
    const registerProbeTimer = (callback: () => void, delayMs: number) => {
      const timerId = window.setTimeout(callback, delayMs);
      interactionProbeTimerIdsRef.current.push(timerId);
    };

    const selectionIds = args.nodes.slice(0, Math.min(3, args.nodes.length)).map((node) => node.id);
    if (selectionIds.length > 1) {
      interactionProbeRef.current.selection = {
        ids: selectionIds,
        startedAt: measureStart(),
      };
      registerProbeTimer(() => args.handleSelectionGroupChange(selectionIds), 40);
    }

    registerProbeTimer(() => {
      interactionProbeRef.current.inspect = {
        nodeId: targetNode.id,
        startedAt: measureStart(),
      };
      args.handleInspectNode(targetNode.id);
    }, 120);

    registerProbeTimer(() => {
      const latestNode = args.nodesRef.current.find((node) => node.id === targetNode.id) ?? targetNode;
      const basePosition = latestNode.position ?? args.fallbackDesignPosition(0);
      const nextPosition = {
        x: basePosition.x + 24,
        y: basePosition.y + 12,
      };
      interactionProbeRef.current.move = {
        nodeId: targetNode.id,
        position: nextPosition,
        startedAt: measureStart(),
      };
      args.handleMoveNode(targetNode.id, nextPosition);
    }, 220);

    if (canNavigateToSource(targetNode)) {
      registerProbeTimer(() => {
        interactionProbeRef.current.source = {
          nodeId: targetNode.id,
          startedAt: measureStart(),
        };
        traceLinkGraph("probe.app.sourceNavigation.start", {
          nodeId: targetNode.id,
        });
        args.handleRequestSourceNavigation(targetNode.id);
      }, 340);
    }
  }, [args.edges, args.nodes]);
}
