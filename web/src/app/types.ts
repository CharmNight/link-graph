/**
 * 重新导出传输层使用的数据信封类型，便于上层统一从 types.ts 引用而无需关心分包。
 */
// 助手域类型：从 assistantTypes 引入并在文件末尾 re-export；本地 import 是为了文件内部其他接口引用。
import type {
  AssistantActionId,
  AssistantIntent,
  AssistantTurnKind,
  IndexedGraphFreshness,
  IndexedGraphVisibilityReason,
} from "./assistant/assistantTypes";

export type {
  LinkGraphArtifactSliceEnvelope,
  LinkGraphFeedbackSliceEnvelope,
  LinkGraphIncrementalTransportEnvelope,
  LinkGraphSnapshotEnvelope,
  LinkGraphTransportEnvelopeBase,
} from "./transportTypes";

import type {
  DiffStatus,
  DraftCompareStatus,
  GraphPatch,
  LinkGraphDocument,
  LinkGraphSceneId,
  LinkGraphSceneState,
} from "./graphTypes";

/**
 * 重新导出图谱核心模型中的类型，让 types.ts 成为外部使用图谱数据结构的统一入口。
 */
export type {
  BindingStatus,
  Certainty,
  DiffStatus,
  DraftCompareStatus,
  EdgeType,
  GraphDiffElementKind,
  GraphEditOperation,
  GraphEditRequest,
  GraphEditRequestSource,
  GraphFocusRequest,
  GraphPatch,
  GraphPatchAction,
  GraphPatchOperation,
  GraphPosition,
  GraphSourceTag,
  ChildInvocationExpansionState,
  InvocationExpansionContextMode,
  InvocationExpansionRegistry,
  InvocationExpansionRegistryEntry,
  InvocationExpansionSceneState,
  LinkGraphDocument,
  LinkGraphEdge,
  LinkGraphEdgeRoute,
  LinkGraphEdgeRouteSection,
  LinkGraphLayoutState,
  LinkGraphNode,
  LinkGraphSceneId,
  LinkGraphSceneState,
  NodeType,
} from "./graphTypes";

/**
 * 标记结果来源：禁用、本地规则推导、远程模型，影响后续是否需要展示降级提示。
 */
export type LlmResultSource = "DISABLED" | "LOCAL_RULE" | "REMOTE";

/**
 * 证据强度等级：从直接源码命中到完全未观测，用于衡量回答的可信度。
 */
export type ResultEvidenceLevel = "DIRECT_SOURCE" | "DIRECT_GRAPH" | "CALLSITE_ONLY" | "NOT_OBSERVED";

/**
 * 草稿声明的类别：代码事实、风险提示、说明性备注、结构性建议。
 */
export type DraftClaimType = "CODE_FACT" | "RISK_HINT" | "EXPLANATION_NOTE" | "STRUCTURAL_SUGGESTION";

/**
 * 草稿补丁预览的来源场景：问答、Diff 复核、上次应用，决定预览刷新的时机。
 */
export type DraftPatchPreviewSource = "QA" | "DIFF_REVIEW" | "LAST_APPLIED";

/**
 * 分析视图的种类：事实图、流程图、资源关系视图、架构图、类图、复核图。
 */
export type AnalysisDisplayMode =
  | "FACT_GRAPH"
  | "FLOWCHART"
  | "RESOURCE_RELATION_VIEW"
  | "ARCHITECTURE_GRAPH"
  | "CLASS_DIAGRAM"
  | "REVIEW_GRAPH";

/**
 * 流程步骤粒度：业务流程、方法调用、代码语义，决定步骤的抽象层级。
 */
export type StepGranularity = "BUSINESS" | "METHOD_CALL" | "CODE_SEMANTIC";

/**
 * 流程步骤的语义类别：业务动作、方法调用、条件分支、返回、资源交互、结构概览。
 */
export type StepKind =
  | "BUSINESS_ACTION"
  | "METHOD_CALL"
  | "CONDITION"
  | "RETURN"
  | "RESOURCE_INTERACTION"
  | "STRUCTURE_OVERVIEW";

/**
 * 候选变更的生命周期状态：待确认、已确认、已拒绝、已被取代。
 */
export type CandidateDraftChangeStatus = "PENDING_CONFIRMATION" | "CONFIRMED" | "REJECTED" | "SUPERSEDED";

/**
 * 调查线索的状态：开放、提升为候选、被驳回、阻塞、被取代。
 */
export type InvestigationThreadStatus = "OPEN" | "PROMOTED" | "DISMISSED" | "BLOCKED" | "SUPERSEDED";

/**
 * 风险处置结论：未解决、暂缓、接受、证据耗尽、驳回、提升为候选。
 */
export type RiskResolutionStatus =
  | "UNRESOLVED"
  | "DEFERRED"
  | "ACCEPTED_RISK"
  | "EVIDENCE_EXHAUSTED"
  | "DISMISSED"
  | "PROMOTED";

/**
 * 单轮调查的结果状态：已提升为候选、有进展仍开放、无进展、被驳回、被阻塞。
 */
export type InvestigationTurnOutcomeStatus =
  | "PROMOTED_TO_CANDIDATE"
  | "OPEN_WITH_PROGRESS"
  | "OPEN_NO_PROGRESS"
  | "DISMISSED"
  | "BLOCKED";

/**
 * 工作台中草稿条目的种类：变更或备注。
 */
export type DraftEntryKind = "CHANGE" | "NOTE";

/**
 * 问答消息的发言角色：用户或助手。
 */
export type QaMessageRole = "USER" | "ASSISTANT";

/**
 * 问答请求的种类：通用提问或针对调查线索的追问。
 */
export type QaRequestKind = "ASK" | "INVESTIGATE_THREAD";

/**
 * 问答的执行模式：自动选择、仅回答、复核、变更建议、调查式追问。
 */
export type QaMode = "AUTO" | "ANSWER" | "REVIEW" | "CHANGE" | "INVESTIGATE";

/**
 * 阶段准入评估的目标：方案阶段或代码阶段。
 */
export type StageEligibilityTarget = "PLAN" | "CODE";

/**
 * 助手域类型已拆到 `app/assistant/assistantTypes.ts`；这里仅做 re-export 以兼容现有 import 路径。
 * 新代码请直接从 `assistantTypes.ts` 导入。
 */
export type {
  AssistantActionId,
  AssistantIntent,
  AssistantTurnKind,
  IndexedGraphFreshness,
  IndexedGraphVisibilityReason,
} from "./assistant/assistantTypes";

/**
 * 图视图的呈现描述：包含锚点目标、泳道、隐藏分桶与交互控件，决定视图的可见性与组织方式。
 */
export interface GraphViewPresentation {
  /** 当前视图聚焦的目标节点信息 */
  target: GraphPresentationTarget;
  /** 视图中按列/行/区组织起来的泳道 */
  lanes: GraphPresentationLane[];
  /** 被折叠隐藏的元素分桶，便于一键恢复显示 */
  hiddenBuckets: GraphHiddenBucket[];
  /** 用户可执行的视图控件（如切换作用域、搜索、展开） */
  controls: GraphPresentationControls;
}

/**
 * 视图聚焦的节点目标描述：标题、副标题以及所在位置，供 UI 在头部展示。
 */
export interface GraphPresentationTarget {
  /** 锚点节点 ID，空表示无具体锚点 */
  nodeId: string | null;
  /** 目标主标题 */
  title: string;
  /** 目标副标题 */
  subtitle: string;
  /** 可选的源文件位置标签 */
  location?: string | null;
}

/**
 * 视图泳道：用于按列/行/区分组排列节点，影响布局算法。
 */
export interface GraphPresentationLane {
  /** 泳道唯一标识 */
  id: string;
  /** 泳道显示名 */
  label: string;
  /** 泳道方向：列、行或区域 */
  axis: "COLUMN" | "ROW" | "ZONE";
  /** 在所属方向上的排序值 */
  order: number;
  /** 泳道在布局中的语义角色（如"调用方"、"被调用方"） */
  role: string;
}

/**
 * 隐藏元素分桶：把被折叠的节点和边按主题分组，方便用户查看与恢复。
 */
export interface GraphHiddenBucket {
  /** 分桶标识 */
  id: string;
  /** 分桶显示名 */
  label: string;
  /** 分桶内元素总数 */
  count: number;
  /** 被隐藏的节点 ID 列表 */
  nodeIds: string[];
  /** 被隐藏的边 ID 列表 */
  edgeIds: string[];
}

/**
 * 视图交互控件元信息：作用域可选项、是否支持搜索与展开。
 */
export interface GraphPresentationControls {
  /** 当前主作用域标签 */
  primaryScope: string;
  /** 可切换的作用域标签集合 */
  availableScopes: string[];
  /** 是否允许搜索节点 */
  searchable: boolean;
  /** 是否允许展开/折叠分组 */
  expandable: boolean;
}

/**
 * 事实图的统计摘要：可见/全量/隐藏数量，以及是否被截断。
 */
export interface FactGraphSummary {
  /** 锚点标题（用于回显当前焦点） */
  anchorTitle?: string | null;
  /** 当前可见节点数 */
  visibleNodeCount: number;
  /** 完整图中的节点总数 */
  fullNodeCount: number;
  /** 被隐藏的节点数 */
  hiddenNodeCount?: number;
  /** 被隐藏的边数 */
  hiddenEdgeCount?: number;
  /** 是否因为上限而截断 */
  truncated?: boolean;
}

/**
 * 事实图视图文档：包含可见子图、完整图、投影索引、摘要与呈现描述。
 */
export interface FactGraphViewDocument {
  /** 当前展示的子图 */
  visibleGraph: LinkGraphDocument;
  /** 全量未裁剪的图（用于"展开全部"等场景） */
  fullGraph: LinkGraphDocument;
  /** 视图锚点节点 ID */
  anchorNodeId?: string | null;
  /** 投影索引，记录可见元素到原始元素的映射 */
  projectionIndex?: GraphProjectionIndex;
  /** 视图统计摘要 */
  summary: FactGraphSummary;
  /** 视图呈现描述 */
  presentation: GraphViewPresentation;
}

