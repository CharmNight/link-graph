import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DiffPanel } from "./DiffPanel";
import type { DiffItem } from "../types";

const items: DiffItem[] = [
  {
    id: "class:order-draft-dto",
    title: "OrderDraftDto",
    status: "ONLY_IN_MERMAID",
    description: "Design node is missing from code.",
  },
];

describe("DiffPanel", () => {
  it("renders diff items and supports selecting one item", async () => {
    const user = userEvent.setup();
    const selected: string[] = [];

    render(<DiffPanel items={items} onSelectItem={(itemId) => selected.push(itemId)} />);

    expect(screen.getByText("OrderDraftDto")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /focus class:order-draft-dto/i }));

    expect(selected).toEqual(["class:order-draft-dto"]);
  });
});
