import { describe, expect, it } from "vitest";
import type { LinkGraphEdge } from "../../../../app/types";
import {
  classDiagramCompactRelationLabel,
  classDiagramRelationDetailLabel,
  classDiagramRelationDisplayLabel,
  classDiagramRelationKind,
  classDiagramRelationSortRank,
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
    expect(classDiagramRelationDisplayLabel(relation)).toBe("field config");
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

    expect(classDiagramRelationDisplayLabel(relation)).toBe("field image +4");
    expect(classDiagramRelationDetailLabel(relation)).toBe([
      "field image",
      "ctor image",
      "return apply",
      "call apply",
      "param update.image",
    ].join("\n"));
  });

  it("shortens method-qualified labels for the canvas while keeping detail text intact", () => {
    const relation = edge({
      "uml.relation.kind": "DEPENDENCY",
      "classDiagram.relation.role": "METHOD_PARAMETER",
      "classDiagram.relation.label": "param onMetadataUpdate.delta",
      "classDiagram.relation.weight": "45",
    });

    expect(classDiagramRelationDisplayLabel(relation)).toBe("param delta");
    expect(classDiagramRelationDetailLabel(relation)).toBe("param onMetadataUpdate.delta");
    expect(classDiagramCompactRelationLabel("param onMetadataVersionChanged.metadataVersion")).toBe("param metadataVersion");
  });
});