/**
 * 流程图统计摘要：节点/分支/异常路径计数以及完整性提示。
 */
export interface FlowchartSummary {
  /** 当前节点数 */
  nodeCount: number;
  /** 分支数（条件节点） */
  branchCount: number;
  /** 异常路径数 */
  exceptionPathCount: number;
  /** 全量节点数 */
  fullNodeCount?: number;
  /** 全量边数 */
  fullEdgeCount?: number;
  /** 隐藏节点数 */
  hiddenNodeCount?: number;
  /** 隐藏边数 */
  hiddenEdgeCount?: number;
  /** 是否被截断 */
  truncated?: boolean;
  /** 不完整节点数（无法解析语义的节点） */
  incompleteNodeCount?: number;
  /** 不完整边数 */
  incompleteEdgeCount?: number;
  /** 语义上是否完整 */
  semanticallyIncomplete?: boolean;
  /** 合成边数（用于补全缺失路径） */
  syntheticEdgeCount?: number;
  /** 合成入口边数 */
  syntheticEntryEdgeCount?: number;
}

/**
 * 流程图视图文档：可见子图、完整图、投影索引与摘要。
 */
export interface FlowchartViewDocument {
  /** 当前展示的子图 */
  visibleGraph: LinkGraphDocument;
  /** 全量未裁剪的图 */
  fullGraph: LinkGraphDocument;
  /** 锚点节点 ID */
  anchorNodeId?: string | null;
  /** 投影索引 */
  projectionIndex?: GraphProjectionIndex;
  /** 视图统计摘要 */
  summary: FlowchartSummary;
}

/**
 * 资源关系视图的摘要：可见节点、关系与资源数量，以及降级原因。
 */
export interface ResourceRelationSummary {
  /** 可见节点数 */
  visibleNodeCount: number;
  /** 关系数 */
  relationCount: number;
  /** 资源数 */
  resourceCount: number;
  /** 降级原因：无资源单元、无绑定关系等 */
  fallbackReason: "NONE" | "NO_RESOURCE_UNITS" | "NO_BINDING_RELATIONS" | string;
  /** 各泳道的元素数量 */
  laneCounts: Record<string, number>;
}

/**
 * 资源关系视图文档：呈现资源与绑定方之间的交互关系。
 */
export interface ResourceRelationViewDocument {
  /** 当前展示的子图 */
  visibleGraph: LinkGraphDocument;
  /** 全量未裁剪的图 */
  fullGraph: LinkGraphDocument;
  /** 锚点节点 ID */
  anchorNodeId?: string | null;
  /** 投影索引 */
  projectionIndex?: GraphProjectionIndex;
  /** 视图统计摘要 */
  summary: ResourceRelationSummary;
}

/**
 * 索引图统计摘要：覆盖架构/类图/复核视图的通用指标（数量、完整性、缓存状态等）。
 */
export interface IndexedGraphSummary {
  /** 视图种类 */
  view: "ARCHITECTURE" | "CLASS_DIAGRAM" | "REVIEW" | string;
  /** 锚点元素的类别（类/方法/资源等） */
  anchorKind?: string | null;
  /** 锚点节点 ID */
  anchorNodeId?: string | null;
  /** 锚点显示标题 */
  anchorTitle?: string | null;
  /** 锚点的全限定名 */
  anchorQualifiedName?: string | null;
  /** 作用域种类（包/模块/类等） */
  scopeKind: string;
  /** 作用域显示名 */
  scopeLabel: string;
  /** 关系种类列表 */
  relationKinds: string[];
  /** 视图扩展深度 */
  depth: number;
  /** 项目节点总数 */
  projectNodeCount: number;
  /** 项目中类节点数 */
  projectClassCount: number;
  /** 外部库节点数 */
  externalNodeCount: number;
  /** JDK 节点数 */
  jdkNodeCount: number;
  /** 在作用域内节点数 */
  scopedNodeCount: number;
  /** 当前可见节点数 */
  visibleNodeCount: number;
  /** 隐藏节点数 */
  hiddenNodeCount: number;
  /** 隐藏边数 */
  hiddenEdgeCount: number;
  /** 候选（未选中展示）节点数 */
  candidateNodeCount: number;
  /** 候选边数 */
  candidateEdgeCount: number;
  /** 是否被截断 */
  truncated: boolean;
  /** 完整性描述 */
  completeness: string;
  /** 索引缓存状态 */
  cacheState: string;
  /** 是否包含外部库 */
  includeExternalLibraries?: boolean;
  /** 是否包含 JDK */
  includeJdk?: boolean;
  /** 项目源码节点数 */
  projectSourceNodeCount?: number;
  /** 外部库节点数 */
  externalLibraryNodeCount?: number;
  /** 资源节点数 */
  resourceNodeCount?: number;
  /** 聚合节点数 */
  aggregateNodeCount?: number;
  /** 项目层节点分类计数 */
  projectLayerCounts?: IndexedGraphLayerCounts | null;
  /** 可见层节点分类计数 */
  visibleLayerCounts?: IndexedGraphLayerCounts | null;
  /** 作用域层节点分类计数 */
  scopedLayerCounts?: IndexedGraphLayerCounts | null;
  /** 候选层节点分类计数 */
  candidateLayerCounts?: IndexedGraphLayerCounts | null;
  /** 隐藏层节点分类计数 */
  hiddenLayerCounts?: IndexedGraphLayerCounts | null;
  /** 折叠层节点分类计数 */
  collapsedLayerCounts?: IndexedGraphLayerCounts | null;
  /** 索引新鲜度信息 */
  freshness?: IndexedGraphFreshness | null;
  /** 各类元素被隐藏/折叠的原因列表 */
  visibilityReasons?: IndexedGraphVisibilityReason[] | null;
}

/**
 * 不同层级（项目源码、外部库、JDK、资源、聚合）的节点数量分布。
 */
export interface IndexedGraphLayerCounts {
  /** 项目源码节点数 */
  projectSource?: number;
  /** 外部库节点数 */
  externalLibrary?: number;
  /** JDK 节点数 */
  jdk?: number;
  /** 资源节点数 */
  resource?: number;
  /** 聚合节点数 */
  aggregate?: number;
}

// `IndexedGraphFreshness` 与 `IndexedGraphVisibilityReason` 已迁移到
// `app/assistant/assistantTypes.ts`；本文件开头通过 `export type { ... } from` 再导出，
// 兼容旧 import 路径。

/** 索引图支持的视图种类 */
export type IndexedGraphView = "ARCHITECTURE" | "CLASS_DIAGRAM" | "REVIEW";

/** 各索引视图对应的异步请求状态映射 */
export type IndexedGraphRequestStates = Partial<Record<IndexedGraphView, AsyncRequestState>>;

/**
 * 索引图视口的可选裁剪参数（最大可见节点/边数）。
 */
export interface IndexedGraphViewportOptions {
  /** 最大可见节点数 */
  maxVisibleNodes?: number | null;
  /** 最大可见边数 */
  maxVisibleEdges?: number | null;
}

/** 关系细节级别：仅结构、作用域内方法体关系、完整 */
export type IndexedGraphRelationDetail = "STRUCTURE_ONLY" | "SCOPED_BODY_RELATIONS" | "COMPLETE";

/**
 * 类图索引查询的可选参数：邻域限制、成员限制、关系细节级别。
 */
export interface IndexedClassDiagramOptions {
  /** 邻域扩展跳数限制 */
  neighborhoodLimit: number;
  /** 每个类型展示成员数上限 */
  memberLimit: number;
  /** 关系细节级别 */
  relationDetail?: IndexedGraphRelationDetail;
}

/**
 * 类用法检索的参数：是否启用、目标节点、来源文件、上下文限制等。
 */
export interface IndexedClassUsageOptions {
  /** 是否启用类用法检索 */
  enabled: boolean;
  /** 目标节点 ID */
  targetNodeId?: string | null;
  /** 目标全限定名 */
  targetQualifiedName?: string | null;
  /** 触发检索的虚拟文件 URL */
  sourceVirtualFileUrl?: string | null;
  /** 触发检索的源文件路径 */
  sourcePath?: string | null;
  /** 用法分组数上限 */
  maxUsageGroups: number;
  /** 每组用条目数上限 */
  maxUsageEntries: number;
  /** 是否包含 import 语句 */
  includeImports: boolean;
}

/**
 * 类用法种类：类型引用、字段类型、方法参数、返回值、构造调用、注解、import、继承、实现等。
 */
export type ClassUsageKind =
  | "TYPE_REFERENCE"
  | "FIELD_TYPE"
  | "METHOD_PARAMETER"
  | "METHOD_RETURN"
  | "CONSTRUCTOR_CALL"
  | "ANNOTATION"
  | "IMPORT"
  | "EXTENDS"
  | "IMPLEMENTS"
  | "OTHER";

/** 用法归属的容器种类：类、方法或文件级别 */
export type ClassUsageOwnerKind = "CLASS" | "METHOD" | "FILE";

/**
 * 类用法检索的目标描述：节点 ID、全限定名与显示名。
 */
export interface ClassUsageTarget {
  /** 目标节点 ID */
  nodeId: string;
  /** 全限定名 */
  qualifiedName: string;
  /** 用于 UI 展示的名称 */
  displayName: string;
}

/**
 * 单条用法记录：精确到行列位置的代码引用。
 */
