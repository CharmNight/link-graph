import { describe, expect, it } from "vitest";
import type { AsyncRequestState, OperationFeedback } from "./types";
import { resolveToolbarFeedback } from "./asyncRequestStatus";

function runningRequest(overrides: Partial<AsyncRequestState> = {}): AsyncRequestState {
  return {
    phase: "RUNNING",
    scene: "审计",
    statusMessage: "正在等待远程 LLM 审计响应",
    startedAtEpochMillis: 100,
    ...overrides,
  };
}

function succeededRequest(overrides: Partial<AsyncRequestState> = {}): AsyncRequestState {
  return {
    phase: "SUCCEEDED",
    scene: "审计",
    statusMessage: "审计完成，已生成待确认变更。",
    finishedAtEpochMillis: 200,
    ...overrides,
  };
}

describe("resolveToolbarFeedback", () => {
  it("prefers the latest running async request over stale operation feedback", () => {
    const operationFeedback: OperationFeedback = {
      level: "INFO",
      message: "已发起远程 LLM 审计请求，当前采用流式输出。",
    };

    const result = resolveToolbarFeedback({
      operationFeedback,
      lastMessageType: "auditResult",
      requestStates: [
        succeededRequest({
          scene: "讲解",
          statusMessage: "讲解完成。",
          finishedAtEpochMillis: 120,
        }),
        runningRequest({
          scene: "审计",
          startedAtEpochMillis: 180,
        }),
      ],
    });

    expect(result).toEqual({
      level: "INFO",
      message: "审计：正在等待远程 LLM 审计响应",
      source: "async-request",
    });
  });

  it("prefers the latest completed async request when no request is running", () => {
    const result = resolveToolbarFeedback({
      operationFeedback: {
        level: "SUCCESS",
        message: "已加载当前编辑器上下文链路：OrderController.submit",
      },
      lastMessageType: "auditResult",
      requestStates: [
        succeededRequest({
          scene: "讲解",
          statusMessage: "讲解完成。",
          finishedAtEpochMillis: 220,
        }),
        succeededRequest({
          scene: "审计",
          statusMessage: "审计完成，已生成待确认变更。",
          finishedAtEpochMillis: 320,
        }),
      ],
    });

    expect(result).toEqual({
      level: "SUCCESS",
      message: "审计：审计完成，已生成待确认变更。",
      source: "async-request",
    });
  });

  it("falls back to operation feedback when all request states are idle or empty", () => {
    const result = resolveToolbarFeedback({
      operationFeedback: {
        level: "SUCCESS",
        message: "已确认候选变更并写入草稿层。",
      },
      requestStates: [
        { phase: "IDLE" },
        { phase: "SUCCEEDED", scene: "讲解" },
      ],
    });

    expect(result).toEqual({
      level: "SUCCESS",
      message: "已确认候选变更并写入草稿层。",
      source: "operation-feedback",
    });
  });

  it("surfaces failed async requests as error-level toolbar feedback", () => {
    const result = resolveToolbarFeedback({
      operationFeedback: null,
      requestStates: [
        {
          phase: "FAILED",
          scene: "代码草稿",
          errorMessage: "代码草稿失败",
          finishedAtEpochMillis: 450,
        },
      ],
    });

    expect(result).toEqual({
      level: "ERROR",
      message: "代码草稿：代码草稿失败",
      source: "async-request",
    });
  });

  it("provides a stable scene-based success message when a completed async request has no explicit status text", () => {
    const result = resolveToolbarFeedback({
      operationFeedback: {
        level: "SUCCESS",
        message: "已加载当前编辑器上下文链路：OrderController.submit",
      },
      lastMessageType: "graphBeautificationResult",
      requestStates: [
        {
          phase: "SUCCEEDED",
          scene: "链路讲解",
          finishedAtEpochMillis: 520,
        },
      ],
    });

    expect(result).toEqual({
      level: "SUCCESS",
      message: "链路讲解：链路讲解完成，已更新步骤列表",
      source: "async-request",
    });
  });
});
