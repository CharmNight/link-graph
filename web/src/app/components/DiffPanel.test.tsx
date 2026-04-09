import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DiffPanel } from "./DiffPanel";
import type { DiffItem } from "../types";

const items: DiffItem[] = [
  {
    id: "class:order-draft-dto",
    title: "OrderDraftDto",
    status: "ONLY_IN_MERMAID",
    description: "Design node is missing from code.",
  },
];

describe("DiffPanel", () => {
  it("renders diff items and supports selecting one item", async () => {
    const user = userEvent.setup();
    const selected: string[] = [];
    const questions: string[] = [];

    render(
      <DiffPanel
        items={items}
        selectedItemIds={["class:order-draft-dto"]}
        factGraph={{ nodes: [], edges: [] }}
        designBaseline={{ nodes: [{ id: "class:order-draft-dto", type: "CLASS", title: "OrderDraftDto", inputs: [], outputs: [], certainty: "PROVEN", bindingStatus: "DESIGN_ONLY" }], edges: [] }}
        result={{
          source: "MOCK",
          question: "为什么设计节点没有落地？",
          answer: "设计基线里有 DTO，但代码事实层还没有对应实现。",
          findings: [],
          promptPreview: "prompt",
          warnings: ["模型 gpt-5.4 在当前兼容服务中不可用（HTTP 503 / model_not_found）。请在设置中改成服务端已开通的模型。"],
          patch: {
            summary: "新增 DTO 草稿",
            operations: [],
            addedNodeIds: [],
            removedNodeIds: [],
            addedEdgeIds: [],
            removedEdgeIds: [],
          },
        }}
        onSelectItem={(itemId) => selected.push(itemId)}
        onRequestReview={(question) => questions.push(question)}
        onOpenPatchPreview={() => questions.push("open-preview")}
      />,
    );

    expect(screen.getByText("OrderDraftDto")).toBeInTheDocument();
    expect(screen.getByText("当前焦点：OrderDraftDto")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /定位 class:order-draft-dto/i }));
    await user.type(screen.getByLabelText("差异问题"), "为什么设计节点没有落地？");
    await user.click(screen.getByRole("button", { name: "继续差异问答" }));
    await user.click(screen.getByRole("button", { name: "查看并写入修订草稿" }));

    expect(selected).toEqual(["class:order-draft-dto"]);
    expect(questions).toEqual(["为什么设计节点没有落地？", "open-preview"]);
    expect(screen.getByText("核心结论")).toBeInTheDocument();
    expect(screen.getByText("设计基线里有 DTO，但代码事实层还没有对应实现。")).toBeInTheDocument();
    expect(screen.getByText("当前对比对象：设计基线 Mermaid vs 代码事实链路")).toBeInTheDocument();
    expect(screen.getByText("来源 本地规则")).toBeInTheDocument();
    expect(screen.getByText(/模型 gpt-5.4 在当前兼容服务中不可用/)).toBeInTheDocument();
    expect(screen.queryByText(/model_not_found/)).not.toBeInTheDocument();
  });

  it("shows the failure reason and allows retrying diff review with the current question", async () => {
    const user = userEvent.setup();
    const questions: string[] = [];

    render(
      <DiffPanel
        items={items}
        selectedItemIds={["class:order-draft-dto"]}
        factGraph={{ nodes: [], edges: [] }}
        designBaseline={{ nodes: [], edges: [] }}
        result={null}
        requestError="差异问答失败：HTTP 503"
        onSelectItem={() => undefined}
        onRequestReview={(question) => questions.push(question)}
        onOpenPatchPreview={() => undefined}
      />,
    );

    expect(screen.getByText("差异问答失败：HTTP 503")).toBeInTheDocument();
    await user.type(screen.getByLabelText("差异问题"), "请重新解释这些差异");
    await user.click(screen.getByRole("button", { name: "重试差异问答" }));

    expect(questions).toEqual(["请重新解释这些差异"]);
  });
});
