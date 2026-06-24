import type { ReactNode } from "react";
import type { GraphViewPresentation } from "../types";
import { visibleGraphCountLabel } from "./graphPresentation";
import { GraphPresentationToolbar } from "./GraphPresentationToolbar";

/** GraphViewShell 组件的入参。 */
interface GraphViewShellProps {
  /** 视图呈现规范。 */
  presentation: GraphViewPresentation;
  /** 可见节点数。 */
  visibleNodeCount: number;
  /** 完整节点数。 */
  fullNodeCount: number;
  /** 当前搜索关键词。 */
  query: string;
  /** 当前作用域。 */
  scope: string;
  /** 画布内容 slot（React Flow 面板等）。 */
  children: ReactNode;
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
 * 图谱视图外壳：把标题条、工具栏和画布内容组合为完整视图。
 *
 * 布局结构：
 * - 标题条：视图标题 + 副标题 + 节点数标签；
 * - 工具栏：搜索/作用域/定位/展开等控件（委托给 [GraphPresentationToolbar]）；
 * - 画布区：children slot，由各视图填充自己的 React Flow 面板。
 *
 * 本组件不持有任何状态，只做布局骨架。
 */
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
      {/* 标题条 */}
      <header className="graph-view-title-strip">
        <div className="graph-view-title-main">
          <strong>{presentation.target.title}</strong>
          {presentation.target.subtitle ? <span>{presentation.target.subtitle}</span> : null}
        </div>
        {/* 节点数标签 */}
        <span className="graph-view-count">
          {visibleGraphCountLabel(presentation, visibleNodeCount, fullNodeCount)}
        </span>
      </header>
      {/* 工具栏 */}
      <GraphPresentationToolbar
        presentation={presentation}
        query={query}
        scope={scope}
        onQueryChange={onQueryChange}
        onScopeChange={onScopeChange}
        onLocateTarget={onLocateTarget}
        onExpand={onExpand}
      />
      {/* 画布内容 slot */}
      <div className="graph-view-body">
        {children}
      </div>
    </section>
  );
}
