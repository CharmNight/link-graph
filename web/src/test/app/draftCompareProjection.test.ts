import { describe, expect, it } from "vitest";
import { buildDraftCompareProjection } from "../../app/draftCompareProjection";
import type { DraftWorkbenchEntry, LinkGraphDocument } from "../../app/types";

function visibleGraphFixture(): LinkGraphDocument {
  return {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
      },
      {
        id: "method:new-node",
        type: "METHOD",
        title: "OrderCompensator.compensate",
        inputs: [],
        outputs: [],
        confidence: "VERIFIED",
        binding: "CODE_BOUND",
      },
    ],
    edges: [
      {
        id: "edge:submit->compensate",
        type: "CALL",
        source: "method:submit-order",
        target: "method:new-node",
      },
    ],
  };
}

function draftEntryFixture(): DraftWorkbenchEntry {
  return {
    entryId: "draft-change-compensate",
    kind: "CHANGE",
    title: "补充失败补偿说明",
    sourceChangeId: "change-compensate",
    targetStepIds: ["step-submit-order"],
    targetNodeIds: ["method:submit-order", "method:missing-from-view", "method:new-node"],
    beforeState: "当前没有失败补偿说明",
    afterState: "补充失败补偿逻辑说明",
    reason: "当前链路缺少失败补偿语义。",
    impactSummary: "影响订单提交失败后的处理理解。",
    claimType: "CODE_FACT",
    evidence: [
      {
        id: "finding-compensate",
        claim: "异常流程需要补偿动作。",
        evidenceLevel: "DIRECT_SOURCE",
        references: [
          { nodeId: "method:submit-order" },
          { nodeId: "method:new-node" },
        ],
      },
    ],
  };
}

