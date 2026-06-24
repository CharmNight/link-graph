import type { AssistantFailureResult } from "../../types";

/** AssistantFailureNotice 组件的入参。 */
interface AssistantFailureNoticeProps {
  /** 失败结果对象。 */
  failure: AssistantFailureResult;
}

/**
 * 助理请求失败通知卡片。
 *
 * 在助理面板中展示一条失败结果：
 * - 固定标题"请求失败"；
 * - 主消息（必填）；
 * - 可选详细消息（补充说明）。
 *
 * 使用 role="status" 让辅助技术识别为状态更新。
 */
export function AssistantFailureNotice({ failure }: AssistantFailureNoticeProps) {
  return (
    <div className="assistant-failure-notice" role="status" aria-label="AI 请求失败">
      <strong>请求失败</strong>
      <p className="assistant-result-text">{failure.message}</p>
      {failure.detailMessage ? <p className="muted assistant-result-text">{failure.detailMessage}</p> : null}
    </div>
  );
}
