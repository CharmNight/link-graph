import { useState } from "react";
import { Button } from "./Button";

/** ArtifactTextDisclosure 组件的入参。 */
interface ArtifactTextDisclosureProps {
  /** 折叠状态下按钮文案。 */
  buttonLabel: string;
  /** 展开状态下按钮文案。 */
  expandedLabel: string;
  /** 关联的 artifact ID；用于按需加载。 */
  artifactId?: string | null;
  /** 已有的文本内容；可空（尚未加载）。 */
  text?: string | null;
  /** 文本尚未加载时展示的占位文案。 */
  emptyText?: string;
  /** 按需加载 artifact 内容的回调。 */
  onRequestArtifact?: (artifactId: string) => void;
}

/**
 * 通用 artifact 文本折叠面板。
 *
 * 提供一个"查看/隐藏"按钮，点击切换展开状态。
 * 展开时如果已有文本则展示 pre 块；如果文本未加载但 artifactId 存在，
 * 则触发 onRequestArtifact 请求加载，同时展示占位文案。
 *
 * 既无文本也无 artifactId 时返回 null（无法展示任何内容）。
 */
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

  // 既无文本也无法请求加载：不渲染
  if (!hasText && !canRequest) {
    return null;
  }

  return (
    <>
      <div className="panel-actions">
        <Button
          onClick={() => {
            setExpanded((current) => {
              const next = !current;
              // 展开且当前无文本但有 artifactId：触发按需加载
              if (next && !hasText && artifactId) {
                onRequestArtifact?.(artifactId);
              }
              return next;
            });
          }}
        >
          {expanded ? expandedLabel : buttonLabel}
        </Button>
      </div>
      {expanded ? (
        hasText ? (
          // 已有文本：pre 块保留格式展示
          <pre className="prompt-preview">{text}</pre>
        ) : (
          // 无文本但正在加载：展示占位文案
          <p className="muted">{emptyText}</p>
        )
      ) : null}
    </>
  );
}
