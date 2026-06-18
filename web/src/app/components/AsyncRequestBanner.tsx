import { useState } from "react";
import { asyncRequestExecutionModeLabel, normalizeWorkbenchWording, qaModeLabel } from "../labels";
import type { AsyncRequestState } from "../types";
import { Button } from "./Button";
import { resolveAsyncRequestPrimaryMessage } from "../asyncRequestStatus";

interface AsyncRequestBannerProps {
  requestState?: AsyncRequestState | null;
  telemetryCollapsedByDefault?: boolean;
}

export function resolveEffectiveRequestState(
  requestState?: AsyncRequestState | null,
): AsyncRequestState | null {
  return requestState ?? null;
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
  if (requestState.phase === "SUCCEEDED") {
    return null;
  }
  if (requestState.detailMessage?.trim()) {
    return normalizeWorkbenchWording(requestState.detailMessage.trim());
  }
  if (
    (requestState.phase === "FAILED" || requestState.phase === "TIMED_OUT") &&
    requestState.errorMessage?.trim() &&
    bannerTitle(requestState) !== requestState.errorMessage.trim()
  ) {
    return normalizeWorkbenchWording(requestState.errorMessage.trim());
  }
  return null;
}

function collapseDetailMessage(
  detail: string | null,
  collapseByDefault: boolean,
): { inlineDetail: string | null; expandedDetail: string | null } {
  const normalizedDetail = detail?.trim() || null;
  if (!normalizedDetail) {
    return {
      inlineDetail: null,
      expandedDetail: null,
    };
  }
  if (!collapseByDefault) {
    return {
      inlineDetail: normalizedDetail,
      expandedDetail: null,
    };
  }
  const lines = normalizedDetail
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
  if (lines.length <= 1) {
    return {
      inlineDetail: normalizedDetail,
      expandedDetail: null,
    };
  }
  return {
    inlineDetail: lines[0] ?? normalizedDetail,
    expandedDetail: normalizedDetail,
  };
}

function bannerTelemetry(requestState: AsyncRequestState): Array<{ label: string; value: string }> {
  const fields: Array<{ label: string; value: string | null }> = [
    {
      label: "请求",
      value: requestState.requestId != null ? `#${requestState.requestId}` : null,
    },
    {
      label: "场景",
      value: requestState.scene?.trim() ? normalizeWorkbenchWording(requestState.scene.trim()) : null,
    },
    {
      label: "执行模式",
      value: requestState.executionMode ? asyncRequestExecutionModeLabel(requestState.executionMode) : null,
    },
    {
      label: "请求模式",
      value: requestState.requestedMode ? qaModeLabel(requestState.requestedMode) : null,
    },
    {
      label: "实际模式",
      value: requestState.effectiveMode ? qaModeLabel(requestState.effectiveMode) : null,
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
  ];
  return fields.filter((field): field is { label: string; value: string } => Boolean(field.value));
}

export function AsyncRequestBanner({
  requestState,
  telemetryCollapsedByDefault = false,
}: AsyncRequestBannerProps) {
  const effectiveRequestState = resolveEffectiveRequestState(requestState);
  if (!effectiveRequestState) {
    return null;
  }

  const tone = bannerTone(effectiveRequestState);
  const title = bannerTitle(effectiveRequestState);
  const { inlineDetail, expandedDetail } = collapseDetailMessage(
    bannerDetail(effectiveRequestState),
    telemetryCollapsedByDefault,
  );
  const preview = effectiveRequestState.previewText?.trim() || null;
  const telemetry = bannerTelemetry(effectiveRequestState);
  if (!tone && !title && !inlineDetail && !expandedDetail && !preview && telemetry.length === 0) {
    return null;
  }
  const hasExpandableDetails = Boolean(preview) || telemetry.length > 0 || Boolean(expandedDetail);
  const shouldCollapseDetailsByDefault = telemetryCollapsedByDefault || Boolean(preview);
  const [detailsExpanded, setDetailsExpanded] = useState(false);
  const shouldShowDetails = !shouldCollapseDetailsByDefault || detailsExpanded;

  return (
    <div className={`request-state-banner ${tone ?? ""}`.trim()} aria-live="polite">
      <div className="request-state-banner-head">
        {title ? <strong className="request-state-banner-title">{title}</strong> : null}
        {hasExpandableDetails && shouldCollapseDetailsByDefault ? (
          <Button
            compact
            aria-expanded={shouldShowDetails}
            onClick={() => setDetailsExpanded((current) => !current)}
          >
            {shouldShowDetails ? "收起请求详情" : "展开请求详情"}
          </Button>
        ) : null}
      </div>
      {inlineDetail ? <p className="muted">{inlineDetail}</p> : null}
      {shouldShowDetails ? (
        <div className="request-state-banner-details">
          {expandedDetail ? <pre className="request-state-preview">{expandedDetail}</pre> : null}
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
