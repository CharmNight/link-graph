import { useLayoutEffect, useRef } from "react";
import { useUpdateNodeInternals } from "@xyflow/react";

export function useStableNodeInternalsUpdate(nodeId: string, internalsSignature: string) {
  const updateNodeInternals = useUpdateNodeInternals();
  const previousSignatureRef = useRef<string | null>(null);

  useLayoutEffect(() => {
    const previousSignature = previousSignatureRef.current;
    previousSignatureRef.current = internalsSignature;
    if (previousSignature === null || previousSignature === internalsSignature) {
      return;
    }
    updateNodeInternals(nodeId);
  }, [internalsSignature, nodeId, updateNodeInternals]);
}
