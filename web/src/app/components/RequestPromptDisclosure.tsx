import { ArtifactTextDisclosure } from "./ArtifactTextDisclosure";

/** RequestPromptDisclosure 组件的入参。 */
interface RequestPromptDisclosureProps {
  /** 已有的提示词预览文本（来自请求结果）；可空。 */
  promptPreview?: string | null;
  /** 提示词对应的 artifact ID（按需加载时使用）；可空。 */
  promptPreviewArtifactId?: string | null;
  /** 是否标记"有可用预览"（即使内容尚未加载）。 */
  promptPreviewAvailable?: boolean;
  /** 通过 artifactId 解析文本的回调；用于已加载场景。 */
  resolveArtifactText?: (artifactId: string) => string | null;
  /** 按需请求加载 artifact 的回调。 */
  onRequestArtifact?: (artifactId: string) => void;
}

/**
 * 提示词预览的折叠面板。
 *
 * 综合判断"是否已有文本"、"是否有 artifact 引用"、"是否标记可用"，
 * 决定是否展示折叠面板。让用户在需要时才查看完整提示词，避免默认占用大量版面。
 *
 * 内部委托给通用 [ArtifactTextDisclosure] 组件做实际渲染。
 */
export function RequestPromptDisclosure({
  promptPreview,
  promptPreviewArtifactId,
  promptPreviewAvailable = false,
  resolveArtifactText,
  onRequestArtifact,
}: RequestPromptDisclosureProps) {
  // 优先用 artifactId 解析文本；缺失时用 promptPreview
  const artifactText = promptPreviewArtifactId ? resolveArtifactText?.(promptPreviewArtifactId) ?? null : null;
  const text = promptPreview?.trim() ? promptPreview : artifactText;
  const hasText = Boolean(text?.trim());
  const hasArtifact = Boolean(promptPreviewArtifactId);
  // 都没有任何可用信号时不渲染
  if (!promptPreviewAvailable && !hasText && !hasArtifact) {
    return null;
  }
  if (!hasText && !hasArtifact) {
    return null;
  }

  return (
    <ArtifactTextDisclosure
      buttonLabel="查看提示词"
      expandedLabel="隐藏提示词"
      artifactId={promptPreviewArtifactId ?? null}
      text={text}
      emptyText="正在按需加载提示词..."
      onRequestArtifact={onRequestArtifact}
    />
  );
}
