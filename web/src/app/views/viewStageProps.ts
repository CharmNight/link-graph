import type {
  DraftCompareProjection,
  GraphFocusRequest,
  GraphSurfaceExperimentFlags,
  IndexedClassDiagramOptions,
  IndexedGraphRequestStates,
  GraphPosition,
  InvocationExpansionSceneState,
} from "../types";

/**
 * 所有视图共享的基础 props。
 *
 * 既包含只读数据（选中节点、焦点、差异投影、折叠状态等），
 * 也包含通用回调（选中、跳转、移动、折叠等）。
 * 各视图组件按需扩展这个基础接口。
 */
export interface BaseStageProps {
  /** 当前选中节点 ID。 */
  selectedNodeId: string | null;
  /** 焦点节点请求；带 nonce 用于多次触发区分。 */
  focusNodeRequest?: GraphFocusRequest | null;
  /** 链路讲解当前聚焦的节点 ID。 */
  explanationFocusNodeId?: string | null;
  /** 草稿变更涉及的节点 ID 列表。 */
  draftChangedNodeIds?: string[];
  /** 草稿比对投影数据。 */
  draftCompareProjection?: DraftCompareProjection | null;
  /** 多选场景下当前选中的节点 ID 组。 */
  selectedGroupNodeIds?: string[];
  /** 隐藏的节点 ID 列表。 */
  hiddenNodeIds?: string[];
  /** 折叠的节点 ID 列表。 */
  collapsedNodeIds?: string[];
  /** 流程图调用展开的折叠/激活状态。 */
  invocationExpansionState?: InvocationExpansionSceneState | null;
  /** 节点 ID → 折叠子节点数 的映射；用于显示"已折叠 N 项"。 */
  collapsedDescendantCountByNodeId?: Record<string, number>;
  /** 视图实验标志；为空表示未启用任何实验。 */
  experiments?: GraphSurfaceExperimentFlags | null;
  /** 节点选中回调。 */
  onSelectNode: (nodeId: string) => void;
  /** 多选变化回调。 */
  onSelectionGroupChange?: (nodeIds: string[]) => void;
  /** 节点"检查"（打开属性面板等）回调。 */
  onInspectNode: (nodeId: string) => void;
  /** 节点拖动到新位置回调。 */
  onMoveNode: (nodeId: string, position: GraphPosition) => void;
  /** 多节点批量移动回调。 */
  onMoveNodes?: (updates: Array<{ id: string; position: GraphPosition }>) => void;
  /** 格式化布局回调。 */
  onFormatLayout?: () => void;
  /** 请求链路讲解回调；可传入指定节点。 */
  onRequestBeautification?: (selectedNodeId?: string) => void;
  /** 请求源码跳转回调。 */
  onRequestSourceNavigation: (nodeId: string) => void;
  /** 预填问答输入框回调。 */
  onPrimeQuestionComposer?: (selectedNodeId?: string) => void;
  /** 折叠/展开节点回调。 */
  onToggleCollapseNode?: (nodeId: string) => void;
  /** 打开问答回调。 */
  onOpenQa?: (selectedNodeId?: string) => void;
  /** 展开溢出（被折叠）节点回调。 */
  onExpandOverflowNode?: (nodeId: string) => void;
  /** 展开调用结构回调。 */
  onExpandInvocation?: (nodeId: string) => void;
  /** 移除已展开的调用结构回调。 */
  onRemoveInvocationExpansion?: (expansionId: string) => void;
  /** 折叠某个调用展开块。 */
  onCollapseInvocationExpansion?: (expansionId: string) => void;
  /** 打开某个折叠的调用展开块。 */
  onOpenInvocationExpansion?: (expansionId: string) => void;
}

/**
 * 可编辑视图额外携带的回调。
 *
 * 事实图、流程图、资源关系图三个视图允许用户增删节点与边，
 * 因此需要这组编辑回调。
 */
