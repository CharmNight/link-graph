import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { GraphCanvas } from "./GraphCanvas";
import type { LinkGraphEdge, LinkGraphNode } from "../types";

const nodes: LinkGraphNode[] = [
  {
    id: "method:place-order",
    type: "METHOD",
    title: "OrderService.place",
    signature: "com.example.OrderService.place(java.lang.String):void",
    certainty: "RULE_INFERRED",
    bindingStatus: "BOUND",
  },
  {
    id: "sql:insert-order",
    type: "SQL",
    title: "insert into orders",
    certainty: "LLM_SUGGESTED",
    bindingStatus: "DESIGN_ONLY",
  },
];

const edges: LinkGraphEdge[] = [
  {
    id: "call:place-order->insert-order",
    type: "CALL",
    source: "method:place-order",
    target: "sql:insert-order",
  },
];

describe("GraphCanvas", () => {
  it("renders method and non-method nodes with issue badges", () => {
    render(
      <GraphCanvas
        nodes={nodes}
        edges={edges}
        onAddNode={() => undefined}
        onSelectNode={() => undefined}
        onDeleteNode={() => undefined}
        onReconnectEdge={() => undefined}
      />,
    );

    expect(screen.getByText("OrderService.place")).toBeInTheDocument();
    expect(screen.getByText("insert into orders")).toBeInTheDocument();
    expect(screen.getByText("RULE_INFERRED")).toBeInTheDocument();
    expect(screen.getByText("LLM_SUGGESTED")).toBeInTheDocument();
  });

  it("fires add delete and reconnect callbacks", async () => {
    const user = userEvent.setup();
    const events: string[] = [];

    render(
      <GraphCanvas
        nodes={nodes}
        edges={edges}
        onAddNode={() => events.push("add")}
        onSelectNode={(nodeId) => events.push(`select:${nodeId}`)}
        onDeleteNode={(nodeId) => events.push(`delete:${nodeId}`)}
        onReconnectEdge={(edgeId) => events.push(`reconnect:${edgeId}`)}
      />,
    );

    await user.click(screen.getByRole("button", { name: /add node/i }));
    await user.click(screen.getByRole("button", { name: /inspect method:place-order/i }));
    await user.click(screen.getByRole("button", { name: /delete method:place-order/i }));
    await user.click(screen.getByRole("button", { name: /reconnect call:place-order->insert-order/i }));

    expect(events).toEqual([
      "add",
      "select:method:place-order",
      "delete:method:place-order",
      "reconnect:call:place-order->insert-order",
    ]);
  });
});
