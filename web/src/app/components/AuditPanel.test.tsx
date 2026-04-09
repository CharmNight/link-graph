import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AuditPanel } from "./AuditPanel";

describe("AuditPanel", () => {
  it("renders audit results as structured readable sections instead of one long paragraph", async () => {
    const user = userEvent.setup();
    const questions: string[] = [];
    const events: string[] = [];

    render(
      <AuditPanel
        selectedNodeIds={["method:submit-order"]}
        selectedNodeTitle="OrderController.submit"
        factGraph={{ nodes: [], edges: [] }}
        draftGraph={{ nodes: [], edges: [] }}
        designBaseline={{ nodes: [], edges: [] }}
        result={{
          source: "REMOTE",
          question: "这段链路是否遗漏默认兜底？",
          answer: "当前链路缺少默认兜底分支。导入校验和重复提交控制都依赖这条分支。建议先补说明节点，再决定是否生成代码。",
          findings: [],
          promptPreview: "prompt",
          warnings: [
            "模型 gpt-5.4 在当前兼容服务中不可用（HTTP 503 / model_not_found）。请在设置中改成服务端已开通的模型。",
          ],
          patch: {
            summary: "建议新增默认兜底说明节点。",
            operations: [],
            addedNodeIds: ["doc:fallback"],
            removedNodeIds: [],
            addedEdgeIds: [],
            removedEdgeIds: [],
          },
        }}
        onRequestAudit={(question) => questions.push(question)}
        onOpenPatchPreview={() => events.push("open-preview")}
      />,
    );

    expect(screen.getByText("核心结论")).toBeInTheDocument();
    expect(screen.getByText("当前链路缺少默认兜底分支。")).toBeInTheDocument();
    expect(screen.getByText("关键影响")).toBeInTheDocument();
    expect(screen.getByText("导入校验和重复提交控制都依赖这条分支。")).toBeInTheDocument();
    expect(screen.getByText("建议动作")).toBeInTheDocument();
    expect(screen.getByText("建议先补说明节点，再决定是否生成代码。")).toBeInTheDocument();
    expect(screen.getByText("草稿写回建议")).toBeInTheDocument();
    expect(screen.getByText("建议新增默认兜底说明节点。")).toBeInTheDocument();
    expect(screen.getByText(/模型 gpt-5.4 在当前兼容服务中不可用/)).toBeInTheDocument();
    expect(screen.queryByText("model_not_found")).not.toBeInTheDocument();

    await user.type(screen.getByLabelText("审计问题"), "请重新检查默认兜底");
    await user.click(screen.getByRole("button", { name: "开始审计" }));
    await user.click(screen.getByRole("button", { name: "查看并写入审计草稿" }));

    expect(questions).toEqual(["请重新检查默认兜底"]);
    expect(events).toEqual(["open-preview"]);
  });

  it("parses heading and bullet style answers into readable sections", () => {
    render(
      <AuditPanel
        selectedNodeIds={[]}
        selectedNodeTitle={null}
        factGraph={{ nodes: [], edges: [] }}
        draftGraph={{ nodes: [], edges: [] }}
        designBaseline={null}
        result={{
          source: "REMOTE",
          question: "请审计整个链路",
          answer: [
            "结论：当前链路缺少失败兜底。",
            "影响：",
            "- 导入失败后没有统一回滚说明",
            "- 重复提交控制点不可见",
            "建议：",
            "1. 先补审计说明节点",
            "2. 再决定是否生成代码",
          ].join("\n"),
          findings: [],
          promptPreview: "prompt",
          warnings: [],
          patch: null,
        }}
        onRequestAudit={() => undefined}
        onOpenPatchPreview={() => undefined}
      />,
    );

    expect(screen.getByText("核心结论")).toBeInTheDocument();
    expect(screen.getByText("当前链路缺少失败兜底。")).toBeInTheDocument();
    expect(screen.getByText("关键影响")).toBeInTheDocument();
    expect(screen.getByText("导入失败后没有统一回滚说明")).toBeInTheDocument();
    expect(screen.getByText("重复提交控制点不可见")).toBeInTheDocument();
    expect(screen.getByText("建议动作")).toBeInTheDocument();
    expect(screen.getByText("先补审计说明节点")).toBeInTheDocument();
    expect(screen.getByText("再决定是否生成代码")).toBeInTheDocument();
  });

  it("shows the failure reason and allows retrying audit with the current question", async () => {
    const user = userEvent.setup();
    const requests: string[] = [];

    render(
      <AuditPanel
        selectedNodeIds={[]}
        selectedNodeTitle={null}
        factGraph={{ nodes: [], edges: [] }}
        draftGraph={{ nodes: [], edges: [] }}
        designBaseline={null}
        result={null}
        requestState={{
          phase: "FAILED",
          errorMessage: "审计失败：HTTP 503",
          statusMessage: "审计失败",
          detailMessage: "远程服务暂时不可用。",
          startedAtEpochMillis: 100,
          finishedAtEpochMillis: 200,
          streaming: false,
          fallbackUsed: false,
        }}
        onRequestAudit={(question) => requests.push(question)}
        onOpenPatchPreview={() => undefined}
      />,
    );

    expect(screen.getByText("审计失败：HTTP 503")).toBeInTheDocument();
    await user.type(screen.getByLabelText("审计问题"), "请重新审计这个范围");
    await user.click(screen.getByRole("button", { name: "重试审计" }));

    expect(requests).toEqual(["请重新审计这个范围"]);
  });

  it("shows remote waiting details instead of a blind long-running spinner", () => {
    render(
      <AuditPanel
        selectedNodeIds={[]}
        selectedNodeTitle={null}
        factGraph={{ nodes: [], edges: [] }}
        draftGraph={{ nodes: [], edges: [] }}
        designBaseline={null}
        result={null}
        requestState={{
          phase: "RUNNING",
          errorMessage: null,
          statusMessage: "正在等待远程 LLM 响应",
          detailMessage: "当前采用完整返回，不是流式输出。最长等待 45 秒，超时后会停止等待并提示失败。",
          startedAtEpochMillis: Date.now() - 4_000,
          finishedAtEpochMillis: null,
          streaming: false,
          fallbackUsed: false,
        }}
        onRequestAudit={() => undefined}
        onOpenPatchPreview={() => undefined}
      />,
    );

    expect(screen.getByText("正在等待远程 LLM 响应")).toBeInTheDocument();
    expect(screen.getByText(/不是流式输出/)).toBeInTheDocument();
    expect(screen.getByText(/最长等待 45 秒/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "审计中..." })).toBeDisabled();
  });

  it("shows backend request telemetry so local-rule execution is explicit", () => {
    render(
      <AuditPanel
        selectedNodeIds={[]}
        selectedNodeTitle={null}
        factGraph={{ nodes: [], edges: [] }}
        draftGraph={{ nodes: [], edges: [] }}
        designBaseline={null}
        result={null}
        requestState={{
          phase: "RUNNING",
          errorMessage: null,
          statusMessage: "正在执行审计本地规则",
          detailMessage: "当前直接使用本地规则或模板，不会发起远程 LLM 请求。",
          startedAtEpochMillis: 1_710_000_000_000,
          finishedAtEpochMillis: null,
          streaming: false,
          fallbackUsed: false,
          requestId: 23,
          scene: "审计",
          executionMode: "LOCAL_RULE",
          providerLabel: "OpenAI Compatible",
          model: "gpt-5.4",
          endpointSummary: "example.com/v1/chat/completions",
          promptPreviewAvailable: true,
        } as never}
        onRequestAudit={() => undefined}
        onOpenPatchPreview={() => undefined}
      />,
    );

    expect(screen.getByText("正在执行审计本地规则")).toBeInTheDocument();
    expect(screen.getByText("当前直接使用本地规则或模板，不会发起远程 LLM 请求。")).toBeInTheDocument();
    expect(screen.getByText("请求")).toBeInTheDocument();
    expect(screen.getByText("#23")).toBeInTheDocument();
    expect(screen.getByText("场景")).toBeInTheDocument();
    expect(screen.getByText("审计")).toBeInTheDocument();
    expect(screen.getByText("执行模式")).toBeInTheDocument();
    expect(screen.getByText("本地规则")).toBeInTheDocument();
    expect(screen.getByText("提供方")).toBeInTheDocument();
    expect(screen.getByText("OpenAI Compatible")).toBeInTheDocument();
    expect(screen.getByText("模型")).toBeInTheDocument();
    expect(screen.getByText("gpt-5.4")).toBeInTheDocument();
    expect(screen.getByText("接口")).toBeInTheDocument();
    expect(screen.getByText("example.com/v1/chat/completions")).toBeInTheDocument();
    expect(screen.getByText("提示词")).toBeInTheDocument();
    expect(screen.getByText("可查看")).toBeInTheDocument();
  });

  it("shows scope-specific graph counts instead of whole-layer totals when nodes are selected", () => {
    render(
      <AuditPanel
        selectedNodeIds={["method:submit-order"]}
        selectedNodeTitle="OrderController.submit"
        factGraph={{
          nodes: [
            {
              id: "method:submit-order",
              type: "METHOD",
              title: "OrderController.submit",
              inputs: [],
              outputs: ["void"],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              sourceTag: "FACT",
            },
            {
              id: "method:submit-service",
              type: "METHOD",
              title: "OrderService.submit",
              inputs: [],
              outputs: ["void"],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              sourceTag: "FACT",
            },
            {
              id: "method:compensate",
              type: "METHOD",
              title: "OrderService.compensate",
              inputs: [],
              outputs: ["void"],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              sourceTag: "FACT",
            },
          ],
          edges: [
            {
              id: "edge:submit-order->submit-service",
              type: "CALL",
              source: "method:submit-order",
              target: "method:submit-service",
            },
            {
              id: "edge:submit-service->compensate",
              type: "CALL",
              source: "method:submit-service",
              target: "method:compensate",
            },
          ],
        }}
        draftGraph={{
          nodes: [
            {
              id: "method:submit-order",
              type: "METHOD",
              title: "OrderController.submit",
              inputs: [],
              outputs: ["void"],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              sourceTag: "FACT",
            },
            {
              id: "method:submit-service",
              type: "METHOD",
              title: "OrderService.submit",
              inputs: [],
              outputs: ["void"],
              certainty: "PROVEN",
              bindingStatus: "BOUND",
              sourceTag: "FACT",
            },
            {
              id: "doc:manual-note",
              type: "DOC_PAGE",
              title: "人工说明",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              sourceTag: "DRAFT_MANUAL",
            },
          ],
          edges: [
            {
              id: "edge:submit-order->submit-service",
              type: "CALL",
              source: "method:submit-order",
              target: "method:submit-service",
            },
            {
              id: "edge:submit-order->manual-note",
              type: "LINKS_DOC",
              source: "method:submit-order",
              target: "doc:manual-note",
            },
          ],
        }}
        designBaseline={{
          nodes: [
            {
              id: "method:submit-order",
              type: "METHOD",
              title: "OrderController.submit",
              inputs: [],
              outputs: ["void"],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              sourceTag: "DESIGN_BASELINE",
            },
            {
              id: "doc:design-note",
              type: "DOC_PAGE",
              title: "设计说明",
              inputs: [],
              outputs: [],
              certainty: "PROVEN",
              bindingStatus: "DESIGN_ONLY",
              sourceTag: "DESIGN_BASELINE",
            },
          ],
          edges: [
            {
              id: "edge:submit-order->design-note",
              type: "LINKS_DOC",
              source: "method:submit-order",
              target: "doc:design-note",
            },
          ],
        }}
        result={null}
        onRequestAudit={() => undefined}
        onOpenPatchPreview={() => undefined}
      />,
    );

    const factCard = screen.getByText("事实层").closest("article");
    const draftCard = screen.getByText("草稿层").closest("article");
    const designCard = screen.getByText("设计基线").closest("article");

    expect(factCard).not.toBeNull();
    expect(draftCard).not.toBeNull();
    expect(designCard).not.toBeNull();
    expect(factCard).toHaveTextContent("2 节点 / 1 连线");
    expect(draftCard).toHaveTextContent("3 节点 / 2 连线");
    expect(designCard).toHaveTextContent("2 节点 / 1 连线");
    expect(screen.queryByText("3 节点 / 2 连线，详情已按统计模式折叠")).not.toBeInTheDocument();
  });
});
