import type { GraphViewPresentation } from "../types";
import { formatHiddenBuckets } from "./graphPresentation";

/** GraphPresentationToolbar 组件的入参。 */
interface GraphPresentationToolbarProps {
  /** 视图呈现规范（含控件状态、隐藏桶等）。 */
  presentation: GraphViewPresentation;
  /** 当前搜索关键词。 */
  query: string;
  /** 当前作用域。 */
  scope: string;
  /** 关键词变化回调。 */
  onQueryChange: (query: string) => void;
  /** 作用域切换回调。 */
  onScopeChange: (scope: string) => void;
  /** 定位目标回调。 */
  onLocateTarget: () => void;
  /** 展开回调。 */
  onExpand: () => void;
}

/**
 * 图谱呈现工具栏。
 *
 * 根据呈现规范选择性渲染：
 * - 搜索框（searchable=true 时显示）；
 * - 作用域切换按钮组；
 * - 定位/展开等图标按钮；
 * - 隐藏桶摘要（"还有 N 项被隐藏"）。
 *
 * 由 [GraphViewShell] 组合到画布上方。
 */
export function GraphPresentationToolbar({
  presentation,
  query,
  scope,
  onQueryChange,
  onScopeChange,
  onLocateTarget,
  onExpand,
}: GraphPresentationToolbarProps) {
  // 格式化隐藏桶为可读文本列表
  const hiddenBuckets = formatHiddenBuckets(presentation.hiddenBuckets);
  // 作用域列表：优先用 availableScopes；缺失时退化为只含 primaryScope 的数组
  const scopes = presentation.controls.availableScopes.length > 0
    ? presentation.controls.availableScopes
    : [presentation.controls.primaryScope].filter(Boolean);

  return (
    <div className="graph-presentation-toolbar">
      {/* 搜索框：仅 searchable=true 时显示 */}
      {presentation.controls.searchable ? (
        <label className="graph-presentation-search">
          <span className="sr-only">搜索</span>
          <input
            type="search"
            value={query}
            placeholder="搜索"
            onChange={(event) => onQueryChange(event.currentTarget.value)}
          />
        </label>
      ) : <span />}
      <div className="graph-presentation-actions">
        {/* 作用域按钮组 */}
        {scopes.length > 0 ? (
          <div className="graph-presentation-scopes" role="group" aria-label="范围">
            {scopes.map((item) => (
              <button
                key={item}
                type="button"
                className={item === scope ? "is-active" : ""}
                onClick={() => onScopeChange(item)}
              >
                {item}
              </button>
            ))}
          </div>
        ) : null}
        {/* 定位按钮 */}
        <button type="button" className="graph-presentation-icon-button" onClick={onLocateTarget} aria-label="定位">
          ⌖
        </button>
        {/* 展开按钮：仅 expandable=true 时显示 */}
        {presentation.controls.expandable ? (
          <button type="button" className="graph-presentation-icon-button" onClick={onExpand} aria-label="展开">
          </button>
        ) : null}
      </div>
      {/* 隐藏桶摘要：有内容时才展示 */}
      {hiddenBuckets.length > 0 ? (
        <div className="graph-presentation-hidden" aria-label="折叠内容">
          {hiddenBuckets.map((bucket) => (
            <span key={bucket} className="graph-presentation-hidden-chip">{bucket}</span>
          ))}
        </div>
      ) : null}
    </div>
  );
}
