import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { CodeDraftTurnCard } from "../../../../app/assistant/cards/CodeDraftTurnCard";
import type { AssistantTurn, GeneratedCodeDraft } from "../../../../app/types";

function baseTurn(overrides: Partial<AssistantTurn> = {}): AssistantTurn {
  return {
    turnId: "turn-code-draft",
    kind: "CODE_DRAFT",
    createdAtEpochMillis: 1,
    context: {
      selectedNodeIds: [],
      selectedDiffItemIds: [],
      analysisDisplayMode: "FLOWCHART",
      currentSceneId: "WORKSPACE_FLOWCHART",
      selectedMethodSignature: null,
      scopeLabel: "OrderController.submit",
    },
    ...overrides,
  } as AssistantTurn;
}

function draft(overrides: Partial<GeneratedCodeDraft> = {}): GeneratedCodeDraft {
  return {
    id: "draft-fallback-test",
    sourceNodeId: "method:submit-order",
    title: "补齐订单提交失败测试",
    targetPath: "src/test/java/com/example/OrderControllerTest.java",
    commandKind: "CREATE_FILE",
    content: "class OrderControllerTest {}",
    warnings: [],
    ...overrides,
  };
}

describe("CodeDraftTurnCard", () => {
  it("renders empty state with the generate button when no drafts are present", () => {
    const onRequestCodeDrafts = vi.fn();
    render(
      <CodeDraftTurnCard
        turn={baseTurn()}
        onRequestCodeDrafts={onRequestCodeDrafts}
        onWriteCodeDrafts={vi.fn()}
      />,
    );

    expect(screen.getByText("还没有代码草稿。先基于实现建议生成代码 diff。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "生成代码 diff" })).toBeInTheDocument();
  });

  it("renders failure notice when the turn failed and no drafts are present", () => {
    render(
      <CodeDraftTurnCard
        turn={baseTurn({
          codeDrafts: [],
          failure: {
            resultId: "code-draft-failure:1",
            message: "代码生成超时",
            detailMessage: "RemoteLlmEndpoint timeout",
            phase: "FAILED",
            requestId: 1,
            sourceMessageType: "requestCodeDrafts",
          },
        })}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
      />,
    );

    expect(screen.getByText("代码生成超时")).toBeInTheDocument();
    expect(screen.queryByText("还没有代码草稿。")).not.toBeInTheDocument();
  });

  it("renders each draft title, path, and write-all button when drafts are present", () => {
    render(
      <CodeDraftTurnCard
        turn={baseTurn({
          codeDrafts: [
            draft(),
            draft({
              id: "draft-logging",
              title: "补充失败日志埋点",
              targetPath: "src/main/java/com/example/OrderController.java",
            }),
          ],
        })}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
      />,
    );

    expect(screen.getByText("补齐订单提交失败测试")).toBeInTheDocument();
    expect(screen.getByText("src/test/java/com/example/OrderControllerTest.java")).toBeInTheDocument();
    expect(screen.getByText("补充失败日志埋点")).toBeInTheDocument();
    expect(screen.getByText("src/main/java/com/example/OrderController.java")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "写入全部" })).toBeInTheDocument();
  });

  it("deduplicates warnings across turn-level warnings and per-draft warnings", () => {
    render(
      <CodeDraftTurnCard
        turn={baseTurn({
          codeDraftWarnings: ["缺少测试覆盖", "文件已存在"],
          codeDrafts: [
            draft({ warnings: ["文件已存在"] }),
            draft({ id: "draft-2", warnings: ["覆盖范围有限"] }),
          ],
        })}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
      />,
    );

    const warningGroup = screen.getByLabelText("代码草稿警告");
    expect(warningGroup).toHaveTextContent("缺少测试覆盖");
    expect(warningGroup).toHaveTextContent("文件已存在");
    expect(warningGroup).toHaveTextContent("覆盖范围有限");
  });

  it("invokes onWriteCodeDrafts when 写入全部 is clicked", async () => {
    const user = userEvent.setup();
    const onWriteAll = vi.fn();

    render(
      <CodeDraftTurnCard
        turn={baseTurn({ codeDrafts: [draft()] })}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={onWriteAll}
      />,
    );

    await user.click(screen.getByRole("button", { name: "写入全部" }));

    expect(onWriteAll).toHaveBeenCalledTimes(1);
  });

  it("does not render per-draft action buttons when their callbacks are missing", () => {
    render(
      <CodeDraftTurnCard
        turn={baseTurn({ codeDrafts: [draft()] })}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
      />,
    );

    expect(screen.queryByRole("button", { name: "写入" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "打开 diff" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "打开源码" })).not.toBeInTheDocument();
  });

  it("invokes per-draft callbacks with the correct draft id or path", async () => {
    const user = userEvent.setup();
    const onWriteSingle = vi.fn();
    const onOpenNativeDiff = vi.fn();
    const onOpenDraft = vi.fn();

    render(
      <CodeDraftTurnCard
        turn={baseTurn({ codeDrafts: [draft()] })}
        onRequestCodeDrafts={vi.fn()}
        onWriteCodeDrafts={vi.fn()}
        onWriteSingleCodeDraft={onWriteSingle}
        onOpenNativeDiff={onOpenNativeDiff}
        onOpenDraft={onOpenDraft}
      />,
    );

    await user.click(screen.getByRole("button", { name: "写入" }));
    await user.click(screen.getByRole("button", { name: "打开 diff" }));
    await user.click(screen.getByRole("button", { name: "打开源码" }));

    expect(onWriteSingle).toHaveBeenCalledWith("draft-fallback-test");
    expect(onOpenNativeDiff).toHaveBeenCalledWith("draft-fallback-test");
    expect(onOpenDraft).toHaveBeenCalledWith("src/test/java/com/example/OrderControllerTest.java");
  });

  it("invokes onRequestCodeDrafts when 重新生成代码 diff is clicked", async () => {
    const user = userEvent.setup();
    const onRequestCodeDrafts = vi.fn();

    render(
      <CodeDraftTurnCard
        turn={baseTurn({ codeDrafts: [draft()] })}
        onRequestCodeDrafts={onRequestCodeDrafts}
        onWriteCodeDrafts={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "重新生成代码 diff" }));

    expect(onRequestCodeDrafts).toHaveBeenCalledTimes(1);
  });
});
