import type { ButtonHTMLAttributes, ReactNode } from "react";

export type ButtonVariant = "primary" | "ghost" | "danger";

export interface ButtonProps
  extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "type"> {
  /** Visual variant. Defaults to `ghost`. */
  variant?: ButtonVariant;
  /** Tight padding/height modifier — renders the `compact` class. */
  compact?: boolean;
  /** Button type attribute. Defaults to `button` (never accidental form submit). */
  type?: "button" | "submit" | "reset";
  children: ReactNode;
}

const VARIANT_CLASS: Record<ButtonVariant, string> = {
  primary: "primary-button",
  ghost: "ghost-button",
  danger: "danger-button",
};

/**
 * Shared button atom.
 *
 * Replaces the 46+ raw `<button className="primary-button|ghost-button|danger-button">`
 * call sites. Preserves the exact class names so the existing CSS (and the
 * visual-contract regexes that assert those classes) keep working unchanged.
 *
 * The `compact` modifier renders the same `compact` class that the CSS pairs
 * with these variants (e.g. `ghost-button compact`), so call sites pass
 * `compact` instead of concatenating the class string by hand.
 */
export function Button({
  variant = "ghost",
  compact = false,
  type = "button",
  className,
  children,
  ...rest
}: ButtonProps) {
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
