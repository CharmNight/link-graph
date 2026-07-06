/**
 * 节点/边的"确定性"分级，表示数据来源的可信程度。
 * - PROVEN：来自代码静态分析、可被验证的事实；
 * - RULE_INFERRED：基于规则推断，通常正确但未直接验证；
 * - LLM_SUGGESTED：由大模型给出，需要人工确认。
 */
export type Certainty = "PROVEN" | "RULE_INFERRED" | "LLM_SUGGESTED";

/**
 * 节点与底层资源的"绑定状态"，描述设计图节点与实际代码之间的同步关系。
 * - BOUND：已与代码绑定；
 * - DESIGN_ONLY：仅存在于设计图，尚未生成/绑定代码；
 * - GENERATABLE：可被生成；
 * - PARTIALLY_SYNCED：部分同步（例如方法已绑定但字段未绑定）；
 * - CONFLICTED：与代码存在冲突，需要人工解决。
 */
export type BindingStatus =
  | "BOUND"
  | "DESIGN_ONLY"
  | "GENERATABLE"
  | "PARTIALLY_SYNCED"
  | "CONFLICTED";

/** 与代码/Mermaid 之间的差异状态，用于差异比对视图。 */
export type DiffStatus = "MATCHED" | "ONLY_IN_CODE" | "ONLY_IN_MERMAID" | "MODIFIED";
/** 草稿变更在差异比对中的分类：修改、新增、删除。 */
export type DraftCompareStatus = "MODIFIED" | "ADDED" | "REMOVED";
/** 节点/边的来源标签，用于追溯数据出处。 */
export type GraphSourceTag = "FACT" | "DESIGN_BASELINE" | "DRAFT_MANUAL" | "DRAFT_AI" | "UNCERTAIN_FACT";
/** 图补丁或差异描述中的作用对象类型：节点或边。 */
export type GraphDiffElementKind = "NODE" | "EDGE";
/** 图补丁支持的操作类型集合。 */
export type GraphPatchAction =
  | "ADD_NODE"
  | "UPDATE_NODE"
  | "DELETE_NODE"
  | "ADD_EDGE"
  | "UPDATE_EDGE"
  | "DELETE_EDGE"
  | "ADD_ANNOTATION"
  | "MARK_UNCERTAIN";

/** 图中所有支持的节点类型，覆盖方法、类、资源、流程元素等多种语义。 */
export type NodeType =
  | "METHOD"
  | "FLOW_SCOPE"
  | "FLOW_ACTION"
  | "TERMINAL"
  | "MERGE"
  | "CLASS"
  | "MODULE"
  | "PACKAGE"
  | "INTERFACE"
  | "ENUM"
  | "ANNOTATION"
  | "RECORD"
  | "OBJECT"
  | "EXTERNAL_CLASS"
  | "LIBRARY"
  | "SERVICE"
  | "COMPONENT"
  | "LAYER"
  | "RESOURCE"
  | "SQL"
  | "HTTP_ENDPOINT"
  | "FEIGN_CLIENT"
  | "DUBBO_SERVICE"
  | "MQ_TOPIC"
  | "MQ_CONSUMER"
  | "CONFIG_ITEM"
  | "XML_RESOURCE"
  | "DOC_PAGE"
  | "UNCERTAIN_LINK";

/** 图中所有支持的边类型，覆盖调用、包含、继承、注入、路由、映射等多种关系。 */
export type EdgeType =
  | "CALL"
  | "CONTAINS_FLOW"
  | "CONTROL_FLOW"
  | "IMPLEMENTS"
  | "EXTENDS"
  | "USES_TYPE"
  | "INJECT"
  | "ROUTES_TO"
  | "MAPS_TO_SQL"
  | "PUBLISHES_TO"
  | "CONSUMES_FROM"
  | "BINDS_CONFIG"
  | "LINKS_DOC"
  | "USES_PROXY"
  | "REFLECTS_TO"
  | "SPI_RESOLVES_TO"
  | "TESTS"
  | "GENERATES"
  | "CLASS_USAGE";

/** 二维坐标点。 */
export interface GraphPosition {
  x: number;
  y: number;
}