export interface ClassUsageEntry {
  /** 条目唯一 ID */
  id: string;
  /** 所属容器 ID */
  ownerId: string;
  /** 用法种类 */
  kind: ClassUsageKind;
  /** 源文件路径 */
  filePath: string;
  /** 行号 */
  line: number;
  /** 列号 */
  column: number;
  /** 用法处的代码文本 */
  text: string;
  /** 关联的虚拟文件 URL（用于跳转编辑器） */
  virtualFileUrl?: string | null;
  /** 所属类的全限定名 */
  ownerQualifiedName?: string | null;
  /** 所属方法签名 */
  ownerMethodSignature?: string | null;
}

/**
 * 用法分组：按容器（类/方法/文件）聚合的一组用法条目。
 */
export interface ClassUsageGroup {
  /** 分组 ID */
  id: string;
  /** 所属节点 ID */
  ownerNodeId?: string | null;
  /** 所属容器种类 */
  ownerKind: ClassUsageOwnerKind;
  /** 分组标题 */
  title: string;
  /** 所属容器的全限定名 */
  qualifiedName?: string | null;
  /** 所属文件路径 */
  filePath?: string | null;
  /** 关联的虚拟文件 URL */
  virtualFileUrl?: string | null;
  /** 该分组下的所有用法 */
  usages: ClassUsageEntry[];
}

/**
 * 用法检索的统计摘要：覆盖总数、是否截断、是否可继续请求更多。
 */
export interface ClassUsageSummary {
  /** 目标节点 ID */
  targetNodeId: string;
  /** 目标全限定名 */
  targetQualifiedName: string;
  /** 分组总数 */
  groupCount: number;
  /** 用法总数 */
  usageCount: number;
  /** 可见分组数 */
  visibleGroupCount: number;
  /** 可见用法数 */
  visibleUsageCount: number;
  /** 是否被截断 */
  truncated: boolean;
  /** 分组上限 */
  maxUsageGroups: number;
  /** 条目上限 */
  maxUsageEntries: number;
  /** 是否包含 import */
  includeImports: boolean;
  /** 是否还能继续请求更多结果 */
  canRequestMore: boolean;
}

/**
 * 类用法检索的完整结果：目标描述、分组列表与统计摘要。
 */
export interface ClassUsageSearchResult {
  /** 检索目标 */
  target: ClassUsageTarget;
  /** 用法分组列表 */
  groups: ClassUsageGroup[];
  /** 摘要信息 */
  summary: ClassUsageSummary;
}

/**
 * 复核图索引查询参数：各类关联节点的数量上限。
 */
export interface IndexedReviewGraphOptions {
  /** 变更节点上限 */
  maxChangedNodes: number;
  /** 关联测试节点上限 */
  maxRelatedTestNodes: number;
  /** 上游节点上限 */
  maxUpstreamNodes: number;
  /** 下游节点上限 */
  maxDownstreamNodes: number;
}

/**
 * 架构图统计摘要：模块/包/服务/资源/层级等结构指标。
 */
export interface ArchitectureGraphSummary {
  /** 模块数 */
  moduleCount: number;
  /** 包数 */
  packageCount: number;
  /** 服务数 */
  serviceCount: number;
  /** 组件数 */
  componentCount?: number;
  /** 资源数 */
  resourceCount: number;
  /** 层级数 */
  layerCount: number;
  /** 库数量 */
  libraryCount?: number;
  /** JDK 数量 */
  jdkCount?: number;
  /** 关系数 */
  relationCount: number;
  /** 类总数 */
  classCount: number;
  /** 关系节点数 */
  relationshipNodeCount?: number;
  /** 仅列出但未连接的节点数 */
  inventoryOnlyNodeCount?: number;
  /** 未连接的包数 */
  unconnectedPackageCount?: number;
  /** 未连接的组件数 */
  unconnectedComponentCount?: number;
  /** 未连接的服务边界数 */
  unconnectedServiceBoundaryCount?: number;
  /** 未连接的资源数 */
  unconnectedResourceCount?: number;
  /** 外部依赖分组数 */
  externalDependencyGroupCount?: number;
  /** JDK 分组数 */
  jdkGroupCount?: number;
  /** 是否截断 */
  truncated?: boolean;
  /** 隐藏节点数 */
  hiddenNodeCount?: number;
  /** 隐藏边数 */
  hiddenEdgeCount?: number;
  /** 索引摘要（详细统计） */
  indexed?: IndexedGraphSummary | null;
  /** 项目结构关系分组列表 */
  projectStructureRelationGroups?: ProjectStructureRelationGroup[];
}

/**
 * 项目结构关系分组：把若干底层关系聚合成一条可展示的"模块间/包间"关系。
 */
export interface ProjectStructureRelationGroup {
  /** 分组 ID */
  id: string;
  /** 起点节点 ID */
  fromNodeId: string;
  /** 终点节点 ID */
  toNodeId: string;
  /** 展示用的关系种类代码 */
  displayRelationKind: string;
  /** 展示用的关系名称 */
  displayRelation: string;
  /** 聚合的关系种类集合 */
  relationKinds: string[];
  /** 聚合的底层关系数 */
  count: number;
  /** 关系可信度（高/中/低） */
  confidence: string;
  /** 源关系 ID 列表 */
  sourceRelationIds: string[];
  /** 证据引用样本 */
  sampleEvidenceRefs: string[];
  /** 是否默认可见 */
  defaultVisible: boolean;
  /** 被隐藏的原因 */
  hiddenReason?: string | null;
}

/**
 * 架构图视图文档：可见子图、完整图、投影索引、摘要与呈现描述。
 */
export interface ArchitectureGraphViewDocument {
  /** 当前展示的子图 */
  visibleGraph: LinkGraphDocument;
  /** 全量未裁剪的图 */
  fullGraph: LinkGraphDocument;
  /** 锚点节点 ID */
  anchorNodeId?: string | null;
  /** 投影索引 */
  projectionIndex?: GraphProjectionIndex;
  /** 视图摘要 */
  summary: ArchitectureGraphSummary;
  /** 视图呈现描述 */
  presentation: GraphViewPresentation;
}

/**
 * 类图统计摘要：类/接口/枚举/注解/记录等数量与关系完整性。
 */
export interface ClassDiagramSummary {
  /** 类数 */
  classCount: number;
  /** 字段数 */
  fieldCount?: number;
  /** 接口数 */
  interfaceCount: number;
  /** 枚举数 */
  enumCount: number;
  /** 注解类型数 */
  annotationCount: number;
  /** 记录类（Java record）数 */
  recordCount: number;
  /** Kotlin object 单例数 */
  objectCount: number;
  /** 关系数 */
  relationCount: number;
  /** SPI 提供者数 */
  spiProviderCount?: number;
  /** 反射关系数 */
  reflectionRelationCount?: number;
  /** 关系完整性级别 */
  relationCompleteness?: IndexedGraphRelationDetail | string;
  /** 作用域内类型数 */
  scopeTypeCount?: number;
  /** 项目内类型总数 */
  projectTypeCount?: number;
  /** 项目内类总数 */
  projectClassCount?: number;
  /** 作用域的依据：邻域扩展或显式作用域 */
  scopeBasis?: "CLASS_NEIGHBORHOOD" | "EXPLICIT_SCOPE" | string;
  /** 锚点类型节点 ID */
  anchorTypeNodeId?: string | null;
  /** 锚点类型标题 */
  anchorTypeTitle?: string | null;
  /** 锚点类型全限定名 */
  anchorTypeQualifiedName?: string | null;
  /** 邻域扩展跳数 */
  neighborhoodLimit?: number;
  /** 每类型成员上限 */
  memberLimit?: number;
  /** 候选类型总数（被裁剪前） */
  neighborhoodCandidateTypeCount?: number;
  /** 邻域是否被截断 */
  neighborhoodTruncated?: boolean;
  /** 整体是否被截断 */
  truncated?: boolean;
  /** 隐藏节点数 */
  hiddenNodeCount?: number;
  /** 隐藏边数 */
  hiddenEdgeCount?: number;
  /** 索引摘要 */
  indexed?: IndexedGraphSummary | null;
}

/**
 * 类图视图文档：含用法检索结果（可选）。
 */
export interface ClassDiagramViewDocument {
  /** 当前展示的子图 */
  visibleGraph: LinkGraphDocument;
  /** 全量未裁剪的图 */
  fullGraph: LinkGraphDocument;
  /** 锚点节点 ID */
  anchorNodeId?: string | null;
  /** 投影索引 */
  projectionIndex?: GraphProjectionIndex;
  /** 视图摘要 */
  summary: ClassDiagramSummary;
  /** 视图呈现描述 */
  presentation: GraphViewPresentation;
  /** 用法检索结果（如启用） */
  usage?: ClassUsageSearchResult | null;
}

/**
 * 复核图统计摘要：变更符号/上下游/相关测试/受影响包模块/证据引用等计数。
 */
export interface ReviewGraphSummary {
  /** 变更符号数 */
  changedSymbolCount: number;
  /** 上游节点数 */
  upstreamCount: number;
  /** 下游节点数 */
  downstreamCount: number;
  /** 相关测试数 */
  relatedTestCount: number;
  /** 受影响包数 */
  affectedPackageCount: number;
  /** 受影响模块数 */
  affectedModuleCount: number;
  /** 证据引用数 */
  evidenceRefCount: number;
  /** 是否截断 */
  truncated?: boolean;
  /** 隐藏节点数 */
  hiddenNodeCount?: number;
  /** 隐藏边数 */
  hiddenEdgeCount?: number;
  /** 选中的 Diff 项 ID 列表 */
  selectedDiffItemIds?: string[];
  /** 变更节点上限 */
  maxChangedNodes?: number;
  /** 上游节点上限 */
  maxUpstreamNodes?: number;
  /** 下游节点上限 */
  maxDownstreamNodes?: number;
  /** 相关测试节点上限 */
  maxRelatedTestNodes?: number;
  /** 索引摘要 */
  indexed?: IndexedGraphSummary | null;
}

/**
 * 复核图中变更文件的描述：旧/新路径、变更种类、hunk 数与相似度。
 */
