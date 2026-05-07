import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { PatchResultSummary } from "../../../app/components/PatchResultSummary";

describe("PatchResultSummary", () => {
  it("renders the original question and readable warning details", () => {
    render(
      <PatchResultSummary
        title="问答回答"
        result={{
          source: "MOCK",
          question: "为什么这段链路可能遗漏默认兜底？",
          answer: [
            "结论：当前链路缺少默认兜底说明。",
            "关键影响：",
            "- 当路由条件未命中时，人工问答仍无法确认真实业务分支。",
            "建议动作：",
            "- 先补一个默认兜底说明节点，再决定是否生成代码。",
            "注意事项：",
            "- 该建议来自规则分析，仍需结合真实实现复核。",
          ].join("\n"),
          findings: [
            {
              id: "fallback-missing",
              claim: "当前上下文没有直接观察到默认兜底分支。",
              evidenceLevel: "NOT_OBSERVED",
              references: [
                {
                  nodeId: "method:order-service-place",
                },
              ],
            },
          ],
          candidateChanges: [],
          newCandidateChanges: [],
          promptPreview: "prompt",
          warnings: [
            "远程 LLM 问答失败，已回退为本地规则分析：HTTP 503 / model_not_found。已尝试接口：https://example.com/v1/chat/completions，模型：gpt-5.4。",
          ],
        }}
      />,
    );

    expect(screen.getByText("提问")).toBeInTheDocument();
    expect(screen.getByText("为什么这段链路可能遗漏默认兜底？")).toBeInTheDocument();
    expect(screen.getByText("关键影响")).toBeInTheDocument();
    expect(screen.getByText("建议动作")).toBeInTheDocument();
    expect(screen.getByText("真实性边界")).toBeInTheDocument();
    expect(screen.getByText("关键结论与证据")).toBeInTheDocument();
    expect(screen.getByText("当前上下文没有直接观察到默认兜底分支。")).toBeInTheDocument();
    expect(screen.getByText("当前未观察到")).toBeInTheDocument();
    expect(screen.getByText("关联节点 method:order-service-place")).toBeInTheDocument();
    expect(screen.getByText("当前回答属于本地规则分析，用于帮助你定位风险，不是完整源码真值判定。")).toBeInTheDocument();
    expect(screen.getByText(/已尝试接口：https:\/\/example\.com\/v1\/chat\/completions/)).toBeInTheDocument();
  });

  it("keeps prompt preview collapsed until the user explicitly expands it", async () => {
    const user = userEvent.setup();

    render(
      <PatchResultSummary
        title="问答回答"
        result={{
          source: "REMOTE",
          question: "这段链路的真正提示词是什么？",
          answer: "结论：当前可以看到调试用提示词。",
          findings: [],
          candidateChanges: [],
          newCandidateChanges: [],
          promptPreview: "system: audit graph\nuser: inspect fallback branch",
          warnings: [],
        }}
      />,
    );

    expect(screen.queryByText("system: audit graph\nuser: inspect fallback branch")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "查看提示词" }));
    expect(screen.getByText((_, element) => element?.textContent === "system: audit graph\nuser: inspect fallback branch")).toBeInTheDocument();
  });
});
