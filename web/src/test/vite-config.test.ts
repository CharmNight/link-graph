import { describe, expect, it } from "vitest";
import elkLayoutEngineText from "../app/reactflow/elkLayoutEngine.ts?raw";
import configText from "../../vite.config.ts?raw";

describe("vite config", () => {
  it("uses relative asset base for IDE embedded pages", () => {
    expect(configText).toContain('base: "./"');
  });

  it("collects frontend tests from the dedicated src/test tree", () => {
    expect(configText).toContain('include: ["src/test/**/*.test.{ts,tsx}"]');
  });

  it("splits heavy runtime libraries instead of suppressing chunk size warnings", () => {
    expect(configText).toContain("manualChunks");
    expect(configText).toContain("vendor-react");
    expect(configText).toContain("vendor-graph");
    expect(configText).not.toContain("vendor-runtime");
    expect(configText).not.toContain("vendor-elk");
    expect(configText).not.toContain("chunkSizeWarningLimit");
  });

  it("keeps the ELK worker as an external asset instead of inlining the minified worker into JavaScript chunks", () => {
    expect(elkLayoutEngineText).toContain("elk-worker.min.js?url");
    expect(elkLayoutEngineText).not.toContain("elk-worker.min.js?raw");
    expect(elkLayoutEngineText).not.toContain('import BundledElk from "elkjs/lib/elk.bundled.js"');
  });
});
