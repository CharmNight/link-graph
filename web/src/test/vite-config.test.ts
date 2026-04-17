import { describe, expect, it } from "vitest";
import configText from "../../vite.config.ts?raw";

describe("vite config", () => {
  it("uses relative asset base for IDE embedded pages", () => {
    expect(configText).toContain('base: "./"');
  });

  it("collects frontend tests from the dedicated src/test tree", () => {
    expect(configText).toContain('include: ["src/test/**/*.test.{ts,tsx}"]');
  });
});
