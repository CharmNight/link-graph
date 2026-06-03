import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GraphStageFooter } from "../../../app/components/GraphStageFooter";
import type { IndexedGraphSummary } from "../../../app/types";

describe("GraphStageFooter", () => {
  it("labels indexed graph counts by their backend meaning instead of claiming filtered frontend render counts", () => {
    render(
      <GraphStageFooter
        analysisDisplayMode="ARCHITECTURE_GRAPH"
        activeViewGraph={{ nodes: [], edges: [], nodeCount: 96 }}
        fullNodeCount={1114}
        hasExplanationFocus={false}
        draftChangedNodeCount={0}
        indexedSummary={{
          ...indexedSummary(),
          visibleNodeCount: 96,
          candidateNodeCount: 1114,
          hiddenNodeCount: 1018,
          visibleLayerCounts: {
            projectSource: 42,
            externalLibrary: 38,
            jdk: 9,
            resource: 7,
            aggregate: 12,
          },
        }}
        codeDiffStatus="MISSING"
      />,
    );

    expect(screen.queryByText("节点 96 / 1114")).not.toBeInTheDocument();
    expect(screen.queryByText(/前端渲染/)).not.toBeInTheDocument();
    expect(screen.queryByText(/关系候选/)).not.toBeInTheDocument();
    expect(screen.queryByText(/当前筛选/)).not.toBeInTheDocument();
    expect(screen.getByText("窗口节点 96 / 候选节点 1114 / 窗口外 1018 / 窗口来源 项目 42 · 三方 38 · JDK 9 · 资源 7 · 聚合 12")).toBeInTheDocument();
    expect(screen.getByText("代码 diff 未生成")).toBeInTheDocument();
  });

  it("does not invent indexed graph counts when backend fields are missing", () => {
    render(
      <GraphStageFooter
        analysisDisplayMode="REVIEW_GRAPH"
        activeViewGraph={{ nodes: [], edges: [], nodeCount: 9061 }}
        fullNodeCount={0}
        hasExplanationFocus={false}
        draftChangedNodeCount={0}
        codeDiffStatus="MISSING"
      />,
    );

    expect(screen.getByText("indexed 统计未返回")).toBeInTheDocument();
  });

  it("renders backend indexed visibility reasons", () => {
    render(
      <GraphStageFooter
        analysisDisplayMode="CLASS_DIAGRAM"
        activeViewGraph={{ nodes: [], edges: [], nodeCount: 12 }}
        fullNodeCount={100}
        hasExplanationFocus={false}
        draftChangedNodeCount={0}
        indexedSummary={{
          ...indexedSummary(),
          visibilityReasons: [
            {
              code: "VIEWPORT_NODE_LIMIT",
              label: "窗口限制隐藏了部分节点或关系",
              nodeCount: 88,
              edgeCount: 9,
            },
            {
              code: "JDK_LAYER_DISABLED",
              label: "JDK 层未启用",
            },
          ],
        }}
        codeDiffStatus="MISSING"
      />,
    );

    expect(screen.getByText("窗口限制隐藏了部分节点或关系（节点 88 / 关系 9）")).toBeInTheDocument();
    expect(screen.getByText("JDK 层未启用")).toBeInTheDocument();
  });

  it("surfaces indexed graph freshness and cache state so backend reuse is visible", () => {
    render(
      <GraphStageFooter
        analysisDisplayMode="ARCHITECTURE_GRAPH"
        activeViewGraph={{ nodes: [], edges: [], nodeCount: 12 }}
        fullNodeCount={100}
        hasExplanationFocus={false}
        draftChangedNodeCount={0}
        indexedSummary={{
          ...indexedSummary(),
          cacheState: "REUSED_FULL_INDEX",
          freshness: {
            state: "STALE",
            dirtyReason: "VFS_CHANGE",
            pendingFileCount: 3,
            pendingFileSamples: ["src/main/java/com/example/OrderService.java"],
            lastIndexedAtEpochMillis: 1710000000000,
            staleSinceEpochMillis: 1710000100000,
          },
        }}
        codeDiffStatus="MISSING"
      />,
    );

    expect(screen.getByText("索引 STALE：待刷新 3 个文件")).toBeInTheDocument();
    expect(screen.getByText("索引缓存：复用完整索引")).toBeInTheDocument();
  });
});

function indexedSummary(): IndexedGraphSummary {
  return {
    view: "ARCHITECTURE",
    anchorKind: null,
    anchorNodeId: null,
    anchorTitle: null,
    anchorQualifiedName: null,
    scopeKind: "PROJECT",
    scopeLabel: "Project",
    relationKinds: [],
    depth: 1,
    projectNodeCount: 1114,
    projectClassCount: 900,
    externalNodeCount: 38,
    jdkNodeCount: 9,
    scopedNodeCount: 1114,
    visibleNodeCount: 96,
    hiddenNodeCount: 1018,
    hiddenEdgeCount: 0,
    candidateNodeCount: 1114,
    candidateEdgeCount: 0,
    truncated: true,
    completeness: "Interactive",
    cacheState: "REUSED_FULL_INDEX",
  };
}
