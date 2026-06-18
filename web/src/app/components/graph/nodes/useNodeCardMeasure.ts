import { useLayoutEffect, useRef } from "react";
import { measureNodeContentBox } from "./measureNodeContentBox";

export interface NodeMeasure {
  width: number;
  height: number;
}

/**
 * Shared sizing hook for graph node cards.
 *
 * Every node card (Fact / Flowchart / Resource / ClassDiagram / Architecture)
 * previously inlined the same `useLayoutEffect` block that measures its root
 * element and reports the size up to the layout engine. This hook centralises
 * that contract so the measurement behaviour is defined once.
 *
 * @param onMeasure callback invoked with the measured `{ width, height }`
 *   whenever the deps change. Stable measurement: reads `offsetWidth/Height`
 *   first, falling back to client box then bounding rect (see
 *   {@link measureNodeContentBox}).
 * @param deps the re-measure dependency array — pass the values that affect the
 *   card's rendered size (e.g. the node, a collapsed flag).
 */
export function useNodeCardMeasure(
  onMeasure: ((size: NodeMeasure) => void) | undefined,
  deps: ReadonlyArray<unknown>,
) {
  const rootRef = useRef<HTMLDivElement | null>(null);

  useLayoutEffect(() => {
    const size = measureNodeContentBox(rootRef.current);
    if (size) {
      onMeasure?.(size);
    }
    // deps are intentional — callers control the re-measure surface.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return rootRef;
}
