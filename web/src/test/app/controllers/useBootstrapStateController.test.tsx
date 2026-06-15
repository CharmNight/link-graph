import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { acknowledgeSnapshot, announceFrontendReady, subscribeBootstrap } from "../../../app/api";
import { useBootstrapStateController } from "../../../app/controllers/useBootstrapStateController";
import { EMPTY_STATE } from "../../../app/sampleState";
import type { LinkGraphSnapshotEnvelope } from "../../../app/types";

vi.mock("../../../app/api", () => ({
  acknowledgeSnapshot: vi.fn(),
  announceFrontendReady: vi.fn(),
  subscribeBootstrap: vi.fn(),
}));

let bootstrapListener: ((envelope: LinkGraphSnapshotEnvelope) => void) | null = null;

function snapshotEnvelope(
  revision: number,
  transportType?: LinkGraphSnapshotEnvelope["transportType"],
): LinkGraphSnapshotEnvelope {
  return {
    sessionId: "session-1",
    revision,
    transportType,
    state: {
      ...structuredClone(EMPTY_STATE),
      snapshotRevision: revision,
      operationFeedback: transportType === "FEEDBACK_SLICE"
        ? {
            level: "INFO",
            message: "Layout saved",
          }
        : null,
    } as LinkGraphSnapshotEnvelope["state"],
  };
}

describe("useBootstrapStateController", () => {
  beforeEach(() => {
    bootstrapListener = null;
    vi.clearAllMocks();
    vi.mocked(subscribeBootstrap).mockImplementation((listener) => {
      bootstrapListener = listener;
      return vi.fn();
    });
  });

  it("applies same-revision incremental feedback slices and acknowledges authoritative slices", () => {
    const applyBootstrapState = vi.fn();
    renderHook(() =>
      useBootstrapStateController({
        initialRevision: 5,
        applyBootstrapState,
      }),
    );

    act(() => {
      bootstrapListener?.(snapshotEnvelope(5));
      bootstrapListener?.(snapshotEnvelope(5, "FEEDBACK_SLICE"));
      bootstrapListener?.(snapshotEnvelope(5, "ARTIFACT_SLICE"));
    });

    expect(applyBootstrapState).toHaveBeenCalledTimes(2);
    expect(applyBootstrapState).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({
        operationFeedback: {
          level: "INFO",
          message: "Layout saved",
        },
      }),
    );
    expect(applyBootstrapState).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({
        snapshotRevision: 5,
      }),
    );
    expect(acknowledgeSnapshot).toHaveBeenCalledWith(5);
    expect(acknowledgeSnapshot).toHaveBeenCalledTimes(1);
    expect(announceFrontendReady).toHaveBeenCalledWith(5);
  });
});
