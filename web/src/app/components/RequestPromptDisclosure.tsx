import { ArtifactTextDisclosure } from "./ArtifactTextDisclosure";

interface RequestPromptDisclosureProps {
  promptPreview?: string | null;
  promptPreviewArtifactId?: string | null;
  promptPreviewAvailable?: boolean;
  resolveArtifactText?: (artifactId: string) => string | null;
  onRequestArtifact?: (artifactId: string) => void;
}

export function RequestPromptDisclosure({
  promptPreview,
  promptPreviewArtifactId,
  promptPreviewAvailable = false,
  resolveArtifactText,
  onRequestArtifact,
}: RequestPromptDisclosureProps) {
  const artifactText = promptPreviewArtifactId ? resolveArtifactText?.(promptPreviewArtifactId) ?? null : null;
  const text = promptPreview?.trim() ? promptPreview : artifactText;
  const hasText = Boolean(text?.trim());
  const hasArtifact = Boolean(promptPreviewArtifactId);
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
