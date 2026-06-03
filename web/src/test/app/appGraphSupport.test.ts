import { describe, expect, it } from "vitest";
import {
  deriveFlowchartSummary,
  scopeFlowchartGraphToAnchorMethod,
  syncArchitectureGraphViewLayout,
  syncClassDiagramViewLayout,
  syncReviewGraphViewLayout,
} from "../../app/appGraphSupport";
import type {
  ArchitectureGraphViewDocument,
  ClassDiagramViewDocument,
  GraphViewPresentation,
  IndexedGraphSummary,
  LinkGraphDocument,
  ReviewGraphViewDocument,
} from "../../app/types";

const EMPTY_PRESENTATION: GraphViewPresentation = {
  target: {
    nodeId: null,
    title: "",
    subtitle: "",
    location: null,
  },
  lanes: [],
  hiddenBuckets: [],
  controls: {
    primaryScope: "",
    availableScopes: [],
    searchable: true,
    expandable: true,
  },
};

describe("deriveFlowchartSummary", () => {
  it("counts flow-scope IF nodes as branches even when stale metadata says process", () => {
    const graph: LinkGraphDocument = {
      nodes: [
        {
          id: "scope:delete-if",
          type: "FLOW_SCOPE",
          title: "if (delete)",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "flow.kind": "IF",
            "flowchart.kind": "PROCESS",
          },
        },
      ],
      edges: [],
    };

    expect(deriveFlowchartSummary(graph).branchCount).toBe(1);
  });
});

describe("scopeFlowchartGraphToAnchorMethod", () => {
  it("keeps invocation expansion nodes attached to the anchored method", () => {
    const graph: LinkGraphDocument = {
      nodes: [
        {
          id: "method:caller",
          type: "METHOD",
          title: "Caller.run",
          signature: "com.example.Caller.run():void",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: { "flowchart.kind": "ENTRY" },
        },
        {
          id: "invoke:create-info",
          type: "FLOW_ACTION",
          title: "systemService.createInfo()",
          signature: "com.example.SystemService.createInfo():void",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "flow.kind": "INVOCATION",
            "flow.ownerMethod": "com.example.Caller.run():void",
            "flowchart.kind": "SUBROUTINE",
          },
        },
        {
          id: "method:create-info",
          type: "METHOD",
          title: "SystemService.createInfo",
          signature: "com.example.SystemService.createInfo():void",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "flow.ownerMethod": "com.example.SystemService.createInfo():void",
            "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
            "linkGraph.expansion.id": "invocation:expansion-1",
          },
        },
        {
          id: "action:save-info",
          type: "FLOW_ACTION",
          title: "saveInfo()",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "flow.kind": "ACTION",
            "flow.ownerMethod": "com.example.SystemService.createInfo():void",
            "linkGraph.expansion.sourceInvocationNodeId": "invoke:create-info",
            "linkGraph.expansion.id": "invocation:expansion-1",
          },
        },
        {
          id: "method:other",
          type: "METHOD",
          title: "Other.run",
          signature: "com.example.Other.run():void",
          inputs: [],
          outputs: [],
          certainty: "PROVEN",
          bindingStatus: "BOUND",
        },
      ],
      edges: [
        {
          id: "control:caller-to-invoke",
          type: "CONTROL_FLOW",
          source: "method:caller",
          target: "invoke:create-info",
        },
        {
          id: "call:invoke-to-target",
          type: "CALL",
          source: "invoke:create-info",
          target: "method:create-info",
        },
        {
          id: "control:target-to-save",
          type: "CONTROL_FLOW",
          source: "method:create-info",
          target: "action:save-info",
        },
        {
          id: "call:caller-to-other",
          type: "CALL",
          source: "method:caller",
          target: "method:other",
        },
      ],
    };

    const scoped = scopeFlowchartGraphToAnchorMethod(graph, "method:caller");

    expect(scoped.nodes.map((node) => node.id).sort()).toEqual([
      "action:save-info",
      "invoke:create-info",
      "method:caller",
      "method:create-info",
    ]);
    expect(scoped.edges.map((edge) => edge.id).sort()).toEqual([
      "call:invoke-to-target",
      "control:caller-to-invoke",
      "control:target-to-save",
    ]);
  });
});

