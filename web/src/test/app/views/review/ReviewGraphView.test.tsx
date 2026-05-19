import type { ReactNode } from "react";
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ReviewGraphView } from "../../../../app/views/review/ReviewGraphView";
import type { ReviewGraphViewDocument } from "../../../../app/types";

const { useMeasuredLayoutMock } = vi.hoisted(() => ({
  useMeasuredLayoutMock: vi.fn(),
}));

vi.mock("../../../../app/reactflow/useMeasuredLayout", () => ({
  useMeasuredLayout: useMeasuredLayoutMock,
}));

vi.mock("../../../../app/reactflow/GraphFlowSurface", () => ({
  GraphFlowSurface: (props: {
    anchorNodeId?: string | null;
    editable?: boolean;
    header?: ReactNode;
    emptyState?: ReactNode;
    nodes: Array<unknown>;
    edges: Array<unknown>;
  }) => (
    <div
      data-testid="graph-flow-surface"
      data-anchor={props.anchorNodeId ?? ""}
      data-editable={String(props.editable)}
    >
      {props.header}
      {props.nodes.length === 0 ? props.emptyState : null}
      {props.nodes.length}:{props.edges.length}
    </div>
  ),
}));

const view: ReviewGraphViewDocument = {
  visibleGraph: {
    nodes: [
      {
        id: "method:new",
        type: "METHOD",
        title: "NewService.moved",
        inputs: [],
        outputs: [],
        certainty: "PROVEN",
        bindingStatus: "BOUND",
        metadata: {
          "review.role": "CHANGED",
        },
      },
    ],
    edges: [],
  },
  fullGraph: {
    nodes: [],
    edges: [],
  },
  anchorNodeId: "method:new",
  summary: {
    changedSymbolCount: 1,
    upstreamCount: 0,
    downstreamCount: 0,
    relatedTestCount: 1,
    affectedPackageCount: 1,
    affectedModuleCount: 0,
    evidenceRefCount: 1,
  },
  changedFiles: [
    {
      oldPath: "src/main/java/com/example/OldService.java",
      newPath: "src/main/java/com/example/NewService.java",
      changeKind: "RENAMED",
      hunkCount: 1,
      similarity: 72,
    },
  ],
  changedHunks: [
    {
      filePath: "src/main/java/com/example/NewService.java",
      oldFilePath: "src/main/java/com/example/OldService.java",
      newFilePath: "src/main/java/com/example/NewService.java",
      changeKind: "HUNK_RENAMED",
      header: "@@ -3,1 +3,1 @@",
      oldStartLine: 3,
      oldLineCount: 1,
      newStartLine: 3,
      newLineCount: 1,
      matchedSymbolIds: ["method:new"],
    },
    {
      filePath: "src/main/java/com/example/Unmatched.java",
      newFilePath: "src/main/java/com/example/Unmatched.java",
      changeKind: "HUNK_ADDED",
      header: "@@ -0,0 +8,1 @@",
      newStartLine: 8,
      newLineCount: 1,
      matchedSymbolIds: [],
    },
  ],
  unmatchedHunks: [
    {
      filePath: "src/main/java/com/example/Unmatched.java",
      newFilePath: "src/main/java/com/example/Unmatched.java",
      changeKind: "HUNK_ADDED",
      header: "@@ -0,0 +8,1 @@",
      newStartLine: 8,
      newLineCount: 1,
      matchedSymbolIds: [],
    },
  ],
  baselineOnlySymbols: [
    {
      symbolId: "baseline:method:old",
      qualifiedName: "com.example.OldService.moved():void",
      filePath: "src/main/java/com/example/OldService.java",
      startLine: 3,
      endLine: 3,
      changeKind: "MODIFIED",
      blastRadiusIncomplete: true,
    },
  ],
  relatedTests: [
    {
      symbolId: "method:test",
      qualifiedName: "com.example.NewServiceTest.coversMove():void",
      reason: "CALL_PATH",
      filePath: "src/test/java/com/example/NewServiceTest.java",
      startLine: 12,
    },
  ],
  affectedPackages: ["com.example"],
  affectedModules: [],
  evidenceSnippets: [
    {
      title: "com.example.NewService.moved():void",
      kind: "MODIFIED",
      filePath: "src/main/java/com/example/NewService.java",
      startLine: 3,
      endLine: 3,
      snippet: "void moved() {}",
    },
  ],
};

const noop = () => undefined;

describe("ReviewGraphView", () => {
  beforeEach(() => {
    useMeasuredLayoutMock.mockReset();
    useMeasuredLayoutMock.mockReturnValue({
      nodes: view.visibleGraph.nodes,
      edges: view.visibleGraph.edges,
      layoutPending: false,
      requestRelayout: vi.fn(),
    });
  });

  it("renders structured diff, baseline, related test, package, and evidence details", () => {
    render(
      <ReviewGraphView
        view={view}
        selectedNodeId="method:new"
        onAddNode={noop}
        onSelectNode={noop}
        onInspectNode={noop}
        onDeleteNode={noop}
        onCreateEdge={noop}
        onDeleteEdge={noop}
        onMoveNode={noop}
        onRequestSourceNavigation={noop}
        onImportMermaid={noop}
      />,
    );

    expect(screen.getByTestId("review-graph-view")).toBeInTheDocument();
    expect(screen.getByTestId("graph-flow-surface")).toHaveAttribute("data-anchor", "method:new");
    expect(screen.getByLabelText("Review Graph 变更文件")).toHaveTextContent("NewService.java");
    expect(screen.getByLabelText("Review Graph 变更文件")).toHaveTextContent("72%");
    expect(screen.getByLabelText("Review Graph 变更文件")).toHaveTextContent("Unmatched.java:8");
    expect(screen.getByLabelText("Review Graph 基线符号")).toHaveTextContent("OldService.moved");
    expect(screen.getByLabelText("Review Graph 影响范围")).toHaveTextContent("com.example");
    expect(screen.getByLabelText("Review Graph 影响范围")).toHaveTextContent("调用路径");
    expect(screen.getByLabelText("Review Graph 证据片段")).toHaveTextContent("void moved() {}");
  });
});