export interface ReviewGraphChangedFile {
  /** 变更前路径 */
  oldPath?: string | null;
  /** 变更后路径 */
  newPath?: string | null;
  /** 变更种类（新增/修改/删除/重命名） */
  changeKind: string;
  /** hunk 数量 */
  hunkCount: number;
  /** 重命名相似度 */
  similarity?: number | null;
}

/**
 * 复核图中变更 hunk 的描述：行范围与匹配到的符号 ID。
 */
export interface ReviewGraphChangedHunk {
  /** 当前文件路径 */
  filePath: string;
  /** 变更前文件路径 */
  oldFilePath?: string | null;
  /** 变更后文件路径 */
  newFilePath?: string | null;
  /** 变更种类 */
  changeKind: string;
  /** hunk 头部描述 */
  header: string;
  /** 变更前起始行 */
  oldStartLine?: number | null;
  /** 变更前行数 */
  oldLineCount?: number | null;
  /** 变更后起始行 */
  newStartLine?: number | null;
  /** 变更后行数 */
  newLineCount?: number | null;
  /** 匹配到的符号 ID 列表 */
  matchedSymbolIds: string[];
}

/**
 * 变更符号的详细信息：位置、变更种类、爆炸半径是否完整等。
 */
export interface ReviewGraphChangedSymbolDetail {
  /** 符号 ID */
  symbolId: string;
  /** 全限定名 */
  qualifiedName: string;
  /** 文件路径 */
  filePath?: string | null;
  /** 起始行 */
  startLine?: number | null;
  /** 结束行 */
  endLine?: number | null;
  /** 变更种类 */
  changeKind: string;
  /** 爆炸半径是否完整（受限于上限时为 false） */
  blastRadiusIncomplete: boolean;
  /** 无法计算爆炸半径时的原因 */
  unavailableReason?: string | null;
}

/**
 * 相关测试的详细信息：测试符号、关联原因与位置。
 */
export interface ReviewGraphRelatedTestDetail {
  /** 测试符号 ID */
  symbolId: string;
  /** 全限定名 */
  qualifiedName: string;
  /** 关联原因（如"测试了被变更符号"） */
  reason: string;
  /** 文件路径 */
  filePath?: string | null;
  /** 起始行 */
  startLine?: number | null;
}

/**
 * 复核图证据片段：把与变更相关的源码片段封装为可展示的证据。
 */
export interface ReviewGraphEvidenceSnippet {
  /** 片段标题 */
  title: string;
  /** 片段种类（如"调用点"、"实现"等） */
  kind: string;
  /** 文件路径 */
  filePath?: string | null;
  /** 起始行 */
  startLine?: number | null;
  /** 结束行 */
  endLine?: number | null;
  /** 源码片段文本 */
  snippet?: string | null;
  /** 无法获取时的原因 */
  unavailableReason?: string | null;
}

/**
 * 复核图视图文档：除了图与摘要外，还包含变更文件/hunk/符号/测试等多维度证据。
 */
export interface ReviewGraphViewDocument {
  /** 当前展示的子图 */
  visibleGraph: LinkGraphDocument;
  /** 全量未裁剪的图 */
  fullGraph: LinkGraphDocument;
  /** 锚点节点 ID */
  anchorNodeId?: string | null;
  /** 投影索引 */
  projectionIndex?: GraphProjectionIndex;
  /** 视图摘要 */
  summary: ReviewGraphSummary;
  /** 变更文件列表 */
  changedFiles?: ReviewGraphChangedFile[];
  /** 变更 hunk 列表 */
  changedHunks?: ReviewGraphChangedHunk[];
  /** 未匹配到符号的 hunk 列表 */
  unmatchedHunks?: ReviewGraphChangedHunk[];
  /** 仅在基线中存在的符号列表（已删除） */
  baselineOnlySymbols?: ReviewGraphChangedSymbolDetail[];
  /** 相关测试列表 */
  relatedTests?: ReviewGraphRelatedTestDetail[];
  /** 受影响的包列表 */
  affectedPackages?: string[];
  /** 受影响的模块列表 */
  affectedModules?: string[];
  /** 证据片段列表 */
  evidenceSnippets?: ReviewGraphEvidenceSnippet[];
}

/**
 * 投影节点/边的映射种类：精确、合并别名、路径别名、只读索引、只读合成、只读溢出。
 * 影响元素是否可被编辑命令操作。
 */
export type GraphProjectionMappingKind =
  | "EXACT"
  | "MERGED_ALIAS"
  | "PATH_ALIAS"
  | "INDEXED_READONLY"
  | "SYNTHETIC_READONLY"
  | "OVERFLOW_READONLY";

/**
 * 图编辑命令种类：新增/更新/删除节点、删除子树、连接、删除边、在边上插入节点。
 */
export type GraphEditCommandKind =
  | "ADD_NODE"
  | "UPDATE_NODE"
  | "DELETE_NODE"
  | "DELETE_NODE_SUBTREE"
  | "CONNECT_NODES"
  | "DELETE_EDGE"
  | "INSERT_NODE_INTO_EDGE";

/**
 * 投影节点到规范节点之间的映射关系：声明此投影节点支持哪些编辑命令。
 */
export interface GraphProjectionNodeMapping {
  /** 投影节点 ID */
  projectedNodeId: string;
  /** 映射种类 */
  mappingKind: GraphProjectionMappingKind;
  /** 规范节点 ID 列表（一个投影可能合并多个规范节点） */
  canonicalNodeIds: string[];
  /** 此投影节点支持的编辑命令种类 */
  editableCommandKinds: GraphEditCommandKind[];
}

/**
 * 投影边到规范边的映射关系：包括路径节点和支持的编辑命令。
 */
export interface GraphProjectionEdgeMapping {
  /** 投影边 ID */
  projectedEdgeId: string;
  /** 映射种类 */
  mappingKind: GraphProjectionMappingKind;
  /** 规范边 ID 列表 */
  canonicalEdgeIds: string[];
  /** 路径上的节点 ID 列表（用于路径折叠） */
  canonicalPathNodeIds: string[];
  /** 此投影边支持的编辑命令种类 */
  editableCommandKinds: GraphEditCommandKind[];
}

/**
 * 投影索引：保存视图所有节点/边的映射关系，是视图与底层图之间的桥梁。
 */
export interface GraphProjectionIndex {
  /** 节点映射表 */
  nodeMappings: Record<string, GraphProjectionNodeMapping>;
  /** 边映射表 */
  edgeMappings: Record<string, GraphProjectionEdgeMapping>;
}

/**
 * 证据引用：指向具体节点或源文件位置的范围。
 */
export interface ResultEvidenceReference {
  /** 关联节点 ID */
  nodeId?: string | null;
  /** 源文件路径 */
  filePath?: string | null;
  /** 起始行 */
  startLine?: number | null;
  /** 结束行 */
  endLine?: number | null;
}

/**
 * 单条证据发现：声明、强度等级以及关联引用列表。
 */
export interface ResultEvidenceFinding {
  /** 发现 ID */
  id: string;
  /** 声明内容（自然语言） */
  claim: string;
  /** 证据强度 */
  evidenceLevel: ResultEvidenceLevel;
  /** 引用列表 */
  references: ResultEvidenceReference[];
}

/**
 * 问答 / 检查类请求的完整结果：回答、可选补丁、证据、候选变更、调查线索等。
 */
export interface GraphPatchResult {
  /** 结果来源（禁用/本地规则/远程） */
  source: LlmResultSource;
  /** 原始问题 */
  question: string;
  /** 用户请求的执行模式 */
  requestedMode?: QaMode | null;
  /** 实际生效的执行模式 */
  effectiveMode?: QaMode | null;
  /** 回答正文 */
  answer: string;
  /** 提示词预览文本 */
  promptPreview: string | null;
  /** 提示词预览的产物 ID（用于完整内容下载） */
  promptPreviewArtifactId?: string | null;
  /** 建议的图补丁 */
  patch?: GraphPatch | null;
  /** 证据列表 */
  findings: ResultEvidenceFinding[];
  /** 累积的候选变更列表 */
  candidateChanges: CandidateDraftChange[];
  /** 本轮新增的候选变更列表 */
  newCandidateChanges: CandidateDraftChange[];
  /** 关联的调查线索 */
  investigationThreads?: InvestigationThread[];
  /** 最近一轮的输出结果 */
  latestTurnOutcome?: InvestigationTurnOutcome | null;
  /** 最近若干轮的输出结果列表 */
  recentTurnOutcomes?: InvestigationTurnOutcome[];
  /** 关联的源码片段 */
  sourceContext?: SourceSnippetContext[];
  /** 证据追踪条目 */
  evidenceTrace?: EvidenceTraceEntry[];
  /** 关联的问答会话 */
  qaSession?: QaConversationSession | null;
  /** 警告信息列表 */
  warnings: string[];
}

/**
 * 图呈现上下文：当前图、全量图、锚点、选中节点与各类隐藏元素计数。
 */
export interface GraphPresentationContext {
  /** 当前展示的图 */
  graph: LinkGraphDocument;
  /** 全量未裁剪的图 */
  fullGraph?: LinkGraphDocument | null;
  /** 锚点节点 ID */
  anchorNodeId?: string | null;
  /** 当前选中的节点 ID 列表 */
  selectedNodeIds?: string[];
  /** 当前方法内被隐藏的节点数 */
  hiddenCurrentMethodNodeCount: number;
  /** 跨方法被隐藏的节点数 */
  hiddenCrossMethodNodeCount: number;
}

/**
 * 源码片段上下文：用于把源代码相关片段附加到提示词中作为参考。
 */