/** 聚焦某节点的请求；nonce 用于区分多次聚焦同一节点的不同动作。 */
export interface GraphFocusRequest {
  nodeId: string;
  nonce: number;
}

/** 边路由中的一段：起点、终点以及中间可选的转折点。 */
export interface LinkGraphEdgeRouteSection {
  /** 段的起点坐标。 */
  startPoint: GraphPosition;
  /** 段的终点坐标。 */
  endPoint: GraphPosition;
  /** 中间转折点，用于折线渲染。 */
  bendPoints?: GraphPosition[];
}

/** 完整的边路由：由若干段拼接组成。 */
export interface LinkGraphEdgeRoute {
  sections: LinkGraphEdgeRouteSection[];
}

/** 整张图的布局状态：节点 ID 到坐标的映射。 */
export interface LinkGraphLayoutState {
  positions: Record<string, GraphPosition>;
}

/** 调用展开上下文模式：默认按用户正在阅读的活动链过滤上下文。 */
export type InvocationExpansionContextMode = "ACTIVE_CHAIN";

/** 调用展开子状态快照，用于恢复父展开重新打开后的子展开状态。 */
export interface ChildInvocationExpansionState {
  activeExpansionId?: string | null;
  activeExpansionPath?: string[];
  collapsedExpansionIds?: string[];
  activeSiblingByParentContext?: Record<string, string>;
}

/** 流程图调用展开的 UI/session 状态；不写入语义图 metadata。 */
export interface InvocationExpansionSceneState {
  activeExpansionId?: string | null;
  activeExpansionPath: string[];
  collapsedExpansionIds: string[];
  activeSiblingByParentContext: Record<string, string>;
  blockPositions: Record<string, GraphPosition>;
  lastChildStateByExpansionId: Record<string, ChildInvocationExpansionState>;
  contextMode: InvocationExpansionContextMode;
}

/**
 * 工作台支持的场景标识。
 * - 各 WORKSPACE_* 表示工作台的不同分析视图（事实、流程、资源、架构、类图、审查）；
 * - DIFF 表示差异比对专用场景。
 */
export type LinkGraphSceneId =
  | "WORKSPACE_FACT"
  | "WORKSPACE_FLOWCHART"
  | "WORKSPACE_RESOURCE_RELATION"
  | "WORKSPACE_ARCHITECTURE_GRAPH"
  | "WORKSPACE_CLASS_DIAGRAM"
  | "WORKSPACE_REVIEW_GRAPH"
  | "DIFF";

/** 单个场景（视图）的本地状态：选中节点、锚点、布局与折叠节点等。 */
export interface LinkGraphSceneState {
  /** 当前选中节点 ID。 */
  selectedNodeId?: string | null;
  /** 视图锚点节点 ID（如类图主类）。 */
  anchorNodeId?: string | null;
  /** 该场景下所有节点的坐标。 */
  layoutState: LinkGraphLayoutState;
  /** 布局版本号，用于判断是否需要重新计算位置。 */
  layoutRevision: number;
  /** 已折叠的节点 ID 列表（这些节点的子节点不显示）。 */
  collapsedNodeIds: string[];
  /** 流程图调用展开的折叠/激活状态，独立于普通节点折叠。 */
  invocationExpansionState?: InvocationExpansionSceneState | null;
}

/** 图节点：图中任意一个可视化实体。 */
export interface LinkGraphNode {
  /** 节点唯一 ID。 */
  id: string;
  /** 节点类型，决定渲染样式与语义。 */
  type: NodeType;
  /** 节点展示标题。 */
  title: string;
  /** 节点对应的源码位置（文件路径+偏移等）。 */
  location?: string;
  /** 节点的签名（用于方法、类等可定位符号）。 */
  signature?: string;
  /** 输入参数（适用于方法节点）。 */
  inputs: string[];
  /** 输出参数（适用于方法节点）。 */
  outputs: string[];
  /** 文档说明文本。 */
  doc?: string;
  /** 该节点的确定性等级。 */
  certainty: Certainty;
  /** 与底层代码的绑定状态。 */
  bindingStatus: BindingStatus;
  /** 在差异比对中的状态。 */
  diffStatus?: DiffStatus;
  /** 节点坐标（与布局状态独立存储时使用）。 */
  position?: GraphPosition;
  /** 自由元数据映射，扩展字段。 */
  metadata?: Record<string, string>;
  /** 来源标签。 */
  sourceTag?: GraphSourceTag;
}

