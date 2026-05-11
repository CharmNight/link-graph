import { describe, expect, it } from "vitest";
import {
  deriveChangeTrayState,
  deriveCurrentTarget,
  deriveEvidencePanelState,
  deriveLinkGraphOutline,
  deriveWorkflowStageStates,
} from "../../../app/components/hybridDerivations";
import type {
  DraftWorkbenchState,
  GraphBeautificationResult,
  GraphPatchResult,
  LinkGraphDocument,
  LinkGraphNode,
} from "../../../app/types";

const methodNode: LinkGraphNode = {
  id: "method:submit",
  type: "METHOD",
  title: "OrderController.submit",
  location: "src/OrderController.java:8",
  signature: "submit():void",
  inputs: [],
  outputs: [],
  certainty: "PROVEN",
  bindingStatus: "BOUND",
  sourceTag: "FACT",
};

const resourceNode: LinkGraphNode = {
  id: "xml:mapper",
  type: "XML_RESOURCE",
  title: "OrderMapper.xml",
  inputs: [],
  outputs: [],
  certainty: "RULE_INFERRED",
  bindingStatus: "BOUND",
  sourceTag: "FACT",
};

const graph: LinkGraphDocument = {
  nodes: [methodNode, resourceNode],
  edges: [{ id: "edge-1", type: "CALL", source: methodNode.id, target: resourceNode.id }],
};

const auditResult: GraphPatchResult = {
  source: "LOCAL_RULE",
  question: "是否有风险？",
  answer: "有补偿风险。",
  promptPreview: null,
  patch: null,
  findings: [
    {
      id: "finding-source",
      claim: "源码直接命中入口。",
      evidenceLevel: "DIRECT_SOURCE",
      references: [{ nodeId: methodNode.id, filePath: "src/OrderController.java", startLine: 8, endLine: 16 }],
    },
  ],
  candidateChanges: [
    {
      changeId: "change-1",
      status: "PENDING_CONFIRMATION",
      title: "补充失败补偿说明",
      targetStepIds: [],
      targetNodeIds: [methodNode.id],
      reason: "缺少失败补偿。",
      impactSummary: "影响失败路径。",
      evidence: [],
    },
  ],
  newCandidateChanges: [],
  investigationThreads: [
    {
      threadId: "thread-1",
      status: "OPEN",
      title: "失败补偿可能缺失",
      targetStepIds: [],
      targetNodeIds: [methodNode.id],
      summary: "当前没有看到失败分支。",
      evidenceGap: "缺失败分支。",
      recommendedQuestion: "继续取证失败分支。",
      evidence: [],
    },
  ],
  sourceContext: [
    {
      nodeId: methodNode.id,
      filePath: "src/OrderController.java",
      startLine: 8,
      endLine: 16,
      snippet: "orderService.submit(request);",
    },
  ],
  evidenceTrace: [
    {
      nodeId: methodNode.id,
      resolvedNodeId: methodNode.id,
      filePath: "src/OrderController.java",
      reason: "加入问答上下文。",
      startLine: 8,
      endLine: 16,
      includedInPrompt: true,
    },
  ],
  warnings: [],
};

const explanation: GraphBeautificationResult = {
  source: "LOCAL_RULE",
  granularity: "BUSINESS",
  promptPreview: null,
  warnings: [],
  steps: [
    {
      stepId: "step-1",
      title: "提交订单",
      granularity: "BUSINESS",
      kind: "BUSINESS_ACTION",
      description: "提交订单。",
      primaryNodeId: methodNode.id,
      evidence: [
        {
          id: "step-evidence-1",
          claim: "讲解也命中入口源码。",
          evidenceLevel: "DIRECT_GRAPH",
          references: [{ nodeId: methodNode.id }],
        },
      ],
      followUpQuestions: [],
      downstreamTargets: [],
    },
  ],
};

const draftState: DraftWorkbenchState = {
  draftChanges: [
    {
      entryId: "draft-1",
      kind: "CHANGE",
      sourceChangeId: "change-1",
      title: "补充失败补偿说明",
      targetStepIds: [],
      targetNodeIds: [methodNode.id],
      reason: "缺少失败补偿。",
      impactSummary: "影响失败路径。",
      evidence: [],
    },
  ],
  draftNotes: [],
};

