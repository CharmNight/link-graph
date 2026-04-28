import { render, screen } from "@testing-library/react";
import { SyncPreviewPanel } from "../../../app/components/SyncPreviewPanel";
import type { SyncPreviewItem } from "../../../app/types";

const previewItems: SyncPreviewItem[] = [
  {
    id: "create-dto",
    title: "新增 DTO",
    description: "生成 OrderDraftDto.java",
    risk: "LOW",
  },
  {
    id: "wire-call",
    title: "补充服务调用",
    description: "把 placeDraft 调用补到 controller",
    risk: "MEDIUM",
  },
];

describe("SyncPreviewPanel", () => {
  it("renders sync preview items", () => {
    render(<SyncPreviewPanel items={previewItems} />);

    expect(screen.getByText("新增 DTO")).toBeInTheDocument();
    expect(screen.getByText("生成 OrderDraftDto.java")).toBeInTheDocument();
    expect(screen.getByText("补充服务调用")).toBeInTheDocument();
    expect(screen.getByText("中")).toBeInTheDocument();
  });
});
