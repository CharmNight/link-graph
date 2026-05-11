import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { LinkGraphOutline } from "../../../app/components/LinkGraphOutline";
import type { LinkGraphOutlineItem, LinkGraphOutlineMetrics } from "../../../app/components/hybridDerivations";

const metrics: LinkGraphOutlineMetrics = {
  visibleNodeCount: 4,
  fullNodeCount: 8,
  sourceAnchorCount: 1,
  inferredEvidenceCount: 2,
  draftImpactCount: 1,
  blockingRiskCount: 1,
};

const items: LinkGraphOutlineItem[] = [
  {
    id: "method:submit",
    label: "OrderController.submit",
    kind: "METHOD",
    group: "entry",
    badge: "入口",
    meta: "src/OrderController.java:8",
  },
  {
    id: "sql:insert",
    label: "insert order",
    kind: "SQL",
    group: "criticalPath",
    severity: "info",
  },
  {
    id: "source:mapper",
    label: "OrderMapper.xml",
    kind: "XML_RESOURCE",
    group: "evidence",
    severity: "success",
  },
  {
    id: "thread:missing-compensation",
    label: "失败补偿可能缺失",
    kind: "RISK",
    group: "risk",
    severity: "danger",
  },
  {
    id: "draft:compensation",
    label: "补充失败补偿说明",
    kind: "DRAFT",
    group: "draft",
    severity: "warning",
  },
];

describe("LinkGraphOutline", () => {
  it("groups link outline items and renders real metrics", () => {
    render(
      <LinkGraphOutline
        metrics={metrics}
        items={items}
        activeItemId="method:submit"
        query=""
        onQueryChange={vi.fn()}
        onSelectItem={vi.fn()}
      />,
    );

    expect(screen.getByRole("complementary", { name: "链路大纲" })).toBeInTheDocument();
    expect(screen.getByText("4 / 8")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "入口" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "关键路径" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "资源证据" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "风险线索" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "草稿影响" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /OrderController\.submit/ })).toHaveAttribute("aria-current", "true");
  });

  it("filters by query and delegates item selection", async () => {
    const user = userEvent.setup();
    const onQueryChange = vi.fn();
    const onSelectItem = vi.fn();

    render(
      <LinkGraphOutline
        metrics={metrics}
        items={items}
        query="mapper"
        onQueryChange={onQueryChange}
        onSelectItem={onSelectItem}
      />,
    );

    const outline = screen.getByRole("complementary", { name: "链路大纲" });
    expect(within(outline).getByText("OrderMapper.xml")).toBeInTheDocument();
    expect(within(outline).queryByText("OrderController.submit")).not.toBeInTheDocument();

    await user.type(screen.getByRole("searchbox", { name: "搜索链路大纲" }), "x");
    await user.click(screen.getByRole("button", { name: /OrderMapper\.xml/ }));

    expect(onQueryChange).toHaveBeenCalled();
    expect(onSelectItem).toHaveBeenCalledWith("source:mapper");
  });

  it("shows a search empty state without removing group headings", () => {
    render(
      <LinkGraphOutline
        metrics={metrics}
        items={items}
        query="not-found"
        onQueryChange={vi.fn()}
        onSelectItem={vi.fn()}
      />,
    );

    expect(screen.getByRole("heading", { name: "入口" })).toBeInTheDocument();
    expect(screen.getByText("未找到匹配的方法、资源或证据。请调整关键词，或切换图谱视图查看完整链路。")).toBeInTheDocument();
  });

  it("collapses and expands outline groups without losing the group count", async () => {
    const user = userEvent.setup();

    render(
      <LinkGraphOutline
        metrics={metrics}
        items={items}
        query=""
        onQueryChange={vi.fn()}
        onSelectItem={vi.fn()}
      />,
    );

    const entrySection = screen.getByRole("region", { name: "入口" });
    expect(within(entrySection).getByRole("button", { name: /OrderController\.submit/ })).toBeInTheDocument();
    expect(within(entrySection).getByText("1")).toBeInTheDocument();

    await user.click(within(entrySection).getByRole("button", { name: "折叠入口" }));

    expect(within(entrySection).queryByRole("button", { name: /OrderController\.submit/ })).not.toBeInTheDocument();
    expect(within(entrySection).getByText("1")).toBeInTheDocument();
    expect(within(entrySection).getByRole("button", { name: "展开入口" })).toHaveAttribute("aria-expanded", "false");

    await user.click(within(entrySection).getByRole("button", { name: "展开入口" }));

    expect(within(entrySection).getByRole("button", { name: /OrderController\.submit/ })).toBeInTheDocument();
    expect(within(entrySection).getByRole("button", { name: "折叠入口" })).toHaveAttribute("aria-expanded", "true");
  });
});
