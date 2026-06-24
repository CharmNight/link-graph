import { useState } from "react";
import { asyncRequestExecutionModeLabel, normalizeWorkbenchWording, qaModeLabel } from "../labels";
import type { AsyncRequestState } from "../types";
import { Button } from "./Button";
import { resolveAsyncRequestPrimaryMessage } from "../asyncRequestStatus";

/** AsyncRequestBanner 的入参。 */
interface AsyncRequestBannerProps {
  /** 待展示的异步请求状态；为空时不渲染。 */
  requestState?: AsyncRequestState | null;
  /** 详情区是否默认折叠；默认 false（展开）。 */
  telemetryCollapsedByDefault?: boolean;
}

/**
 * 把外部传入的 requestState 规范化为非空值或 null。
 * 抽成函数便于其他模块复用同样的"空判定"语义。
 */
export function resolveEffectiveRequestState(
  requestState?: AsyncRequestState | null,
): AsyncRequestState | null {
  return requestState ?? null;
}

/**
 * 根据请求状态推断横幅色调。
 *
 * - FAILED / TIMED_OUT → is-error；
 * - 触发回退或非默认执行模式 → is-warning；
 * - RUNNING → is-info；
 * - 其他（成功 / 空闲） → 无色调（null）。
 */
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

/** 取横幅主标题；委托给 resolveAsyncRequestPrimaryMessage 统一逻辑。 */
function bannerTitle(requestState: AsyncRequestState): string | null {
  return resolveAsyncRequestPrimaryMessage(requestState);
}

/**
 * 取横幅详细说明。
 *
 * 优先使用 detailMessage；失败/超时时回退到 errorMessage（避免与标题重复）。
 * 成功态不展示详情（返回 null），避免冗余。
 */
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

/**
 * 决定详情文案的折叠形态。
 *
 * 折叠模式下：长文本只显示首行，剩余展开后才可见；
 * 非折叠模式下：全部内容都作为 inlineDetail 展示。
 *
 * 单行内容即使开启折叠也直接展示（不强制折叠）。
 */
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
  // 折叠模式下按行切分；只有 1 行时直接 inline 展示
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

/**
 * 构造遥测字段列表（请求 ID、场景、模式等）。
 * 字段值为空的不进入结果，避免出现空行。
 */
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

/**
 * 异步请求状态横幅组件。
 *
 * 在工具栏上方展示当前异步请求的状态（运行中 / 失败 / 成功等），
 * 并提供可展开的详情区（遥测字段、预览内容、详细错误等）。
 * 全部内容都为空时不渲染任何 DOM。
 */
export function AsyncRequestBanner({
  requestState,
  telemetryCollapsedByDefault = false,
}: AsyncRequestBannerProps) {
  const effectiveRequestState = resolveEffectiveRequestState(requestState);
  // 无请求状态：不渲染
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
  // 所有字段都为空时不渲染
  if (!tone && !title && !inlineDetail && !expandedDetail && !preview && telemetry.length === 0) {
    return null;
  }
  const hasExpandableDetails = Boolean(preview) || telemetry.length > 0 || Boolean(expandedDetail);
  // 默认折叠条件：外部要求折叠 或 存在预览（预览通常较长，默认折叠）
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
