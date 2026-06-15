import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import {
  LINK_GRAPH_INCREMENTAL_TRANSPORT_TYPES,
  LINK_GRAPH_TRANSPORT_SCHEMA_VERSION,
} from "../../app/transportProtocol";

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
const BOOTSTRAP_STATE_CONTROLLER_PATH = path.join(
  REPO_ROOT,
  "web/src/app/controllers/useBootstrapStateController.ts",
);
const FRONTEND_TYPES_PATH = path.join(REPO_ROOT, "web/src/app/types.ts");
const FRONTEND_TRANSPORT_TYPES_PATH = path.join(REPO_ROOT, "web/src/app/transportTypes.ts");
const FRONTEND_TRANSPORT_PROTOCOL_PATH = path.join(REPO_ROOT, "web/src/app/transportProtocol.ts");
const TRANSPORT_CONTRACT_PATH = path.join(REPO_ROOT, "protocol/graph-editor-transport-contract.json");

describe("bridge contract", () => {
  it("shares transport envelope slice types through one protocol contract", () => {
    const contract = JSON.parse(readFileSync(TRANSPORT_CONTRACT_PATH, "utf8")) as {
      schemaVersion: number;
      incrementalTransportTypes: string[];
    };

    expect(contract.schemaVersion).toBe(LINK_GRAPH_TRANSPORT_SCHEMA_VERSION);
    expect(contract.incrementalTransportTypes).toEqual([
      "ARTIFACT_SLICE",
      "FEEDBACK_SLICE",
    ]);
    expect(LINK_GRAPH_INCREMENTAL_TRANSPORT_TYPES).toEqual(contract.incrementalTransportTypes);
  });

  it("derives frontend transport protocol constants from the shared contract", () => {
    const transportProtocolSource = readFileSync(FRONTEND_TRANSPORT_PROTOCOL_PATH, "utf8");

    expect(transportProtocolSource).toContain("graph-editor-transport-contract.json");
    expect(transportProtocolSource)
      .toContain("LINK_GRAPH_INCREMENTAL_TRANSPORT_TYPES = transportContract.incrementalTransportTypes");
    expect(transportProtocolSource).not.toContain("LINK_GRAPH_TRANSPORT_SCHEMA_VERSION = 1");
    expect(transportProtocolSource)
      .not.toContain('LINK_GRAPH_ARTIFACT_SLICE_TRANSPORT_TYPE = "ARTIFACT_SLICE"');
    expect(transportProtocolSource)
      .not.toContain('LINK_GRAPH_FEEDBACK_SLICE_TRANSPORT_TYPE = "FEEDBACK_SLICE"');
  });

  it("keeps incremental transport decisions behind protocol constants", () => {
    const bootstrapStateControllerSource = readFileSync(BOOTSTRAP_STATE_CONTROLLER_PATH, "utf8");

    expect(bootstrapStateControllerSource).toContain("LINK_GRAPH_ARTIFACT_SLICE_TRANSPORT_TYPE");
    expect(bootstrapStateControllerSource).not.toContain('!== "ARTIFACT_SLICE"');
    expect(bootstrapStateControllerSource).not.toContain("!== 'ARTIFACT_SLICE'");
  });

  it("keeps transport envelope types out of the monolithic frontend type catalog", () => {
    const frontendTypesSource = readFileSync(FRONTEND_TYPES_PATH, "utf8");
    const transportTypesSource = readFileSync(FRONTEND_TRANSPORT_TYPES_PATH, "utf8");

    expect(frontendTypesSource).toContain('export type {');
    expect(frontendTypesSource).toContain('from "./transportTypes"');
    expect(frontendTypesSource).not.toContain("interface LinkGraphSnapshotEnvelope");
    expect(frontendTypesSource).not.toContain("interface LinkGraphArtifactSliceEnvelope");
    expect(transportTypesSource).toContain("interface LinkGraphSnapshotEnvelope");
    expect(transportTypesSource).toContain("type LinkGraphIncrementalTransportEnvelope");
  });

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

  it("does not let frontend application APIs depend on per-action bridge methods", () => {
    const apiSource = readFileSync(API_PATH, "utf8");

    [
      "importMermaid?:",
      "requestAssistantTask?:",
      "requestIndexedGraph?:",
      "applyGraphEditScript?:",
      "layoutChanged?:",
      "frontendReady?:",
      "snapshotAck?:",
      "requestSourceNavigation?:",
    ].forEach((methodSignature) => {
      expect(apiSource).not.toContain(methodSignature);
    });
    expect(apiSource).not.toContain("bridge[commandType");
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

  it("keeps explicit action convenience methods but routes assistant tasks through the unified entrypoint", () => {
    const bridgeRegistrarSource = readFileSync(BRIDGE_REGISTRAR_PATH, "utf8");

    [
      "requestAssistantTask",
      "requestIndexedGraph",
      "applyCodeDrafts",
      "applySingleCodeDraft",
      "openCodeDraftNativeDiff",
      "applyGraphEditScript",
      "layoutChanged",
      "frontendReady",
      "snapshotAck",
      "requestSourceNavigation",
    ].forEach((method) => {
      expect(bridgeRegistrarSource).toContain(`${method}:`);
      expect(bridgeRegistrarSource).toContain(`sendCommand("${method}"`);
    });
    expect(bridgeRegistrarSource).toContain('target: request && request.target ? request.target : { kind: "NewTask" }');
    [
      "requestQa",
      "requestDiffReview",
      "requestGraphBeautification",
      "requestGenerationPlan",
      "requestGenerationPlanDiscussion",
    ].forEach((method) => {
      expect(bridgeRegistrarSource).not.toContain(`${method}:`);
      expect(bridgeRegistrarSource).not.toContain(`sendCommand("${method}"`);
    });
  });
});
