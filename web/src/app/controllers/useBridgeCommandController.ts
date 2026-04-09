import type { BridgeInvocationResult } from "../api";
import type {
  AsyncRequestState,
  OperationFeedback,
} from "../types";
import type {
  BridgeCommandFailureOptions,
  BridgeRejectedCommand,
  RequestFailureNotice,
  RunBridgeCommandOptions,
  SubmitAsyncBridgeCommandOptions,
} from "./bridgeCommandTypes";

interface UseBridgeCommandControllerArgs {
  setOperationFeedback: (feedback: OperationFeedback | null) => void;
  setRequestFailureNotice: (notice: RequestFailureNotice | null) => void;
}

export function createSubmittedRequestState(scene: string): AsyncRequestState {
  return {
    phase: "RUNNING",
    requestId: null,
    scene,
    executionMode: null,
    statusMessage: `已提交${scene}请求`,
    errorMessage: null,
    detailMessage: "等待后端确认执行方式与执行阶段。",
    startedAtEpochMillis: Date.now(),
    finishedAtEpochMillis: null,
    streaming: false,
    fallbackUsed: false,
    providerLabel: null,
    model: null,
    endpointSummary: null,
    promptPreviewAvailable: false,
  };
}

export function createBridgeRejectedRequestState(
  scene: string,
  result: Extract<BridgeInvocationResult, { ok: false }>,
): AsyncRequestState {
  return {
    phase: "FAILED",
    requestId: null,
    scene,
    executionMode: null,
    statusMessage: `${scene}请求未发出`,
    errorMessage: result.message,
    detailMessage: result.detailMessage,
    startedAtEpochMillis: null,
    finishedAtEpochMillis: Date.now(),
    streaming: false,
    fallbackUsed: false,
    providerLabel: null,
    model: null,
    endpointSummary: null,
    promptPreviewAvailable: false,
  };
}

export function createBridgeFailureNotice(
  scene: string,
  result: Extract<BridgeInvocationResult, { ok: false }>,
): RequestFailureNotice {
  return {
    title: `${scene}请求未发出`,
    message: result.message,
    detailMessage: result.detailMessage,
  };
}

export function useBridgeCommandController({
  setOperationFeedback,
  setRequestFailureNotice,
}: UseBridgeCommandControllerArgs) {
  function rejectBridgeInvocation(
    scene: string,
    result: Extract<BridgeInvocationResult, { ok: false }>,
    options: BridgeCommandFailureOptions = {},
  ): BridgeRejectedCommand {
    const requestState = createBridgeRejectedRequestState(scene, result);
    const notice = createBridgeFailureNotice(scene, result);
    const feedback: OperationFeedback = {
      level: options.failureFeedbackLevel ?? "ERROR",
      message: options.failureMessage ?? requestState.errorMessage ?? `${scene}请求未发出`,
    };

    options.applyRejectedRequestState?.(requestState);
    setOperationFeedback(feedback);
    if (options.announceFailure !== false) {
      setRequestFailureNotice(notice);
    }

    const rejection = {
      requestState,
      feedback,
      notice,
    };
    options.onRejected?.(rejection, result);
    return rejection;
  }

  function runBridgeCommand(
    scene: string,
    invoke: () => BridgeInvocationResult,
    options: RunBridgeCommandOptions = {},
  ): BridgeInvocationResult {
    const result = invoke();
    if (!result.ok) {
      rejectBridgeInvocation(scene, result, options);
      return result;
    }
    options.onAccepted?.();
    if (options.successFeedback) {
      setOperationFeedback(options.successFeedback);
    }
    return result;
  }

  function submitAsyncBridgeCommand(
    scene: string,
    invoke: () => BridgeInvocationResult,
    options: SubmitAsyncBridgeCommandOptions = {},
  ): BridgeInvocationResult {
    const result = invoke();
    if (!result.ok) {
      rejectBridgeInvocation(scene, result, options);
      return result;
    }

    options.applySubmittedRequestState?.(createSubmittedRequestState(scene));
    options.onAccepted?.();
    if (options.successFeedback) {
      setOperationFeedback(options.successFeedback);
    }
    return result;
  }

  return {
    rejectBridgeInvocation,
    runBridgeCommand,
    submitAsyncBridgeCommand,
  };
}