export interface SourceSnippetContext {
  /** 关联节点 ID */
  nodeId: string;
  /** 源文件路径 */
  filePath: string;
  /** 起始偏移量 */
  startOffset?: number | null;
  /** 结束偏移量 */
  endOffset?: number | null;
  /** 起始行 */
  startLine?: number | null;
  /** 结束行 */
  endLine?: number | null;
  /** 源码片段文本 */
  snippet?: string | null;
  /** 来源描述（如"反编译"、"源码"） */
  origin?: string | null;
  /** 是否来自反编译 */
  decompiled?: boolean | null;
  /** 关联的虚拟文件 URL */
  virtualFileUrl?: string | null;
}

/**
 * 证据追踪条目：说明某节点/文件被纳入提示词的原因与结果。
 */
export interface EvidenceTraceEntry {
  /** 节点 ID */
  nodeId: string;
  /** 解析后的实际节点 ID（可能经过投影映射） */
  resolvedNodeId?: string | null;
  /** 文件路径 */
  filePath: string;
  /** 纳入原因 */
  reason: string;
  /** 起始行 */
  startLine?: number | null;
  /** 结束行 */
  endLine?: number | null;
  /** 是否实际写入了提示词 */
  includedInPrompt: boolean;
  /** 投影解析过程的轨迹描述 */
  mappingTrace?: string[];
}

/**
 * 编辑作用域：限定一次编辑可以影响的代码区域以及允许的变更种类。
 */
export interface EditScope {
  /** 作用域 ID */
  scopeId: string;
  /** 目标节点 ID */
  targetNodeId: string;
  /** 目标文件路径 */
  filePath: string;
  /** 文件语言 */
  language: string;
  /** 符号种类（类/方法/字段等） */
  symbolKind: string;
  /** 符号签名（可空） */
  symbolSignature?: string | null;
  /** 起始偏移量 */
  startOffset?: number | null;
  /** 结束偏移量 */
  endOffset?: number | null;
  /** 起始行 */
  startLine?: number | null;
  /** 结束行 */
  endLine?: number | null;
  /** 允许的变更种类 */
  allowedChangeKinds: string[];
  /** 支撑证据 ID 列表 */
  supportingFindingIds: string[];
}

/**
 * 单条代码编辑操作：替换方法体、插入方法、添加 import、新增字段或创建文件等。
 */
export interface CodeEditOperation {
  /** 操作 ID */
  operationId: string;
  /** 目标文件路径 */
  filePath: string;
  /** 关联的作用域 ID */
  scopeId?: string | null;
  /** 操作种类 */
  kind: "REPLACE_METHOD_BODY" | "REPLACE_METHOD_BLOCK" | "INSERT_METHOD_AFTER" | "ADD_IMPORT" | "ADD_FIELD" | "CREATE_FILE";
  /** 操作负载数据（如新代码文本） */
  payload: string;
  /** 警告信息列表 */
  warnings: string[];
}

/**
 * 图美化请求的上下文：呈现上下文、源码片段、用户目标与可选的追问请求。
 */
export interface GraphBeautificationContext {
  /** 呈现上下文 */
  presentationContext: GraphPresentationContext;
  /** 相关源码片段 */
  sourceContext: SourceSnippetContext[];
  /** 用户输入的目标描述 */
  userGoal: string;
  /** 偏好的风格 */
  preferredStyle?: string | null;
  /** 解释聚焦点 */
  explanationFocus?: string | null;
  /** 追问请求（可选） */
  followUp?: GraphBeautificationFollowUpRequest | null;
}

/**
 * 图美化追问请求：针对某一步骤提出后续问题。
 */
export interface GraphBeautificationFollowUpRequest {
  /** 关联步骤 ID */
  stepId: string;
  /** 步骤标题 */
  stepTitle: string;
  /** 追问内容 */
  question: string;
}

/**
 * 图美化的请求参数：目标、风格、聚焦点、粒度与可选追问。
 */
export interface GraphBeautificationRequest {
  /** 目标描述 */
  goal?: string;
  /** 偏好风格 */
  preferredStyle?: string | null;
  /** 解释聚焦点 */
  explanationFocus?: string | null;
  /** 聚焦节点 ID */
  focusNodeId?: string | null;
  /** 步骤粒度 */
  granularity?: StepGranularity;
  /** 追问请求 */
  followUp?: GraphBeautificationFollowUpRequest | null;
}

/**
 * 单个美化步骤：标题、描述、证据、代码片段、追问与下游目标。
 */
export interface GraphBeautificationStep {
  /** 步骤 ID */
  stepId: string;
  /** 步骤标题 */
  title: string;
  /** 步骤粒度 */
  granularity: StepGranularity;
  /** 步骤语义类别 */
  kind: StepKind;
  /** 步骤描述 */
  description: string;
  /** 主要关联节点 ID */
  primaryNodeId?: string | null;
  /** 代码片段（可选） */
  codeSnippet?: string | null;
  /** 支撑证据 */
  evidence: ResultEvidenceFinding[];
  /** 可追问的问题 */
  followUpQuestions: string[];
  /** 该步骤影响的下游目标 */
  downstreamTargets: string[];
}

/**
 * 图美化的完整结果：包含步骤列表、提示词预览与警告。
 */
export interface GraphBeautificationResult {
  /** 结果来源 */
  source: LlmResultSource;
  /** 步骤粒度 */
  granularity: StepGranularity;
  /** 步骤列表 */
  steps: GraphBeautificationStep[];
  /** 提示词预览 */
  promptPreview: string | null;
  /** 提示词预览产物 ID */
  promptPreviewArtifactId?: string | null;
  /** 警告信息列表 */
  warnings: string[];
}

/**
 * 候选变更：LLM 提议的、尚未确认的图/代码变更，含证据与编辑作用域。
 */
export interface CandidateDraftChange {
  /** 变更 ID */
  changeId: string;
  /** 当前状态 */
  status: CandidateDraftChangeStatus;
  /** 标题 */
  title: string;
  /** 关联步骤 ID 列表 */
  targetStepIds: string[];
  /** 关联节点 ID 列表 */
  targetNodeIds: string[];
  /** 变更前状态描述 */
  beforeState?: string | null;
  /** 变更后状态描述 */
  afterState?: string | null;
  /** 变更原因 */
  reason: string;
  /** 影响摘要 */
  impactSummary: string;
  /** 声明种类 */
  claimType?: DraftClaimType | null;
  /** 支撑证据列表 */
  evidence?: ResultEvidenceFinding[];
  /** 编辑作用域列表 */
  editScopes?: EditScope[];
  /** 补丁意图（针对节点插入位置） */
  patchIntent?: CandidatePatchIntent | null;
  /** 图补丁 */
  graphPatch?: GraphPatch | null;
}

/** 候选补丁意图模式：更新现有节点、插入新决策、插入新动作、仅添加注解 */
export type CandidatePatchIntentMode =
  | "UPDATE_EXISTING_NODE"
  | "INSERT_NEW_DECISION"
  | "INSERT_NEW_ACTION"
  | "ANNOTATION_ONLY";

/**
 * 候选补丁意图：声明补丁应如何插入到图中。
 */
export interface CandidatePatchIntent {
  /** 模式 */
  mode: CandidatePatchIntentMode;
  /** 目标节点 ID */
  targetNodeId?: string | null;
  /** 附加到的边 ID */
  attachEdgeId?: string | null;
  /** false 分支的目标节点 ID（条件节点） */
  falseBranchTargetNodeId?: string | null;
}

/**
 * 单条问答消息：发言角色、内容以及关联的聚焦目标与轮次输出。
 */
export interface QaConversationMessage {
  /** 消息 ID */
  messageId: string;
  /** 发言角色 */
  role: QaMessageRole;
  /** 消息内容 */
  content: string;
  /** 关联的聚焦目标 ID */
  focusTargetId?: string | null;
  /** 触发的轮次输出 ID */
  turnOutcomeId?: string | null;
}

/**
 * 问答会话：按 scopeKey 维度聚合的消息、候选变更、调查线索与轮次输出。
 */
export interface QaConversationSession {
  /** 会话 ID */
  sessionId: string;
  /** 作用域键（按目标/范围区分会话） */
  scopeKey: string;
  /** 消息列表 */
  messages: QaConversationMessage[];
  /** 候选变更列表 */
  candidateChanges: CandidateDraftChange[];
  /** 关联的调查线索 */
  investigationThreads?: InvestigationThread[];
  /** 轮次输出列表 */
  turnOutcomes?: InvestigationTurnOutcome[];
  /** 当前会话的聚焦目标 */
  focusTargetId?: string | null;
}

/**
 * 一轮调查的证据变化：新增节点、新增文件、最强证据等级变化等。
 */
export interface InvestigationEvidenceDelta {
  /** 本轮新增的节点 ID */
  addedNodeIds: string[];
  /** 本轮新增的文件路径 */
  addedFilePaths: string[];
  /** 之前的最强证据等级 */
  previousStrongestEvidenceLevel?: ResultEvidenceLevel | null;
  /** 当前最强证据等级 */
  currentStrongestEvidenceLevel?: ResultEvidenceLevel | null;
  /** 是否命中了推荐问题 */
  hitRecommendedQuestion: boolean;
}

/**
 * 单轮调查输出：状态、摘要、证据变化与观察到的节点/文件。
 */
export interface InvestigationTurnOutcome {
  /** 输出 ID */
  outcomeId: string;
  /** 关联的调查线索 ID */
  threadId: string;
  /** 输出状态 */
  status: InvestigationTurnOutcomeStatus;
  /** 摘要 */
  summary: string;
  /** 详情 */
  detail: string;
  /** 关联的候选变更 ID（如已提升） */
  candidateChangeId?: string | null;
  /** 阻塞原因 */
  blockedReason?: string | null;
  /** 证据变化 */
  evidenceDelta: InvestigationEvidenceDelta;
  /** 本轮观察到的节点 ID 列表 */
  observedNodeIds: string[];
  /** 本轮观察到的文件路径 */
  observedFilePaths: string[];
  /** 当前最强证据等级 */
  strongestEvidenceLevel?: ResultEvidenceLevel | null;
}