describe("hybridDerivations", () => {
  it("derives current target from detail node before anchor fallback", () => {
    expect(deriveCurrentTarget({ detailNode: methodNode, activeAnchorNode: resourceNode, selectedNodeId: null })).toEqual({
      title: "OrderController.submit",
      path: "src/OrderController.java:8 · submit():void",
    });
    expect(deriveCurrentTarget({ detailNode: null, activeAnchorNode: null, selectedNodeId: "node-x" })).toEqual({
      title: "node-x",
      path: null,
    });
  });

  it("derives workflow stage statuses from requests and real workbench state", () => {
    expect(
      deriveWorkflowStageStates({
        graphBeautificationResult: explanation,
        graphBeautificationRequestState: { phase: "SUCCEEDED" },
        auditResult,
        auditRequestState: { phase: "RUNNING" },
        draftWorkbenchState: draftState,
        draftValidationState: { status: "REVIEW_REQUIRED", message: "blocked", unresolvedThreadIds: ["thread-1"], unresolvedThreads: [] },
        codeDiffStatus: "FAILED",
        codeDraftRequestState: { phase: "FAILED" },
      }),
    ).toEqual({
      understand: "done",
      evidence: "running",
      qa: "running",
      draft: "blocked",
      code: "failed",
    });
  });

  it("derives outline metrics and grouped items from graph, audit and draft state", () => {
    const outline = deriveLinkGraphOutline({
      activeViewGraph: graph,
      fullGraph: { nodes: [methodNode, resourceNode, { ...resourceNode, id: "sql:insert", type: "SQL", title: "insert order" }], edges: [] },
      anchorNodeId: methodNode.id,
      selectedNodeId: methodNode.id,
      auditResult,
      draftWorkbenchState: draftState,
      draftChangedNodeIds: [methodNode.id],
    });

    expect(outline.metrics).toMatchObject({
      visibleNodeCount: 2,
      fullNodeCount: 3,
      sourceAnchorCount: 1,
      draftImpactCount: 1,
      blockingRiskCount: 1,
    });
    expect(outline.items.map((item) => item.group)).toContain("entry");
    expect(outline.items.map((item) => item.group)).toContain("risk");
    expect(outline.items.map((item) => item.group)).toContain("draft");
  });

  it("does not truncate complete critical path and evidence groups while deriving the outline", () => {
    const criticalNodes: LinkGraphNode[] = Array.from({ length: 18 }, (_, index) => ({
      ...methodNode,
      id: `method:${index}`,
      title: `CriticalMethod${index}`,
    }));
    const evidenceNodes: LinkGraphNode[] = Array.from({ length: 16 }, (_, index) => ({
      ...resourceNode,
      id: `xml:${index}`,
      title: `EvidenceResource${index}`,
    }));

    const outline = deriveLinkGraphOutline({
      activeViewGraph: { nodes: [...criticalNodes, ...evidenceNodes], edges: [] },
      fullGraph: null,
      anchorNodeId: criticalNodes[0]!.id,
      selectedNodeId: null,
      auditResult: null,
      draftWorkbenchState: { draftChanges: [], draftNotes: [] },
      draftChangedNodeIds: [],
    });

    expect(outline.items.filter((item) => item.group === "criticalPath")).toHaveLength(18);
    expect(outline.items.filter((item) => item.group === "evidence")).toHaveLength(16);
    expect(outline.items.map((item) => item.label)).toContain("CriticalMethod17");
    expect(outline.items.map((item) => item.label)).toContain("EvidenceResource15");
  });

  it("derives evidence state from audit result and graph explanation evidence", () => {
    const evidence = deriveEvidencePanelState({
      selectedNode: methodNode,
      auditResult,
      graphBeautificationResult: explanation,
    });

    expect(evidence.selectedNodeEvidence.map((item) => item.label)).toContain("源码直接命中入口。");
    expect(evidence.selectedNodeEvidence.map((item) => item.label)).toContain("讲解也命中入口源码。");
    expect(evidence.evidenceGaps).toHaveLength(1);
    expect(evidence.sourceSnippets).toHaveLength(1);
    expect(evidence.evidenceTrace).toHaveLength(1);
    expect(evidence.openRiskThreads).toHaveLength(1);
    expect(evidence.pendingCandidateChanges).toHaveLength(1);
  });

  it("derives tray counts and sync label without making up data", () => {
    expect(
      deriveChangeTrayState({
        auditResult,
        draftWorkbenchState: draftState,
        draftValidationState: { status: "REVIEW_REQUIRED", message: "blocked", unresolvedThreadIds: ["thread-1"], unresolvedThreads: [] },
        codeDiffStatus: "FRESH",
        canUndoDraftPatchApply: true,
        lastAppliedDraftPatchSummary: "已应用 1 条草稿 patch",
        lastDraftPatchApplyResult: null,
      }),
    ).toEqual({
      pendingCandidateCount: 1,
      confirmedDraftCount: 1,
      blockingRiskCount: 1,
      syncStatusLabel: "已应用 1 条草稿 patch",
      codeDiffStatus: "FRESH",
      canApply: true,
      canRevert: true,
    });
  });
});
