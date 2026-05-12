import { useState } from "react";
import type {
  LinkGraphOutlineItem,
  LinkGraphOutlineMetrics,
} from "./hybridDerivations";

interface LinkGraphOutlineProps {
  metrics: LinkGraphOutlineMetrics;
  items: LinkGraphOutlineItem[];
  activeItemId?: string | null;
  query: string;
  onQueryChange: (value: string) => void;
  onSelectItem: (id: string) => void;
}

const GROUPS: Array<{ id: LinkGraphOutlineItem["group"]; label: string }> = [
  { id: "entry", label: "入口" },
  { id: "criticalPath", label: "关键路径" },
  { id: "evidence", label: "资源证据" },
  { id: "risk", label: "风险线索" },
  { id: "draft", label: "草稿影响" },
];

export function LinkGraphOutline({
  metrics,
  items,
  activeItemId = null,
  query,
  onQueryChange,
  onSelectItem,
}: LinkGraphOutlineProps) {
  const [collapsedGroups, setCollapsedGroups] = useState<Set<LinkGraphOutlineItem["group"]>>(() => new Set());
  const normalizedQuery = query.trim().toLowerCase();
  const filteredItems = normalizedQuery
    ? items.filter((item) => itemMatchesQuery(item, normalizedQuery))
    : items;
  const visibleItemCount = filteredItems.length;

  function toggleGroup(groupId: LinkGraphOutlineItem["group"]) {
    setCollapsedGroups((current) => {
      const next = new Set(current);
      if (next.has(groupId)) {
        next.delete(groupId);
      } else {
        next.add(groupId);
      }
      return next;
    });
  }

  return (
    <aside className="link-outline" aria-label="链路大纲">
      <div className="link-outline-head">
        <div>
          <p className="eyebrow">Outline</p>
          <h2>链路大纲</h2>
          <p className="muted">从入口、资源证据、风险和草稿定位当前链路。</p>
        </div>
        <label className="link-outline-search">
          <span className="sr-only">搜索链路大纲</span>
          <input
            type="search"
            role="searchbox"
            aria-label="搜索链路大纲"
            value={query}
            placeholder="搜索方法、资源、证据"
            onChange={(event) => onQueryChange(event.currentTarget.value)}
          />
        </label>
      </div>

      <div className="link-outline-scroll m-scrollbar">
        <dl className="link-outline-metrics" aria-label="链路指标">
          <div>
            <dt>可见 / 全量</dt>
            <dd>{metrics.visibleNodeCount} / {metrics.fullNodeCount}</dd>
          </div>
          <div>
            <dt>入口</dt>
            <dd>{metrics.sourceAnchorCount}</dd>
          </div>
          <div>
            <dt>推断证据</dt>
            <dd>{metrics.inferredEvidenceCount}</dd>
          </div>
          <div>
            <dt>草稿影响</dt>
            <dd>{metrics.draftImpactCount}</dd>
          </div>
          <div>
            <dt>阻塞风险</dt>
            <dd>{metrics.blockingRiskCount}</dd>
          </div>
        </dl>

        {items.length === 0 ? (
          <div className="link-outline-empty">
            <strong>当前图谱没有可展示节点</strong>
            <p className="muted">请先加载链路图谱，或从 IDE 右键方法追加节点。</p>
          </div>
        ) : null}

        {GROUPS.map((group) => {
          const groupItems = filteredItems.filter((item) => item.group === group.id);
          const collapsed = collapsedGroups.has(group.id);
          return (
            <section key={group.id} className="link-outline-section" aria-label={group.label}>
              <div className="link-outline-section-title">
                <button
                  type="button"
                  className="link-outline-section-toggle"
                  aria-expanded={!collapsed}
                  aria-label={`${collapsed ? "展开" : "折叠"}${group.label}`}
                  onClick={() => toggleGroup(group.id)}
                >
                  <span className="link-outline-collapse-icon" aria-hidden="true">{collapsed ? ">" : "v"}</span>
                  <h3>{group.label}</h3>
                </button>
                <span className="app-pill">{groupItems.length}</span>
              </div>
              {collapsed ? null : (
                <div className="link-outline-section-items">
                  {groupItems.map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      className={`link-outline-row link-outline-row-${item.severity ?? "normal"}`}
                      aria-current={activeItemId === item.id ? "true" : undefined}
                      onClick={() => onSelectItem(item.id)}
                    >
                      <span className="link-outline-kind">{item.kind}</span>
                      <span className="link-outline-main">
                        <span className="link-outline-meta-row">
                          <span className="link-outline-label">{item.label}</span>
                          {item.badge ? <span className="status-pill">{item.badge}</span> : null}
                        </span>
                        {item.meta ? <span className="link-outline-meta">{item.meta}</span> : null}
                      </span>
                    </button>
                  ))}
                </div>
              )}
              {!collapsed && groupItems.length === 0 && normalizedQuery ? (
                <div className="link-outline-group-empty">
                  <span className="muted">本组没有匹配项</span>
                </div>
              ) : null}
            </section>
          );
        })}

        {items.length > 0 && visibleItemCount === 0 ? (
          <div className="link-outline-empty">
            <strong>未找到匹配的方法、资源或证据。请调整关键词，或切换图谱视图查看完整链路。</strong>
          </div>
        ) : null}
      </div>
    </aside>
  );
}

function itemMatchesQuery(item: LinkGraphOutlineItem, query: string): boolean {
  return [
    item.label,
    item.kind,
    item.badge,
    item.meta,
  ].some((value) => value?.toLowerCase().includes(query));
}
