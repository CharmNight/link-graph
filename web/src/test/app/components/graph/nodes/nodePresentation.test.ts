import { describe, expect, it } from "vitest";
import { nodeTooltip, signaturePreview } from "../../../../../app/components/graph/nodes/nodePresentation";
import type { LinkGraphNode } from "../../../../../app/types";

function methodNode(overrides: Partial<LinkGraphNode> = {}): LinkGraphNode {
  return {
    id: "method:submit-order",
    type: "METHOD",
    title: "OrderService.submit",
    signature: "com.example.OrderService.submit(java.lang.String):java.lang.Boolean",
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
    ...overrides,
  };
}

describe("nodePresentation", () => {
  it("derives method preview from signature when explicit inputs and outputs are missing", () => {
    expect(signaturePreview(methodNode())).toBe("Boolean submit(String)");
  });

  it("does not report void when the method signature has no return type", () => {
    const preview = signaturePreview(methodNode({
      signature: "com.example.OrderService.submit(java.lang.String)",
    }));

    expect(preview).toBe("submit(String)");
    expect(preview).not.toContain("void");
  });

  it("keeps explicit method outputs ahead of signature-derived return type", () => {
    expect(signaturePreview(methodNode({
      outputs: ["com.example.SubmitResult"],
    }))).toBe("SubmitResult submit(String)");
  });

  it("keeps generic parameter commas inside one signature-derived parameter", () => {
    expect(signaturePreview(methodNode({
      signature: "com.example.OrderService.submit(java.util.Map<java.lang.String,java.lang.Integer>):void",
    }))).toBe("void submit(Map<String,Integer>)");
  });

  it("labels signature-derived input and output in the tooltip", () => {
    const tooltip = nodeTooltip(methodNode());

    expect(tooltip).toContain("签名参数: java.lang.String");
    expect(tooltip).toContain("签名返回: java.lang.Boolean");
    expect(tooltip).not.toContain("输入: java.lang.String");
  });
});
