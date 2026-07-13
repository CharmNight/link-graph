import { render, screen } from "@testing-library/react";
import { AsyncRequestFailureDialog } from "../../../app/components/AsyncRequestFailureDialog";
import { MermaidImportDialog } from "../../../app/components/MermaidImportDialog";
import { PropertyPanel } from "../../../app/components/PropertyPanel";
import themeCss from "../../../app/theme.css?raw";
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
  confidence: "VERIFIED",
  binding: "CODE_BOUND",
};

describe("shared modal surfaces", () => {
  it("uses the same centered modal structure for edit, import, and failure dialogs", () => {
    render(
      <>
        <PropertyPanel
          selectedNode={node}
          onUpdateNode={() => undefined}
          onDeleteNode={() => undefined}
          onRequestSourceNavigation={() => undefined}
          onClose={() => undefined}
        />
        <MermaidImportDialog
          open
          value="graph TD"
          onChange={() => undefined}
          onCancel={() => undefined}
          onConfirm={() => undefined}
        />
        <AsyncRequestFailureDialog
          open
          title="请求失败"
          message="IDE bridge 尚未就绪。"
          onClose={() => undefined}
        />
      </>,
    );

    for (const name of ["编辑节点", "导入 Mermaid", "请求状态通知"]) {
      const dialog = screen.getByRole("dialog", { name });
      expect(dialog).toHaveClass("modal-dialog");
      expect(dialog.querySelector(".modal-header")).not.toBeNull();
      expect(dialog.querySelector(".modal-body")).not.toBeNull();
      expect(dialog.querySelector(".modal-footer")).not.toBeNull();
    }
  });

  it("keeps shared modal content inset from the viewport and fixes actions to the modal footer", () => {
    // P2: property-drawer 已改为贴右滑入抽屉，不再与 modal-backdrop 共用居中布局
    expect(themeCss).toMatch(
      /\.modal-backdrop\s*\{(?=[^}]*place-items:\s*center;)(?=[^}]*padding:\s*clamp\(16px,\s*3vw,\s*32px\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.property-drawer-backdrop\s*\{(?=[^}]*display:\s*grid;)(?=[^}]*grid-template-columns:\s*1fr\s*auto;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.modal-dialog\s*\{(?=[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\)\s+auto;)(?=[^}]*overflow:\s*hidden;)(?=[^}]*border-radius:\s*var\(--radius-xl\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.modal-body\s*\{(?=[^}]*overflow:\s*auto;)(?=[^}]*padding:\s*18px\s+20px;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.modal-footer\s*\{(?=[^}]*border-top:\s*1px solid var\(--line-soft\);)(?=[^}]*padding:\s*14px\s+20px;)[^}]*\}/s,
    );
  });
});