export interface EditableStageProps extends BaseStageProps {
  /** 新增节点回调；kind 决定是方法节点还是文档页节点。 */
  onAddNode: (kind: "METHOD" | "DOC_PAGE", position?: GraphPosition) => void;
  /** 删除节点回调。 */
  onDeleteNode: (nodeId: string) => void;
  /** 删除节点及其整棵子树回调。 */
  onDeleteNodeSubtree?: (nodeId: string) => void;
  /** 新增边回调；sourceHandle/targetHandle 用于多端口节点。 */
  onCreateEdge: (
    sourceId: string,
    targetId: string,
    sourceHandle?: string | null,
    targetHandle?: string | null,
  ) => void;
  /** 删除边回调。 */
  onDeleteEdge: (edgeId: string) => void;
  /** 在已有边上插入新节点（断开原边、新增两条边）回调。 */
  onInsertNodeIntoEdge?: (edgeId: string, kind: "METHOD" | "DOC_PAGE") => void;
  /** 触发 Mermaid 导入对话框回调。 */
  onImportMermaid: () => void;
}

/**
 * 索引只读视图额外携带的回调。
 *
 * 架构图、类图、审查图三个视图基于索引生成，不允许用户直接增删节点，
 * 但支持请求不同范围/配置的索引图。
 */
export interface IndexedReadonlyStageProps extends BaseStageProps {
  /** 索引图请求状态集合。 */
  indexedGraphRequestStates?: IndexedGraphRequestStates | null;
  /** 请求类图回调；可指定锚点节点。 */
  onRequestClassDiagram?: (scopeNodeId?: string | null) => void;
  /** 请求类图回调（带配置项）。 */
  onRequestClassDiagramWithOptions?: (
    scopeNodeId: string | null | undefined,
    options: Partial<IndexedClassDiagramOptions>,
  ) => void;
  /** 请求类使用关系图回调。 */
  onRequestClassUsages?: (
    targetNodeId: string,
    options?: {
      scopeNodeId?: string | null;
      targetQualifiedName?: string | null;
      sourceVirtualFileUrl?: string | null;
      sourcePath?: string | null;
      maxUsageGroups?: number | null;
      maxUsageEntries?: number | null;
      includeImports?: boolean | null;
    },
  ) => void;
  /** 请求包依赖图回调。 */
  onRequestPackageDependencyGraph?: (
    packageName?: string | null,
    options?: { includeExternalLibraries?: boolean; includeJdk?: boolean },
  ) => void;
  /** 请求架构图回调。 */
  onRequestArchitectureGraph?: (options?: { includeExternalLibraries?: boolean; includeJdk?: boolean }) => void;
  /** 请求审查图（带配置项）回调。 */
  onRequestReviewGraphWithOptions?: (
    selectedDiffItemIds: string[],
    options: {
      maxChangedNodes?: number;
      maxRelatedTestNodes?: number;
      maxUpstreamNodes?: number;
      maxDownstreamNodes?: number;
    },
  ) => void;
}

/** 允许编辑的展示模式：事实图、流程图、资源关系图。 */
export type EditableDisplayMode = "FACT_GRAPH" | "FLOWCHART" | "RESOURCE_RELATION_VIEW";

/** 索引只读的展示模式：架构图、类图、审查图。 */
export type IndexedReadonlyDisplayMode = "ARCHITECTURE_GRAPH" | "CLASS_DIAGRAM" | "REVIEW_GRAPH";

/**
 * 按展示模式区分的视图 props 联合类型。
 *
 * 当 analysisDisplayMode 是可编辑模式时，stageProps 对应 EditableStageProps；
 * 否则对应 IndexedReadonlyStageProps。这种联合让上层组件可以类型安全地按模式分发 props。
 */
export type AppGraphStagePropsByMode =
  | { analysisDisplayMode: EditableDisplayMode; stageProps: EditableStageProps }
  | { analysisDisplayMode: IndexedReadonlyDisplayMode; stageProps: IndexedReadonlyStageProps };
