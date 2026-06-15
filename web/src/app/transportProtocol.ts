import rawTransportContract from "../../../protocol/graph-editor-transport-contract.json";

const transportContract = rawTransportContract as {
  readonly schemaVersion: 1;
  readonly incrementalTransportTypes: readonly ["ARTIFACT_SLICE", "FEEDBACK_SLICE"];
};

export const LINK_GRAPH_TRANSPORT_SCHEMA_VERSION = transportContract.schemaVersion;

export const LINK_GRAPH_INCREMENTAL_TRANSPORT_TYPES = transportContract.incrementalTransportTypes;

export type LinkGraphIncrementalTransportType =
  (typeof LINK_GRAPH_INCREMENTAL_TRANSPORT_TYPES)[number];

export const LINK_GRAPH_ARTIFACT_SLICE_TRANSPORT_TYPE = LINK_GRAPH_INCREMENTAL_TRANSPORT_TYPES[0];
export const LINK_GRAPH_FEEDBACK_SLICE_TRANSPORT_TYPE = LINK_GRAPH_INCREMENTAL_TRANSPORT_TYPES[1];

export function isLinkGraphIncrementalTransportType(
  value: unknown,
): value is LinkGraphIncrementalTransportType {
  return typeof value === "string"
    && LINK_GRAPH_INCREMENTAL_TRANSPORT_TYPES.includes(value as LinkGraphIncrementalTransportType);
}
