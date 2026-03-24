import { render, screen } from "@testing-library/react";
import { SyncPreviewPanel } from "./SyncPreviewPanel";
import type { SyncPreviewItem } from "../types";

const previewItems: SyncPreviewItem[] = [
  {
    id: "create-dto",
    title: "Create DTO",
    description: "Generate OrderDraftDto.java",
    risk: "LOW",
  },
  {
    id: "wire-call",
    title: "Wire Service Call",
    description: "Add placeDraft invocation to controller",
    risk: "MEDIUM",
  },
];

describe("SyncPreviewPanel", () => {
  it("renders sync preview items", () => {
    render(<SyncPreviewPanel items={previewItems} />);

    expect(screen.getByText("Create DTO")).toBeInTheDocument();
    expect(screen.getByText("Generate OrderDraftDto.java")).toBeInTheDocument();
    expect(screen.getByText("Wire Service Call")).toBeInTheDocument();
    expect(screen.getByText("MEDIUM")).toBeInTheDocument();
  });
});