/**
 * 调查线索：围绕一个未解决问题展开的多轮调查记录。
 */
export interface InvestigationThread {
  /** 线索 ID */
  threadId: string;
  /** 线索状态 */
  status: InvestigationThreadStatus;
  /** 标题 */
  title: string;
  /** 关联步骤 ID 列表 */
  targetStepIds: string[];
  /** 关联节点 ID 列表 */
  targetNodeIds: string[];
  /** 当前摘要 */
  summary: string;
  /** 证据缺口描述 */
  evidenceGap: string;
  /** 推荐下一个问题 */
  recommendedQuestion: string;
  /** 声明种类 */
  claimType?: DraftClaimType | null;
  /** 已收集的证据 */
  evidence: ResultEvidenceFinding[];
  /** 最近一次轮次输出 ID */
  latestTurnOutcomeId?: string | null;
  /** 处置结论 */
  resolution?: RiskResolution | null;
}

/**
 * 风险处置结论：关联的线索 ID、状态与备注。
 */
export interface RiskResolution {
  /** 线索 ID */
  threadId: string;
  /** 处置状态 */
  status: RiskResolutionStatus;
  /** 处置备注 */
  note: string;
}

/**
 * 可重放的问答请求：保留足够信息以便失败后恢复或重试。
 */
export interface ReplayableQaRequest {
  /** 请求 ID */
  requestId: string;
  /** 请求种类 */
  kind: QaRequestKind;
  /** 问题内容 */
  question: string;
  /** 请求模式 */
  mode?: QaMode;
  /** 选中节点列表 */
  selectedNodeIds: string[];
  /** 来源调查线索 ID */
  sourceThreadId?: string | null;
  /** 基础会话 ID（继承历史的会话） */
  baseSessionId?: string | null;
}

/**
 * 问答请求的恢复状态：记录最近一次提交和最近一次失败的请求。
 */
export interface QaRequestRecoveryState {
  /** 最近一次已提交请求 */
  lastSubmittedRequest?: ReplayableQaRequest | null;
  /** 最近一次失败请求 */
  lastFailedRequest?: ReplayableQaRequest | null;
}

/** 草稿校验状态：空、需要复核、就绪 */
export type DraftValidationStatus = "EMPTY" | "REVIEW_REQUIRED" | "READY";

/**
 * 草稿校验状态详情：包含未解决调查线索列表与展示信息。
 */
export interface DraftValidationState {
  /** 校验状态 */
  status: DraftValidationStatus;
  /** 顶层展示消息 */
  message: string;
  /** 详细消息 */
  detailMessage?: string | null;
  /** 未解决的线索 ID 列表 */
  unresolvedThreadIds: string[];
  /** 未解决的线索对象列表 */
  unresolvedThreads: InvestigationThread[];
}

/**
 * 阶段准入评估结果：是否允许进入下一阶段，以及阻塞原因。
 */
export interface StageEligibilityDecision {
  /** 评估目标 */
  target: StageEligibilityTarget;
  /** 阶段标签 */
  stageLabel: string;
  /** 是否允许进入 */
  allowed: boolean;
  /** 展示消息 */
  message: string;
  /** 详细消息 */
  detailMessage?: string | null;
  /** 阻塞线索 ID 列表 */
  blockingThreadIds: string[];
  /** 未解决线索 ID 列表 */
  unresolvedThreadIds: string[];
}

/**
 * 工作台中的草稿条目：可以是变更条目也可以是备注条目。
 */
export interface DraftWorkbenchEntry {
  /** 条目 ID */
  entryId: string;
  /** 条目种类 */
  kind: DraftEntryKind;
  /** 标题 */
  title: string;
  /** 关联的候选变更 ID */
  sourceChangeId?: string | null;
  /** 关联步骤 ID 列表 */
  targetStepIds: string[];
  /** 关联节点 ID 列表 */
  targetNodeIds: string[];
  /** 变更前状态描述 */
  beforeState?: string | null;
  /** 变更后状态描述 */
  afterState?: string | null;
  /** 原因描述 */
  reason: string;
  /** 影响摘要 */
  impactSummary: string;
  /** 声明种类 */
  claimType?: DraftClaimType | null;
  /** 支撑证据列表 */
  evidence: ResultEvidenceFinding[];
  /** 编辑作用域列表 */
  editScopes?: EditScope[];
  /** 补丁意图 */
  patchIntent?: CandidatePatchIntent | null;
  /** 图补丁 */
  graphPatch?: GraphPatch | null;
}

/**
 * 工作台草稿状态：变更条目与备注条目两组。
 */
export interface DraftWorkbenchState {
  /** 变更条目列表 */
  draftChanges: DraftWorkbenchEntry[];
  /** 备注条目列表 */
  draftNotes: DraftWorkbenchEntry[];
}

/**
 * 助手"解释"视图的状态：结果、请求状态、当前粒度与历史轨迹。
 */
export interface AssistantExplanationViewState {
  /** 当前结果 */
  result: GraphBeautificationResult | null;
  /** 请求状态 */
  requestState: AsyncRequestState;
  /** 当前选中步骤 ID */
  selectedStepId?: string | null;
  /** 当前粒度 */
  granularity: StepGranularity;
  /** 历史深度 */
  historyDepth: number;
  /** 是否可以回到上一步 */
  canReturnToPrevious: boolean;
  /** 历史轨迹的步骤 ID 列表 */
  historyTrail: string[];
  /** 当前会话标签 */
  currentSessionLabel?: string | null;
  /** 上一会话标签 */
  previousSessionLabel?: string | null;
}

/**
 * 助手"问答"视图的状态：结果、请求状态、选中条目与作用域标签。
 */
export interface AssistantQaViewState {
  /** 当前结果 */
  result: GraphPatchResult | null;
  /** 请求状态 */
  requestState: AsyncRequestState;
  /** 请求恢复状态 */
  qaRequestRecoveryState?: QaRequestRecoveryState | null;
  /** 选中的候选变更 ID */
  selectedChangeId?: string | null;
  /** 选中的调查线索 ID */
  selectedThreadId?: string | null;
  /** 作用域标签 */
  scopeLabel?: string | null;
}

/**
 * 助手执行某轮操作时的上下文快照：选中的节点/Diff 项、当前场景、作用域等。
 */
export interface AssistantContextSnapshot {
  /** 选中的节点 ID 列表 */
  selectedNodeIds: string[];
  /** 选中的 Diff 项 ID 列表 */
  selectedDiffItemIds: string[];
  /** 当前分析展示模式 */
  analysisDisplayMode?: string | null;
  /** 当前场景 ID */
  currentSceneId?: string | null;
  /** 选中的方法签名 */
  selectedMethodSignature?: string | null;
  /** 作用域标签 */
  scopeLabel: string;
}

/**
 * 助手轮次的引用信息：用于在历史列表中展示，实际结果存于结果存储中。
 */
export interface AssistantTurnRef {
  /** 轮次 ID */
  turnId: string;
  /** 轮次种类 */
  kind: AssistantTurnKind;
  /** 意图 */
  intent?: AssistantIntent | null;
  /** 触发的动作 ID */
  actionId?: AssistantActionId | null;
  /** 触发该轮次的消息类型 */
  sourceMessageType: string;
  /** 关联的结果 ID */
  resultId: string;
  /** 创建时间戳 */
  createdAtEpochMillis: number;
  /** 上下文快照 */
  context: AssistantContextSnapshot;
}

/**
 * 助手失败结果：包含错误消息、阶段与上下文。
 */
export interface AssistantFailureResult {
  /** 结果 ID */
  resultId: string;
  /** 失败消息 */
  message: string;
  /** 失败详情 */
  detailMessage?: string | null;
  /** 失败阶段 */
  phase: AsyncRequestPhase | string;
  /** 关联请求 ID */
  requestId?: number | null;
  /** 触发的消息类型 */
  sourceMessageType: string;
  /** 创建时间戳 */
  createdAtEpochMillis?: number | null;
}

/**
 * 助手输入框的目标：新任务、问答恢复、解释追问、方案讨论或风险调查。
 * 决定了输入框提交时的处理逻辑与附带上下文。
 */
export type AssistantComposerTarget =
  | { kind: "NewTask" }
  | {
      kind: "QaRecovery";
      requestId: string;
      selectedNodeIds?: string[];
      sourceThreadId?: string | null;
      mode?: QaMode | null;
    }
  | {
      kind: "ExplanationFollowUp";
      stepId: string;
      stepTitle?: string | null;
      focusNodeId?: string | null;
    }
  | {
      kind: "GenerationDiscussion";
      planItemId?: string | null;
    }
  | {
      kind: "RiskInvestigation";
      threadId: string;
      targetNodeIds?: string[];
    };

/**
 * 助手输入框的状态：草稿内容、目标、动作 ID、场景 ID 与问答模式。
 */
export interface AssistantComposerState {
  /** 草稿文本 */
  draft: string;
  /** 输入目标 */
  target: AssistantComposerTarget;
  /** 草稿来源：自动填充或用户输入 */
  draftSource?: "AUTO" | "USER" | null;
  /** 关联动作 ID */
  actionId?: AssistantActionId | null;
  /** 关联场景 ID */
  sceneId?: LinkGraphSceneId | null;
  /** 问答模式 */
  qaMode?: QaMode | null;
}

/**
 * 助手结果存储中的单条条目：按种类携带不同类型的结果对象。
 */
