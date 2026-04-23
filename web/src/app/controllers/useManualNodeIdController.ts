import { useRef } from "react";
import { nextManualNodeSequence } from "../manualNodeIds";

export function useManualNodeIdController(initialNodes: Array<{ id: string }>) {
  const nextManualNodeIdRef = useRef(nextManualNodeSequence(initialNodes));

  function syncManualNodeIdCounters(nextNodes: Array<{ id: string }>) {
    nextManualNodeIdRef.current = Math.max(
      nextManualNodeIdRef.current,
      nextManualNodeSequence(nextNodes),
    );
  }

  return {
    nextManualNodeIdRef,
    syncManualNodeIdCounters,
  };
}
