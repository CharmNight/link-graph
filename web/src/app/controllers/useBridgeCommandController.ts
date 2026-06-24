// 桥接命令控制器 Hook。
// 统一封装桥接命令的执行、成功/失败处理、反馈与通知逻辑。
// 所有需要通过 IDE bridge 派发命令的调用方都应使用本 Hook 提供的 runBridgeCommand / submitAsyncBridgeCommand。
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

/** useBridgeCommandController 的入参。 */
interface UseBridgeCommandControllerArgs {
  /** 设置操作反馈的回调。 */
  setOperationFeedback: (feedback: OperationFeedback | null) => void;
  /** 设置请求失败通知的回调（用于弹窗）。 */
  setRequestFailureNotice: (notice: RequestFailureNotice | null) => void;
}

/**
 * 构造一条请求失败通知。
 * @param scene 场景名（例如"导入 Mermaid"）
 * @param result 桥接调用失败结果
 * @return 请求失败通知对象
 */
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

/**
 * 桥接命令控制器 Hook。
 *
 * 提供三个核心函数：
 * - rejectBridgeInvocation：处理桥接调用失败（设置反馈 + 可选弹窗 + 回调）；
 * - runBridgeCommand：执行桥接命令，成功走 onAccepted + 成功反馈，失败走 reject 流程；
 * - submitAsyncBridgeCommand：与 runBridgeCommand 行为一致（当前实现相同，保留独立名称便于未来语义区分）。
 */
export function useBridgeCommandController({
  setOperationFeedback,
  setRequestFailureNotice,
}: UseBridgeCommandControllerArgs) {
  /**
   * 处理桥接调用失败。
   * 生成反馈消息 + 失败通知，按选项决定是否弹出通知，最后触发 onRejected 回调。
   */
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
    // announceFailure 默认 true；显式设为 false 时不弹失败弹窗
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

  /**
   * 执行一次桥接命令。
   *
   * 流程：
   * 1) 调用 invoke() 执行实际桥接调用；
   * 2) 失败：走 rejectBridgeInvocation 流程；
   * 3) 成功：触发 onAccepted 回调 + 可选成功反馈。
   *
   * @param scene 场景名（用于反馈/通知文案）
   * @param invoke 实际执行桥接调用的函数
   * @param options 成功/失败处理选项
   * @return 桥接调用结果
   */
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

  /**
   * 提交异步桥接命令。
   * 当前与 [runBridgeCommand] 行为一致；保留独立名称便于未来区分同步/异步语义。
   */
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
