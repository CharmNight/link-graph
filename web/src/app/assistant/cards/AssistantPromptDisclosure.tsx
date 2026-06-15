import { RequestPromptDisclosure } from "../../components/RequestPromptDisclosure";
import type { AssistantArtifactAccess } from "../assistantArtifacts";

interface AssistantPromptDisclosureProps extends AssistantArtifactAccess {
  promptPreview?: string | null;
  promptPreviewArtifactId?: string | null;
}

export function AssistantPromptDisclosure({
  promptPreview,
  promptPreviewArtifactId,
  resolveArtifactText,
  onRequestArtifact,
}: AssistantPromptDisclosureProps) {
  if (!promptPreview?.trim() && !promptPreviewArtifactId) {
    return null;
  }

  return (
    <section className="assistant-evidence-block">
      <strong>调试用提示词</strong>
      <RequestPromptDisclosure
        promptPreview={promptPreview ?? null}
        promptPreviewArtifactId={promptPreviewArtifactId ?? null}
        promptPreviewAvailable={Boolean(promptPreview?.trim() || promptPreviewArtifactId)}
        resolveArtifactText={resolveArtifactText}
        onRequestArtifact={onRequestArtifact}
      />
    </section>
  );
}
