import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import type { LinkGraphBootstrapState } from "./types";

const bootstrapState: LinkGraphBootstrapState = {
  graph: {
    nodes: [
      {
        id: "method:submit-order",
        type: "METHOD",
        title: "OrderController.submit",
        location: "src/main/java/com/example/OrderController.java:8:1",
        signature: "com.example.OrderController.submit():void",
        doc: "Submit order entry.",
        certainty: "PROVEN",
        bindingStatus: "BOUND",
      },
      {
        id: "class:order-draft-dto",
        type: "CLASS",
        title: "OrderDraftDto",
        certainty: "LLM_SUGGESTED",
        bindingStatus: "DESIGN_ONLY",
        diffStatus: "ONLY_IN_MERMAID",
      },
    ],
    edges: [
      {
        id: "call:submit-order->order-draft-dto",
        type: "CALL",
        source: "method:submit-order",
        target: "class:order-draft-dto",
      },
    ],
  },
  diffItems: [
    {
      id: "class:order-draft-dto",
      title: "OrderDraftDto",
      status: "ONLY_IN_MERMAID",
      description: "Design node is missing from code.",
    },
  ],
  syncPreviewItems: [
    {
      id: "create-order-draft",
      title: "Create OrderDraftDto",
      description: "Generate DTO class from Mermaid node",
      risk: "LOW",
    },
  ],
  selectedNodeId: "method:submit-order",
};

describe("App", () => {
  beforeEach(() => {
    window.linkGraphBootstrap = structuredClone(bootstrapState);
    window.linkGraphBridge = {
      exportMermaid: vi.fn(),
      requestSyncPreview: vi.fn(),
      graphChanged: vi.fn(),
      nodeSelected: vi.fn(),
      requestSourceNavigation: vi.fn(),
    };
  });

  it("hydrates from IDE bootstrap state and sends graph updates back through the bridge", async () => {
    const user = userEvent.setup();
    render(<App />);

    expect(screen.getByText("OrderController.submit")).toBeInTheDocument();
    expect(screen.getByText("Design node is missing from code.")).toBeInTheDocument();
    expect(screen.getByText("Create OrderDraftDto")).toBeInTheDocument();

    await user.clear(screen.getByLabelText(/title/i));
    await user.type(screen.getByLabelText(/title/i), "OrderController.submitDraft");
    await user.click(screen.getByRole("button", { name: /save changes/i }));

    expect(window.linkGraphBridge?.graphChanged).toHaveBeenCalledTimes(1);
    expect(window.linkGraphBridge?.graphChanged).toHaveBeenCalledWith({
      nodes: expect.arrayContaining([
        expect.objectContaining({
          id: "method:submit-order",
          title: "OrderController.submitDraft",
          type: "METHOD",
        }),
      ]),
      edges: [
        expect.objectContaining({
          id: "call:submit-order->order-draft-dto",
          fromNodeId: "method:submit-order",
          toNodeId: "class:order-draft-dto",
        }),
      ],
    });
  });

  it("routes toolbar actions through the IDE bridge", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: /export mermaid/i }));
    await user.click(screen.getByRole("button", { name: /request sync/i }));

    expect(window.linkGraphBridge?.exportMermaid).toHaveBeenCalledTimes(1);
    expect(window.linkGraphBridge?.requestSyncPreview).toHaveBeenCalledTimes(1);
  });

  it("selects nodes from diff panel and requests source navigation from the property panel", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: /focus class:order-draft-dto/i }));
    expect(window.linkGraphBridge?.nodeSelected).toHaveBeenCalledWith("class:order-draft-dto");
    expect(screen.getByRole("heading", { name: "class:order-draft-dto" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /inspect method:submit-order/i }));
    expect(window.linkGraphBridge?.nodeSelected).toHaveBeenCalledWith("method:submit-order");

    await user.click(screen.getByRole("button", { name: /open source/i }));
    expect(window.linkGraphBridge?.requestSourceNavigation).toHaveBeenCalledWith("method:submit-order");
  });
});
