import type { AssistantFailureResult } from "../../types";

interface AssistantFailureNoticeProps {
  failure: AssistantFailureResult;
}

export function AssistantFailureNotice({ failure }: AssistantFailureNoticeProps) {
  return (
    <div className="assistant-failure-notice" role="status" aria-label="AI 请求失败">
      <strong>请求失败</strong>
      <p className="assistant-result-text">{failure.message}</p>
      {failure.detailMessage ? <p className="muted assistant-result-text">{failure.detailMessage}</p> : null}
    </div>
  );
}
