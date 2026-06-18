import type { ReactNode } from "react";
import type { DraftCompareStatus, LinkGraphNode } from "../../../types";
import { GraphNodeStateBadges } from "./GraphNodeStateBadges";
import { useNodeCardMeasure, type NodeMeasure } from "./useNodeCardMeasure";

export interface NodeCardBaseProps {
  node: LinkGraphNode;
  selected: boolean;
  explanationFocused?: boolean;
  draftChanged?: boolean;
  /** Extra badge context forwarded to the shared state-badges strip. */
  draftCompareStatus?: DraftCompareStatus;
  onMeasure?: (size: NodeMeasure) => void;
  /** Values that affect the card's rendered size; re-measures when they change. */
  measureDeps: ReadonlyArray<unknown>;
  /**
   * Variant class name(s) appended after the base `flow-node-card` shell class
   * and before the `is-selected` state class, e.g. `"flowchart-node-card kind-process"`
   * or `"resource-node-card lane-upstream"`. Pass a string or an array.
   */
  variantClassName?: string | ReadonlyArray<string>;
  /** Override the base shell class (`flow-node-card`). Defaults to the shared one. */
  baseClassName?: string;
  children: ReactNode;
}

/**
 * Thin skeleton shared by every graph node card.
 *
 * Centralises the contract that was duplicated across all five cards:
 *   - root `<div ref>` measured by {@link useNodeCardMeasure}
 *   - `data-node-id` for hit-testing
 *   - the class-string composition: `[base, ...variant, is-selected]`
 *   - the `<GraphNodeStateBadges>` strip rendered as the first child
 *
 * Card-specific slot content (title / owner / signature / meta / compartments)
 * is passed as `children` — the slot vocabularies diverge too much between
 * cards (class-diagram has UML compartments; fact has an expand button; etc.)
 * to express as a fixed slot API, so this shell deliberately stays structural.
 */
export function NodeCardBase({
  node,
  selected,
  explanationFocused = false,
  draftChanged = false,
  onMeasure,
  measureDeps,
  variantClassName,
  baseClassName = "flow-node-card",
  children,
}: NodeCardBaseProps) {
  const rootRef = useNodeCardMeasure(onMeasure, measureDeps);
  const variantClasses = Array.isArray(variantClassName)
    ? variantClassName.filter(Boolean)
    : variantClassName
      ? [variantClassName]
      : [];
  const className = [baseClassName, ...variantClasses, selected ? "is-selected" : ""]
    .join(" ")
    .trim();

  return (
    <div ref={rootRef} className={className} data-node-id={node.id}>
      <GraphNodeStateBadges
        selected={selected}
        explanationFocused={explanationFocused}
        draftChanged={draftChanged}
      />
      {children}
    </div>
  );
}
