import type { ReactNode } from "react";
import type { GraphViewPresentation } from "../types";
import { visibleGraphCountLabel } from "./graphPresentation";
import { GraphPresentationToolbar } from "./GraphPresentationToolbar";

interface GraphViewShellProps {
  presentation: GraphViewPresentation;
  visibleNodeCount: number;
  fullNodeCount: number;
  query: string;
  scope: string;
  children: ReactNode;
  onQueryChange: (query: string) => void;
  onScopeChange: (scope: string) => void;
  onLocateTarget: () => void;
  onExpand: () => void;
}

export function GraphViewShell({
  presentation,
  visibleNodeCount,
  fullNodeCount,
  query,
  scope,
  children,
  onQueryChange,
  onScopeChange,
  onLocateTarget,
  onExpand,
}: GraphViewShellProps) {
  return (
    <section className="graph-view-shell">
      <header className="graph-view-title-strip">
        <div className="graph-view-title-main">
          <strong>{presentation.target.title}</strong>
          {presentation.target.subtitle ? <span>{presentation.target.subtitle}</span> : null}
        </div>
        <span className="graph-view-count">
          {visibleGraphCountLabel(presentation, visibleNodeCount, fullNodeCount)}
        </span>
      </header>
      <GraphPresentationToolbar
        presentation={presentation}
        query={query}
        scope={scope}
        onQueryChange={onQueryChange}
        onScopeChange={onScopeChange}
        onLocateTarget={onLocateTarget}
        onExpand={onExpand}
      />
      <div className="graph-view-body">
        {children}
      </div>
    </section>
  );
}
