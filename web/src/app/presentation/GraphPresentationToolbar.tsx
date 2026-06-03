import type { GraphViewPresentation } from "../types";
import { formatHiddenBuckets } from "./graphPresentation";

interface GraphPresentationToolbarProps {
  presentation: GraphViewPresentation;
  query: string;
  scope: string;
  onQueryChange: (query: string) => void;
  onScopeChange: (scope: string) => void;
  onLocateTarget: () => void;
  onExpand: () => void;
}

export function GraphPresentationToolbar({
  presentation,
  query,
  scope,
  onQueryChange,
  onScopeChange,
  onLocateTarget,
  onExpand,
}: GraphPresentationToolbarProps) {
  const hiddenBuckets = formatHiddenBuckets(presentation.hiddenBuckets);
  const scopes = presentation.controls.availableScopes.length > 0
    ? presentation.controls.availableScopes
    : [presentation.controls.primaryScope].filter(Boolean);

  return (
    <div className="graph-presentation-toolbar">
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
        <button type="button" className="graph-presentation-icon-button" onClick={onLocateTarget} aria-label="定位">
          ⌖
        </button>
        {presentation.controls.expandable ? (
          <button type="button" className="graph-presentation-icon-button" onClick={onExpand} aria-label="展开">
            ↗
          </button>
        ) : null}
      </div>
      {hiddenBuckets.length > 0 ? (
        <div className="graph-presentation-hidden-buckets" aria-label="折叠内容">
          {hiddenBuckets.map((bucket) => (
            <span key={bucket} className="graph-presentation-chip">{bucket}</span>
          ))}
        </div>
      ) : null}
    </div>
  );
}
