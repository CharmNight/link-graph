import type { ReactNode } from "react";

/**
 * Chip 组件支持的样式变体。
 * 与 CSS 类名一一对应，保证视觉契约不变。
 */
export type ChipVariant = "toolbar-chip" | "app-pill" | "status-pill" | "risk-pill";

export interface ChipProps {
  /**
   * 选择哪种 pill 词汇表渲染。与现有 CSS 类名一一对应，
   * 保证 CSS（以及视觉契约测试的正则）无需变更。
   * - `toolbar-chip` — 工具栏上下文标签（通常与 `assistant-context-chip` 一起使用）
   * - `app-pill` — 计数 / 标签（信息色调）
   * - `status-pill` — 图例 / 页脚状态（中性）
   * - `risk-pill` — 风险强调（警告色调）
   */
  variant?: ChipVariant;
  /** 追加在 variant 类名之后的额外类名（例如 `assistant-context-chip`）。 */
  className?: string;
  /** 鼠标悬停时的提示文本。 */
  title?: string;
  children: ReactNode;
}

/**
 * 通用 Chip / Pill 原子组件。
 *
 * 替换散落各处的 `<span className="toolbar-chip|app-pill|status-pill|risk-pill">` 调用，
 * 让四种 pill 词汇表有统一入口。保留原有类名不变，避免影响 CSS 与视觉契约测试。
 */
export function Chip({
  variant = "status-pill",
  className,
  title,
  children,
}: ChipProps) {
  // 把 variant 与 className 拼接为单一字符串，过滤 falsy 值避免出现多余空格
  const classes = [variant, className].filter(Boolean).join(" ").trim();
  return (
    <span className={classes} title={title}>
      {children}
    </span>
  );
}
