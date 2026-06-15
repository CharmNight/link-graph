import type {
  AnalysisDisplayMode,
  AsyncRequestExecutionMode,
  BindingStatus,
  Certainty,
  DraftCompareStatus,
  DiffStatus,
  EdgeType,
  GenerationPlanSource,
  GraphPatchAction,
  GraphSourceTag,
  LlmResultSource,
  MermaidIssueCategory,
  NodeType,
  QaMode,
  ResultEvidenceLevel,
  ResultEvidenceReference,
  StepGranularity,
  StepKind,
} from "./types";

export function certaintyLabel(value: Certainty): string {
  switch (value) {
    case "PROVEN":
      return "已确认";
    case "RULE_INFERRED":
      return "规则推断";
    case "LLM_SUGGESTED":
      return "AI建议";
  }
}

export function relationConfidenceLabel(value?: string | null): string | null {
  switch (value) {
    case "PROVEN":
      return "静态确认";
    case "RULE_INFERRED":
      return "规则推断";
    case "AMBIGUOUS":
      return "多候选";
    case "RUNTIME_REQUIRED":
      return "需运行时确认";
    default:
      return value?.trim() ? value : null;
  }
}

export function bindingStatusLabel(value: BindingStatus): string {
  switch (value) {
    case "BOUND":
      return "已绑定";
    case "DESIGN_ONLY":
      return "仅设计";
    case "GENERATABLE":
      return "可生成";
    case "PARTIALLY_SYNCED":
      return "部分同步";
    case "CONFLICTED":
      return "存在冲突";
  }
}

export function diffStatusLabel(value: DiffStatus): string {
  switch (value) {
    case "MATCHED":
      return "一致";
    case "ONLY_IN_CODE":
      return "仅代码存在";
    case "ONLY_IN_MERMAID":
      return "仅 Mermaid 存在";
    case "MODIFIED":
      return "已修改";
  }
}

export function draftCompareStatusLabel(value: DraftCompareStatus): string {
  switch (value) {
    case "ADDED":
      return "草稿新增";
    case "REMOVED":
      return "草稿删除";
    case "MODIFIED":
    default:
      return "草稿修改";
  }
}

export function riskLabel(value: "LOW" | "MEDIUM" | "HIGH"): string {
  switch (value) {
    case "LOW":
      return "低";
    case "MEDIUM":
      return "中";
    case "HIGH":
      return "高";
  }
}

export function generationSourceLabel(value: GenerationPlanSource): string {
  switch (value) {
    case "DISABLED":
      return "已禁用";
    case "LOCAL_RULE":
      return "规则生成";
    case "REMOTE":
      return "远程 LLM";
  }
}

export function llmResultSourceLabel(value: LlmResultSource): string {
  switch (value) {
    case "DISABLED":
      return "已禁用";
    case "LOCAL_RULE":
      return "本地规则";
    case "REMOTE":
      return "远程 LLM";
  }
}

export function beautificationBoundaryDescription(value: LlmResultSource): string {
  switch (value) {
    case "DISABLED":
      return "当前没有启用讲解结果。";
    case "LOCAL_RULE":
      return "当前结果来自本地规则整理，只覆盖当前画布、图关系和已采集源码片段，不等于完整源码真值。";
    case "REMOTE":
      return "当前结果来自远程 LLM 讲解建议，它基于当前图摘要和源码片段生成，不等于源码真值判定。";
  }
}

export function patchResultBoundaryDescription(value: LlmResultSource): string {
  switch (value) {
    case "DISABLED":
      return "当前没有可用的分析结果。";
    case "LOCAL_RULE":
      return "当前回答属于本地规则分析，用于帮助你定位风险，不是完整源码真值判定。";
    case "REMOTE":
      return "当前回答属于远程 LLM 建议，可用于辅助问答和补图，但不是代码事实结论。";
  }
}