/** 图边：连接两个节点的有向关系。 */
export interface LinkGraphEdge {
  /** 边唯一 ID。 */
  id: string;
  /** 边类型，决定渲染样式与语义。 */
  type: EdgeType;
  /** 起点（源）节点 ID。 */
  source: string;
  /** 终点（目标）节点 ID。 */
  target: string;
  /** 起点上的连接柄（用于多端口节点）。 */
  sourceHandle?: string | null;
  /** 终点上的连接柄。 */
  targetHandle?: string | null;
  /** 边上的展示标签。 */
  label?: string;
  /** 边的路由信息（折线路径）。 */
  route?: LinkGraphEdgeRoute;
  /** 自由元数据映射。 */
  metadata?: Record<string, string>;
  /** 来源标签。 */
  sourceTag?: GraphSourceTag;
}

/** 图文档：一张完整的图，包含节点、边及可选的补丁与统计信息。 */
export interface LinkGraphDocument {
  /** 全部节点列表。 */
  nodes: LinkGraphNode[];
  /** 全部边列表。 */
  edges: LinkGraphEdge[];
  /** 关联的补丁（若该图是补丁应用后的结果）。 */
  patch?: GraphPatch | null;
  /** 后端给出的节点总数提示值（可能大于 nodes.length，表示被截断）。 */
  nodeCount?: number;
  /** 后端给出的边总数提示值。 */
  edgeCount?: number;
  /** 是否被截断（节点/边未全部加载）。 */
  truncated?: boolean;
}

/** 单条图补丁操作：对节点或边的原子变更。 */
export interface GraphPatchOperation {
  /** 操作唯一 ID，便于引用与撤销。 */
  id: string;
  /** 操作类型（增/改/删等）。 */
  action: GraphPatchAction;
  /** 作用对象类型：节点或边。 */
  elementKind: GraphDiffElementKind;
  /** 作用对象 ID。 */
  elementId: string;
  /** 操作展示标题。 */
  title?: string | null;
  /** 操作描述摘要。 */
  summary?: string | null;
  /** 节点对象（仅当涉及节点时）。 */
  node?: LinkGraphNode | null;
  /** 边对象（仅当涉及边时）。 */
  edge?: LinkGraphEdge | null;
  /** 自由元数据。 */
  metadata?: Record<string, string>;
}

/** 图补丁：一组操作的集合，附带节点/边的增删 ID 列表便于快速 diff。 */
export interface GraphPatch {
  /** 补丁摘要说明。 */
  summary?: string | null;
  /** 全部操作的有序列表。 */
  operations: GraphPatchOperation[];
  /** 被新增的节点 ID 列表。 */
  addedNodeIds: string[];
  /** 被移除的节点 ID 列表。 */
  removedNodeIds: string[];
  /** 被新增的边 ID 列表。 */
  addedEdgeIds: string[];
  /** 被移除的边 ID 列表。 */
  removedEdgeIds: string[];
}

/** 图编辑操作联合类型：表示一次原子编辑（节点/边的新增或删除）。 */
export type GraphEditOperation =
  | {
      type: "UPSERT_NODE";
      node: LinkGraphNode;
    }
  | {
      type: "REMOVE_NODE";
      nodeId: string;
    }
  | {
      type: "UPSERT_EDGE";
      edge: LinkGraphEdge;
    }
  | {
      type: "REMOVE_EDGE";
      edgeId: string;
    };

/** 图编辑请求来源：前端用户操作、AI 工具，或调试自动化。 */
export type GraphEditRequestSource = "FRONTEND" | "AI_TOOL" | "DEBUG_AUTOMATION";

/** 图编辑请求：把一组操作打包发送给后端应用。 */
export interface GraphEditRequest {
  /** 目标场景 ID。 */
  sceneId: LinkGraphSceneId;
  /** 基于的工作台版本号，用于乐观并发控制。 */
  baseWorkspaceRevision: number;
  /** 待应用的操作列表。 */
  operations: GraphEditOperation[];
  /** 请求来源。 */
  source: GraphEditRequestSource;
}
