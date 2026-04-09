import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { SelectedNodeSummary } from "./SelectedNodeSummary";
import type { LinkGraphNode } from "../types";

describe("SelectedNodeSummary", () => {
  it("shows flow action nodes as actions first and exposes their anchor method separately", () => {
    const node: LinkGraphNode = {
      id: "flow-action:get-security-subject",
      type: "FLOW_ACTION",
      title: "SecurityUtils.getSubject()",
      location: "src/main/java/com/example/ShiroUtils.java:42:1",
      signature: "SecurityUtils.getSubject()",
      inputs: [],
      outputs: [],
      doc: "当前方法关键动作",
      certainty: "PROVEN",
      bindingStatus: "BOUND",
      metadata: {
        "flow.anchorMethod": "com.example.ShiroUtils.getSubject():org.apache.shiro.subject.Subject",
      },
    };

    render(
      <SelectedNodeSummary
        selectedNode={node}
        onInspectNode={() => undefined}
        onRequestSourceNavigation={() => undefined}
        onRequestAudit={() => undefined}
        onRequestBeautification={() => undefined}
      />,
    );

    expect(screen.getByRole("heading", { name: "SecurityUtils.getSubject()" })).toBeInTheDocument();
    expect(screen.getByText("当前方法内部动作")).toBeInTheDocument();
    expect(screen.getByText("所属方法")).toBeInTheDocument();
    expect(
      screen.getByText("com.example.ShiroUtils.getSubject():org.apache.shiro.subject.Subject"),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "讲解当前链路" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "审计当前节点" })).toBeInTheDocument();
  });

  it("keeps graph-level explain and audit actions visible even when no node is selected", () => {
    render(
      <SelectedNodeSummary
        selectedNode={null}
        onInspectNode={() => undefined}
        onRequestSourceNavigation={() => undefined}
        onRequestAudit={() => undefined}
        onRequestBeautification={() => undefined}
      />,
    );

    expect(screen.getByRole("button", { name: "讲解当前链路" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "审计当前链路" })).toBeInTheDocument();
  });

  it("routes selected-node explain and audit actions with the concrete node id", async () => {
    const user = userEvent.setup();
    const onRequestBeautification = vi.fn();
    const onRequestAudit = vi.fn();
    const node: LinkGraphNode = {
      id: "method:submit-order",
      type: "METHOD",
      title: "OrderController.submit",
      signature: "com.example.OrderController.submit():void",
      location: "src/main/java/com/example/OrderController.java:8:1",
      inputs: [],
      outputs: [],
      doc: "提交订单入口",
      certainty: "PROVEN",
      bindingStatus: "BOUND",
    };

    render(
      <SelectedNodeSummary
        selectedNode={node}
        onInspectNode={() => undefined}
        onRequestSourceNavigation={() => undefined}
        onRequestAudit={onRequestAudit}
        onRequestBeautification={onRequestBeautification}
      />,
    );

    await user.click(screen.getByRole("button", { name: "讲解当前链路" }));
    await user.click(screen.getByRole("button", { name: "审计当前节点" }));

    expect(onRequestBeautification).toHaveBeenCalledWith("method:submit-order");
    expect(onRequestAudit).toHaveBeenCalledWith("method:submit-order");
  });
});
