import { describe, expect, it } from "vitest";
import {
  findExplanationStep,
  resolveExplanationFollowUpQuestion,
  resolveExplanationRerunIntent,
  resolveExplanationStepRawNodeId,
} from "../../app/appExplanationStepModel";
import type { GraphBeautificationResult, GraphBeautificationStep } from "../../app/types";

function explanationStep(overrides: Partial<GraphBeautificationStep> = {}): GraphBeautificationStep {
  return {
    stepId: "step:validate",
    title: "校验订单",
    granularity: "METHOD_CALL",
    kind: "METHOD_CALL",
    description: "校验订单入参。",
    primaryNodeId: "method:validate",
    codeSnippet: null,
    evidence: [],
    followUpQuestions: ["校验失败会怎么处理？"],
    downstreamTargets: [],
    ...overrides,
  };
}

function explanationResult(steps: GraphBeautificationStep[]): GraphBeautificationResult {
  return {
    source: "LOCAL_RULE",
    granularity: "METHOD_CALL",
    steps,
    promptPreview: null,
    warnings: [],
  };
}

describe("appExplanationStepModel", () => {
  it("finds explanation steps by id", () => {
    const step = explanationStep();
    const result = explanationResult([step]);

    expect(findExplanationStep(result, "step:validate")).toBe(step);
    expect(findExplanationStep(result, "missing")).toBeNull();
    expect(findExplanationStep(null, "step:validate")).toBeNull();
  });

  it("uses primary node id before falling back to evidence references", () => {
    expect(resolveExplanationStepRawNodeId(explanationStep())).toBe("method:validate");
    expect(resolveExplanationStepRawNodeId(explanationStep({
      primaryNodeId: null,
      evidence: [{
        id: "finding:1",
        claim: "直接源码显示校验调用。",
        evidenceLevel: "DIRECT_SOURCE",
        references: [
          { filePath: "OrderService.kt" },
          { nodeId: "method:evidence" },
        ],
      }],
    }))).toBe("method:evidence");
    expect(resolveExplanationStepRawNodeId(undefined)).toBeNull();
  });

  it("resolves follow-up questions from custom text, step suggestions, then default wording", () => {
    expect(resolveExplanationFollowUpQuestion(explanationStep(), "  自定义问题？  ")).toBe("自定义问题？");
    expect(resolveExplanationFollowUpQuestion(explanationStep(), "   ")).toBe("校验失败会怎么处理？");
    expect(resolveExplanationFollowUpQuestion(explanationStep({
      followUpQuestions: [],
    }))).toBe("请继续解释这一步的关键输入、条件和输出。");
  });

  it("keeps class description reruns on the describe intent only for class diagrams", () => {
    expect(resolveExplanationRerunIntent("CLASS_DIAGRAM", "DESCRIBE_CLASS")).toBe("DESCRIBE_CLASS");
    expect(resolveExplanationRerunIntent("FLOWCHART", "DESCRIBE_CLASS")).toBe("EXPLAIN_CODE");
    expect(resolveExplanationRerunIntent("CLASS_DIAGRAM", "EXPLAIN_CODE")).toBe("EXPLAIN_CODE");
  });
});
