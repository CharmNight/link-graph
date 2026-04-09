import { useState } from "react";

interface ArtifactTextDisclosureProps {
  buttonLabel: string;
  expandedLabel: string;
  artifactId?: string | null;
  text?: string | null;
  emptyText?: string;
  onRequestArtifact?: (artifactId: string) => void;
}

export function ArtifactTextDisclosure({
  buttonLabel,
  expandedLabel,
  artifactId,
  text,
  emptyText = "正在按需加载内容...",
  onRequestArtifact,
}: ArtifactTextDisclosureProps) {
  const [expanded, setExpanded] = useState(false);
  const hasText = Boolean(text?.trim());
  const canRequest = Boolean(artifactId);

  if (!hasText && !canRequest) {
    return null;
  }

  return (
    <>
      <div className="panel-actions">
        <button
          type="button"
          className="ghost-button"
          onClick={() => {
            setExpanded((current) => {
              const next = !current;
              if (next && !hasText && artifactId) {
                onRequestArtifact?.(artifactId);
              }
              return next;
            });
          }}
        >
          {expanded ? expandedLabel : buttonLabel}
        </button>
      </div>
      {expanded ? (
        hasText ? (
          <pre className="prompt-preview">{text}</pre>
        ) : (
          <p className="muted">{emptyText}</p>
        )
      ) : null}
    </>
  );
}
