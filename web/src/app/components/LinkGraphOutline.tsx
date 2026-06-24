import { useState } from "react";
import type {
  LinkGraphOutlineItem,
  LinkGraphOutlineMetrics,
} from "./hybridDerivations";

/** LinkGraphOutline 组件的入参。 */
interface LinkGraphOutlineProps {
  /** 链路指标。 */
  metrics: LinkGraphOutlineMetrics;
  /** 大纲条目列表（按分组展示）。 */
  items: LinkGraphOutlineItem[];
  /** 当前高亮的大纲条目 ID。 */
  activeItemId?: string | null;
  /** 搜索关键词。 */
  query: string;
  /** 关键词变化回调。 */
  onQueryChange: (value: string) => void;
  /** 选中条目的回调。 */
  onSelectItem: (id: string) => void;
}

/**
 * 大纲分组定义：固定顺序与标签。
 *
 * 把链路中的节点按语义角色分为入口、关键路径、证据、风险、草稿五组，
 * 让用户可以按角色快速定位节点。
 */
const GROUPS: Array<{ id: LinkGraphOutlineItem["group"]; label: string }> = [
  { id: "entry", label: "入口" },
  { id: "criticalPath", label: "关键路径" },
  { id: "evidence", label: "资源证据" },
  { id: "risk", label: "风险线索" },
  { id: "draft", label: "草稿影响" },
];

/**
 * 链路大纲侧边栏。
 *
 * 在画布左侧展示链路中所有节点的结构化大纲，让用户可以：
 * - 看到当前链路的整体指标（可见/全量、入口数、证据数等）；
 * - 按分组浏览节点；
 * - 通过搜索快速定位节点；
 * - 点击节点跳转到画布对应位置。
 *
 * 支持分组折叠与搜索过滤；空列表与无匹配结果有各自的占位文案。
 */
export function LinkGraphOutline({
  metrics,
  items,
  activeItemId = null,
  query,
  onQueryChange,
  onSelectItem,
}: LinkGraphOutlineProps) {
  // 各分组的折叠状态：用 Set 记录被折叠的分组 ID
  const [collapsedGroups, setCollapsedGroups] = useState<Set<LinkGraphOutlineItem["group"]>>(() => new Set());
  const normalizedQuery = query.trim().toLowerCase();
  // 有关键词时按关键词过滤条目
  const filteredItems = normalizedQuery
    ? items.filter((item) => itemMatchesQuery(item, normalizedQuery))
    : items;
  const visibleItemCount = filteredItems.length;

  /** 切换某分组的折叠状态。 */
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
        <h2>链路大纲</h2>
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
        {/* 指标区：展示链路整体统计 */}
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

        {/* 完全没有节点时给出空态提示 */}
        {items.length === 0 ? (
          <div className="link-outline-empty">
            <strong>当前图谱没有可展示节点</strong>
            <p className="muted">请先加载链路图谱，或从 IDE 右键方法追加节点。</p>
          </div>
        ) : null}

        {/* 按 GROUPS 顺序渲染各分组 */}
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
              {/* 折叠时不渲染条目；展开时渲染条目列表 */}
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
              {/* 展开但无匹配项时给出提示 */}
              {!collapsed && groupItems.length === 0 && normalizedQuery ? (
                <div className="link-outline-group-empty">
                  <span className="muted">本组没有匹配项</span>
                </div>
              ) : null}
            </section>
          );
        })}

        {/* 有节点但搜索后无匹配：给出整体空态提示 */}
        {items.length > 0 && visibleItemCount === 0 ? (
          <div className="link-outline-empty">
            <strong>未找到匹配的方法、资源或证据。请调整关键词，或切换图谱视图查看完整链路。</strong>
          </div>
        ) : null}
      </div>
    </aside>
  );
}

/**
 * 判断单个大纲条目是否匹配搜索关键词。
 * 检查标签、种类、徽章、元数据任一字段是否包含关键词。
 */
function itemMatchesQuery(item: LinkGraphOutlineItem, query: string): boolean {
  return [
    item.label,
    item.kind,
    item.badge,
    item.meta,
  ].some((value) => value?.toLowerCase().includes(query));
}
