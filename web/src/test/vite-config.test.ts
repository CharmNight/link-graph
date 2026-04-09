import { describe, expect, it } from "vitest";
import configText from "../../vite.config.ts?raw";

describe("vite config", () => {
  it("uses relative asset base for IDE embedded pages", () => {
    expect(configText).toContain('base: "./"');
  });
});
