import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PropertyPanel } from "./PropertyPanel";
import type { LinkGraphNode } from "../types";

const node: LinkGraphNode = {
  id: "method:place-order",
  type: "METHOD",
  title: "OrderService.place",
  signature: "com.example.OrderService.place(java.lang.String):void",
  doc: "Places an order.",
  certainty: "PROVEN",
  bindingStatus: "BOUND",
};

describe("PropertyPanel", () => {
  it("updates node fields and supports delete", async () => {
    const user = userEvent.setup();
    const updates: LinkGraphNode[] = [];
    const deleted: string[] = [];

    render(
      <PropertyPanel
        selectedNode={node}
        onUpdateNode={(nextNode) => updates.push(nextNode)}
        onDeleteNode={(nodeId) => deleted.push(nodeId)}
      />,
    );

    await user.clear(screen.getByLabelText(/title/i));
    await user.type(screen.getByLabelText(/title/i), "OrderService.placeDraft");
    await user.click(screen.getByRole("button", { name: /save changes/i }));
    await user.click(screen.getByRole("button", { name: /delete node/i }));

    expect(updates).toHaveLength(1);
    expect(updates[0].title).toBe("OrderService.placeDraft");
    expect(deleted).toEqual(["method:place-order"]);
  });
});
