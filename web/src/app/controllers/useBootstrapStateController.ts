import { startTransition, useEffect, useRef } from "react";
import { acknowledgeSnapshot, announceFrontendReady, subscribeBootstrap } from "../api";
import { summarizeBootstrapState, traceLinkGraph } from "../debug";
import type { LinkGraphBootstrapState, LinkGraphIncrementalTransportEnvelope } from "../types";
import {
  LINK_GRAPH_ARTIFACT_SLICE_TRANSPORT_TYPE,
  isLinkGraphIncrementalTransportType,
} from "../transportProtocol";

interface UseBootstrapStateControllerArgs {
  initialRevision: number;
  applyBootstrapState: (nextState: LinkGraphBootstrapState) => void;
}

function isIncrementalSlice(
  transportType: LinkGraphIncrementalTransportEnvelope["type"] | undefined,
): boolean {
  return isLinkGraphIncrementalTransportType(transportType);
}

export function useBootstrapStateController({
  initialRevision,
  applyBootstrapState,
}: UseBootstrapStateControllerArgs) {
  const lastAppliedSnapshotRevisionRef = useRef(initialRevision);
  const applyBootstrapStateRef = useRef(applyBootstrapState);

  useEffect(() => {
    applyBootstrapStateRef.current = applyBootstrapState;
  }, [applyBootstrapState]);

  useEffect(() => {
    const unsubscribe = subscribeBootstrap((envelope) => {
      if (
        envelope.revision < lastAppliedSnapshotRevisionRef.current
        || (
          envelope.revision === lastAppliedSnapshotRevisionRef.current
          && !isIncrementalSlice(envelope.transportType)
        )
      ) {
        return;
      }
      traceLinkGraph("app.bootstrapEvent.received", summarizeBootstrapState(envelope.state));
      startTransition(() => {
        applyBootstrapStateRef.current(envelope.state);
      });
      if (envelope.revision > lastAppliedSnapshotRevisionRef.current) {
        lastAppliedSnapshotRevisionRef.current = envelope.revision;
      }
      if (envelope.transportType !== LINK_GRAPH_ARTIFACT_SLICE_TRANSPORT_TYPE) {
        acknowledgeSnapshot(envelope.revision);
      }
    });
    announceFrontendReady(
      Number.isFinite(lastAppliedSnapshotRevisionRef.current)
        ? lastAppliedSnapshotRevisionRef.current
        : undefined,
    );
    return unsubscribe;
  }, []);

  return {
    lastAppliedSnapshotRevisionRef,
  };
}