describe("view layout summary sync", () => {
  it("keeps backend architecture summary when only positions change", () => {
    const indexed = indexedSummary("ARCHITECTURE", 10);
    const view: ArchitectureGraphViewDocument = {
      visibleGraph: {
        nodes: [node("module", "MODULE")],
        edges: [],
      },
      fullGraph: {
        nodes: [node("module", "MODULE")],
        edges: [],
      },
      summary: {
        moduleCount: 1,
        packageCount: 0,
        serviceCount: 0,
        resourceCount: 0,
        layerCount: 0,
        relationCount: 0,
        classCount: 42,
        indexed,
      },
      presentation: EMPTY_PRESENTATION,
    };

    const next = syncArchitectureGraphViewLayout(view, [{ id: "module", position: { x: 1, y: 2 } }]);

    expect(next.summary).toBe(view.summary);
    expect(next.summary.indexed).toBe(indexed);
    expect(next.summary.classCount).toBe(42);
  });

  it("keeps backend review graph summary when only positions change", () => {
    const indexed = indexedSummary("REVIEW", 3);
    const view: ReviewGraphViewDocument = {
      visibleGraph: {
        nodes: [node("changed", "METHOD", { "review.role": "CHANGED" })],
        edges: [],
      },
      fullGraph: {
        nodes: [node("changed", "METHOD", { "review.role": "CHANGED" })],
        edges: [],
      },
      summary: {
        changedSymbolCount: 1,
        upstreamCount: 0,
        downstreamCount: 0,
        relatedTestCount: 0,
        affectedPackageCount: 0,
        affectedModuleCount: 0,
        evidenceRefCount: 7,
        indexed,
      },
    };

    const next = syncReviewGraphViewLayout(view, [{ id: "changed", position: { x: 20, y: 30 } }]);

    expect(next.summary).toBe(view.summary);
    expect(next.summary.indexed).toBe(indexed);
    expect(next.visibleGraph.nodes[0]?.position).toEqual({ x: 20, y: 30 });
    expect(next.summary.evidenceRefCount).toBe(7);
  });

  it("keeps backend class diagram summary when only positions change", () => {
    const indexed = indexedSummary("CLASS_DIAGRAM", 24);
    const view: ClassDiagramViewDocument = {
      visibleGraph: {
        nodes: [node("service", "CLASS")],
        edges: [],
      },
      fullGraph: {
        nodes: [node("service", "CLASS")],
        edges: [],
      },
      summary: {
        classCount: 1,
        interfaceCount: 0,
        enumCount: 0,
        annotationCount: 0,
        recordCount: 0,
        objectCount: 0,
        relationCount: 0,
        spiProviderCount: 3,
        reflectionRelationCount: 2,
        indexed,
      },
      presentation: EMPTY_PRESENTATION,
    };

    const next = syncClassDiagramViewLayout(view, [{ id: "service", position: { x: 4, y: 8 } }]);

    expect(next.summary).toBe(view.summary);
    expect(next.summary.indexed).toBe(indexed);
    expect(next.visibleGraph.nodes[0]?.position).toEqual({ x: 4, y: 8 });
    expect(next.summary.spiProviderCount).toBe(3);
    expect(next.summary.reflectionRelationCount).toBe(2);
  });
});

function node(
  id: string,
  type: LinkGraphDocument["nodes"][number]["type"],
  metadata?: Record<string, string>,
): LinkGraphDocument["nodes"][number] {
  return {
    id,
    type,
    title: id,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    metadata,
  };
}

function indexedSummary(view: IndexedGraphSummary["view"], projectNodeCount: number): IndexedGraphSummary {
  return {
    view,
    anchorKind: null,
    anchorNodeId: null,
    anchorTitle: null,
    anchorQualifiedName: null,
    scopeKind: "PROJECT",
    scopeLabel: "Project",
    relationKinds: [],
    depth: 1,
    projectNodeCount,
    projectClassCount: projectNodeCount,
    externalNodeCount: 1,
    jdkNodeCount: 1,
    projectSourceNodeCount: projectNodeCount,
    externalLibraryNodeCount: 1,
    resourceNodeCount: 0,
    aggregateNodeCount: 0,
    projectLayerCounts: {
      projectSource: projectNodeCount,
      externalLibrary: 1,
      jdk: 1,
      resource: 0,
      aggregate: 0,
    },
    visibleLayerCounts: {
      projectSource: 1,
      externalLibrary: 0,
      jdk: 0,
      resource: 0,
      aggregate: 0,
    },
    scopedLayerCounts: {
      projectSource: 1,
      externalLibrary: 0,
      jdk: 0,
      resource: 0,
      aggregate: 0,
    },
    candidateLayerCounts: {
      projectSource: 1,
      externalLibrary: 0,
      jdk: 0,
      resource: 0,
      aggregate: 0,
    },
    hiddenLayerCounts: {
      projectSource: 0,
      externalLibrary: 0,
      jdk: 0,
      resource: 0,
      aggregate: 0,
    },
    collapsedLayerCounts: {
      projectSource: 0,
      externalLibrary: 0,
      jdk: 0,
      resource: 0,
      aggregate: 0,
    },
    scopedNodeCount: 1,
    visibleNodeCount: 1,
    hiddenNodeCount: 0,
    hiddenEdgeCount: 0,
    candidateNodeCount: 1,
    candidateEdgeCount: 0,
    truncated: false,
    completeness: "Interactive",
    cacheState: "REUSED_FULL_INDEX",
  };
}
