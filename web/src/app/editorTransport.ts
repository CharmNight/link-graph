import type {
  LinkGraphBootstrapState,
  LinkGraphIncrementalTransportEnvelope,
  LinkGraphSnapshotEnvelope,
} from "./types";

type BootstrapListener = (envelope: LinkGraphSnapshotEnvelope) => void;
type SnapshotAcknowledger = (revision: number) => void;
type FrontendReadyNotifier = (payload: { lastAppliedRevision: number | null }) => void;

const LEGACY_SESSION_ID = "legacy";

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
  return "type" in value && "sessionId" in value && "revision" in value && "state" in value;
}

function normalizeEnvelope(
  detail: LinkGraphSnapshotEnvelope | LinkGraphBootstrapState,
): LinkGraphSnapshotEnvelope {
  if (isSnapshotEnvelope(detail)) {
    return detail;
  }
  return {
    sessionId: LEGACY_SESSION_ID,
    revision: detail.snapshotRevision ?? 0,
    state: detail,
    transportType: "LEGACY_BOOTSTRAP",
  };
}

function flushLatestEnvelope(forceDispatch = false): void {
  if (!frontendReady || !latestEnvelope) {
    return;
  }
  if (!forceDispatch && latestEnvelope.revision <= lastDeliveredRevision) {
    return;
  }
  if (latestEnvelope.revision > lastDeliveredRevision) {
    lastDeliveredRevision = latestEnvelope.revision;
  }
  listeners.forEach((listener) => listener(latestEnvelope as LinkGraphSnapshotEnvelope));
}

function handleBootstrapEnvelope(
  detail: LinkGraphSnapshotEnvelope | LinkGraphBootstrapState | LinkGraphIncrementalTransportEnvelope,
): void {
  const normalizedEnvelope = normalizeTransportEnvelope(detail);
  if (!normalizedEnvelope) {
    return;
  }
  if (!latestEnvelope || normalizedEnvelope.revision >= latestEnvelope.revision) {
    latestEnvelope = normalizedEnvelope;
    window.linkGraphBootstrap = normalizedEnvelope.state;
  }
  flushLatestEnvelope(normalizedEnvelope.transportType === "ARTIFACT_SLICE");
}

function handleBootstrapEvent(event: Event): void {
  const customEvent = event as CustomEvent<LinkGraphSnapshotEnvelope | LinkGraphBootstrapState>;
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
  handleBootstrapEnvelope(envelope as LinkGraphSnapshotEnvelope | LinkGraphBootstrapState);
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
  detail: LinkGraphSnapshotEnvelope | LinkGraphBootstrapState | LinkGraphIncrementalTransportEnvelope,
): LinkGraphSnapshotEnvelope | null {
  if (isIncrementalTransportEnvelope(detail)) {
    const baseState = latestEnvelope?.state ?? window.linkGraphBootstrap ?? null;
    if (!baseState) {
      return null;
    }

    return {
      sessionId: detail.sessionId,
      revision: detail.revision,
      state: {
        ...structuredClone(baseState),
        ...detail.state,
        artifactContents: {
          ...(baseState.artifactContents ?? {}),
          ...(detail.state.artifactContents ?? {}),
        },
      },
      transportType: detail.type,
    };
  }

  return normalizeEnvelope(detail);
}
