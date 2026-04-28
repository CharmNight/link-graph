package com.charmnight.linkgraph.ui

object GraphBrowserDebugProbe {
    fun buildRuntimeProbeScript(reason: String): String {
        val escapedReason = escapeJsString(reason)
        return """
            (function() {
              const reason = "$escapedReason";
              const delays = [0, 120, 480, 1200, 2500];
              const round = (value) => Number.isFinite(value) ? Math.round(value) : null;
              const summarizeRect = (element) => {
                if (!element || !element.getBoundingClientRect) {
                  return null;
                }
                const rect = element.getBoundingClientRect();
                return {
                  width: Math.round(rect.width),
                  height: Math.round(rect.height),
                  top: Math.round(rect.top),
                  left: Math.round(rect.left),
                  right: Math.round(rect.right),
                  bottom: Math.round(rect.bottom)
                };
              };
              const summarizeBoxMetrics = (element) => {
                if (!element) {
                  return null;
                }
                return {
                  clientWidth: Number.isFinite(element.clientWidth) ? Math.round(element.clientWidth) : null,
                  clientHeight: Number.isFinite(element.clientHeight) ? Math.round(element.clientHeight) : null,
                  scrollWidth: Number.isFinite(element.scrollWidth) ? Math.round(element.scrollWidth) : null,
                  scrollHeight: Number.isFinite(element.scrollHeight) ? Math.round(element.scrollHeight) : null,
                  offsetWidth: Number.isFinite(element.offsetWidth) ? Math.round(element.offsetWidth) : null,
                  offsetHeight: Number.isFinite(element.offsetHeight) ? Math.round(element.offsetHeight) : null
                };
              };
              const summarizeComputedStyle = (element, properties) => {
                if (!element || typeof window.getComputedStyle !== "function") {
                  return null;
                }
                try {
                  const computed = window.getComputedStyle(element);
                  return properties.reduce((result, propertyName) => {
                    result[propertyName] = computed.getPropertyValue(propertyName) || null;
                    return result;
                  }, {});
                } catch (error) {
                  return { error: String(error) };
                }
              };
              const summarizeSvgRect = (rect) => {
                if (!rect) {
                  return null;
                }
                return {
                  x: round(rect.x),
                  y: round(rect.y),
                  width: round(rect.width),
                  height: round(rect.height)
                };
              };
              const summarizePoint = (point) => {
                if (!point) {
                  return null;
                }
                return {
                  x: round(point.x),
                  y: round(point.y)
                };
              };
              const summarizeFlowPoint = (point, flowHostRect, viewportState) => {
                if (!point || !flowHostRect || !viewportState || !Number.isFinite(viewportState.scale) || Math.abs(viewportState.scale) < 0.0001) {
                  return null;
                }
                return {
                  x: round((point.x - flowHostRect.left - viewportState.translateX) / viewportState.scale),
                  y: round((point.y - flowHostRect.top - viewportState.translateY) / viewportState.scale)
                };
              };
              const summarizeRectCenter = (element) => {
                if (!element || !element.getBoundingClientRect) {
                  return null;
                }
                const rect = element.getBoundingClientRect();
                return {
                  x: round((rect.left + rect.right) / 2),
                  y: round((rect.top + rect.bottom) / 2)
                };
              };
              const parseViewportState = (viewport) => {
                const style = viewport?.getAttribute ? (viewport.getAttribute("style") ?? "") : "";
                const matched = /translate\(([-\d.]+)px,\s*([-\d.]+)px\)\s*scale\(([-\d.]+)\)/.exec(style);
                if (!matched) {
                  return null;
                }
                const translateX = Number.parseFloat(matched[1] ?? "");
                const translateY = Number.parseFloat(matched[2] ?? "");
                const scale = Number.parseFloat(matched[3] ?? "");
                if (!Number.isFinite(translateX) || !Number.isFinite(translateY) || !Number.isFinite(scale)) {
                  return null;
                }
                return {
                  translateX,
                  translateY,
                  scale
                };
              };
              const summarizeIntersection = (left, right) => {
                if (!left || !right) {
                  return null;
                }
                const width = Math.max(0, Math.min(left.right, right.right) - Math.max(left.left, right.left));
                const height = Math.max(0, Math.min(left.bottom, right.bottom) - Math.max(left.top, right.top));
                return {
                  width: round(width),
                  height: round(height),
                  area: round(width * height)
                };
              };
              const summarizeVisualViewport = () => {
                if (!window.visualViewport) {
                  return null;
                }
                return {
                  width: round(window.visualViewport.width),
                  height: round(window.visualViewport.height),
                  offsetLeft: round(window.visualViewport.offsetLeft),
                  offsetTop: round(window.visualViewport.offsetTop),
                  pageLeft: round(window.visualViewport.pageLeft),
                  pageTop: round(window.visualViewport.pageTop),
                  scale: round(window.visualViewport.scale)
                };
              };
              const emitPayload = (payload) => {
                if (typeof window.linkGraphDebugTrace === "function") {
                  window.linkGraphDebugTrace(payload);
                  return;
                }
                window.__linkGraphTraceBuffer = Array.isArray(window.__linkGraphTraceBuffer) ? window.__linkGraphTraceBuffer : [];
                window.__linkGraphTraceBuffer.push(payload);
              };
              const emitEvent = (event, payload) => {
                emitPayload(JSON.stringify({
                  time: new Date().toISOString(),
                  event,
                  payload: payload ?? null
                }));
              };
              const intersects = (left, right) => {
                if (!left || !right) {
                  return false;
                }
                return !(left.right < right.left || left.left > right.right || left.bottom < right.top || left.top > right.bottom);
              };
              const summarizeHandle = (element, flowHostRect, viewportState) => {
                const screenCenter = summarizeRectCenter(element);
                return {
                  handleId: element?.dataset?.handleid ?? null,
                  nodeId: element?.dataset?.nodeid ?? null,
                  handlePosition: element?.dataset?.handlepos ?? null,
                  rect: summarizeRect(element),
                  screenCenter,
                  flowPoint: summarizeFlowPoint(screenCenter, flowHostRect, viewportState)
                };
              };
              const summarizeFlowNode = (element, flowHostRect, viewportState) => {
                const shell = element?.querySelector(".flowchart-react-node, .fact-graph-react-node, .resource-relation-react-node");
                const card = shell?.querySelector(".flow-node-card");
                const handles = shell
                  ? Array.from(shell.querySelectorAll(".react-flow__handle")).map((handle) => summarizeHandle(handle, flowHostRect, viewportState))
                  : [];
                return {
                  nodeId: element?.dataset?.id ?? element?.dataset?.nodeid ?? null,
                  selected: element?.classList?.contains("selected") === true,
                  wrapperRect: summarizeRect(element),
                  wrapperCenter: summarizeRectCenter(element),
                  wrapperCenterFlowPoint: summarizeFlowPoint(summarizeRectCenter(element), flowHostRect, viewportState),
                  shellRect: summarizeRect(shell),
                  cardRect: summarizeRect(card),
                  handleRects: handles
                };
              };
              const summarizeDecisionNode = (element, flowHostRect, viewportState) => {
                const summary = summarizeFlowNode(element, flowHostRect, viewportState);
                return {
                  ...summary,
                  shellRect: summary.shellRect,
                  cardRect: summary.cardRect
                };
              };
              const summarizeFlowNodeGeometry = (element, flowHostRect, viewportState) => ({
                nodeId: element?.dataset?.id ?? element?.dataset?.nodeid ?? null,
                selected: element?.classList?.contains("selected") === true,
                className: element?.className ?? null,
                ...summarizeFlowNode(element, flowHostRect, viewportState)
              });
              const summarizeEdge = (element) => {
                const path = element?.querySelector(".react-flow__edge-path");
                const length = path?.getTotalLength ? path.getTotalLength() : null;
                const safeLength = typeof length === "number" && Number.isFinite(length) ? length : null;
                return {
                  edgeId: element?.dataset?.id ?? null,
                  pathD: path?.getAttribute("d") ?? null,
                  pathBox: (() => {
                    try {
                      return path?.getBBox ? summarizeSvgRect(path.getBBox()) : null;
                    } catch (error) {
                      return { error: String(error) };
                    }
                  })(),
                  startPoint: safeLength !== null && path?.getPointAtLength ? summarizePoint(path.getPointAtLength(0)) : null,
                  endPoint: safeLength !== null && path?.getPointAtLength
                    ? summarizePoint(path.getPointAtLength(Math.max(safeLength - 0.01, 0)))
                    : null
                };
              };
              const summarizeEdgeGeometry = (element) => ({
                edgeId: element?.dataset?.id ?? null,
                className: element?.className ?? null,
                ...summarizeEdge(element)
              });
              const summarizeNodeVisual = (element) => {
                if (!element) {
                  return null;
                }
                const shell = element.querySelector(".flowchart-react-node, .fact-graph-react-node, .resource-relation-react-node");
                const card = shell?.querySelector(".flow-node-card");
                const title = shell?.querySelector(".flowchart-node-title, .flow-node-owner");
                return {
                  nodeId: element?.dataset?.id ?? element?.dataset?.nodeid ?? null,
                  wrapperClassName: element?.className ?? null,
                  shellClassName: shell?.className ?? null,
                  cardClassName: card?.className ?? null,
                  titleText: title?.textContent?.trim() ?? null,
                  wrapperRect: summarizeRect(element),
                  shellRect: summarizeRect(shell),
                  cardRect: summarizeRect(card),
                  wrapperStyle: summarizeComputedStyle(element, [
                    "opacity",
                    "transform",
                    "filter",
                    "outline",
                    "outline-offset",
                    "box-shadow",
                    "pointer-events"
                  ]),
                  shellStyle: summarizeComputedStyle(shell, [
                    "opacity",
                    "transform",
                    "filter",
                    "background-color",
                    "border",
                    "border-color",
                    "border-width",
                    "border-style",
                    "border-radius",
                    "box-shadow",
                    "outline",
                    "outline-offset"
                  ]),
                  cardStyle: summarizeComputedStyle(card, [
                    "opacity",
                    "transform",
                    "filter",
                    "background-color",
                    "border",
                    "border-color",
                    "border-width",
                    "border-style",
                    "border-radius",
                    "box-shadow",
                    "outline",
                    "outline-offset"
                  ]),
                  titleStyle: summarizeComputedStyle(title, [
                    "color",
                    "opacity",
                    "font-size",
                    "font-weight",
                    "line-height",
                    "text-decoration"
                  ])
                };
              };
              const summarizeEdgeVisual = (element) => {
                if (!element) {
                  return null;
                }
                const path = element.querySelector(".react-flow__edge-path");
                return {
                  edgeId: element?.dataset?.id ?? null,
                  className: element?.className ?? null,
                  pathClassName: path?.className?.baseVal ?? path?.className ?? null,
                  pathStyleAttribute: path?.getAttribute?.("style") ?? null,
                  markerStart: path?.getAttribute?.("marker-start") ?? null,
                  markerEnd: path?.getAttribute?.("marker-end") ?? null,
                  pathStyle: summarizeComputedStyle(path, [
                    "opacity",
                    "stroke",
                    "stroke-width",
                    "stroke-dasharray",
                    "filter",
                    "marker-start",
                    "marker-end"
                  ])
                };
              };
              const summarizeProbeNode = (element, flowHostRect, viewportState) => ({
                nodeId: element?.dataset?.id ?? element?.dataset?.nodeid ?? null,
                rect: summarizeRect(element),
                center: summarizeRectCenter(element),
                centerFlowPoint: summarizeFlowPoint(summarizeRectCenter(element), flowHostRect, viewportState),
                transform: element?.style?.transform ?? null,
                handles: Array.from(element?.querySelectorAll?.(".react-flow__handle") ?? []).map((handle) =>
                  summarizeHandle(handle, flowHostRect, viewportState),
                )
              });
              const summarizeVisibleEdges = () =>
                Array.from(document.querySelectorAll(".react-flow__edge")).slice(0, 24).map((edge) => summarizeEdge(edge));
              const edgeSignature = (edge) => JSON.stringify({
                pathD: edge?.pathD ?? null,
                startPoint: edge?.startPoint ?? null,
                endPoint: edge?.endPoint ?? null
              });
              const countChangedEdges = (beforeEdges, afterEdges) => {
                const beforeIndex = new Map((beforeEdges ?? []).map((edge) => [edge.edgeId, edgeSignature(edge)]));
                return (afterEdges ?? []).reduce((count, edge) => {
                  if (!edge?.edgeId) {
                    return count;
                  }
                  return beforeIndex.get(edge.edgeId) === edgeSignature(edge) ? count : count + 1;
                }, 0);
              };
              const dispatchMouseEvent = (targets, type, x, y, buttons) => {
                targets.forEach((target) => {
                  if (!target?.dispatchEvent) {
                    return;
                  }
                  target.dispatchEvent(new MouseEvent(type, {
                    bubbles: true,
                    cancelable: true,
                    composed: true,
                    button: 0,
                    buttons,
                    which: buttons === 0 ? 0 : 1,
                    detail: 1,
                    clientX: x,
                    clientY: y,
                    screenX: x,
                    screenY: y,
                    view: window
                  }));
                });
              };
              const dispatchPointerEvent = (targets, type, x, y, buttons) => {
                if (typeof window.PointerEvent !== "function") {
                  return;
                }
                targets.forEach((target) => {
                  if (!target?.dispatchEvent) {
                    return;
                  }
                  target.dispatchEvent(new PointerEvent(type, {
                    bubbles: true,
                    cancelable: true,
                    composed: true,
                    button: 0,
                    buttons,
                    clientX: x,
                    clientY: y,
                    screenX: x,
                    screenY: y,
                    pointerId: 1,
                    pointerType: "mouse",
                    isPrimary: true,
                    view: window
                  }));
                });
              };
              const dispatchDragGesture = (targets, phase, x, y, buttons) => {
                if (phase === "down") {
                  dispatchPointerEvent(targets, "pointerdown", x, y, buttons);
                  dispatchMouseEvent(targets, "mousedown", x, y, buttons);
                  return;
                }
                if (phase === "move") {
                  dispatchPointerEvent(targets, "pointermove", x, y, buttons);
                  dispatchMouseEvent(targets, "mousemove", x, y, buttons);
                  return;
                }
                dispatchPointerEvent(targets, "pointerup", x, y, buttons);
                dispatchMouseEvent(targets, "mouseup", x, y, buttons);
              };
              const selectDragDelta = (forwardSpace, backwardSpace, preferred) => {
                if (forwardSpace >= preferred) {
                  return preferred;
                }
                if (backwardSpace >= preferred) {
                  return -preferred;
                }
                if (forwardSpace >= 16) {
                  return Math.max(Math.min(forwardSpace - 8, preferred), 8);
                }
                if (backwardSpace >= 16) {
                  return -Math.max(Math.min(backwardSpace - 8, preferred), 8);
                }
                return 0;
              };
              const summarizeDragSnapshot = (nodeId) => {
                const node = Array.from(document.querySelectorAll(".react-flow__node")).find((element) =>
                  (element?.dataset?.id ?? element?.dataset?.nodeid ?? null) === nodeId,
                );
                const flowHost = document.querySelector(".react-flow");
                const viewport = document.querySelector(".react-flow__viewport");
                const viewportState = parseViewportState(viewport);
                return {
                  node: summarizeProbeNode(node, flowHost?.getBoundingClientRect ? flowHost.getBoundingClientRect() : null, viewportState),
                  edges: summarizeVisibleEdges(),
                  viewportStyle: document.querySelector(".react-flow__viewport")?.getAttribute("style") ?? null
                };
              };
              const parseTraceMessage = (serialized) => {
                if (typeof serialized !== "string" || serialized.length === 0) {
                  return null;
                }
                try {
                  return JSON.parse(serialized);
                } catch (error) {
                  return null;
                }
              };
              const summarizeTraceDiagnostics = () => {
                const history = Array.isArray(window.__linkGraphTraceHistory) ? window.__linkGraphTraceHistory : [];
                const parsedHistory = history
                  .slice(-12)
                  .map((entry) => parseTraceMessage(entry))
                  .filter((entry) => entry && typeof entry === "object");
                const reverseHistory = [...parsedHistory].reverse();
                const lastTrace = parseTraceMessage(window.__linkGraphLastTrace);
                const lastViewportTrace = reverseHistory.find((entry) => {
                  const eventName = String(entry?.event ?? "");
                  return eventName === "graphFlowSurface.scheduleViewport"
                    || eventName.startsWith("graphFlowSurface.viewport");
                }) ?? null;
                const lastBootstrapTrace = reverseHistory.find((entry) =>
                  String(entry?.event ?? "").startsWith("app.applyBootstrapState"),
                ) ?? null;
                return {
                  debugEnabled: window.__linkGraphDebugEnabled === true,
                  interactionProbeEnabled: window.__linkGraphInteractionProbe === true,
                  hasTraceSink: typeof window.linkGraphDebugTrace === "function",
                  traceBufferLength: Array.isArray(window.__linkGraphTraceBuffer) ? window.__linkGraphTraceBuffer.length : 0,
                  traceHistoryLength: history.length,
                  lastTraceEvent: lastTrace?.event ?? null,
                  lastTraceTime: lastTrace?.time ?? null,
                  recentTraceEvents: parsedHistory.map((entry) => entry?.event ?? null),
                  lastViewportTrace: lastViewportTrace
                    ? {
                        event: lastViewportTrace.event ?? null,
                        time: lastViewportTrace.time ?? null,
                        payload: lastViewportTrace.payload ?? null
                      }
                    : null,
                  lastBootstrapTrace: lastBootstrapTrace
                    ? {
                        event: lastBootstrapTrace.event ?? null,
                        time: lastBootstrapTrace.time ?? null,
                        payload: lastBootstrapTrace.payload ?? null
                      }
                    : null
                };
              };
              const runInteractionProbe = () => {
                if (window.__linkGraphInteractionProbe !== true || String(reason).indexOf("loadAnalysisOutcome") === -1) {
                  return;
                }
                window.__linkGraphInteractionState = window.__linkGraphInteractionState ?? {};
                const interactionState = window.__linkGraphInteractionState;
                const runKey = String(reason);
                if (interactionState.lastDragRunKey === runKey) {
                  return;
                }
                interactionState.lastDragRunKey = runKey;
                window.setTimeout(() => {
                  const shell = document.querySelector("[data-testid='graph-canvas-shell']");
                  const shellRect = shell?.getBoundingClientRect ? shell.getBoundingClientRect() : null;
                  const visibleNodes = Array.from(document.querySelectorAll(".react-flow__node")).filter((node) =>
                    intersects(node?.getBoundingClientRect ? node.getBoundingClientRect() : null, shellRect),
                  );
                  const targetNode = visibleNodes[0];
                  if (!targetNode || !shellRect) {
                    emitEvent("jcef.interactionProbe.drag.skipped", {
                      reason,
                      cause: "no-visible-node",
                      visibleNodeCount: visibleNodes.length
                    });
                    return;
                  }
                  const targetNodeId = targetNode?.dataset?.id ?? targetNode?.dataset?.nodeid ?? null;
                  const targetRect = targetNode.getBoundingClientRect ? targetNode.getBoundingClientRect() : null;
                  if (!targetRect || !targetNodeId) {
                    emitEvent("jcef.interactionProbe.drag.skipped", {
                      reason,
                      cause: "missing-node-geometry"
                    });
                    return;
                  }
                  const deltaX = selectDragDelta(shellRect.right - targetRect.right, targetRect.left - shellRect.left, 56);
                  const deltaY = selectDragDelta(shellRect.bottom - targetRect.bottom, targetRect.top - shellRect.top, 28);
                  if (Math.abs(deltaX) < 8 && Math.abs(deltaY) < 8) {
                    emitEvent("jcef.interactionProbe.drag.skipped", {
                      reason,
                      cause: "insufficient-drag-room",
                      nodeId: targetNodeId
                    });
                    return;
                  }
                  const startX = Math.round((targetRect.left + targetRect.right) / 2);
                  const startY = Math.round((targetRect.top + targetRect.bottom) / 2);
                  const endX = startX + deltaX;
                  const endY = startY + deltaY;
                  const beforeSnapshot = summarizeDragSnapshot(targetNodeId);
                  emitEvent("jcef.interactionProbe.drag.started", {
                    reason,
                    nodeId: targetNodeId,
                    start: { x: startX, y: startY },
                    delta: { x: deltaX, y: deltaY },
                    before: beforeSnapshot
                  });
                  dispatchDragGesture([targetNode], "down", startX, startY, 1);
                  window.setTimeout(() => {
                    dispatchDragGesture([window], "move", startX + Math.round(deltaX * 0.2), startY + Math.round(deltaY * 0.2), 1);
                  }, 24);
                  window.setTimeout(() => {
                    dispatchDragGesture([window], "move", startX + Math.round(deltaX * 0.65), startY + Math.round(deltaY * 0.65), 1);
                  }, 56);
                  window.setTimeout(() => {
                    dispatchDragGesture([window], "move", endX, endY, 1);
                    emitEvent("jcef.interactionProbe.drag.progress", {
                      reason,
                      nodeId: targetNodeId,
                      snapshot: summarizeDragSnapshot(targetNodeId)
                    });
                  }, 96);
                  window.setTimeout(() => {
                    dispatchDragGesture([window], "up", endX, endY, 0);
                    window.setTimeout(() => {
                      const afterSnapshot = summarizeDragSnapshot(targetNodeId);
                      const beforeRect = beforeSnapshot?.node?.rect ?? null;
                      const afterRect = afterSnapshot?.node?.rect ?? null;
                      const movedDistance = beforeRect && afterRect
                        ? round(Math.hypot(afterRect.left - beforeRect.left, afterRect.top - beforeRect.top))
                        : null;
                      emitEvent("jcef.interactionProbe.drag.completed", {
                        reason,
                        nodeId: targetNodeId,
                        movedDistance,
                        changedEdgeCount: countChangedEdges(beforeSnapshot?.edges ?? [], afterSnapshot?.edges ?? []),
                        after: afterSnapshot
                      });
                    }, 96);
                  }, 168);
                }, 900);
              };
              const emit = (stage) => {
                try {
                  const root = document.getElementById("root");
                  const html = document.documentElement;
                  const body = document.body;
                  const shell = document.querySelector("[data-testid='graph-canvas-shell']");
                  const flowHost = document.querySelector(".react-flow");
                  const flowPane = document.querySelector(".react-flow__pane");
                  const flowNodesLayer = document.querySelector(".react-flow__nodes");
                  const flowEdgesLayer = document.querySelector(".react-flow__edges");
                  const flowEdgesSvg = flowEdgesLayer?.querySelector("svg") ?? null;
                  const workbenchShell = document.querySelector(".workbench-shell");
                  const workbenchPanel = document.querySelector(".workbench-panel-body");
                  const activeWorkbenchTab = document.querySelector(".workbench-tab-button.active");
                  const requestBanner = document.querySelector(".async-request-banner");
                  const viewport = document.querySelector(".react-flow__viewport");
                  const viewportState = parseViewportState(viewport);
                  const flowNodes = Array.from(document.querySelectorAll(".react-flow__node"));
                  const selectedNodes = flowNodes.filter((node) => node.classList?.contains("selected"));
                  const explanationFocusNodes = flowNodes.filter((node) => node.classList?.contains("is-explanation-focus"));
                  const draftChangedNodes = flowNodes.filter((node) => node.classList?.contains("is-draft-change"));
                  const highlightedNodes = flowNodes.filter((node) =>
                    node.classList?.contains("selected") ||
                      node.classList?.contains("is-explanation-focus") ||
                      node.classList?.contains("is-draft-change"),
                  );
                  const decisionNodes = Array.from(document.querySelectorAll(".react-flow__node")).filter((node) =>
                    node.querySelector(".flowchart-react-node.kind-decision"),
                  );
                  const flowEdges = Array.from(document.querySelectorAll(".react-flow__edge"));
                  const allFlowNodeRects = flowNodes
                    .map((node) =>
                      summarizeFlowNodeGeometry(
                        node,
                        flowHost?.getBoundingClientRect ? flowHost.getBoundingClientRect() : null,
                        viewportState,
                      ),
                    )
                    .sort((left, right) => String(left?.nodeId ?? "").localeCompare(String(right?.nodeId ?? "")));
                  const allFlowEdgeEndpoints = flowEdges
                    .map((edge) => summarizeEdgeGeometry(edge))
                    .sort((left, right) => String(left?.edgeId ?? "").localeCompare(String(right?.edgeId ?? "")));
                  const shellRectRaw = shell && shell.getBoundingClientRect ? shell.getBoundingClientRect() : null;
                  const firstNodeRectRaw = flowNodes[0] && flowNodes[0].getBoundingClientRect ? flowNodes[0].getBoundingClientRect() : null;
                  const selectedNodeRectRaw = selectedNodes[0] && selectedNodes[0].getBoundingClientRect ? selectedNodes[0].getBoundingClientRect() : null;
                  const flowHostRectRaw = flowHost && flowHost.getBoundingClientRect ? flowHost.getBoundingClientRect() : null;
                  const visibleNodeCountInShell = shellRectRaw
                    ? flowNodes.reduce((count, node) => {
                        const rect = node.getBoundingClientRect ? node.getBoundingClientRect() : null;
                        return intersects(rect, shellRectRaw) ? count + 1 : count;
                      }, 0)
                    : null;
                  const payload = JSON.stringify({
                    time: new Date().toISOString(),
                    event: "jcef.runtimeProbe",
                    payload: {
                      reason,
                      stage,
                      readyState: document.readyState,
                      title: document.title,
                      devicePixelRatio: Number.isFinite(window.devicePixelRatio) ? window.devicePixelRatio : null,
                      visualViewport: summarizeVisualViewport(),
                      windowSize: {
                        innerWidth: round(window.innerWidth),
                        innerHeight: round(window.innerHeight),
                        outerWidth: round(window.outerWidth),
                        outerHeight: round(window.outerHeight),
                        scrollX: round(window.scrollX),
                        scrollY: round(window.scrollY)
                      },
                      screenSize: {
                        width: round(window.screen?.width),
                        height: round(window.screen?.height),
                        availWidth: round(window.screen?.availWidth),
                        availHeight: round(window.screen?.availHeight)
                      },
                      rootChildren: root ? root.childElementCount : null,
                      rootTextSample: root ? (root.textContent || "").trim().slice(0, 120) : null,
                      flowNodeCount: flowNodes.length,
                      flowEdgeCount: document.querySelectorAll(".react-flow__edge").length,
                      flowCardCount: document.querySelectorAll(".flow-node-card").length,
                      selectedNodeCount: selectedNodes.length,
                      explanationFocusNodeCount: explanationFocusNodes.length,
                      draftChangedNodeCount: draftChangedNodes.length,
                      visibleNodeCountInShell,
                      htmlRect: summarizeRect(html),
                      bodyRect: summarizeRect(body),
                      shellRect: summarizeRect(shell),
                      flowHostRect: summarizeRect(flowHost),
                      flowPaneRect: summarizeRect(flowPane),
                      flowNodesLayerRect: summarizeRect(flowNodesLayer),
                      flowEdgesLayerRect: summarizeRect(flowEdgesLayer),
                      flowEdgesSvgRect: summarizeRect(flowEdgesSvg),
                      workbenchShellRect: summarizeRect(workbenchShell),
                      workbenchPanelRect: summarizeRect(workbenchPanel),
                      htmlBox: summarizeBoxMetrics(html),
                      bodyBox: summarizeBoxMetrics(body),
                      rootBox: summarizeBoxMetrics(root),
                      shellBox: summarizeBoxMetrics(shell),
                      flowHostBox: summarizeBoxMetrics(flowHost),
                      flowPaneBox: summarizeBoxMetrics(flowPane),
                      flowEdgesSvgBox: summarizeBoxMetrics(flowEdgesSvg),
                      activeWorkbenchTab: activeWorkbenchTab?.textContent?.trim() ?? null,
                      activeWorkbenchTabId: activeWorkbenchTab?.id ?? null,
                      requestBannerText: requestBanner?.textContent?.trim()?.slice(0, 200) ?? null,
                      debugTraceState: summarizeTraceDiagnostics(),
                      anchorTitle: document.querySelector(".canvas-reading-card.is-anchor .canvas-reading-title")?.textContent?.trim() ?? null,
                      selectedSummaryTitle: document.querySelectorAll(".canvas-reading-card .canvas-reading-title")[1]?.textContent?.trim() ?? null,
                      selectedCanvasTitle:
                        document.querySelector(".react-flow__node.selected .flowchart-node-title, .react-flow__node.selected .flow-node-owner")
                          ?.textContent
                          ?.trim() ?? null,
                      viewportState,
                      selectedNodeIntersectionWithShell: summarizeIntersection(selectedNodeRectRaw, shellRectRaw),
                      selectedNodeIntersectionWithFlowHost: summarizeIntersection(selectedNodeRectRaw, flowHostRectRaw),
                      firstNodeRect: firstNodeRectRaw ? {
                        width: Math.round(firstNodeRectRaw.width),
                        height: Math.round(firstNodeRectRaw.height),
                        top: Math.round(firstNodeRectRaw.top),
                        left: Math.round(firstNodeRectRaw.left)
                      } : null,
                      viewportStyle: viewport ? (viewport.getAttribute("style") || null) : null,
                      htmlStyle: summarizeComputedStyle(html, [
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "contain",
                        "transform",
                        "filter"
                      ]),
                      bodyStyle: summarizeComputedStyle(body, [
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "contain",
                        "transform",
                        "filter"
                      ]),
                      rootStyle: summarizeComputedStyle(root, [
                        "display",
                        "position",
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "contain",
                        "transform",
                        "filter"
                      ]),
                      shellStyle: summarizeComputedStyle(shell, [
                        "display",
                        "position",
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "contain",
                        "isolation",
                        "transform",
                        "transform-origin",
                        "will-change",
                        "filter"
                      ]),
                      flowHostStyle: summarizeComputedStyle(flowHost, [
                        "display",
                        "position",
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "contain",
                        "isolation",
                        "transform",
                        "transform-origin",
                        "will-change",
                        "filter"
                      ]),
                      flowPaneStyle: summarizeComputedStyle(flowPane, [
                        "display",
                        "position",
                        "overflow",
                        "clip-path",
                        "transform",
                        "transform-origin",
                        "will-change",
                        "pointer-events"
                      ]),
                      flowNodesWithHandles: flowNodes
                        .filter((node) => node.querySelector(".react-flow__handle"))
                        .slice(0, 12)
                        .map((node) => summarizeFlowNode(node, flowHost?.getBoundingClientRect ? flowHost.getBoundingClientRect() : null, viewportState)),
                      allFlowNodeRects,
                      highlightedNodes: highlightedNodes.slice(0, 12).map((node) => ({
                        nodeId: node?.dataset?.id ?? node?.dataset?.nodeid ?? null,
                        className: node?.className ?? null,
                        title:
                          node.querySelector(".flowchart-node-title, .flow-node-owner")
                            ?.textContent
                            ?.trim() ?? null,
                      })),
                      decisionNodes: decisionNodes.slice(0, 6).map((node) =>
                        summarizeDecisionNode(node, flowHost?.getBoundingClientRect ? flowHost.getBoundingClientRect() : null, viewportState),
                      ),
                      flowEdgesLayerStyle: summarizeComputedStyle(flowEdgesLayer, [
                        "display",
                        "position",
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "transform",
                        "transform-origin",
                        "filter",
                        "pointer-events"
                      ]),
                      flowEdgesSvgStyle: summarizeComputedStyle(flowEdgesSvg, [
                        "display",
                        "position",
                        "overflow",
                        "overflow-x",
                        "overflow-y",
                        "clip-path",
                        "transform",
                        "transform-origin",
                        "filter",
                        "pointer-events"
                      ]),
                      viewportComputedStyle: summarizeComputedStyle(viewport, [
                        "display",
                        "position",
                        "overflow",
                        "clip-path",
                        "contain",
                        "transform",
                        "transform-origin",
                        "will-change",
                        "filter",
                        "opacity"
                      ]),
                      flowEdges: flowEdges.slice(0, 24).map((edge) => summarizeEdge(edge)),
                      allFlowEdgeEndpoints,
                      selectedNodeVisual: summarizeNodeVisual(selectedNodes[0] ?? null),
                      highlightedNodeVisuals: highlightedNodes.slice(0, 4).map((node) => summarizeNodeVisual(node)),
                      edgeVisualSample: flowEdges.slice(0, 8).map((edge) => summarizeEdgeVisual(edge))
                    }
                  });
                  emitPayload(payload);
                } catch (error) {
                  const payload = JSON.stringify({
                    time: new Date().toISOString(),
                    event: "jcef.runtimeProbeFailed",
                    payload: {
                      reason,
                      stage,
                      error: String(error)
                    }
                  });
                  emitPayload(payload);
                }
              };
              delays.forEach((delay) => window.setTimeout(() => emit("t" + delay), delay));
              runInteractionProbe();
            })();
        """.trimIndent()
    }

    private fun escapeJsString(value: String): String = buildString(value.length + 8) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