export interface AssistantResultStoreEntry {
  /** 轮次种类 */
  kind: AssistantTurnKind;
  /** 失败结果 */
  failure?: AssistantFailureResult | null;
  /** 问答结果 */
  qa?: GraphPatchResult | null;
  /** 解释结果 */
  explanation?: GraphBeautificationResult | null;
  /** 生成方案 */
  generationPlan?: GenerationPlan | null;
  /** 生成方案讨论会话 */
  generationDiscussionSession?: GenerationPlanDiscussionSession | null;
  /** 代码草稿列表 */
  codeDrafts?: GeneratedCodeDraft[];
  /** 代码草稿的警告信息 */
  codeDraftWarnings?: string[];
  /** 检查结果 */
  check?: GraphPatchResult | null;
}

/** 助手结果存储：以结果 ID 为键的映射表 */
export type AssistantResultStore = Record<string, AssistantResultStoreEntry>;

/**
 * 助手会话状态：会话 ID、活跃意图、上下文锁定状态、输入框与历史轮次。
 */
export interface AssistantSessionState {
  /** 会话 ID */
  sessionId: string;
  /** 当前活跃意图 */
  activeIntent: AssistantIntent;
  /** 当前活跃动作 ID */
  activeActionId?: AssistantActionId | null;
  /** 上下文是否锁定（防止用户改变选择） */
  contextLocked: boolean;
  /** 上下文快照 */
  context: AssistantContextSnapshot;
  /** 输入框状态 */
  composer?: AssistantComposerState | null;
  /** 下一个结果序号（用于生成 ID） */
  nextResultSequence?: number;
  /** 轮次引用列表 */
  turns: AssistantTurnRef[];
}

/**
 * 助手轮次的完整对象：包含引用信息以及对应的结果对象（用于详情展示）。
 */
export interface AssistantTurn {
  /** 轮次 ID */
  turnId: string;
  /** 轮次种类 */
  kind: AssistantTurnKind;
  /** 意图 */
  intent?: AssistantIntent | null;
  /** 动作 ID */
  actionId?: AssistantActionId | null;
  /** 创建时间戳 */
  createdAtEpochMillis: number;
  /** 上下文快照 */
  context: AssistantContextSnapshot;
  /** 失败结果 */
  failure?: AssistantFailureResult | null;
  /** 问答结果 */
  qa?: GraphPatchResult | null;
  /** 解释结果 */
  explanation?: GraphBeautificationResult | null;
  /** 生成方案 */
  generationPlan?: GenerationPlan | null;
  /** 生成方案讨论会话 */
  generationDiscussionSession?: GenerationPlanDiscussionSession | null;
  /** 代码草稿 */
  codeDrafts?: GeneratedCodeDraft[];
  /** 代码草稿警告 */
  codeDraftWarnings?: string[];
  /** 检查结果 */
  check?: GraphPatchResult | null;
}

/**
 * 草稿工作台视图状态：草稿数据、比对模式与当前选中条目。
 */
export interface DraftWorkbenchViewState {
  /** 草稿状态 */
  draftState: DraftWorkbenchState;
  /** 比对模式：仅展示变更后 / 同时比对前后 */
  compareMode: "after" | "compare";
  /** 选中条目 ID */
  selectedEntryId?: string | null;
}

/**
 * 方案讨论中的单条消息：发言角色、内容与聚焦条目。
 */
export interface GenerationPlanDiscussionMessage {
  /** 消息 ID */
  messageId: string;
  /** 发言角色 */
  role: QaMessageRole;
  /** 消息内容 */
  content: string;
  /** 关联的方案条目 ID */
  focusItemId?: string | null;
}

/**
 * 方案讨论会话：消息列表、聚焦条目、提示词预览等。
 */
export interface GenerationPlanDiscussionSession {
  /** 会话 ID */
  sessionId: string;
  /** 消息列表 */
  messages: GenerationPlanDiscussionMessage[];
  /** 当前聚焦的方案条目 */
  focusItemId?: string | null;
  /** 提示词预览 */
  promptPreview?: string | null;
  /** 提示词预览产物 ID */
  promptPreviewArtifactId?: string | null;
}

/**
 * 草稿实现建议的状态：缺失/运行中/新鲜/过期/失败，以及对应的条目列表。
 */
export interface DraftImplementationSuggestionState {
  /** 当前状态 */
  status: "MISSING" | "RUNNING" | "FRESH" | "STALE" | "FAILED";
  /** 摘要描述 */
  summary?: string | null;
  /** 建议条目列表 */
  items: GenerationPlanItem[];
  /** 来源 */
  source?: GenerationPlanSource | null;
  /** 警告列表 */
  warnings: string[];
  /** 提示词预览 */
  promptPreview?: string | null;
  /** 提示词预览产物 ID */
  promptPreviewArtifactId?: string | null;
  /** 对应的草稿版本 */
  draftVersion?: number | null;
  /** 关联的方案草稿版本 */
  generationPlanDraftVersion?: number | null;
}

/**
 * 草稿比对摘要：作用域节点数、可见节点/边数与隐藏元素计数。
 */
export interface DraftCompareSummary {
  /** 作用域节点总数 */
  scopeNodeCount: number;
  /** 可见节点数 */
  visibleNodeCount: number;
  /** 可见边数 */
  visibleEdgeCount: number;
  /** 隐藏节点数 */
  hiddenNodeCount: number;
  /** 隐藏边数 */
  hiddenEdgeCount: number;
}

/**
 * 草稿比对投影：单个工作台条目对应的比对图与逐元素状态。
 */
export interface DraftCompareProjection {
  /** 条目 ID */
  entryId: string;
  /** 条目标题 */
  entryTitle: string;
  /** 用于比对的图 */
  compareGraph: LinkGraphDocument;
  /** 每个节点的比对状态（新增/删除/修改等） */
  nodeStatuses: Record<string, DraftCompareStatus>;
  /** 每条边的比对状态 */
  edgeStatuses: Record<string, DraftCompareStatus>;
  /** 比对摘要 */
  summary: DraftCompareSummary;
}

/**
 * 同步预览条目：描述一个待用户确认的同步操作。
 */
export interface SyncPreviewItem {
  /** 条目 ID */
  id: string;
  /** 标题 */
  title: string;
  /** 描述 */
  description: string;
  /** 风险等级 */
  risk: "LOW" | "MEDIUM" | "HIGH";
}

/** 生成方案的来源：禁用 / 本地规则 / 远程 */
export type GenerationPlanSource = "DISABLED" | "LOCAL_RULE" | "REMOTE";

/**
 * 单条生成方案条目：标题、描述、风险等级与目标路径。
 */
export interface GenerationPlanItem {
  /** 条目 ID */
  id: string;
  /** 标题 */
  title: string;
  /** 描述 */
  description: string;
  /** 风险等级 */
  risk: "LOW" | "MEDIUM" | "HIGH";
  /** 目标文件路径 */
  targetPath?: string | null;
}

/**
 * 完整的生成方案：来源、摘要、警告、提示词预览与条目列表。
 */
export interface GenerationPlan {
  /** 来源 */
  source: GenerationPlanSource;
  /** 摘要 */
  summary: string;
  /** 警告列表 */
  warnings: string[];
  /** 提示词预览 */
  promptPreview: string | null;
  /** 提示词预览产物 ID */
  promptPreviewArtifactId?: string | null;
  /** 方案条目列表 */
  items: GenerationPlanItem[];
}

/**
 * 单个生成的代码草稿：包含内容、编辑操作、作用域与准备好的编辑。
 */
export interface GeneratedCodeDraft {
  /** 草稿 ID */
  id: string;
  /** 来源节点 ID */
  sourceNodeId: string;
  /** 标题 */
  title: string;
  /** 目标文件路径 */
  targetPath: string;
  /** 内容（完整文件或空，可选从产物加载） */
  content: string | null;
  /** 内容产物 ID（用于按需加载大文件） */
  contentArtifactId?: string | null;
  /** 编辑操作列表（基于编辑命令的描述） */
  editOperations?: CodeEditOperation[];
  /** 编辑作用域列表 */
  editScopes?: EditScope[];
  /** 准备好的编辑（已计算好具体偏移量） */
  preparedEdits?: PreparedCodeEdit[];
  /** 警告列表 */
  warnings: string[];
}

/**
 * 已准备好的代码编辑：包含具体的偏移量、前后文本，可直接应用。
 */
export interface PreparedCodeEdit {
  /** 操作 ID */
  operationId: string;
  /** 目标文件路径 */
  filePath: string;
  /** 关联作用域 ID */
  scopeId?: string | null;
  /** 编辑种类 */
  kind: CodeEditOperation["kind"];
  /** 目标符号签名 */
  targetSymbolSignature?: string | null;
  /** 起始偏移量 */
  startOffset: number;
  /** 结束偏移量 */
  endOffset: number;
  /** 替换前文本 */
  beforeText: string;
  /** 替换后文本 */
  afterText: string;
  /** 警告列表 */
  warnings: string[];
}

/**
 * 代码草稿写入报告：成功写入的文件、被跳过的文件与警告信息。
 */
export interface GeneratedCodeDraftWriteReport {
  /** 已写入的文件列表 */
  writtenFiles: string[];
  /** 被跳过的文件列表 */
  skippedFiles: string[];
  /** 警告列表 */
  warnings: string[];
}

/**
 * 草稿补丁应用结果：总结、应用的操作数、节点/边 ID 与聚焦节点。
 */
export interface DraftPatchApplyResult {
  /** 总结文案 */
  summary: string;
  /** 应用的操作数 */
  appliedOperationCount: number;
  /** 应用涉及的节点 ID 列表 */
  appliedNodeIds: string[];
  /** 应用涉及的边 ID 列表 */
  appliedEdgeIds: string[];
  /** 应用后的聚焦节点 ID */
  focusNodeId?: string | null;
  /** 应用涉及的目标列表 */
  appliedTargets: string[];
}

/**
 * 单个 Diff 条目：标题、状态、描述。
 */
export interface DiffItem {
  /** 条目 ID */
  id: string;
  /** 标题 */
  title: string;
  /** Diff 状态 */
  status: DiffStatus;
  /** 描述 */
  description: string;
}

