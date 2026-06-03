import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { GraphContextMenu } from "../../../../../app/components/graph/actions/GraphContextMenu";

describe("GraphContextMenu", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("clamps the menu back into the viewport when the trigger point is near the lower-right edge", async () => {
    vi.spyOn(window, "innerWidth", "get").mockReturnValue(1000);
    vi.spyOn(window, "innerHeight", "get").mockReturnValue(800);

    render(
      <GraphContextMenu
        x={980}
        y={780}
        actions={[
          { id: "locate", label: "定位到图中节点", onSelect: vi.fn() },
          { id: "inspect", label: "编辑节点", onSelect: vi.fn() },
        ]}
      />,
    );

    const menu = screen.getByRole("menu");

    await waitFor(() => {
      expect(parseFloat(menu.style.left)).toBeLessThan(980);
      expect(parseFloat(menu.style.top)).toBeLessThan(780);
    });
  });

  it("renders in a document-level portal so canvas overflow and footer chrome cannot cover it", () => {
    const { container } = render(
      <div className="graph-canvas-shell">
        <GraphContextMenu
          x={320}
          y={240}
          actions={[
            { id: "locate", label: "定位到图中节点", onSelect: vi.fn() },
          ]}
        />
      </div>,
    );

    const menu = screen.getByRole("menu");

    expect(container.querySelector(".canvas-context-menu")).toBeNull();
    expect(menu.parentElement).toBe(document.body);
  });
});
