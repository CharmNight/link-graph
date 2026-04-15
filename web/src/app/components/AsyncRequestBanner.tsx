import { useState } from "react";
import { asyncRequestExecutionModeLabel } from "../labels";
import type { AsyncRequestState } from "../types";
import { resolveAsyncRequestPrimaryMessage } from "../asyncRequestStatus";

interface AsyncRequestBannerProps {
  requestState?: AsyncRequestState | null;
  isRequesting?: boolean;
  requestError?: string | null;
  telemetryCollapsedByDefault?: boolean;
}

export function resolveEffectiveRequestState(
  requestState?: AsyncRequestState | null,
  isRequesting = false,
  requestError?: string | null,
): AsyncRequestState | null {
  if (requestState) {
    return requestState;
  }
  if (isRequesting) {
    return {
      phase: "RUNNING",
      statusMessage: "请求已提交",
      detailMessage: "等待后端确认执行方式与执行阶段。",
      errorMessage: null,
      streaming: false,
      fallbackUsed: false,
      promptPreviewAvailable: false,
    };
  }
  if (requestError) {
    return {
      phase: "FAILED",
      statusMessage: "请求失败",
      errorMessage: requestError,
    };
  }
  return null;
}

function bannerTone(requestState: AsyncRequestState): "is-info" | "is-warning" | "is-error" | null {
  if (requestState.phase === "FAILED" || requestState.phase === "TIMED_OUT") {
    return "is-error";
  }
  if (
    requestState.fallbackUsed ||
    requestState.executionMode === "DISABLED" ||
    requestState.executionMode === "REMOTE_FALLBACK"
  ) {
    return "is-warning";
  }
  if (requestState.phase === "RUNNING") {
    return "is-info";
  }
  return null;
}

function bannerTitle(requestState: AsyncRequestState): string | null {
  return resolveAsyncRequestPrimaryMessage(requestState);
}

function bannerDetail(requestState: AsyncRequestState): string | null {
  if (requestState.detailMessage?.trim()) {
    return requestState.detailMessage.trim();
  }
  if (
    (requestState.phase === "FAILED" || requestState.phase === "TIMED_OUT") &&
    requestState.errorMessage?.trim() &&
    bannerTitle(requestState) !== requestState.errorMessage.trim()
  ) {
    return requestState.errorMessage.trim();
  }
  return null;
}

function bannerTelemetry(requestState: AsyncRequestState): Array<{ label: string; value: string }> {
  const promptPreviewStatus = requestState.promptPreviewAvailable
    ? requestState.phase === "SUCCEEDED"
      ? "可查看"
      : "结果后可查看"
    : null;
  const fields: Array<{ label: string; value: string | null }> = [
    {
      label: "请求",
      value: requestState.requestId != null ? `#${requestState.requestId}` : null,
    },
    {
      label: "场景",
      value: requestState.scene?.trim() || null,
    },
    {
      label: "执行模式",
      value: requestState.executionMode ? asyncRequestExecutionModeLabel(requestState.executionMode) : null,
    },
    {
      label: "提供方",
      value: requestState.providerLabel?.trim() || null,
    },
    {
      label: "模型",
      value: requestState.model?.trim() || null,
    },
    {
      label: "接口",
      value: requestState.endpointSummary?.trim() || null,
    },
    {
      label: "返回方式",
      value: requestState.phase !== "IDLE" ? (requestState.streaming ? "流式预览" : "完整返回") : null,
    },
    {
      label: "提示词",
      value: promptPreviewStatus,
    },
  ];
  return fields.filter((field): field is { label: string; value: string } => Boolean(field.value));
}

export function AsyncRequestBanner({
  requestState,
  isRequesting = false,
  requestError,
  telemetryCollapsedByDefault = false,
}: AsyncRequestBannerProps) {
  const effectiveRequestState = resolveEffectiveRequestState(requestState, isRequesting, requestError);
  if (!effectiveRequestState) {
    return null;
  }

  const tone = bannerTone(effectiveRequestState);
  const title = bannerTitle(effectiveRequestState);
  const detail = bannerDetail(effectiveRequestState);
  const preview = effectiveRequestState.previewText?.trim() || null;
  const telemetry = bannerTelemetry(effectiveRequestState);
  if (!tone && !title && !detail && !preview && telemetry.length === 0) {
    return null;
  }
  const hasExpandableDetails = Boolean(preview) || telemetry.length > 0;
  const [detailsExpanded, setDetailsExpanded] = useState(() => !telemetryCollapsedByDefault);
  const shouldShowDetails = !hasExpandableDetails || detailsExpanded;

  return (
    <div className={`request-state-banner ${tone ?? ""}`.trim()}>
      <div className="request-state-banner-head">
        {title ? <strong className="request-state-banner-title">{title}</strong> : null}
        {hasExpandableDetails && telemetryCollapsedByDefault ? (
          <button
            type="button"
            className="ghost-button compact"
            aria-expanded={detailsExpanded}
            onClick={() => setDetailsExpanded((current) => !current)}
          >
            {detailsExpanded ? "收起请求详情" : "展开请求详情"}
          </button>
        ) : null}
      </div>
      {detail ? <p className="muted">{detail}</p> : null}
      {shouldShowDetails ? (
        <div className="request-state-banner-details">
          {preview ? <pre className="request-state-preview">{preview}</pre> : null}
          {telemetry.length > 0 ? (
            <dl className="request-state-meta-grid">
              {telemetry.map((item) => (
                <div key={`${item.label}:${item.value}`} className="request-state-meta-item">
                  <dt>{item.label}</dt>
                  <dd>{item.value}</dd>
                </div>
              ))}
            </dl>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
