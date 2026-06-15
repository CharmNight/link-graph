import type { LinkGraphIncrementalTransportEnvelope, LinkGraphSnapshotEnvelope } from "./types";
import { measureDuration, measureStart, traceLinkGraph } from "./debug";
import { isLinkGraphIncrementalTransportType } from "./transportProtocol";

type BootstrapListener = (envelope: LinkGraphSnapshotEnvelope) => void;
type SnapshotAcknowledger = (revision: number) => void;
type FrontendReadyNotifier = (payload: { lastAppliedRevision: number | null }) => void;

let initialized = false;
let frontendReady = false;
let latestEnvelope: LinkGraphSnapshotEnvelope | null = null;
let lastDeliveredRevision = Number.NEGATIVE_INFINITY;
let lastAcknowledgedRevision = Number.NEGATIVE_INFINITY;
const listeners = new Set<BootstrapListener>();

function isSnapshotEnvelope(value: unknown): value is LinkGraphSnapshotEnvelope {
  if (!value || typeof value !== "object") {
    return false;
  }
  return "sessionId" in value && "revision" in value && "state" in value;
}

function isIncrementalTransportEnvelope(value: unknown): value is LinkGraphIncrementalTransportEnvelope {
  if (!value || typeof value !== "object") {
    return false;
  }
  return "type" in value
    && isLinkGraphIncrementalTransportType(value.type)
    && "sessionId" in value
    && "revision" in value
    && "state" in value;
}

function isSupportedIncrementalSlice(
  envelope: LinkGraphSnapshotEnvelope | null,
): envelope is LinkGraphSnapshotEnvelope & { transportType: LinkGraphIncrementalTransportEnvelope["type"] } {
  return isLinkGraphIncrementalTransportType(envelope?.transportType);
}

function flushLatestEnvelope(forceDispatch = false): void {
  const startedAt = measureStart();
  if (!frontendReady || !latestEnvelope) {
    return;
  }
  if (!forceDispatch && latestEnvelope.revision <= lastDeliveredRevision) {
    return;
  }
  if (latestEnvelope.revision > lastDeliveredRevision) {
    lastDeliveredRevision = latestEnvelope.revision;
  }
  const listenerCount = listeners.size;
  listeners.forEach((listener) => listener(latestEnvelope as LinkGraphSnapshotEnvelope));
  traceLinkGraph("editorTransport.flushLatestEnvelope", {
    revision: latestEnvelope.revision,
    forceDispatch,
    listenerCount,
    durationMs: measureDuration(startedAt),
  });
}

function handleBootstrapEnvelope(
  detail: LinkGraphSnapshotEnvelope | LinkGraphIncrementalTransportEnvelope,
): void {
  const normalizedEnvelope = normalizeTransportEnvelope(detail);
  if (!normalizedEnvelope) {
    return;
  }
  if (!latestEnvelope || normalizedEnvelope.revision >= latestEnvelope.revision) {
    latestEnvelope = normalizedEnvelope;
    window.linkGraphBootstrap = normalizedEnvelope.state;
  }
  flushLatestEnvelope(isSupportedIncrementalSlice(normalizedEnvelope));
}

function handleBootstrapEvent(event: Event): void {
  const customEvent = event as CustomEvent<
    LinkGraphSnapshotEnvelope | LinkGraphIncrementalTransportEnvelope
  >;
  handleBootstrapEnvelope(customEvent.detail);
}

function ensureInitialized(): void {
  if (initialized || typeof window === "undefined") {
    return;
  }
  window.addEventListener("link-graph-bootstrap", handleBootstrapEvent as EventListener);
  initialized = true;
}

export function subscribeBootstrap(listener: BootstrapListener): () => void {
  ensureInitialized();
  listeners.add(listener);
  flushLatestEnvelope();
  return () => {
    listeners.delete(listener);
  };
}

export function announceFrontendReady(
  lastAppliedRevision?: number,
  onReady?: FrontendReadyNotifier,
): void {
  ensureInitialized();
  frontendReady = true;
  onReady?.({
    lastAppliedRevision: Number.isFinite(lastAppliedRevision) ? lastAppliedRevision ?? null : null,
  });
  flushLatestEnvelope();
}

export function acknowledgeSnapshot(
  revision: number,
  onAcknowledge?: SnapshotAcknowledger,
): void {
  if (revision <= lastAcknowledgedRevision) {
    return;
  }
  lastAcknowledgedRevision = revision;
  onAcknowledge?.(revision);
}

export function dispatchBootstrapForTest(
  envelope: LinkGraphSnapshotEnvelope | LinkGraphIncrementalTransportEnvelope,
): void {
  handleBootstrapEnvelope(envelope);
}

export function resetEditorTransportForTest(): void {
  frontendReady = false;
  latestEnvelope = null;
  lastDeliveredRevision = Number.NEGATIVE_INFINITY;
  lastAcknowledgedRevision = Number.NEGATIVE_INFINITY;
  listeners.clear();
}

ensureInitialized();

function normalizeTransportEnvelope(
  detail: LinkGraphSnapshotEnvelope | LinkGraphIncrementalTransportEnvelope,
): LinkGraphSnapshotEnvelope | null {
  if (isIncrementalTransportEnvelope(detail)) {
    const baseState = latestEnvelope?.state ?? window.linkGraphBootstrap ?? null;
    if (!baseState) {
      return null;
    }
    const startedAt = measureStart();
    const baseArtifactCount = Object.keys(baseState.artifactContents ?? {}).length;
    const incomingArtifactCount = Object.keys(detail.state.artifactContents ?? {}).length;
    const state = {
      ...baseState,
      ...detail.state,
      artifactContents: {
        ...(baseState.artifactContents ?? {}),
        ...(detail.state.artifactContents ?? {}),
      },
    };
    traceLinkGraph("editorTransport.incrementalSlice.merge", {
      type: detail.type,
      revision: detail.revision,
      baseArtifactCount,
      incomingArtifactCount,
      mergedArtifactCount: Object.keys(state.artifactContents ?? {}).length,
      durationMs: measureDuration(startedAt),
    });

    return {
      sessionId: detail.sessionId,
      revision: detail.revision,
      state,
      transportType: detail.type,
    };
  }

  if (!isSnapshotEnvelope(detail)) {
    return null;
  }
  return detail;
}