/** Mermaid 问题种类：语法、结构、语义、绑定 */
export type MermaidIssueCategory = "SYNTAX" | "STRUCTURE" | "SEMANTIC" | "BINDING";

/**
 * Mermaid 解析或校验时发现的单个问题。
 */
export interface MermaidIssue {
  /** 问题种类 */
  category: MermaidIssueCategory;
  /** 问题代码 */
  code: string;
  /** 描述信息 */
  message: string;
  /** 源码行号 */
  line?: number | null;
  /** 关联节点 ID */
  nodeId?: string | null;
  /** 关联边 ID */
  edgeId?: string | null;
}

/** 操作反馈等级：信息、成功、警告、错误 */
export type OperationFeedbackLevel = "INFO" | "SUCCESS" | "WARNING" | "ERROR";

/**
 * 一次操作后给用户的反馈消息。
 */
export interface OperationFeedback {
  /** 反馈等级 */
  level: OperationFeedbackLevel;
  /** 反馈消息 */
  message: string;
}

/** 源码导航的执行阶段：空闲/进行中/成功/未找到/失败 */
export type SourceNavigationPhase = "IDLE" | "RUNNING" | "SUCCEEDED" | "NOT_FOUND" | "FAILED";

/** 源码导航结果：已打开 */
export type SourceNavigationResult = "OPENED";

/**
 * 源码导航状态：当前阶段、目标路径、行列与错误信息。
 */
export interface SourceNavigationState {
  /** 关联节点 ID */
  nodeId?: string | null;
  /** 当前阶段 */
  phase: SourceNavigationPhase;
  /** 结果 */
  result?: SourceNavigationResult | null;
  /** 目标文件路径 */
  targetPath?: string | null;
  /** 行号 */
  line?: number | null;
  /** 列号 */
  column?: number | null;
  /** 错误信息 */
  errorMessage?: string | null;
}

/** 异步请求阶段：空闲/进行中/成功/失败/超时 */
export type AsyncRequestPhase = "IDLE" | "RUNNING" | "SUCCEEDED" | "FAILED" | "TIMED_OUT";

/** 异步请求的执行模式：禁用 / 本地规则 / 远程就绪 / 远程降级 */
export type AsyncRequestExecutionMode = "DISABLED" | "LOCAL_RULE" | "REMOTE_READY" | "REMOTE_FALLBACK";

/**
 * 异步请求状态：阶段、请求 ID、执行模式、状态消息、错误信息、流式状态等。
 */
export interface AsyncRequestState {
  /** 当前阶段 */
  phase: AsyncRequestPhase;
  /** 请求 ID（用于关联前端响应） */
  requestId?: number | null;
  /** 关联场景标签 */
  scene?: string | null;
  /** 执行模式 */
  executionMode?: AsyncRequestExecutionMode | null;
  /** 状态展示消息 */
  statusMessage?: string | null;
  /** 错误消息 */
  errorMessage?: string | null;
  /** 错误详情 */
  detailMessage?: string | null;
  /** 开始时间戳 */
  startedAtEpochMillis?: number | null;
  /** 结束时间戳 */
  finishedAtEpochMillis?: number | null;
  /** 是否处于流式输出 */
  streaming?: boolean;
  /** 是否使用了降级 */
  fallbackUsed?: boolean;
  /** 流式阶段标识 */
  streamPhase?: string | null;
  /** 流式预览文本 */
  previewText?: string | null;
  /** 预览更新时间戳 */
  previewUpdatedAtEpochMillis?: number | null;
  /** 是否正在收尾结构化结果 */
  finalizingStructuredResult?: boolean;
  /** 服务方标签 */
  providerLabel?: string | null;
  /** 模型名称 */
  model?: string | null;
  /** 端点摘要 */
  endpointSummary?: string | null;
  /** 是否可查看提示词预览 */
  promptPreviewAvailable?: boolean;
  /** 用户请求的模式 */
  requestedMode?: QaMode | null;
  /** 实际生效的模式 */
  effectiveMode?: QaMode | null;
}

/**
 * 图表面板的实验性开关：仅渲染可见元素、拖拽屏蔽等。
 */
export interface GraphSurfaceExperimentFlags {
  /** 仅渲染可见元素（性能优化） */
  onlyRenderVisibleElements?: boolean;
  /** 拖拽屏蔽（避免误触发其他交互） */
  dragShielding?: boolean;
}

/**
 * 前端启动时由后端推送的初始状态快照：包含所有场景、各种视图与各类请求状态。
 * 该对象是工作台的"单一真相源"，UI 全部从这里派生。
 */
export interface LinkGraphBootstrapState {
  /** 当前分析展示模式 */
  analysisDisplayMode?: AnalysisDisplayMode | null;
  /** 当前场景 ID */
  currentSceneId: LinkGraphSceneId;
  /** 各场景的状态映射 */
  sceneStates: Record<LinkGraphSceneId, LinkGraphSceneState>;
  /** 工作台当前图 */
  workspaceGraph: LinkGraphDocument;
  /** 工作台基线图（未应用任何草稿前的状态） */
  workspaceBaseGraph: LinkGraphDocument;
  /** 语义事实图 */
  semanticFactGraph: LinkGraphDocument;
  /** 设计基线图（用于对比） */
  designBaselineGraph?: LinkGraphDocument | null;
  /** 事实图视图 */
  factGraphView?: FactGraphViewDocument | null;
  /** 流程图视图 */
  flowchartView?: FlowchartViewDocument | null;
  /** 资源关系视图 */
  resourceRelationView?: ResourceRelationViewDocument | null;
  /** 架构图视图 */
  architectureGraphView?: ArchitectureGraphViewDocument | null;
  /** 类图视图 */
  classDiagramView?: ClassDiagramViewDocument | null;
  /** 复核图视图 */
  reviewGraphView?: ReviewGraphViewDocument | null;
  /** 各索引视图的请求状态 */
  indexedGraphRequestStates?: IndexedGraphRequestStates | null;
  /** 语义图版本号 */
  semanticRevision?: number;
  /** 工作台图版本号 */
  workspaceRevision?: number;
  /** 快照版本号 */
  snapshotRevision?: number;
  /** 草稿补丁预览 */
  draftPatchPreview?: GraphPatch | null;
  /** 草稿工作台状态 */
  draftWorkbenchState?: DraftWorkbenchState | null;
  /** 是否可以撤销上一次草稿应用 */
  canUndoDraftPatchApply?: boolean;
  /** 上一次草稿应用的总结文案 */
  lastAppliedDraftPatchSummary?: string | null;
  /** 当前问答结果 */
  qaResult?: GraphPatchResult | null;
  /** 问答请求状态 */
  qaRequestState?: AsyncRequestState | null;
  /** 问答请求恢复状态 */
  qaRequestRecoveryState?: QaRequestRecoveryState | null;
  /** Diff 复核结果 */
  diffReviewResult?: GraphPatchResult | null;
  /** Diff 复核请求状态 */
  diffReviewRequestState?: AsyncRequestState | null;
  /** 图美化结果 */
  graphBeautificationResult?: GraphBeautificationResult | null;
  /** 图美化请求状态 */
  graphBeautificationRequestState?: AsyncRequestState | null;
  /** Mermaid 解析问题列表 */
  mermaidIssues: MermaidIssue[];
  /** Diff 条目列表 */
  diffItems: DiffItem[];
  /** 同步预览条目列表 */
  syncPreviewItems: SyncPreviewItem[];
  /** 草稿版本号 */
  draftVersion?: number;
  /** 当前生成方案 */
  generationPlan?: GenerationPlan | null;
  /** 生成方案草稿版本号 */
  generationPlanDraftVersion?: number | null;
  /** 生成方案请求状态 */
  generationPlanRequestState?: AsyncRequestState | null;
  /** 草稿校验状态 */
  draftValidationState?: DraftValidationState | null;
  /** 方案讨论会话 */
  generationPlanDiscussionSession?: GenerationPlanDiscussionSession | null;
  /** 方案讨论请求状态 */
  generationPlanDiscussionRequestState?: AsyncRequestState | null;
  /** 已生成的代码草稿列表 */
  generatedCodeDrafts?: GeneratedCodeDraft[];
  /** 代码草稿版本号 */
  generatedCodeDraftVersion?: number | null;
  /** 代码草稿警告 */
  generatedCodeDraftWarnings?: string[];
  /** 代码草稿来源 */
  generatedCodeDraftSource?: LlmResultSource | null;
  /** 代码草稿提示词预览 */
  generatedCodeDraftPromptPreview?: string | null;
  /** 代码草稿提示词预览产物 ID */
  generatedCodeDraftPromptPreviewArtifactId?: string | null;
  /** 代码草稿写入报告 */
  generatedCodeDraftWriteReport?: GeneratedCodeDraftWriteReport | null;
  /** 代码草稿请求状态 */
  codeDraftRequestState?: AsyncRequestState | null;
  /** 代码阶段准入决策 */
  codeEligibilityDecision?: StageEligibilityDecision | null;
  /** 上一次草稿补丁应用结果 */
  lastDraftPatchApplyResult?: DraftPatchApplyResult | null;
  /** 源码导航状态 */
  sourceNavigationState?: SourceNavigationState | null;
  /** 操作反馈消息 */
  operationFeedback?: OperationFeedback | null;
  /** 图表面板实验开关 */
  graphSurfaceExperiments?: GraphSurfaceExperimentFlags | null;
  /** 助手会话状态 */
  assistantSessionState?: AssistantSessionState | null;
  /** 助手结果存储 */
  assistantResultStore?: AssistantResultStore | null;
  /** 产物 ID 到内容的映射（用于按需加载大文本） */
  artifactContents?: Record<string, string>;
  /** 最近一次收到的消息类型 */
  lastMessageType?: string | null;
  /** 最近的图来源标签 */
  lastGraphSource?: string | null;
}