export function resultEvidenceLevelLabel(value: ResultEvidenceLevel): string {
  switch (value) {
    case "DIRECT_SOURCE":
      return "直接源码";
    case "DIRECT_GRAPH":
      return "直接图事实";
    case "CALLSITE_ONLY":
      return "仅调用点";
    case "NOT_OBSERVED":
      return "当前未观察到";
  }
}

export function stepGranularityLabel(value: StepGranularity): string {
  switch (value) {
    case "BUSINESS":
      return "业务级";
    case "METHOD_CALL":
      return "方法调用级";
    case "CODE_SEMANTIC":
      return "代码语义级";
  }
}

export function stepKindLabel(value: StepKind): string {
  switch (value) {
    case "BUSINESS_ACTION":
      return "业务动作";
    case "METHOD_CALL":
      return "方法调用";
    case "CONDITION":
      return "条件判断";
    case "RETURN":
      return "返回结果";
    case "RESOURCE_INTERACTION":
      return "资源交互";
    case "STRUCTURE_OVERVIEW":
      return "结构概览";
  }
}

export function formatResultEvidenceReference(reference: ResultEvidenceReference): string {
  const lineSuffix =
    reference.startLine == null
      ? ""
      : reference.endLine != null && reference.endLine !== reference.startLine
        ? `:${reference.startLine}-${reference.endLine}`
        : `:${reference.startLine}`;
  if (reference.filePath) {
    return `${reference.filePath}${lineSuffix}`;
  }
  if (reference.nodeId) {
    return `关联节点 ${reference.nodeId}`;
  }
  return "未提供引用位置";
}

export function asyncRequestExecutionModeLabel(value?: AsyncRequestExecutionMode | null): string {
  switch (value) {
    case "DISABLED":
      return "远程未启用";
    case "LOCAL_RULE":
      return "本地规则";
    case "REMOTE_READY":
      return "远程请求";
    case "REMOTE_FALLBACK":
      return "远程失败后回退";
    default:
      return "待确认";
  }
}

export function qaModeLabel(value?: QaMode | null): string {
  switch (value) {
    case "AUTO":
      return "Auto";
    case "ANSWER":
      return "只回答";
    case "REVIEW":
      return "风险复核";
    case "CHANGE":
      return "代码调整";
    case "INVESTIGATE":
      return "继续取证";
    default:
      return "待识别";
  }
}

export function patchPreviewBoundaryDescription(): string {
  return "这里展示的是建议写回当前工作图的草稿 patch，不是已经确认的代码事实。";
}

export function draftClaimTypeLabel(value?: string | null): string {
  switch (value) {
    case "CODE_FACT":
      return "代码事实";
    case "RISK_HINT":
      return "风险提示";
    case "EXPLANATION_NOTE":
      return "解释性注释";
    case "STRUCTURAL_SUGGESTION":
      return "结构建议";
    default:
      return "未标注";
  }
}

export function draftClaimTypeDescription(value?: string | null): string {
  switch (value) {
    case "CODE_FACT":
      return "表示这条草稿可以直接回到源码定位核对。";
    case "RISK_HINT":
      return "表示这条草稿是在提醒边界或异常风险，不是已发生事实。";
    case "EXPLANATION_NOTE":
      return "表示这条草稿主要用于帮助阅读链路，不是新增业务逻辑。";
    case "STRUCTURAL_SUGGESTION":
      return "表示这条草稿是在补图或补关系，属于结构性建议。";
    default:
      return "这条草稿还没有明确归类，应用前需要人工复核。";
  }
}

export function issueCategoryLabel(value: MermaidIssueCategory): string {
  switch (value) {
    case "SYNTAX":
      return "语法";
    case "STRUCTURE":
      return "结构";
    case "SEMANTIC":
      return "语义";
    case "BINDING":
      return "绑定";
  }
}