describe("buildDraftCompareProjection", () => {
  it("marks an updated existing decision node as modified in place instead of inventing a compare ghost", () => {
    const projection = buildDraftCompareProjection({
      compareMode: "compare",
      selectedEntry: {
        ...draftEntryFixture(),
        title: "调整删除判断",
        targetNodeIds: ["scope:delete-guard"],
        graphPatch: {
          summary: "调整删除判断",
          operations: [
            {
              id: "patch-op-delete-guard",
              action: "UPDATE_NODE",
              elementKind: "NODE",
              elementId: "scope:delete-guard",
              node: {
                id: "scope:delete-guard",
                type: "FLOW_SCOPE",
                title: "if (delete == true)",
                inputs: [],
                outputs: [],
                confidence: "SUGGESTED",
                binding: "CODE_BOUND",
                metadata: {
                  "flowchart.kind": "DECISION",
                },
              },
            },
          ],
          addedNodeIds: [],
          removedNodeIds: [],
          addedEdgeIds: [],
          removedEdgeIds: [],
        },
        evidence: [
          {
            id: "finding-delete-guard",
            claim: "当前源码里直接能看到删除判断条件。",
            evidenceLevel: "DIRECT_SOURCE",
            references: [{ nodeId: "scope:delete-guard" }],
          },
        ],
      },
      visibleGraph: {
        nodes: [
          {
            id: "scope:delete-guard",
            type: "FLOW_SCOPE",
            title: "if (delete == true)",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flowchart.kind": "DECISION",
            },
          },
        ],
        edges: [],
      },
      referenceGraph: {
        nodes: [
          {
            id: "scope:delete-guard",
            type: "FLOW_SCOPE",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flowchart.kind": "DECISION",
            },
          },
        ],
        edges: [],
      },
      workingGraph: {
        nodes: [
          {
            id: "scope:delete-guard",
            type: "FLOW_SCOPE",
            title: "if (delete == true)",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flowchart.kind": "DECISION",
            },
          },
        ],
        edges: [],
      },
    });

    expect(projection).toMatchObject({
      nodeStatuses: {
        "scope:delete-guard": "MODIFIED",
      },
      edgeStatuses: {},
      summary: {
        visibleNodeCount: 1,
        hiddenNodeCount: 0,
      },
    });
    expect(projection?.compareGraph.nodes.map((node) => node.id)).toEqual(["scope:delete-guard"]);
  });

  it("builds compare annotations from real graph diffs only, instead of marking every scoped node as modified", () => {
    const projection = buildDraftCompareProjection({
      compareMode: "compare",
      selectedEntry: draftEntryFixture(),
      visibleGraph: visibleGraphFixture(),
      referenceGraph: {
        nodes: [
          {
            id: "method:submit-order",
            type: "METHOD",
            title: "OrderController.submit",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
          {
            id: "method:missing-from-view",
            type: "METHOD",
            title: "OrderController.failover",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
        ],
        edges: [
          {
            id: "edge:submit->missing",
            type: "CALL",
            source: "method:submit-order",
            target: "method:missing-from-view",
          },
        ],
      },
      workingGraph: {
        nodes: [
          {
            id: "method:submit-order",
            type: "METHOD",
            title: "OrderController.submit",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
          {
            id: "method:new-node",
            type: "METHOD",
            title: "OrderCompensator.compensate",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
        ],
        edges: [
          {
            id: "edge:submit->compensate",
            type: "CALL",
            source: "method:submit-order",
            target: "method:new-node",
          },
        ],
      },
    });

    expect(projection).toMatchObject({
      entryId: "draft-change-compensate",
      entryTitle: "补充失败补偿说明",
      compareGraph: {
        nodes: [
          { id: "method:submit-order" },
          { id: "method:new-node" },
          { id: "draft-ghost-node:draft-change-compensate:method:missing-from-view" },
        ],
        edges: [
          { id: "edge:submit->compensate" },
        ],
      },
      nodeStatuses: {
        "draft-ghost-node:draft-change-compensate:method:missing-from-view": "REMOVED",
        "method:new-node": "ADDED",
      },
      edgeStatuses: {
        "edge:submit->compensate": "MODIFIED",
      },
      summary: {
        visibleNodeCount: 2,
        visibleEdgeCount: 1,
        hiddenNodeCount: 1,
        hiddenEdgeCount: 0,
      },
    });
  });

  it("returns null when the selected draft change has no real graph diff to project", () => {
    expect(buildDraftCompareProjection({
      compareMode: "compare",
      selectedEntry: {
        ...draftEntryFixture(),
        targetNodeIds: ["method:submit-order"],
        evidence: [
          {
            id: "finding-submit-order",
            claim: "提交链路需要补充说明。",
            evidenceLevel: "DIRECT_SOURCE",
            references: [{ nodeId: "method:submit-order" }],
          },
        ],
      },
      visibleGraph: {
        nodes: [
          {
            id: "method:submit-order",
            type: "METHOD",
            title: "OrderController.submit",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
        ],
        edges: [],
      },
      referenceGraph: {
        nodes: [
          {
            id: "method:submit-order",
            type: "METHOD",
            title: "OrderController.submit",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
        ],
        edges: [],
      },
      workingGraph: {
        nodes: [
          {
            id: "method:submit-order",
            type: "METHOD",
            title: "OrderController.submit",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
        ],
        edges: [],
      },
    })).toBeNull();
  });

  it("returns null when the draft pane is not in compare mode", () => {
    expect(buildDraftCompareProjection({
      compareMode: "after",
      selectedEntry: draftEntryFixture(),
      visibleGraph: visibleGraphFixture(),
      referenceGraph: null,
      workingGraph: visibleGraphFixture(),
    })).toBeNull();
  });

  it("overlays aliased readable flowchart nodes with after-state content instead of leaving the stale visible title in place", () => {
    const projection = buildDraftCompareProjection({
      compareMode: "compare",
      selectedEntry: {
        ...draftEntryFixture(),
        targetNodeIds: ["action:delete-condition"],
        evidence: [
          {
            id: "finding-delete-condition",
            claim: "条件判断来自被折叠的 guard 条件节点。",
            evidenceLevel: "DIRECT_SOURCE",
            references: [{ nodeId: "action:delete-condition" }],
          },
        ],
      },
      visibleGraph: {
        nodes: [
          {
            id: "scope:file-download-if",
            type: "FLOW_SCOPE",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flowchart.kind": "DECISION",
              "flowchart.projectedFromNodeIds": "action:delete-condition",
            },
          },
        ],
        edges: [],
      },
      referenceGraph: {
        nodes: [
          {
            id: "action:delete-condition",
            type: "FLOW_ACTION",
            title: "if (delete)",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flowchart.kind": "PROCESS",
              "flow.kind": "CONDITION",
            },
          },
        ],
        edges: [],
      },
      workingGraph: {
        nodes: [
          {
            id: "action:delete-condition",
            type: "FLOW_ACTION",
            title: "if (delete == true)",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
            metadata: {
              "flowchart.kind": "PROCESS",
              "flow.kind": "CONDITION",
            },
          },
        ],
        edges: [],
      },
    });

    expect(projection).toMatchObject({
      compareGraph: {
        nodes: [
          {
            id: "scope:file-download-if",
            title: "if (delete == true)",
          },
        ],
      },
      nodeStatuses: {
        "scope:file-download-if": "MODIFIED",
      },
      summary: {
        visibleNodeCount: 1,
        hiddenNodeCount: 0,
      },
    });
  });

  it("preserves the visible graph order when building compare graphs so layout input order does not jump", () => {
    const projection = buildDraftCompareProjection({
      compareMode: "compare",
      selectedEntry: {
        ...draftEntryFixture(),
        targetNodeIds: ["method:z-anchor", "method:new-tail"],
        evidence: [
          {
            id: "finding-layout-order",
            claim: "顺序变化会扰动流程图布局。",
            evidenceLevel: "DIRECT_GRAPH",
            references: [{ nodeId: "method:z-anchor" }, { nodeId: "method:new-tail" }],
          },
        ],
      },
      visibleGraph: {
        nodes: [
          {
            id: "method:z-anchor",
            type: "METHOD",
            title: "ZAnchor",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
          {
            id: "method:a-middle",
            type: "METHOD",
            title: "AMiddle",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
        ],
        edges: [
          {
            id: "edge:z->a",
            type: "CALL",
            source: "method:z-anchor",
            target: "method:a-middle",
          },
        ],
      },
      referenceGraph: {
        nodes: [
          {
            id: "method:z-anchor",
            type: "METHOD",
            title: "ZAnchor",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
          {
            id: "method:a-middle",
            type: "METHOD",
            title: "AMiddle",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
        ],
        edges: [
          {
            id: "edge:z->a",
            type: "CALL",
            source: "method:z-anchor",
            target: "method:a-middle",
          },
        ],
      },
      workingGraph: {
        nodes: [
          {
            id: "method:z-anchor",
            type: "METHOD",
            title: "ZAnchor updated",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
          {
            id: "method:a-middle",
            type: "METHOD",
            title: "AMiddle",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
          {
            id: "method:new-tail",
            type: "METHOD",
            title: "NewTail",
            inputs: [],
            outputs: [],
            confidence: "VERIFIED",
            binding: "CODE_BOUND",
          },
        ],
        edges: [
          {
            id: "edge:z->a",
            type: "CALL",
            source: "method:z-anchor",
            target: "method:a-middle",
          },
          {
            id: "edge:a->tail",
            type: "CALL",
            source: "method:a-middle",
            target: "method:new-tail",
          },
        ],
      },
    });

    expect(projection?.compareGraph.nodes.map((node) => node.id)).toEqual([
      "method:z-anchor",
      "method:a-middle",
      "method:new-tail",
    ]);
    expect(projection?.compareGraph.edges.map((edge) => edge.id)).toEqual([
      "edge:z->a",
      "edge:a->tail",
    ]);
  });
});
