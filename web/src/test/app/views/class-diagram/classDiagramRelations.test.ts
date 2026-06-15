import { describe, expect, it } from "vitest";
import type { LinkGraphEdge } from "../../../../app/types";
import {
  classDiagramCompactRelationLabel,
  classDiagramRelationPresentation,
  classDiagramRelationDetailLabel,
  classDiagramRelationDisplayLabel,
  classDiagramRelationKind,
  classDiagramRelationSortRank,
  isClassDiagramDependencyRelation,
} from "../../../../app/views/class-diagram/classDiagramRelations";

function edge(metadata: Record<string, string>): LinkGraphEdge {
  return {
    id: "edge:validator->config",
    type: "USES_TYPE",
    source: "class:Validator",
    target: "class:KafkaConfig",
    label: "dependency",
    metadata,
  };
}

describe("classDiagramRelations", () => {
  it("derives class diagram labels from backend relation metadata instead of UML guesses", () => {
    const relation = edge({
      "uml.relation.kind": "DEPENDENCY",
      "uml.relation.label": "dependency",
      "classDiagram.relation.role": "FIELD",
      "classDiagram.relation.label": "field config",
      "classDiagram.relation.weight": "90",
    });

    expect(classDiagramRelationKind(relation)).toBe("FIELD");
    expect(classDiagramRelationDisplayLabel(relation)).toBe("字段 config");
    expect(classDiagramRelationSortRank(relation)).toBeLessThan(
      classDiagramRelationSortRank(edge({
        "classDiagram.relation.role": "METHOD_PARAMETER",
        "classDiagram.relation.label": "param validate.request",
        "classDiagram.relation.weight": "30",
      })),
    );
  });

  it("keeps aggregate relation labels collapsed to the backend primary summary", () => {
    const relation = edge({
      "uml.relation.kind": "ASSOCIATION",
      "uml.relation.aggregate.label": "field image +4",
      "uml.relation.aggregate.primaryLabel": "field image",
      "uml.relation.aggregate.secondaryLabels": "ctor image;return apply;call apply;param update.image",
      "classDiagram.relation.role": "FIELD",
      "classDiagram.relation.label": "field image",
    });

    expect(classDiagramRelationDisplayLabel(relation)).toBe("字段 image +4");
    expect(classDiagramRelationDetailLabel(relation)).toBe([
      "字段 image",
      "构造参数 image",
      "返回 apply",
      "调用 apply",
      "参数 update.image",
    ].join("\n"));
  });

  it("shortens method-qualified labels for the canvas while keeping detail text intact", () => {
    const relation = edge({
      "uml.relation.kind": "DEPENDENCY",
      "classDiagram.relation.role": "METHOD_PARAMETER",
      "classDiagram.relation.label": "param onMetadataUpdate.delta",
      "classDiagram.relation.weight": "45",
    });

    expect(classDiagramRelationDisplayLabel(relation)).toBe("参数 delta");
    expect(classDiagramRelationDetailLabel(relation)).toBe("参数 onMetadataUpdate.delta");
    expect(classDiagramCompactRelationLabel("param onMetadataVersionChanged.metadataVersion")).toBe("参数 metadataVersion");
  });

  it("renders class usage overlay relations as dependency-style usage links", () => {
    const relation = edge({
      "classDiagram.relation.role": "CLASS_USAGE",
      "classDiagram.relation.label": "usage",
    });

    expect(classDiagramRelationKind(relation)).toBe("CLASS_USAGE");
    expect(classDiagramRelationDisplayLabel(relation)).toBe("使用");
    expect(isClassDiagramDependencyRelation(relation)).toBe(true);
    expect(classDiagramRelationPresentation(relation).role).toBe("dependency");
    expect(classDiagramRelationSortRank(relation)).toBeLessThan(
      classDiagramRelationSortRank(edge({
        "classDiagram.relation.role": "DEPENDENCY",
        "classDiagram.relation.label": "dependency",
      })),
    );
  });
});
