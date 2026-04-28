import { describe, expect, it } from "vitest";
import type { LinkGraphEdge, LinkGraphNode } from "../../../../app/types";
import { layoutResourceRelationView } from "../../../../app/views/resource/resourceRelationLayout";

function methodNode(id: string, title: string): LinkGraphNode {
  return {
    id,
    type: "METHOD",
    title,
    inputs: [],
    outputs: [],
    certainty: "PROVEN",
    bindingStatus: "BOUND",
  };
}

describe("layoutResourceRelationView", () => {
  it("keeps resource lanes ordered from code to downstream resources", async () => {
    const nodes: LinkGraphNode[] = [
      {
        ...methodNode("method:anchor", "OrderService.submit"),
        metadata: { "resource.lane": "CODE" },
      },
      {
        ...methodNode("resource:http", "OrderApiClient"),
        type: "HTTP_ENDPOINT",
        metadata: { "resource.lane": "INTEGRATION" },
      },
      {
        ...methodNode("resource:sql", "order_mapper.xml#insertOrder"),
        type: "SQL",
        metadata: { "resource.lane": "DATA" },
      },
      {
        ...methodNode("resource:config", "order.timeout"),
        type: "CONFIG_ITEM",
        metadata: { "resource.lane": "CONFIG" },
      },
      {
        ...methodNode("resource:doc", "order-flow.md"),
        type: "DOC_PAGE",
        metadata: { "resource.lane": "DOC" },
      },
    ];
    const edges: LinkGraphEdge[] = [
      { id: "route-http", type: "ROUTES_TO", source: "method:anchor", target: "resource:http" },
      { id: "maps-sql", type: "MAPS_TO_SQL", source: "method:anchor", target: "resource:sql" },
      { id: "bind-config", type: "BINDS_CONFIG", source: "method:anchor", target: "resource:config" },
      { id: "link-doc", type: "LINKS_DOC", source: "method:anchor", target: "resource:doc" },
    ];

    const laidOut = await layoutResourceRelationView({
      graph: { nodes, edges },
      nodes,
      edges,
      anchorNodeId: "method:anchor",
      sizeSnapshot: new Map(),
      reason: "graph",
    });
    const index = new Map(laidOut.nodes.map((node) => [node.id, node]));

    expect(index.get("resource:http")?.position?.x).toBeGreaterThan(index.get("method:anchor")?.position?.x ?? 0);
    expect(index.get("resource:sql")?.position?.x).toBeGreaterThan(index.get("resource:http")?.position?.x ?? 0);
    expect(index.get("resource:config")?.position?.x).toBeGreaterThan(index.get("resource:sql")?.position?.x ?? 0);
    expect(index.get("resource:doc")?.position?.x).toBeGreaterThan(index.get("resource:config")?.position?.x ?? 0);
    expect(laidOut.edges.every((edge) => edge.route?.sections.length)).toBe(true);
  });
});
