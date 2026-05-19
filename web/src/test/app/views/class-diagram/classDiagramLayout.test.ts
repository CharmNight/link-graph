import { describe, expect, it } from "vitest";
import type { LinkGraphEdge, LinkGraphNode } from "../../../../app/types";
import { layoutClassDiagramView } from "../../../../app/views/class-diagram/classDiagramLayout";

function classNode(id: string, title = id): LinkGraphNode {
  return {
    id,
    type: "CLASS",
    title,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
  };
}

function tallClassNode(id: string, title = id): LinkGraphNode {
  return {
    ...classNode(id, title),
    doc: "Coordinates a long-running application workflow and exposes the public API used by neighboring classes.",
    metadata: {
      "uml.field.items": [
        "provider: TaskProvider",
        "scheduler: TaskScheduler",
        "repository: TaskRepository",
        "clock: Clock",
        "events: EventBus",
      ].join("\n"),
      "uml.method.items": [
        "start(command: StartCommand): Result",
        "stop(id: TaskId): void",
        "status(id: TaskId): TaskStatus",
        "reschedule(id: TaskId, instant: Instant): void",
        "publish(event: TaskEvent): void",
      ].join("\n"),
      "uml.field.hiddenCount": "3",
      "uml.method.hiddenCount": "4",
    },
  };
}

function relation(
  id: string,
  source: string,
  target: string,
  kind: string,
): LinkGraphEdge {
  return {
    id,
    type: kind === "INJECTS" ? "INJECT" : kind === "EXTENDS" ? "EXTENDS" : "USES_TYPE",
    source,
    target,
    label: kind.toLowerCase(),
    metadata: {
      "jvm.relation.kind": kind,
    },
  };
}

describe("layoutClassDiagramView", () => {
  it("places the anchor between incoming and outgoing type neighborhoods", async () => {
    const nodes = [
      classNode("consumer", "Consumer"),
      classNode("anchor", "ApplicationFeedbackLevel"),
      classNode("dependency", "GeneratedCodeDraftsPresentation"),
      classNode("base", "BasePresentation"),
    ];
    const edges = [
      relation("consumer-anchor", "consumer", "anchor", "INJECTS"),
      relation("anchor-dependency", "anchor", "dependency", "USES_TYPE"),
      relation("anchor-base", "anchor", "base", "EXTENDS"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const byId = new Map(laidOut.nodes.map((node) => [node.id, node]));

    expect(byId.get("anchor")?.metadata?.["layout.direction"]).toBe("ANCHOR");
    expect(byId.get("consumer")?.metadata?.["layout.direction"]).toBe("INCOMING");
    expect(byId.get("dependency")?.metadata?.["layout.direction"]).toBe("OUTGOING");
    expect(byId.get("base")?.metadata?.["layout.direction"]).toBe("PARENT");
    expect(byId.get("consumer")?.position?.x).toBeLessThan(byId.get("anchor")?.position?.x ?? 0);
    expect(byId.get("dependency")?.position?.x).toBeGreaterThan(byId.get("anchor")?.position?.x ?? 0);
    expect(byId.get("base")?.position?.y).toBeLessThan(byId.get("anchor")?.position?.y ?? 0);
  });

  it("routes class diagram edges through semantic side ports instead of the node center", async () => {
    const nodes = [classNode("anchor"), classNode("dependency"), classNode("base")];
    const edges = [
      relation("anchor-dependency", "anchor", "dependency", "USES_TYPE"),
      relation("anchor-base", "anchor", "base", "EXTENDS"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const usesEdge = laidOut.edges.find((edge) => edge.id === "anchor-dependency");
    const extendsEdge = laidOut.edges.find((edge) => edge.id === "anchor-base");

    expect(usesEdge).toMatchObject({
      sourceHandle: "source-right",
      targetHandle: "target-left",
      metadata: expect.objectContaining({
        "layout.route": "class-diagram-lane",
      }),
    });
    expect(extendsEdge).toMatchObject({
      sourceHandle: "source-bottom",
      targetHandle: "target-top",
    });
    expect(usesEdge?.route?.sections[0]?.bendPoints?.length).toBe(2);
    expect(extendsEdge?.route?.sections[0]?.bendPoints?.length).toBe(2);
  });

  it("stacks tall UML cards without vertical overlap before DOM measurements arrive", async () => {
    const nodes = [
      classNode("anchor", "TaskRunner"),
      tallClassNode("consumer-a", "TaskConsumerA"),
      tallClassNode("consumer-b", "TaskConsumerB"),
      tallClassNode("consumer-c", "TaskConsumerC"),
    ];
    const edges = [
      relation("consumer-a-anchor", "consumer-a", "anchor", "INJECTS"),
      relation("consumer-b-anchor", "consumer-b", "anchor", "INJECTS"),
      relation("consumer-c-anchor", "consumer-c", "anchor", "INJECTS"),
    ];

    const laidOut = await layoutClassDiagramView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const incomingNodes = laidOut.nodes
      .filter((node) => node.metadata?.["layout.direction"] === "INCOMING")
      .sort((left, right) => (left.position?.y ?? 0) - (right.position?.y ?? 0));

    expect(incomingNodes).toHaveLength(3);
    for (let index = 1; index < incomingNodes.length; index += 1) {
      const previous = incomingNodes[index - 1];
      const current = incomingNodes[index];
      const previousBottom = (previous?.position?.y ?? 0) + Number(previous?.metadata?.["layout.estimatedHeight"] ?? 0);
      expect(current?.position?.y ?? 0).toBeGreaterThanOrEqual(previousBottom + 96);
    }
  });
});
