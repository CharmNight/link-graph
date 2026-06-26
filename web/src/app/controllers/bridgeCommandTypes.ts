// 桥接命令相关类型定义。
// 集中定义"请求失败通知""被拒绝命令""命令成功/失败的处理选项"等类型，
// 让多个控制器可以共享同一套类型定义。
import type { BridgeInvocationResult } from "../api";
import type {
  OperationFeedback,
  OperationFeedbackLevel,
} from "../types";

/** 请求失败通知（用于弹窗展示给用户）。 */
export interface RequestFailureNotice {
  /** 通知标题。 */
  title: string;
  /** 主消息。 */
  message: string;
  /** 详细消息；可空。 */
  detailMessage?: string | null;
}

/** 被拒绝的命令：同时携带反馈与通知。 */
export interface BridgeRejectedCommand {
  /** 工具栏反馈消息。 */
  feedback: OperationFeedback;
  /** 弹窗通知。 */
  notice: RequestFailureNotice;
}

/** 命令失败时的处理选项。 */
export interface BridgeCommandFailureOptions {
  /** 是否广播失败（默认 true）。 */
  announceFailure?: boolean;
  /** 失败反馈等级；默认 ERROR。 */
  failureFeedbackLevel?: OperationFeedbackLevel;
  /** 自定义失败消息。 */
  failureMessage?: string;
  /** 被拒绝时的回调，可拿到拒绝详情。 */
  onRejected?: (
    rejection: BridgeRejectedCommand,
    result: Extract<BridgeInvocationResult, { ok: false }>,
  ) => void;
}

/** 命令成功时的处理选项。 */
export interface BridgeCommandSuccessOptions {
  /** 成功时附加的反馈；为空则不产生反馈。 */
  successFeedback?: OperationFeedback | null;
  /** 成功时回调。 */
  onAccepted?: () => void;
}

/** 运行桥接命令时的完整选项（成功 + 失败）。 */
export interface RunBridgeCommandOptions
  extends BridgeCommandFailureOptions, BridgeCommandSuccessOptions {}

/** 提交异步桥接命令的选项；当前与 RunBridgeCommandOptions 一致，保留独立类型便于后续扩展。 */
export type SubmitAsyncBridgeCommandOptions = RunBridgeCommandOptions;
