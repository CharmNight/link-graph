import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DraftValidationPanel } from "../../../app/workbench/DraftValidationPanel";

describe("DraftValidationPanel", () => {
  it("surfaces pending risk validation inside the draft tab", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <DraftValidationPanel
        validationState={{
          status: "REVIEW_REQUIRED",
          message: "当前草稿仍有 2 条待验证风险。",
          detailMessage: "请先确认这些风险是继续取证、接受、排除，还是回退对应草稿变更。",
          unresolvedThreadIds: ["thread-a", "thread-b"],
          unresolvedThreads: [
            {
              threadId: "thread-a",
              title: "默认兜底路径待确认",
              summary: "当前还没有直接证据证明 delete=false 时的兜底处理。",
              recommendedQuestion: "delete=false 时会不会直接返回错误？",
            },
            {
              threadId: "thread-b",
              title: "文件删除后补偿逻辑待确认",
              summary: "尚未确认删除失败后的补偿分支。",
              recommendedQuestion: "删除失败后有没有重试或回滚？",
            },
          ],
        } as any}
        onOpenQaWorkbench={() => events.push("open-qa")}
      />,
    );

    expect(screen.getByText("当前草稿仍有 2 条待验证风险。")).toBeInTheDocument();
    expect(screen.getByText("默认兜底路径待确认")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "继续风险取证" }));

    expect(events).toEqual(["open-qa"]);
  });

  it("shows a ready state once the draft has no unresolved risk", () => {
    render(
      <DraftValidationPanel
        validationState={{
          status: "READY",
          message: "当前草稿已完成验证，可以继续生成实现建议或代码 diff。",
          detailMessage: null,
          unresolvedThreadIds: [],
          unresolvedThreads: [],
        } as any}
        onOpenQaWorkbench={() => undefined}
      />,
    );

    expect(screen.getByText("当前草稿已完成验证，可以继续生成实现建议或代码 diff。")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "继续风险取证" })).not.toBeInTheDocument();
  });
});
