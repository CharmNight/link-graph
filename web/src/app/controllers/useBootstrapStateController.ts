import { startTransition, useEffect, useRef } from "react";
import { acknowledgeSnapshot, announceFrontendReady, subscribeBootstrap } from "../api";
import { summarizeBootstrapState, traceLinkGraph } from "../debug";
import type { LinkGraphBootstrapState } from "../types";

interface UseBootstrapStateControllerArgs {
  initialRevision: number;
  applyBootstrapState: (nextState: LinkGraphBootstrapState) => void;
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
          && envelope.transportType !== "ARTIFACT_SLICE"
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
      if (envelope.transportType !== "ARTIFACT_SLICE") {
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
