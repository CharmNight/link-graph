import type { BridgeInvocationResult } from "../api";
import type {
  AsyncRequestState,
  OperationFeedback,
  OperationFeedbackLevel,
} from "../types";

export interface RequestFailureNotice {
  title: string;
  message: string;
  detailMessage?: string | null;
}

export interface BridgeRejectedCommand {
  requestState: AsyncRequestState;
  feedback: OperationFeedback;
  notice: RequestFailureNotice;
}

export interface BridgeCommandFailureOptions {
  announceFailure?: boolean;
  failureFeedbackLevel?: OperationFeedbackLevel;
  failureMessage?: string;
  applyRejectedRequestState?: (nextState: AsyncRequestState) => void;
  onRejected?: (
    rejection: BridgeRejectedCommand,
    result: Extract<BridgeInvocationResult, { ok: false }>,
  ) => void;
}

export interface BridgeCommandSuccessOptions {
  successFeedback?: OperationFeedback | null;
  onAccepted?: () => void;
}

export interface RunBridgeCommandOptions
  extends BridgeCommandFailureOptions, BridgeCommandSuccessOptions {}

export interface SubmitAsyncBridgeCommandOptions
  extends RunBridgeCommandOptions {
  applySubmittedRequestState?: (nextState: AsyncRequestState) => void;
}
