import type { LinkGraphEdge } from "../../types";

export type ClassDiagramRelationRole = "hierarchy" | "realization" | "association" | "dependency" | "related";

export interface ClassDiagramRelationPresentation {
  role: ClassDiagramRelationRole;
  color: string;
  strokeWidth: number;
  strokeDasharray?: string;
  zIndex: number;
  opacity?: number;
}

export interface ClassDiagramRelationLegendItem {
  id: string;
  label: string;
  role: ClassDiagramRelationRole;
}

const TYPE_HIERARCHY_RELATIONS = new Set(["GENERALIZATION", "REALIZATION", "EXTENDS", "IMPLEMENTS"]);
const STRUCTURAL_ASSOCIATION_RELATIONS = new Set(["COMPOSITION", "AGGREGATION", "ASSOCIATION", "FIELD", "CONSTRUCTOR_PARAMETER"]);
const DEPENDENCY_RELATIONS = new Set(["DEPENDENCY", "USES_TYPE", "INJECTS", "METHOD_CALL", "METHOD_PARAMETER", "METHOD_RETURN", "THROWS", "LOCAL_TYPE", "CLASS_USAGE"]);

export const CLASS_DIAGRAM_RELATION_LEGEND_ITEMS: ClassDiagramRelationLegendItem[] = [
  { id: "generalization", label: "继承", role: "hierarchy" },
  { id: "realization", label: "实现", role: "realization" },
  { id: "association", label: "字段关联", role: "association" },
  { id: "dependency", label: "类型依赖", role: "dependency" },
];

export function classDiagramRelationKind(edge: LinkGraphEdge): string {
  return edge.metadata?.["classDiagram.relation.role"]
    ?? edge.metadata?.["uml.relation.kind"]
    ?? edge.metadata?.["jvm.relation.kind"]
    ?? edge.type;
}

export function classDiagramRelationLabel(edge: LinkGraphEdge): string {
  return edge.metadata?.["classDiagram.relation.label"]?.trim()
    || edge.metadata?.["uml.relation.label"]?.trim()
    || edge.label?.trim()
    || classDiagramRelationKind(edge);
}

export function classDiagramCompactRelationLabel(label: string): string {
  return localizeClassDiagramRelationLabel(label, { compact: true });
}

export function classDiagramRelationDetailText(label: string): string {
  return localizeClassDiagramRelationLabel(label, { compact: false });
}

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

function classDiagramAggregateSecondaryLabels(edge: LinkGraphEdge): string[] {
  return (edge.metadata?.["uml.relation.aggregate.secondaryLabels"] ?? "")
    .split(/[;\n]/)
    .map((label) => label.trim())
    .filter(Boolean);
}

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

export function isClassDiagramHierarchyRelationKind(kind: string): boolean {
  return TYPE_HIERARCHY_RELATIONS.has(kind);
}

export function isClassDiagramHierarchyRelation(edge: LinkGraphEdge): boolean {
  return isClassDiagramHierarchyRelationKind(classDiagramRelationKind(edge));
}

export function isClassDiagramDependencyRelationKind(kind: string): boolean {
  return DEPENDENCY_RELATIONS.has(kind);
}

export function isClassDiagramDependencyRelation(edge: LinkGraphEdge): boolean {
  return isClassDiagramDependencyRelationKind(classDiagramRelationKind(edge));
}

export function isClassDiagramStructuralAssociationRelationKind(kind: string): boolean {
  return STRUCTURAL_ASSOCIATION_RELATIONS.has(kind);
}

export function isClassDiagramStructuralAssociationRelation(edge: LinkGraphEdge): boolean {
  return isClassDiagramStructuralAssociationRelationKind(classDiagramRelationKind(edge));
}

export function isClassDiagramRoutedStructuralRelationKind(kind: string): boolean {
  return isClassDiagramDependencyRelationKind(kind) || isClassDiagramStructuralAssociationRelationKind(kind);
}

export function isClassDiagramRoutedStructuralRelation(edge: LinkGraphEdge): boolean {
  return isClassDiagramRoutedStructuralRelationKind(classDiagramRelationKind(edge));
}

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