export function nodeTypeLabel(value: NodeType): string {
  switch (value) {
    case "METHOD":
      return "方法";
    case "FLOW_SCOPE":
      return "流程作用域";
    case "FLOW_ACTION":
      return "当前方法内部动作";
    case "TERMINAL":
      return "流程终止";
    case "MERGE":
      return "流程汇合";
    case "CLASS":
      return "类";
    case "MODULE":
      return "模块";
    case "PACKAGE":
      return "包";
    case "INTERFACE":
      return "接口";
    case "ENUM":
      return "枚举";
    case "ANNOTATION":
      return "注解";
    case "RECORD":
      return "Record";
    case "OBJECT":
      return "Object";
    case "EXTERNAL_CLASS":
      return "外部类";
    case "LIBRARY":
      return "依赖库";
    case "SERVICE":
      return "服务";
    case "COMPONENT":
      return "组件";
    case "LAYER":
      return "架构层";
    case "RESOURCE":
      return "资源";
    case "SQL":
      return "SQL";
    case "HTTP_ENDPOINT":
      return "HTTP接口";
    case "FEIGN_CLIENT":
      return "Feign客户端";
    case "DUBBO_SERVICE":
      return "Dubbo服务";
    case "MQ_TOPIC":
      return "MQ主题";
    case "MQ_CONSUMER":
      return "MQ消费者";
    case "CONFIG_ITEM":
      return "配置项";
    case "XML_RESOURCE":
      return "XML资源";
    case "DOC_PAGE":
      return "文档页";
    case "UNCERTAIN_LINK":
      return "不确定链路";
  }
}

export function edgeTypeLabel(value: EdgeType): string {
  switch (value) {
    case "CALL":
      return "调用";
    case "CONTAINS_FLOW":
      return "包含流程";
    case "CONTROL_FLOW":
      return "控制流";
    case "IMPLEMENTS":
      return "实现";
    case "EXTENDS":
      return "继承";
    case "USES_TYPE":
      return "类型依赖";
    case "INJECT":
      return "注入";
    case "ROUTES_TO":
      return "路由";
    case "MAPS_TO_SQL":
      return "映射到 SQL";
    case "PUBLISHES_TO":
      return "发布";
    case "CONSUMES_FROM":
      return "消费";
    case "BINDS_CONFIG":
      return "绑定配置";
    case "LINKS_DOC":
      return "链接文档";
    case "USES_PROXY":
      return "使用代理";
    case "REFLECTS_TO":
      return "反射到";
    case "SPI_RESOLVES_TO":
      return "SPI 解析到";
    case "TESTS":
      return "测试";
    case "GENERATES":
      return "生成";
  }
}

export function analysisDisplayModeLabel(value: AnalysisDisplayMode): string {
  switch (value) {
    case "FACT_GRAPH":
      return "事实链路";
    case "FLOWCHART":
      return "流程图";
    case "RESOURCE_RELATION_VIEW":
      return "资源关系";
    case "ARCHITECTURE_GRAPH":
      return "项目结构";
    case "CLASS_DIAGRAM":
      return "类图";
    case "REVIEW_GRAPH":
      return "Review Graph";
  }
}

export function sourceTagLabel(value: GraphSourceTag): string {
  switch (value) {
    case "FACT":
      return "事实层";
    case "DESIGN_BASELINE":
      return "设计基线";
    case "DRAFT_MANUAL":
      return "人工草稿";
    case "DRAFT_AI":
      return "AI草稿";
    case "UNCERTAIN_FACT":
      return "待确认事实";
  }
}

export function graphPatchActionLabel(value: GraphPatchAction): string {
  switch (value) {
    case "ADD_NODE":
      return "新增节点";
    case "UPDATE_NODE":
      return "更新节点";
    case "DELETE_NODE":
      return "删除节点";
    case "ADD_EDGE":
      return "新增连线";
    case "UPDATE_EDGE":
      return "更新连线";
    case "DELETE_EDGE":
      return "删除连线";
    case "ADD_ANNOTATION":
      return "新增注释";
    case "MARK_UNCERTAIN":
      return "标记待确认";
  }
}

