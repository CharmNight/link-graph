import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";

const REPO_ROOT = path.resolve(__dirname, "../../../..");
const API_PATH = path.join(REPO_ROOT, "web/src/app/api.ts");
const BRIDGE_REGISTRAR_PATH = path.join(
  REPO_ROOT,
  "src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserBridgeRegistrar.kt",
);
const PAYLOAD_PARSER_PATH = path.join(
  REPO_ROOT,
  "src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserPayloadParser.kt",
);

describe("bridge contract", () => {
  it("uses one JSON command envelope entrypoint in the browser runtime", () => {
    const apiSource = readFileSync(API_PATH, "utf8");
    const bridgeRegistrarSource = readFileSync(BRIDGE_REGISTRAR_PATH, "utf8");

    expect(apiSource).toContain("sendCommand?: (command: BridgeCommandEnvelope) => void");
    expect(apiSource).toContain("schemaVersion: 1");
    expect(apiSource).toContain("type: BridgeCommandType");
    expect(bridgeRegistrarSource).toContain("private val bridgeCommandQuery: JBCefJSQuery");
    expect(bridgeRegistrarSource).toContain("sendCommand: (command)");
    expect(bridgeRegistrarSource).toContain("schemaVersion: 1");
    expect(bridgeRegistrarSource).toContain("JSON.stringify(command || {})");
  });

  it("does not keep per-action JCEF query fields or delimiter payload parsing", () => {
    const bridgeRegistrarSource = readFileSync(BRIDGE_REGISTRAR_PATH, "utf8");
    const payloadParserSource = readFileSync(PAYLOAD_PARSER_PATH, "utf8");

    expect([...bridgeRegistrarSource.matchAll(/private val [A-Za-z0-9_]+Query: JBCefJSQuery/g)]
      .map((match) => match[0])
      .filter((line) => !line.includes("debugTraceQuery"))).toEqual([
      "private val bridgeCommandQuery: JBCefJSQuery",
    ]);
    expect(bridgeRegistrarSource).not.toContain("requestQaQuery");
    expect(bridgeRegistrarSource).not.toContain("requestIndexedGraphQuery");
    expect(payloadParserSource).not.toContain("PAYLOAD_SEPARATOR");
    expect(payloadParserSource).not.toContain("\\u001F");
    expect(payloadParserSource).not.toContain("URLDecoder");
  });

  it("keeps frontend convenience methods but routes them through sendCommand", () => {
    const bridgeRegistrarSource = readFileSync(BRIDGE_REGISTRAR_PATH, "utf8");

    [
      "requestQa",
      "requestIndexedGraph",
      "applyGraphEditScript",
      "layoutChanged",
      "frontendReady",
      "snapshotAck",
      "requestSourceNavigation",
    ].forEach((method) => {
      expect(bridgeRegistrarSource).toContain(`${method}:`);
      expect(bridgeRegistrarSource).toContain(`sendCommand("${method}"`);
    });
    expect(bridgeRegistrarSource).toContain(
      "requestGraphBeautification: (goal, preferredStyle, explanationFocus, granularity",
    );
    expect(bridgeRegistrarSource).toContain('sendCommand("requestGraphBeautification"');
  });
});
