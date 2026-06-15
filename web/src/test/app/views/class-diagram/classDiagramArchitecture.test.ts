import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

const REPO_ROOT = path.resolve(__dirname, "../../../../..");

function appPath(...segments: string[]): string {
  return path.join(REPO_ROOT, "src/app", ...segments);
}

function readAppSource(...segments: string[]): string {
  return readFileSync(appPath(...segments), "utf8");
}

describe("class diagram architecture", () => {
  it("keeps route crossing repair out of the primary routing engine", () => {
    const routingSource = readAppSource("views/class-diagram/classDiagramRouting.ts");
    const routeRepairPath = appPath("views/class-diagram/classDiagramRouteRepair.ts");
    const routeContextPath = appPath("views/class-diagram/classDiagramRouteContext.ts");

    expect(existsSync(routeRepairPath)).toBe(true);
    expect(existsSync(routeContextPath)).toBe(true);
    expect(routingSource.split("\n").length).toBeLessThan(1465);
    expect(routingSource).not.toContain("function crossingRepairCandidates(");
    expect(routingSource).not.toContain("function buildClassRouteContext(");
    expect(routingSource).toContain("repairEdgeRouteCrossings");
  });
});
