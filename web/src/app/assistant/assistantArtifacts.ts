/**
 * 助理产物访问能力接口。
 *
 * 让上层（如提示词折叠面板）通过统一接口取产物内容：
 * - resolveArtifactText：同步解析已加载的产物文本；
 * - onRequestArtifact：按需请求加载尚未加载的产物。
 */
export interface AssistantArtifactAccess {
  /** 同步解析产物文本；返回 null 表示尚未加载。 */
  resolveArtifactText?: (artifactId: string) => string | null;
  /** 按需请求加载产物。 */
  onRequestArtifact?: (artifactId: string) => void;
}
