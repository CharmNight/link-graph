import type { LinkGraphEdge } from "../../types";

/** 类图关系的语义角色分类，对应 UML 的层级/实现/关联/依赖/其他。 */
export type ClassDiagramRelationRole = "hierarchy" | "realization" | "association" | "dependency" | "related";

/** 关系的展示属性，包括颜色、线宽、虚线模式、z 层级与不透明度，驱动前端边渲染样式。 */
export interface ClassDiagramRelationPresentation {
  role: ClassDiagramRelationRole;
  color: string;
  strokeWidth: number;
  strokeDasharray?: string;
  zIndex: number;
  opacity?: number;
}

/** 图例条目：用于在画布上展示关系种类与其对应的中文标签。 */
export interface ClassDiagramRelationLegendItem {
  id: string;
  label: string;
  role: ClassDiagramRelationRole;
}

// 类型层级关系（继承/实现/扩展/实现接口）的关系种类集合。
const TYPE_HIERARCHY_RELATIONS = new Set(["GENERALIZATION", "REALIZATION", "EXTENDS", "IMPLEMENTS"]);
// 结构性关联关系（组合/聚合/关联/字段/构造参数）的关系种类集合。
const STRUCTURAL_ASSOCIATION_RELATIONS = new Set(["COMPOSITION", "AGGREGATION", "ASSOCIATION", "FIELD", "CONSTRUCTOR_PARAMETER"]);
// 类型依赖关系（依赖/方法调用/参数/返回/抛出/局部变量/使用）的关系种类集合。
const DEPENDENCY_RELATIONS = new Set(["DEPENDENCY", "USES_TYPE", "INJECTS", "METHOD_CALL", "METHOD_PARAMETER", "METHOD_RETURN", "THROWS", "LOCAL_TYPE", "CLASS_USAGE"]);

/** 类图关系图例条目：用于在前端绘制关系种类图例。 */
export const CLASS_DIAGRAM_RELATION_LEGEND_ITEMS: ClassDiagramRelationLegendItem[] = [
  { id: "generalization", label: "继承", role: "hierarchy" },
  { id: "realization", label: "实现", role: "realization" },
  { id: "association", label: "字段关联", role: "association" },
  { id: "dependency", label: "类型依赖", role: "dependency" },
];

/** 读取边的种类标识，按 classDiagram / uml / jvm / 兜底 type 的优先级取值。 */
export function classDiagramRelationKind(edge: LinkGraphEdge): string {
  return edge.metadata?.["classDiagram.relation.role"]
    ?? edge.metadata?.["uml.relation.kind"]
    ?? edge.metadata?.["jvm.relation.kind"]
    ?? edge.type;
}

/** 读取边的展示标签，按多级元数据 + 边自身标签 + 种类兜底的顺序取首个非空值。 */
export function classDiagramRelationLabel(edge: LinkGraphEdge): string {
  return edge.metadata?.["classDiagram.relation.label"]?.trim()
    || edge.metadata?.["uml.relation.label"]?.trim()
    || edge.label?.trim()
    || classDiagramRelationKind(edge);
}

/** 紧凑模式下的关系标签：对前缀/全限定名做简化，便于在节点边显示。 */
export function classDiagramCompactRelationLabel(label: string): string {
  return localizeClassDiagramRelationLabel(label, { compact: true });
}

/** 详情模式下的关系标签：保留完整内容，适合在详情面板中展示。 */
export function classDiagramRelationDetailText(label: string): string {
  return localizeClassDiagramRelationLabel(label, { compact: false });
}

/**
 * 标签本地化核心逻辑：先做精确匹配翻译；再尝试解析"前缀+目标"形式
 * （如 "field Foo.bar"），翻译前缀并按紧凑模式简化目标方法全限定名。
 */
