import type { BridgeInvocationResult } from "../api";
import type {
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
    const notice = createBridgeFailureNotice(scene, result);
    const feedback: OperationFeedback = {
      level: options.failureFeedbackLevel ?? "ERROR",
      message: options.failureMessage ?? result.message ?? `${scene}请求未发出`,
    };

    setOperationFeedback(feedback);
    if (options.announceFailure !== false) {
      setRequestFailureNotice(notice);
    }

    const rejection = {
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
