import type { ReactNode } from "react";

export type ChipVariant = "toolbar-chip" | "app-pill" | "status-pill" | "risk-pill";

export interface ChipProps {
  /**
   * Which pill vocabulary to render. Maps 1:1 to the existing class names so
   * the CSS (and the visual-contract regexes) keep working unchanged.
   * - `toolbar-chip` — context chips (with `assistant-context-chip` companion)
   * - `app-pill` — counts / labels (info-tinted)
   * - `status-pill` — legend / footer states (neutral)
   * - `risk-pill` — risk emphasis (danger-tinted)
   */
  variant?: ChipVariant;
  /** Extra class names appended after the variant class (e.g. `assistant-context-chip`). */
  className?: string;
  title?: string;
  children: ReactNode;
}

/**
 * Shared chip / pill atom.
 *
 * Replaces raw `<span className="toolbar-chip|app-pill|status-pill|risk-pill">`
 * call sites so the four pill vocabularies have one entry point. Preserves the
 * exact class names so existing CSS and visual-contract assertions are stable.
 */
export function Chip({
  variant = "status-pill",
  className,
  title,
  children,
}: ChipProps) {
  const classes = [variant, className].filter(Boolean).join(" ").trim();
  return (
    <span className={classes} title={title}>
      {children}
    </span>
  );
}
