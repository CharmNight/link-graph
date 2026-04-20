import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

function appPath(...segments: string[]) {
  return resolve(__dirname, "../../app", ...segments);
}

describe("three-view architecture gate", () => {
  it("removes the legacy GraphCanvas entrypoint and keeps only the rebuilt view modules", () => {
    expect(existsSync(appPath("components", "GraphCanvas.tsx"))).toBe(false);
    expect(existsSync(appPath("components", "GraphCanvas.test.tsx"))).toBe(false);
    expect(existsSync(appPath("views", "fact", "factGraphNodes.tsx"))).toBe(true);
    expect(existsSync(appPath("views", "flowchart", "flowchartNodes.tsx"))).toBe(true);
    expect(existsSync(appPath("views", "resource", "resourceRelationNodes.tsx"))).toBe(true);
  });

  it("keeps App on the new single-path stage assembly without GraphCanvas imports or fact-view graph overrides", () => {
    const appSource = readFileSync(appPath("App.tsx"), "utf8");
    const graphFlowSurfaceSource = readFileSync(appPath("reactflow", "GraphFlowSurface.tsx"), "utf8");
    const editorTransportSource = readFileSync(appPath("editorTransport.ts"), "utf8");

    expect(appSource).not.toContain("from \"./components/GraphCanvas\"");
    expect(appSource).not.toContain("visibleGraph: {\n              ...factGraphView.visibleGraph,\n              nodes,\n              edges,");
    expect(appSource).not.toContain("state.factGraphView?.visibleGraph ?? state.visibleGraph");
    expect(appSource).not.toContain("state.factGraphView?.fullGraph ?? state.referenceFactGraph ?? visibleGraph");
    expect(appSource).not.toContain("state.flowchartView?.visibleGraph ?? state.visibleGraph");
    expect(appSource).not.toContain("state.resourceRelationView?.visibleGraph ?? state.visibleGraph");
    expect(graphFlowSurfaceSource).not.toContain("graph-canvas-fallback-board");
    expect(editorTransportSource).not.toContain("isLegacyBootstrapState");
    expect(editorTransportSource).not.toContain("\"legacy-bootstrap\"");
  });
});
