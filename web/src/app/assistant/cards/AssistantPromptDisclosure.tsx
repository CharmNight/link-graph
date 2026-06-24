import { RequestPromptDisclosure } from "../../components/RequestPromptDisclosure";
import type { AssistantArtifactAccess } from "../assistantArtifacts";

/** AssistantPromptDisclosure 组件的入参（继承自产物访问能力接口）。 */
interface AssistantPromptDisclosureProps extends AssistantArtifactAccess {
  /** 提示词预览文本（已有内容时优先用）。 */
  promptPreview?: string | null;
  /** 提示词对应的 artifact ID（用于按需加载）。 */
  promptPreviewArtifactId?: string | null;
}

/**
 * 助理面板内的调试用提示词折叠区。
 *
 * 对 [RequestPromptDisclosure] 组件做一层包装：
 * - 包装在 section + 标题"调试用提示词"中，符合助理面板的区块风格；
 * - 同时检查 promptPreview 与 artifactId 都缺失时不渲染。
 */
export function AssistantPromptDisclosure({
  promptPreview,
  promptPreviewArtifactId,
  resolveArtifactText,
  onRequestArtifact,
}: AssistantPromptDisclosureProps) {
  // 既无文本也无 artifact：不渲染
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
