import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { PropertyPanel } from "../../../app/components/PropertyPanel";
import type { LinkGraphNode } from "../../../app/types";

const node: LinkGraphNode = {
  id: "method:place-order",
  type: "METHOD",
  title: "OrderService.place",
  location: "src/main/java/com/example/OrderService.java:12:1",
  signature: "com.example.OrderService.place(java.lang.String):void",
  inputs: ["java.lang.String"],
  outputs: ["com.example.OrderResult"],
  doc: "Places an order.",
  certainty: "PROVEN",
  bindingStatus: "BOUND",
};

describe("PropertyPanel", () => {
  it("updates node fields and supports delete", async () => {
    const user = userEvent.setup();
    const updates: LinkGraphNode[] = [];
    const deleted: string[] = [];
    const opened: string[] = [];

    render(
      <PropertyPanel
        selectedNode={node}
        onUpdateNode={(nextNode) => updates.push(nextNode)}
        onDeleteNode={(nodeId) => deleted.push(nodeId)}
        onRequestSourceNavigation={(nodeId) => opened.push(nodeId)}
        onClose={() => undefined}
      />,
    );

    expect(screen.getByRole("dialog", { name: "编辑节点" })).toBeInTheDocument();
    expect(screen.getByText("类型：方法")).toBeInTheDocument();
    expect(screen.getByText("代码状态：已绑定")).toBeInTheDocument();
    expect(screen.getByText("证据：已确认")).toBeInTheDocument();
    expect(screen.queryByText("METHOD")).not.toBeInTheDocument();
    expect(screen.queryByText("BOUND")).not.toBeInTheDocument();
    expect(screen.queryByText("PROVEN")).not.toBeInTheDocument();
    expect(screen.getByText("源码位置")).toBeInTheDocument();
    expect(screen.getByText("符号签名")).toBeInTheDocument();
    expect(screen.queryByLabelText("位置")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("签名")).not.toBeInTheDocument();

    await user.clear(screen.getByLabelText("标题"));
    await user.type(screen.getByLabelText("标题"), "OrderService.placeDraft");
    await user.clear(screen.getByLabelText("输入"));
    await user.type(screen.getByLabelText("输入"), "java.lang.String, com.example.OrderDraft");
    await user.clear(screen.getByLabelText("输出"));
    await user.type(screen.getByLabelText("输出"), "com.example.OrderDraft");
    await user.click(screen.getByRole("button", { name: "保存修改" }));
    await user.click(screen.getByRole("button", { name: "打开源码" }));
    await user.click(screen.getByRole("button", { name: "删除节点" }));

    expect(updates).toHaveLength(1);
    expect(updates[0].title).toBe("OrderService.placeDraft");
    expect(updates[0].inputs).toEqual(["java.lang.String", "com.example.OrderDraft"]);
    expect(updates[0].outputs).toEqual(["com.example.OrderDraft"]);
    expect(opened).toEqual(["method:place-order"]);
    expect(deleted).toEqual(["method:place-order"]);
  }, 10000);

  it("explains when a node has no source location instead of pretending it can jump", () => {
    render(
      <PropertyPanel
        selectedNode={{
          ...node,
          id: "design:demo",
          title: "Demo",
          location: undefined,
          signature: undefined,
          inputs: [],
          outputs: [],
          bindingStatus: "DESIGN_ONLY",
          certainty: "LLM_SUGGESTED",
        }}
        onUpdateNode={() => undefined}
        onDeleteNode={() => undefined}
        onRequestSourceNavigation={() => undefined}
        onClose={() => undefined}
      />,
    );

    expect(screen.getByText("该节点当前没有可跳转的源码位置。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "打开源码" })).toBeDisabled();
  });

  it("explains when source navigation will fall back to method signature", () => {
    render(
      <PropertyPanel
        selectedNode={{
          ...node,
          location: undefined,
        }}
        onUpdateNode={() => undefined}
        onDeleteNode={() => undefined}
        onRequestSourceNavigation={() => undefined}
        onClose={() => undefined}
      />,
    );

    expect(screen.getByText("当前将按方法/类签名在 IDEA 中定位源码。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "打开源码" })).toBeEnabled();
  });

  it("shows anchor resolution hint and candidate methods from node metadata", () => {
    render(
      <PropertyPanel
        selectedNode={{
          ...node,
          type: "DOC_PAGE",
          metadata: {
            "linkGraph.anchorResolutionState": "AMBIGUOUS",
            "linkGraph.anchorResolutionHint": "当前引用命中多个候选，请补充参数签名后再试。",
            "linkGraph.anchorCandidates": [
              "com.example.OrderService.submit(java.lang.String):java.lang.String",
              "com.example.OrderService.submit(java.lang.Long):java.lang.String",
            ].join("\n"),
          },
        }}
        onUpdateNode={() => undefined}
        onDeleteNode={() => undefined}
        onRequestSourceNavigation={() => undefined}
        onClose={() => undefined}
      />,
    );

    expect(screen.getByText("解析提示")).toBeInTheDocument();
    expect(screen.getByText("当前引用命中多个候选，请补充参数签名后再试。")).toBeInTheDocument();
    expect(screen.getByText("候选方法")).toBeInTheDocument();
    expect(screen.getByText("com.example.OrderService.submit(java.lang.String):java.lang.String")).toBeInTheDocument();
    expect(screen.getByText("com.example.OrderService.submit(java.lang.Long):java.lang.String")).toBeInTheDocument();
  });

  it("explains flow scope nodes as structural containers instead of ordinary methods", () => {
    render(
      <PropertyPanel
        selectedNode={{
          id: "flow:lambda",
          type: "FLOW_SCOPE",
          title: "forEach λ(line)",
          location: "src/main/java/com/example/OrderService.java:18:9",
          signature: "lambda body · Line",
          inputs: [],
          outputs: [],
          doc: "遍历订单行并过滤有效条目。",
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "flow.kind": "LAMBDA",
          },
        }}
        onUpdateNode={() => undefined}
        onDeleteNode={() => undefined}
        onRequestSourceNavigation={() => undefined}
        onClose={() => undefined}
      />,
    );

    expect(screen.getByText("该节点不是独立方法，而是当前方法里的流程作用域容器。")).toBeInTheDocument();
    expect(screen.getByText("作用域类型")).toBeInTheDocument();
    expect(screen.getByText("Lambda 作用域")).toBeInTheDocument();
    expect(screen.getByText("流程摘要")).toBeInTheDocument();
    expect(screen.getByText("lambda body · Line")).toBeInTheDocument();
    expect(screen.queryByLabelText("输入")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("输出")).not.toBeInTheDocument();
  });

  it("explains flow action nodes as current-method internal actions instead of standalone methods", () => {
    render(
      <PropertyPanel
        selectedNode={{
          id: "flow-action:subject",
          type: "FLOW_ACTION",
          title: "SecurityUtils.getSubject()",
          location: "src/main/java/com/example/ShiroUtils.java:42:1",
          signature: "SecurityUtils.getSubject()",
          inputs: [],
          outputs: ["org.apache.shiro.subject.Subject"],
          doc: "当前方法关键动作",
          certainty: "PROVEN",
          bindingStatus: "BOUND",
          metadata: {
            "flow.anchorMethod": "com.example.ShiroUtils.setSysUser(com.example.User):void",
          },
        }}
        onUpdateNode={() => undefined}
        onDeleteNode={() => undefined}
        onRequestSourceNavigation={() => undefined}
        onClose={() => undefined}
      />,
    );

    expect(screen.getByText("类型：当前方法内部动作")).toBeInTheDocument();
    expect(screen.getByText("该节点表示当前方法中的内部执行动作，用来补齐代码阅读顺序，不等同于独立方法定义。")).toBeInTheDocument();
    expect(screen.getByText("所属方法")).toBeInTheDocument();
    expect(screen.getByText("com.example.ShiroUtils.setSysUser(com.example.User):void")).toBeInTheDocument();
    expect(screen.getByText("动作表达式")).toBeInTheDocument();
    expect(screen.getByText("SecurityUtils.getSubject()")).toBeInTheDocument();
  });

  it("keeps unsaved edits when the same node is refreshed from graph sync", async () => {
    const user = userEvent.setup();
    const { rerender } = render(
      <PropertyPanel
        selectedNode={node}
        onUpdateNode={() => undefined}
        onDeleteNode={() => undefined}
        onRequestSourceNavigation={() => undefined}
        onClose={() => undefined}
      />,
    );

    await user.clear(screen.getByLabelText("标题"));
    await user.type(screen.getByLabelText("标题"), "OrderService.placeDraft");

    rerender(
      <PropertyPanel
        selectedNode={{
          ...node,
          doc: "后端刚刚同步了新的说明，但不应该覆盖本地未保存标题。",
        }}
        onUpdateNode={() => undefined}
        onDeleteNode={() => undefined}
        onRequestSourceNavigation={() => undefined}
        onClose={() => undefined}
      />,
    );

    expect(screen.getByLabelText("标题")).toHaveValue("OrderService.placeDraft");
  });

  it("acts as a real modal and blocks wheel or pointer events from reaching the background", () => {
    const onBackgroundWheel = vi.fn();
    const onBackgroundPointerDown = vi.fn();

    const { container } = render(
      <div onWheel={onBackgroundWheel} onPointerDown={onBackgroundPointerDown}>
        <PropertyPanel
          selectedNode={node}
          onUpdateNode={() => undefined}
          onDeleteNode={() => undefined}
          onRequestSourceNavigation={() => undefined}
          onClose={() => undefined}
        />
      </div>,
    );

    const backdrop = container.querySelector(".property-drawer-backdrop");
    const dialog = screen.getByRole("dialog", { name: "编辑节点" });
    expect(backdrop).not.toBeNull();

    fireEvent.wheel(dialog);
    fireEvent.pointerDown(dialog);
    fireEvent.wheel(backdrop!);
    fireEvent.pointerDown(backdrop!);

    expect(onBackgroundWheel).not.toHaveBeenCalled();
    expect(onBackgroundPointerDown).not.toHaveBeenCalled();
  });
});
