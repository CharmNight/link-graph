import type { LinkGraphIncrementalTransportType } from "./transportProtocol";
import type { LinkGraphBootstrapState } from "./types";

export interface LinkGraphSnapshotEnvelope {
  sessionId: string;
  revision: number;
  state: LinkGraphBootstrapState;
  transportType?: LinkGraphIncrementalTransportType;
}

export interface LinkGraphTransportEnvelopeBase {
  sessionId: string;
  revision: number;
}

export interface LinkGraphArtifactSliceEnvelope extends LinkGraphTransportEnvelopeBase {
  type: LinkGraphIncrementalTransportType & "ARTIFACT_SLICE";
  state: Partial<LinkGraphBootstrapState>;
}

export interface LinkGraphFeedbackSliceEnvelope extends LinkGraphTransportEnvelopeBase {
  type: LinkGraphIncrementalTransportType & "FEEDBACK_SLICE";
  state: Partial<LinkGraphBootstrapState>;
}

export type LinkGraphIncrementalTransportEnvelope =
  | LinkGraphArtifactSliceEnvelope
  | LinkGraphFeedbackSliceEnvelope;
