import type { ButtonHTMLAttributes, ReactNode } from "react";

/** Button 支持的视觉变体。 */
export type ButtonVariant = "primary" | "ghost" | "danger";

export interface ButtonProps
  extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "type"> {
  /** 视觉变体；默认为 `ghost`。 */
  variant?: ButtonVariant;
  /** 紧凑模式：渲染 `compact` 类，padding/height 更小。 */
  compact?: boolean;
  /** button 的 type 属性；默认为 `button`，避免意外触发表单提交。 */
  type?: "button" | "submit" | "reset";
  children: ReactNode;
}

/** 变体 → 类名 的映射；与现有 CSS 保持一致。 */
const VARIANT_CLASS: Record<ButtonVariant, string> = {
  primary: "primary-button",
  ghost: "ghost-button",
  danger: "danger-button",
};

/**
 * 通用 Button 原子组件。
 *
 * 替换散落各处的 `<button className="primary-button|ghost-button|danger-button">` 调用（46+ 处）。
 * 保留原有类名不变，避免影响 CSS 与视觉契约测试。
 *
 * `compact` 修饰符渲染同名的 `compact` 类，CSS 中与 variant 配合使用
 * （例如 `ghost-button compact`）；调用方传 `compact` 即可，无需手工拼接类名。
 */
export function Button({
  variant = "ghost",
  compact = false,
  type = "button",
  className,
  children,
  ...rest
}: ButtonProps) {
  // 拼接 variant 类、可选的 compact、外部传入的 className
  const classes = [VARIANT_CLASS[variant], compact ? "compact" : "", className]
    .filter(Boolean)
    .join(" ")
    .trim();
  return (
    <button type={type} className={classes} {...rest}>
      {children}
    </button>
  );
}