function localizeClassDiagramRelationLabel(label: string, { compact }: { compact: boolean }): string {
  const trimmed = label.trim();
  const exact = classDiagramRelationExactLabel(trimmed);
  if (exact) {
    return exact;
  }
  const prefixed = /^(extends|implements|field|ctor|call|param|local|return|throws)\s+(.+)$/.exec(trimmed);
  if (!prefixed) {
    return trimmed;
  }
  const [, prefix, rawTarget] = prefixed;
  const target = rawTarget.trim();
  const localizedPrefix = classDiagramRelationExactLabel(prefix) ?? prefix;
  if (!compact || (prefix !== "param" && prefix !== "local")) {
    return `${localizedPrefix} ${target}`;
  }
  const methodQualified = /^([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)$/.exec(target);
  if (methodQualified) {
    return `${localizedPrefix} ${methodQualified[2]}`;
  }
  return `${localizedPrefix} ${target}`;
}

/** 关系标签精确翻译表：将英文/UML 关系标识映射为面向用户的中文展示名。 */
function classDiagramRelationExactLabel(label: string): string | null {
  switch (label) {
    case "extends":
    case "EXTENDS":
    case "GENERALIZATION":
      return "继承";
    case "implements":
    case "IMPLEMENTS":
    case "REALIZATION":
      return "实现";
    case "field":
    case "FIELD":
      return "字段";
    case "ctor":
    case "CONSTRUCTOR_PARAMETER":
      return "构造参数";
    case "call":
    case "CALL":
    case "CALLS":
    case "METHOD_CALL":
      return "调用";
    case "param":
    case "METHOD_PARAMETER":
      return "参数";
    case "local":
    case "LOCAL_TYPE":
      return "局部类型";
    case "return":
    case "METHOD_RETURN":
      return "返回";
    case "throws":
    case "THROWS":
      return "抛出";
    case "composition":
    case "COMPOSITION":
      return "组合";
    case "aggregation":
    case "AGGREGATION":
      return "聚合";
    case "association":
    case "ASSOCIATION":
      return "关联";
    case "dependency":
    case "DEPENDENCY":
      return "依赖";
    case "usage":
    case "CLASS_USAGE":
      return "使用";
    case "USES_TYPE":
      return "类型依赖";
    case "INJECT":
    case "INJECTS":
      return "注入";
    default:
      return null;
  }
}

/**
 * 边在画布上展示给用户的最终标签：优先聚合主标签 + 隐藏计数提示，
 * 其次聚合标签，最后回退到常规关系标签的紧凑本地化版本。
 */
export function classDiagramRelationDisplayLabel(edge: LinkGraphEdge): string {
  const aggregatePrimaryLabel = edge.metadata?.["uml.relation.aggregate.primaryLabel"]?.trim();
  if (aggregatePrimaryLabel) {
    const hiddenCount = classDiagramAggregateSecondaryLabels(edge).length
      || Math.max(0, Number(edge.metadata?.["uml.relation.aggregate.count"] ?? "1") - 1);
    return hiddenCount > 0
      ? `${classDiagramCompactRelationLabel(aggregatePrimaryLabel)} +${hiddenCount}`
      : classDiagramCompactRelationLabel(aggregatePrimaryLabel);
  }
  const aggregateLabel = edge.metadata?.["uml.relation.aggregate.label"]?.trim();
  if (aggregateLabel) {
    return classDiagramCompactRelationLabel(aggregateLabel);
  }
  return classDiagramCompactRelationLabel(
    edge.metadata?.["classDiagram.relation.label"]?.trim()
      || classDiagramRelationLabel(edge),
  );
}

/** 详情模式下使用的多行标签：将聚合的主/次标签去重后逐行展示，否则使用常规详情标签。 */
export function classDiagramRelationDetailLabel(edge: LinkGraphEdge): string {
  const labels = [
    edge.metadata?.["uml.relation.aggregate.primaryLabel"]?.trim(),
    ...classDiagramAggregateSecondaryLabels(edge),
  ].filter((label): label is string => Boolean(label));
  if (labels.length > 0) {
    return Array.from(new Set(labels.map(classDiagramRelationDetailText))).join("\n");
  }
  return classDiagramRelationDetailText(classDiagramRelationLabel(edge));
}

/** 解析聚合关系元数据中的次级标签列表，按分号/换行切分并去空。 */
function classDiagramAggregateSecondaryLabels(edge: LinkGraphEdge): string[] {
  return (edge.metadata?.["uml.relation.aggregate.secondaryLabels"] ?? "")
    .split(/[;\n]/)
    .map((label) => label.trim())
    .filter(Boolean);
}

/**
 * 关系在布局排序中的权重（数字越小越靠前）。
 * 优先使用元数据中显式指定的 weight（转换为 1000 - weight 的内部排序值）；
 * 否则按关系种类分配固定权重：层级 > 关联 > 调用 > 参数 > 依赖 > 抛出 > 其他。
 */
export function classDiagramRelationSortRank(edge: LinkGraphEdge): number {
  const weight = Number(edge.metadata?.["classDiagram.relation.weight"]);
  if (Number.isFinite(weight)) {
    return 1000 - weight;
  }
  switch (classDiagramRelationKind(edge)) {
    case "GENERALIZATION":
    case "EXTENDS":
      return 0;
    case "REALIZATION":
    case "IMPLEMENTS":
      return 1;
    case "COMPOSITION":
    case "AGGREGATION":
    case "ASSOCIATION":
    case "FIELD":
      return 2;
    case "CONSTRUCTOR_PARAMETER":
      return edge.metadata?.["classDiagram.relation.assignedToField"] === "true" ? 3 : 6;
    case "METHOD_CALL":
    case "CLASS_USAGE":
      return 4;
    case "METHOD_RETURN":
    case "LOCAL_TYPE":
      return 5;
    case "METHOD_PARAMETER":
      return edge.metadata?.["classDiagram.relation.usedInBody"] === "true" ? 6 : 9;
    case "THROWS":
      return 8;
    case "DEPENDENCY":
    case "INJECTS":
    case "USES_TYPE":
      return 7;
    default:
      return 9;
  }
}

/**
 * 关系在渲染阶段的排序权重，用于决定叠层中谁先绘制。
 * 关联类关系最优先（0），方法调用其次（1），方法签名相关为 2，
 * 类型依赖与注入为 2，层级关系（继承/实现）压底（3），其他默认 4。
 */
export function classDiagramRenderedRelationRank(edge: LinkGraphEdge): number {
  switch (classDiagramRelationKind(edge)) {
    case "COMPOSITION":
    case "AGGREGATION":
    case "ASSOCIATION":
    case "FIELD":
    case "CONSTRUCTOR_PARAMETER":
      return 0;
    case "METHOD_CALL":
    case "CLASS_USAGE":
      return 1;
    case "METHOD_RETURN":
    case "METHOD_PARAMETER":
    case "THROWS":
    case "LOCAL_TYPE":
      return 2;
    case "DEPENDENCY":
    case "USES_TYPE":
    case "INJECTS":
      return 2;
    case "GENERALIZATION":
    case "REALIZATION":
    case "EXTENDS":
    case "IMPLEMENTS":
      return 3;
    default:
      return 4;
  }
}

/** 判断给定关系种类是否属于类型层级（继承/实现/扩展/实现接口）。 */
export function isClassDiagramHierarchyRelationKind(kind: string): boolean {
  return TYPE_HIERARCHY_RELATIONS.has(kind);
}

/** 判断边是否属于类型层级关系（封装 kind 版本）。 */
export function isClassDiagramHierarchyRelation(edge: LinkGraphEdge): boolean {
  return isClassDiagramHierarchyRelationKind(classDiagramRelationKind(edge));
}

/** 判断给定关系种类是否属于类型依赖关系。 */
export function isClassDiagramDependencyRelationKind(kind: string): boolean {
  return DEPENDENCY_RELATIONS.has(kind);
}

/** 判断边是否属于类型依赖关系（封装 kind 版本）。 */
export function isClassDiagramDependencyRelation(edge: LinkGraphEdge): boolean {
  return isClassDiagramDependencyRelationKind(classDiagramRelationKind(edge));
}

/** 判断给定关系种类是否属于结构性关联关系。 */
export function isClassDiagramStructuralAssociationRelationKind(kind: string): boolean {
  return STRUCTURAL_ASSOCIATION_RELATIONS.has(kind);
}

/** 判断边是否属于结构性关联关系（封装 kind 版本）。 */
export function isClassDiagramStructuralAssociationRelation(edge: LinkGraphEdge): boolean {
  return isClassDiagramStructuralAssociationRelationKind(classDiagramRelationKind(edge));
}

/** 判断给定关系种类是否属于需要走结构路由的关系（依赖 + 结构关联）。 */
export function isClassDiagramRoutedStructuralRelationKind(kind: string): boolean {
  return isClassDiagramDependencyRelationKind(kind) || isClassDiagramStructuralAssociationRelationKind(kind);
}

/** 判断边是否属于需要走结构路由的关系（封装 kind 版本）。 */
export function isClassDiagramRoutedStructuralRelation(edge: LinkGraphEdge): boolean {
  return isClassDiagramRoutedStructuralRelationKind(classDiagramRelationKind(edge));
}

/**
 * 根据关系种类返回前端展示样式：颜色、线宽、虚线、层级与不透明度。
 * 层级关系用金色实线/虚线，关联关系用绿色，依赖关系用蓝色（部分带虚线），
 * 其他关系使用中性灰色。Z 层级控制叠层覆盖关系。
 */
export function classDiagramRelationPresentation(edge: LinkGraphEdge): ClassDiagramRelationPresentation {
  switch (classDiagramRelationKind(edge)) {
    case "GENERALIZATION":
    case "EXTENDS":
      return { role: "hierarchy", color: "#b58c55", strokeWidth: 3, zIndex: 14 };
    case "REALIZATION":
    case "IMPLEMENTS":
      return { role: "realization", color: "#b58c55", strokeWidth: 3, strokeDasharray: "8 5", zIndex: 14 };
    case "COMPOSITION":
      return { role: "association", color: "#4f8f72", strokeWidth: 2.9, zIndex: 12 };
    case "AGGREGATION":
      return { role: "association", color: "#4f8f72", strokeWidth: 2.8, strokeDasharray: "10 4", zIndex: 12 };
    case "ASSOCIATION":
    case "FIELD":
    case "CONSTRUCTOR_PARAMETER":
    case "USES_TYPE":
      return { role: "association", color: "#4f8f72", strokeWidth: 2.8, zIndex: 12 };
    case "METHOD_CALL":
      return { role: "dependency", color: "#527f9e", strokeWidth: 2.6, zIndex: 10, opacity: 0.92 };
    case "CLASS_USAGE":
      return { role: "dependency", color: "#527f9e", strokeWidth: 2.5, strokeDasharray: "7 5", zIndex: 10, opacity: 0.9 };
    case "DEPENDENCY":
    case "INJECTS":
    case "METHOD_RETURN":
    case "METHOD_PARAMETER":
    case "THROWS":
    case "LOCAL_TYPE":
      return { role: "dependency", color: "#6f8fbc", strokeWidth: 2.4, strokeDasharray: "6 5", zIndex: 8, opacity: 0.88 };
    default:
      return { role: "related", color: "#61717f", strokeWidth: 2.4, zIndex: 9 };
  }
}
