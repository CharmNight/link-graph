import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { createNodeSizeRegistry } from "../../../app/graph/nodeSizeRegistry";
import type { LinkGraphDocument, LinkGraphNode } from "../../../app/types";
import { useMeasuredLayout } from "../../../app/reactflow/useMeasuredLayout";

function methodNode(id: string, title: string): LinkGraphNode {
  return {
    id,
    type: "METHOD",
    title,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
  };
}

function manualNode(id: string, title: string, x: number, y: number): LinkGraphNode {
  return {
    id,
    type: "METHOD",
    title,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "DESIGN_ONLY",
    sourceTag: "DRAFT_MANUAL",
    position: { x, y },
    metadata: {
      "linkGraph.manual": "true",
      "ui.x": String(x),
      "ui.y": String(y),
    },
  };
}

describe("useMeasuredLayout", () => {
  it("keeps an unpositioned graph hidden until async layout returns stable coordinates", async () => {
    const graph: LinkGraphDocument = {
      nodes: [
        methodNode("method:anchor", "OrderService.submit"),
        methodNode("method:callee", "OrderMapper.insert"),
      ],
      edges: [
        {
          id: "edge:anchor->callee",
          type: "CALL",
          source: "method:anchor",
          target: "method:callee",
        },
      ],
    };
    let resolveLayout: ((value: { nodes: LinkGraphNode[]; edges: LinkGraphDocument["edges"] }) => void) | null = null;
    const layout = vi.fn(() =>
      new Promise<{ nodes: LinkGraphNode[]; edges: LinkGraphDocument["edges"] }>((resolve) => {
        resolveLayout = resolve;
      }),
    );

    const { result } = renderHook(() =>
      useMeasuredLayout({
        graph,
        anchorNodeId: "method:anchor",
        layout,
      }),
    );

    expect(result.current.layoutPending).toBe(true);
    expect(result.current.nodes).toEqual([]);
    expect(result.current.edges).toEqual([]);

    await waitFor(() => {
      expect(layout).toHaveBeenCalledTimes(1);
    });

    act(() => {
      resolveLayout?.({
        nodes: graph.nodes.map((node, index) => ({
          ...node,
          position: { x: 120 + index * 320, y: 96 },
        })),
        edges: graph.edges,
      });
    });

    await waitFor(() => {
      expect(result.current.nodes[0]?.position).toEqual({ x: 120, y: 96 });
    });
    expect(result.current.nodes[1]?.position).toEqual({ x: 440, y: 96 });
    expect(result.current.edges).toEqual(graph.edges);
    expect(result.current.layoutPending).toBe(false);
  });

  it("does not trigger a new layout run when the host rerenders only because selection changed", async () => {
    const graph: LinkGraphDocument = {
      nodes: [
        methodNode("method:anchor", "OrderService.submit"),
        methodNode("method:callee", "OrderMapper.insert"),
      ],
      edges: [
        {
          id: "edge:anchor->callee",
          type: "CALL",
          source: "method:anchor",
          target: "method:callee",
        },
      ],
    };
    const layout = vi.fn(async ({ nodes }: { nodes: LinkGraphNode[] }) => {
      return {
        nodes: nodes.map((node, index) => ({
          ...node,
          position: {
            x: 120 + index * 320,
            y: 96,
          },
        })),
        edges: graph.edges,
      };
    });

    const { result, rerender } = renderHook(
      ({ selectedNodeId }) => {
        void selectedNodeId;
        return useMeasuredLayout({
          graph,
          anchorNodeId: "method:anchor",
          layout,
        });
      },
      {
        initialProps: {
          selectedNodeId: "method:anchor",
        },
      },
    );

    await waitFor(() => {
      expect(result.current.nodes[0]?.position).toEqual({ x: 120, y: 96 });
    });
    expect(layout).toHaveBeenCalledTimes(1);

    rerender({ selectedNodeId: "method:callee" });

    await waitFor(() => {
      expect(result.current.nodes[1]?.position).toEqual({ x: 440, y: 96 });
    });
    expect(layout).toHaveBeenCalledTimes(1);
  });

  it("reruns layout when measured node sizes change", async () => {
    const registry = createNodeSizeRegistry();
    const graph: LinkGraphDocument = {
      nodes: [
        methodNode("method:anchor", "OrderService.submit"),
        methodNode("method:callee", "OrderMapper.insert"),
      ],
      edges: [],
    };
    const layout = vi.fn(async ({
      nodes,
      sizeSnapshot,
    }: {
      nodes: LinkGraphNode[];
      sizeSnapshot: ReadonlyMap<string, { width: number; height: number }>;
    }) => {
      const anchorWidth = sizeSnapshot.get("method:anchor")?.width ?? 320;
      return {
        nodes: nodes.map((node, index) => ({
          ...node,
          position: {
            x: 120 + index * anchorWidth,
            y: 96,
          },
        })),
        edges: graph.edges,
      };
    });

    const { result } = renderHook(() =>
      useMeasuredLayout({
        graph,
        anchorNodeId: "method:anchor",
        nodeSizeRegistry: registry,
        layout,
      }),
    );

    await waitFor(() => {
      expect(result.current.nodes[1]?.position).toEqual({ x: 440, y: 96 });
    });
    expect(layout).toHaveBeenCalledTimes(1);

    act(() => {
      registry.set("method:anchor", { width: 512, height: 228 });
    });

    await waitFor(() => {
      expect(result.current.nodes[1]?.position).toEqual({ x: 632, y: 96 });
    });
    expect(layout).toHaveBeenCalledTimes(2);
  });

  it("does not rerun layout when the effective layout size signature is unchanged", async () => {
    const registry = createNodeSizeRegistry();
    const graph: LinkGraphDocument = {
      nodes: [
        methodNode("method:anchor", "OrderService.submit"),
        methodNode("method:callee", "OrderMapper.insert"),
      ],
      edges: [],
    };
    const layout = vi.fn(async ({ nodes }: { nodes: LinkGraphNode[] }) => ({
      nodes: nodes.map((node, index) => ({
        ...node,
        position: {
          x: 120 + index * 320,
          y: 96,
        },
      })),
      edges: graph.edges,
    }));

    const { result } = renderHook(() =>
      useMeasuredLayout({
        graph,
        anchorNodeId: "method:anchor",
        nodeSizeRegistry: registry,
        layout,
        layoutSizeSignature: (nodes, sizeSnapshot) =>
          nodes
            .map((node) => {
              const size = sizeSnapshot.get(node.id);
              return `${node.id}:${Math.max(size?.width ?? 408, 408)}x${Math.max(size?.height ?? 156, 156)}`;
            })
            .join("::"),
      }),
    );

    await waitFor(() => {
      expect(result.current.nodes[1]?.position).toEqual({ x: 440, y: 96 });
    });
    expect(layout).toHaveBeenCalledTimes(1);

    act(() => {
      registry.set("method:anchor", { width: 408, height: 132 });
    });

    await waitFor(() => {
      expect(result.current.nodes[0]?.position).toEqual({ x: 120, y: 96 });
    });
    expect(layout).toHaveBeenCalledTimes(1);
  });

  it("supports explicit relayout requests without replacing the semantic graph input", async () => {
    const graph: LinkGraphDocument = {
      nodes: [methodNode("method:anchor", "OrderService.submit")],
      edges: [],
    };
    let baseX = 120;
    const layout = vi.fn(async ({ nodes }: { nodes: LinkGraphNode[] }) => {
      return {
        nodes: nodes.map((node) => ({
          ...node,
          position: {
            x: baseX,
            y: 96,
          },
        })),
        edges: graph.edges,
      };
    });

    const { result } = renderHook(() =>
      useMeasuredLayout({
        graph,
        anchorNodeId: "method:anchor",
        layout,
      }),
    );

    await waitFor(() => {
      expect(result.current.nodes[0]?.position).toEqual({ x: 120, y: 96 });
    });
    expect(layout).toHaveBeenCalledTimes(1);

    baseX = 360;
    act(() => {
      result.current.requestRelayout();
    });

    await waitFor(() => {
      expect(result.current.nodes[0]?.position).toEqual({ x: 360, y: 96 });
    });
    expect(layout).toHaveBeenCalledTimes(2);
  });

  it("applies direct position-only updates without triggering a fresh layout run", async () => {
    const layout = vi.fn(async ({ nodes }: { nodes: LinkGraphNode[] }) => {
      return {
        nodes: nodes.map((node) => ({
          ...node,
          position: { x: 120, y: 96 },
        })),
        edges: [],
      };
    });

    const initialGraph: LinkGraphDocument = {
      nodes: [methodNode("method:anchor", "OrderService.submit")],
      edges: [],
    };

    const { result, rerender } = renderHook(
      ({ graph }) =>
        useMeasuredLayout({
          graph,
          anchorNodeId: "method:anchor",
          layout,
        }),
      {
        initialProps: {
          graph: initialGraph,
        },
      },
    );

    await waitFor(() => {
      expect(result.current.nodes[0]?.position).toEqual({ x: 120, y: 96 });
    });
    expect(layout).toHaveBeenCalledTimes(1);

    rerender({
      graph: {
        nodes: [
          {
            ...methodNode("method:anchor", "OrderService.submit"),
            position: { x: 360, y: 144 },
            metadata: {
              "ui.x": "360",
              "ui.y": "144",
            },
          },
        ],
        edges: [],
      },
    });

    await waitFor(() => {
      expect(result.current.nodes[0]?.position).toEqual({ x: 360, y: 144 });
    });
    expect(layout).toHaveBeenCalledTimes(1);
  });

  it("reruns layout when positioned node additions are invocation expansion batches", async () => {
    const initialGraph: LinkGraphDocument = {
      nodes: [methodNode("method:caller", "Caller.run")],
      edges: [],
    };
    const expandedNode: LinkGraphNode = {
      ...methodNode("method:create-info", "SystemService.createInfo"),
      position: { x: 640, y: 120 },
      metadata: {
        "ui.x": "640",
        "ui.y": "120",
        "linkGraph.expansion.id": "invocation:1",
        "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
      },
    };
    const layout = vi.fn(async ({ nodes }: { nodes: LinkGraphNode[] }) => ({
      nodes: nodes.map((node, index) => ({
        ...node,
        position: { x: 120 + index * 320, y: 96 },
      })),
      edges: [],
    }));

    const { rerender } = renderHook(
      ({ graph }) =>
        useMeasuredLayout({
          graph,
          anchorNodeId: "method:caller",
          layout,
        }),
      {
        initialProps: {
          graph: initialGraph,
        },
      },
    );

    await waitFor(() => {
      expect(layout).toHaveBeenCalledTimes(1);
    });

    rerender({
      graph: {
        nodes: [initialGraph.nodes[0]!, expandedNode],
        edges: [],
      },
    });

    await waitFor(() => {
      expect(layout).toHaveBeenCalledTimes(2);
    });
  });

  it("reruns layout when edge-only changes add invocation expansion routing metadata", async () => {
    const baseNode = {
      ...methodNode("method:caller", "Caller.run"),
      position: { x: 120, y: 96 },
      metadata: {
        "ui.x": "120",
        "ui.y": "96",
      },
    };
    const expandedNode = {
      ...methodNode("method:create-info", "SystemService.createInfo"),
      position: { x: 640, y: 96 },
      metadata: {
        "ui.x": "640",
        "ui.y": "96",
        "linkGraph.expansion.id": "invocation:1",
        "linkGraph.expansion.sourceInvocationNodeId": "method:caller",
      },
    };
    const initialGraph: LinkGraphDocument = {
      nodes: [baseNode, expandedNode],
      edges: [],
    };
    const layout = vi.fn(async ({ nodes, edges }: { nodes: LinkGraphNode[]; edges: LinkGraphDocument["edges"] }) => ({
      nodes,
      edges,
    }));

    const { rerender } = renderHook(
      ({ graph }) =>
        useMeasuredLayout({
          graph,
          anchorNodeId: "method:caller",
          layout,
        }),
      {
        initialProps: {
          graph: initialGraph,
        },
      },
    );

    await waitFor(() => {
      expect(layout).toHaveBeenCalledTimes(1);
    });

    rerender({
      graph: {
        nodes: [baseNode, expandedNode],
        edges: [{
          id: "call:create-info",
          type: "CALL",
          source: "method:caller",
          target: "method:create-info",
          metadata: {
            "linkGraph.expansion.id": "invocation:1",
          },
        }],
      },
    });

    await waitFor(() => {
      expect(layout).toHaveBeenCalledTimes(2);
    });
  });

  it("preserves previous layout metadata when the host refreshes the same semantic graph without layout fields", async () => {
    const graph: LinkGraphDocument = {
      nodes: [methodNode("method:anchor", "OrderService.submit")],
      edges: [],
    };
    const layout = vi.fn(async ({ nodes }: { nodes: LinkGraphNode[] }) => {
      return {
        nodes: nodes.map((node) => ({
          ...node,
          position: { x: 120, y: 96 },
          metadata: {
            ...(node.metadata ?? {}),
            "layout.direction": "CURRENT",
            "layout.levelLabel": "当前锚点",
          },
        })),
        edges: [],
      };
    });

    const { result, rerender } = renderHook(
      ({ nextGraph }) =>
        useMeasuredLayout({
          graph: nextGraph,
          anchorNodeId: "method:anchor",
          layout,
        }),
      {
        initialProps: {
          nextGraph: graph,
        },
      },
    );

    await waitFor(() => {
      expect(result.current.nodes[0]?.metadata?.["layout.direction"]).toBe("CURRENT");
    });
    expect(layout).toHaveBeenCalledTimes(1);

    rerender({
      nextGraph: {
        nodes: [
          {
            ...methodNode("method:anchor", "OrderService.submit"),
          },
        ],
        edges: [],
      },
    });

    await waitFor(() => {
      expect(result.current.nodes[0]?.metadata?.["layout.direction"]).toBe("CURRENT");
    });
    expect(layout).toHaveBeenCalledTimes(1);
  });

  it("keeps the previous edge route when a relayout request fails", async () => {
    const graph: LinkGraphDocument = {
      nodes: [
        methodNode("method:anchor", "OrderService.submit"),
        methodNode("method:callee", "OrderMapper.insert"),
      ],
      edges: [
        {
          id: "edge:anchor->callee",
          type: "CALL",
          source: "method:anchor",
          target: "method:callee",
        },
      ],
    };
    let shouldFail = false;
    const layout = vi.fn(async ({ nodes }: { nodes: LinkGraphNode[] }) => {
      if (shouldFail) {
        throw new Error("layout failed");
      }
      return {
        nodes: nodes.map((node, index) => ({
          ...node,
          position: {
            x: 120 + index * 320,
            y: 96,
          },
        })),
        edges: [
          {
            ...graph.edges[0]!,
            route: {
              sections: [
                {
                  startPoint: { x: 240, y: 126 },
                  endPoint: { x: 440, y: 126 },
                },
              ],
            },
          },
        ],
      };
    });

    const { result } = renderHook(() =>
      useMeasuredLayout({
        graph,
        anchorNodeId: "method:anchor",
        layout,
      }),
    );

    await waitFor(() => {
      expect(result.current.edges[0]?.route?.sections[0]?.endPoint).toEqual({ x: 440, y: 126 });
    });
    expect(layout).toHaveBeenCalledTimes(1);

    shouldFail = true;
    act(() => {
      result.current.requestRelayout();
    });

    await waitFor(() => {
      expect(layout).toHaveBeenCalledTimes(2);
    });
    await waitFor(() => {
      expect(result.current.layoutPending).toBe(false);
    });
    expect(result.current.edges[0]?.route?.sections[0]?.endPoint).toEqual({ x: 440, y: 126 });
  });

  it("treats edge handle changes as semantic layout input changes", async () => {
    const layout = vi.fn(async ({
      nodes,
      edges,
    }: {
      nodes: LinkGraphNode[];
      edges: LinkGraphDocument["edges"];
    }) => ({
      nodes: nodes.map((node, index) => ({
        ...node,
        position: { x: 120 + index * 320, y: 96 },
      })),
      edges,
    }));

    const { rerender } = renderHook(
      ({ graph }) =>
        useMeasuredLayout({
          graph,
          anchorNodeId: "method:anchor",
          layout,
        }),
      {
        initialProps: {
          graph: {
            nodes: [
              methodNode("method:anchor", "OrderService.submit"),
              methodNode("method:callee", "OrderMapper.insert"),
            ],
            edges: [
              {
                id: "edge:anchor->callee",
                type: "CALL",
                source: "method:anchor",
                target: "method:callee",
                sourceHandle: "source-right",
                targetHandle: "target-left",
              },
            ],
          } satisfies LinkGraphDocument,
        },
      },
    );

    await waitFor(() => {
      expect(layout).toHaveBeenCalledTimes(1);
    });

    rerender({
      graph: {
        nodes: [
          methodNode("method:anchor", "OrderService.submit"),
          methodNode("method:callee", "OrderMapper.insert"),
        ],
        edges: [
          {
            id: "edge:anchor->callee",
            type: "CALL",
            source: "method:anchor",
            target: "method:callee",
            sourceHandle: "source-bottom",
            targetHandle: "target-left",
          },
        ],
      } satisfies LinkGraphDocument,
    });

    await waitFor(() => {
      expect(layout).toHaveBeenCalledTimes(1);
    });
  });

  it("applies edge-only deletions without triggering a fresh layout run", async () => {
    const layout = vi.fn(async ({
      nodes,
      edges,
    }: {
      nodes: LinkGraphNode[];
      edges: LinkGraphDocument["edges"];
    }) => ({
      nodes: nodes.map((node, index) => ({
        ...node,
        position: { x: 120 + index * 320, y: 96 },
      })),
      edges: edges.map((edge) => ({
        ...edge,
        route: {
          sections: [
            {
              startPoint: { x: 240, y: 156 },
              endPoint: { x: 440, y: 156 },
            },
          ],
        },
      })),
    }));

    const { result, rerender } = renderHook(
      ({ graph }) =>
        useMeasuredLayout({
          graph,
          anchorNodeId: "method:anchor",
          layout,
        }),
      {
        initialProps: {
          graph: {
            nodes: [
              methodNode("method:anchor", "OrderService.submit"),
              methodNode("method:callee", "OrderMapper.insert"),
            ],
            edges: [
              {
                id: "edge:anchor->callee",
                type: "CALL",
                source: "method:anchor",
                target: "method:callee",
              },
            ],
          } satisfies LinkGraphDocument,
        },
      },
    );

    await waitFor(() => {
      expect(result.current.nodes[0]?.position).toEqual({ x: 120, y: 96 });
    });
    expect(layout).toHaveBeenCalledTimes(1);

    rerender({
      graph: {
        nodes: [
          methodNode("method:anchor", "OrderService.submit"),
          methodNode("method:callee", "OrderMapper.insert"),
        ],
        edges: [],
      } satisfies LinkGraphDocument,
    });

    await waitFor(() => {
      expect(result.current.edges).toHaveLength(0);
    });
    expect(result.current.nodes[0]?.position).toEqual({ x: 120, y: 96 });
    expect(result.current.nodes[1]?.position).toEqual({ x: 440, y: 96 });
    expect(layout).toHaveBeenCalledTimes(1);
  });

  it("applies positioned manual node additions without triggering a fresh layout run", async () => {
    const layout = vi.fn(async ({
      nodes,
      edges,
    }: {
      nodes: LinkGraphNode[];
      edges: LinkGraphDocument["edges"];
    }) => ({
      nodes: nodes.map((node, index) => ({
        ...node,
        position: { x: 120 + index * 320, y: 96 },
      })),
      edges,
    }));

    const initialGraph: LinkGraphDocument = {
      nodes: [
        methodNode("method:anchor", "OrderService.submit"),
        methodNode("method:callee", "OrderMapper.insert"),
      ],
      edges: [
        {
          id: "edge:anchor->callee",
          type: "CALL",
          source: "method:anchor",
          target: "method:callee",
        },
      ],
    };

    const { result, rerender } = renderHook(
      ({ graph }) =>
        useMeasuredLayout({
          graph,
          anchorNodeId: "method:anchor",
          layout,
        }),
      {
        initialProps: {
          graph: initialGraph,
        },
      },
    );

    await waitFor(() => {
      expect(result.current.nodes[1]?.position).toEqual({ x: 440, y: 96 });
    });
    expect(layout).toHaveBeenCalledTimes(1);

    rerender({
      graph: {
        nodes: [
          methodNode("method:anchor", "OrderService.submit"),
          methodNode("method:callee", "OrderMapper.insert"),
          manualNode("design:1", "新方法1", 760, 240),
        ],
        edges: [
          {
            id: "edge:anchor->callee",
            type: "CALL",
            source: "method:anchor",
            target: "method:callee",
          },
        ],
      } satisfies LinkGraphDocument,
    });

    await waitFor(() => {
      expect(result.current.nodes.find((node) => node.id === "design:1")?.position).toEqual({ x: 760, y: 240 });
    });
    expect(result.current.nodes.find((node) => node.id === "method:anchor")?.position).toEqual({ x: 120, y: 96 });
    expect(result.current.nodes.find((node) => node.id === "method:callee")?.position).toEqual({ x: 440, y: 96 });
    expect(layout).toHaveBeenCalledTimes(1);
  });

  it("applies positioned manual insertions without triggering a fresh layout run", async () => {
    const layout = vi.fn(async ({
      nodes,
      edges,
    }: {
      nodes: LinkGraphNode[];
      edges: LinkGraphDocument["edges"];
    }) => ({
      nodes: nodes.map((node, index) => ({
        ...node,
        position: { x: 120 + index * 320, y: 96 },
      })),
      edges,
    }));

    const initialGraph: LinkGraphDocument = {
      nodes: [
        methodNode("method:anchor", "OrderService.submit"),
        methodNode("method:callee", "OrderMapper.insert"),
      ],
      edges: [
        {
          id: "edge:anchor->callee",
          type: "CONTROL_FLOW",
          source: "method:anchor",
          target: "method:callee",
        },
      ],
    };

    const { result, rerender } = renderHook(
      ({ graph }) =>
        useMeasuredLayout({
          graph,
          anchorNodeId: "method:anchor",
          layout,
        }),
      {
        initialProps: {
          graph: initialGraph,
        },
      },
    );

    await waitFor(() => {
      expect(result.current.nodes[1]?.position).toEqual({ x: 440, y: 96 });
    });
    expect(layout).toHaveBeenCalledTimes(1);

    rerender({
      graph: {
        nodes: [
          methodNode("method:anchor", "OrderService.submit"),
          methodNode("method:callee", "OrderMapper.insert"),
          manualNode("design:2", "新方法2", 280, 156),
        ],
        edges: [
          {
            id: "edge:anchor->inserted",
            type: "CONTROL_FLOW",
            source: "method:anchor",
            target: "design:2",
          },
          {
            id: "edge:inserted->callee",
            type: "CONTROL_FLOW",
            source: "design:2",
            target: "method:callee",
          },
        ],
      } satisfies LinkGraphDocument,
    });

    await waitFor(() => {
      expect(result.current.nodes.find((node) => node.id === "design:2")?.position).toEqual({ x: 280, y: 156 });
    });
    expect(result.current.nodes.find((node) => node.id === "method:anchor")?.position).toEqual({ x: 120, y: 96 });
    expect(result.current.nodes.find((node) => node.id === "method:callee")?.position).toEqual({ x: 440, y: 96 });
    expect(result.current.edges).toHaveLength(2);
    expect(layout).toHaveBeenCalledTimes(1);
  });
});
