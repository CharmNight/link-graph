import { afterEach, describe, expect, it, vi } from "vitest";
import { traceLinkGraph } from "./debug";

describe("traceLinkGraph", () => {
  const consoleLogSpy = vi.spyOn(console, "log").mockImplementation(() => undefined);

  afterEach(() => {
    window.__linkGraphTraceBuffer = [];
    window.linkGraphDebugTrace = undefined;
    consoleLogSpy.mockClear();
  });

  it("does not buffer traces by default when debug bridge is absent", () => {
    traceLinkGraph("graph.rendered", { nodes: 2 });

    expect(window.__linkGraphTraceBuffer ?? []).toHaveLength(0);
    expect(consoleLogSpy).not.toHaveBeenCalled();
  });

  it("sends traces directly to bridge when bridge is available", () => {
    const traceSink = vi.fn();
    window.linkGraphDebugTrace = traceSink;

    traceLinkGraph("graph.rendered", { nodes: 3 });

    expect(traceSink).toHaveBeenCalledTimes(1);
    expect(window.__linkGraphTraceBuffer ?? []).toHaveLength(0);
    expect(consoleLogSpy).not.toHaveBeenCalled();
  });
});