export function draftStatusLabel(value: "READY" | "WRITTEN" | "SKIPPED"): string {
  switch (value) {
    case "READY":
      return "待写入";
    case "WRITTEN":
      return "已写入";
    case "SKIPPED":
      return "已跳过";
  }
}

const WORKBENCH_WORDING_REPLACEMENTS: Array<[from: string, to: string]> = [
  ["生成实现计划", "生成实现建议"],
  ["实现计划已生成", "实现建议已生成"],
  ["实现计划生成", "实现建议生成"],
  ["实现计划失败", "实现建议失败"],
  ["计划阶段", "实现建议阶段"],
  ["当前计划", "当前实现建议"],
  ["计划内容", "实现建议"],
  ["生成计划", "生成实现建议"],
  ["实现计划", "实现建议"],
  ["生成代码草稿", "生成代码 diff"],
  ["代码草稿已生成", "代码 diff 已生成"],
  ["代码草稿生成", "代码 diff 生成"],
  ["代码草稿失败", "代码 diff 失败"],
  ["写入代码草稿", "写入代码 diff"],
  ["代码草稿", "代码 diff"],
  ["生成草稿", "生成代码 diff"],
];

export function normalizeWorkbenchWording(text: string): string {
  let nextText = text;
  WORKBENCH_WORDING_REPLACEMENTS.forEach(([from, to]) => {
    nextText = nextText.replaceAll(from, to);
  });
  return nextText;
}

export function normalizeOptionalWorkbenchWording(text?: string | null): string | null {
  const nextText = text?.trim();
  if (!nextText) {
    return null;
  }
  return normalizeWorkbenchWording(nextText);
}

export function candidateChangeStatusLabel(value: "PENDING_CONFIRMATION" | "CONFIRMED" | "REJECTED" | "SUPERSEDED"): string {
  switch (value) {
    case "PENDING_CONFIRMATION":
      return "待确认";
    case "CONFIRMED":
      return "已确认";
    case "REJECTED":
      return "已拒绝";
    case "SUPERSEDED":
      return "已替代";
  }
}

export function investigationThreadStatusLabel(value: "OPEN" | "PROMOTED" | "DISMISSED" | "BLOCKED" | "SUPERSEDED"): string {
  switch (value) {
    case "OPEN":
      return "进行中";
    case "PROMOTED":
      return "已升级";
    case "DISMISSED":
      return "已排除";
    case "BLOCKED":
      return "已阻塞";
    case "SUPERSEDED":
      return "已替代";
  }
}

export function investigationTurnOutcomeStatusLabel(
  value: "PROMOTED_TO_CANDIDATE" | "OPEN_WITH_PROGRESS" | "OPEN_NO_PROGRESS" | "DISMISSED" | "BLOCKED",
): string {
  switch (value) {
    case "PROMOTED_TO_CANDIDATE":
      return "已升级";
    case "OPEN_WITH_PROGRESS":
      return "有推进";
    case "OPEN_NO_PROGRESS":
      return "无推进";
    case "DISMISSED":
      return "已排除";
    case "BLOCKED":
      return "取证受阻";
  }
}

export function riskResolutionStatusLabel(
  value?: "UNRESOLVED" | "DEFERRED" | "ACCEPTED_RISK" | "EVIDENCE_EXHAUSTED" | "DISMISSED" | "PROMOTED" | null,
): string {
  switch (value) {
    case "UNRESOLVED":
      return "未决";
    case "DEFERRED":
      return "暂挂";
    case "ACCEPTED_RISK":
      return "接受风险";
    case "EVIDENCE_EXHAUSTED":
      return "证据穷尽";
    case "DISMISSED":
      return "已排除";
    case "PROMOTED":
      return "已升级";
    default:
      return "未决";
  }
}
