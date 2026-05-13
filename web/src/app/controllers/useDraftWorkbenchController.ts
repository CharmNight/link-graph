import type { Dispatch, SetStateAction } from "react";
import type {
  DraftWorkbenchEntry,
  DraftWorkbenchState,
  GraphBeautificationResult,
  LinkGraphNode,
  OperationFeedback,
} from "../types";

interface UseDraftWorkbenchControllerArgs {
  graphBeautificationResult: GraphBeautificationResult | null;
  draftWorkbenchState: DraftWorkbenchState;
  nodes: LinkGraphNode[];
  setDraftWorkbenchState: Dispatch<SetStateAction<DraftWorkbenchState>>;
  setSelectedDraftEntryId: Dispatch<SetStateAction<string | null>>;
  setActiveWorkbenchTab: (tab: "explanation" | "audit" | "draft" | "code") => void;
  setOperationFeedback: Dispatch<SetStateAction<OperationFeedback | null>>;
  selectExplanationTargetNode: (nodeId: string, options?: { focusViewport?: boolean }) => void;
  handleSelectExplanationStep: (stepId: string) => void;
  resolveDraftEntryTargetNodeIds: (entry: DraftWorkbenchEntry | null) => string[];
  resolveDisplayedNodeId: (nodeId: string | null | undefined, nodes: LinkGraphNode[]) => string | null;
}

export function useDraftWorkbenchController(args: UseDraftWorkbenchControllerArgs) {
  function handleAddExplanationNoteToDraft(stepId: string) {
    const step = args.graphBeautificationResult?.steps.find((item) => item.stepId === stepId);
    if (!step) {
      return;
    }
    const targetNodeId = step.primaryNodeId
      ?? step.evidence.flatMap((finding) => finding.references).find((reference) => reference.nodeId)?.nodeId
      ?? null;
    const nextEntry: DraftWorkbenchEntry = {
      entryId: `draft-note:${step.stepId}`,
      kind: "NOTE",
      title: step.title,
      sourceChangeId: null,
      targetStepIds: [step.stepId],
      targetNodeIds: targetNodeId ? [targetNodeId] : [],
      beforeState: null,
      afterState: step.description,
      reason: "从讲解步骤加入草稿说明项。",
      impactSummary: "",
      claimType: "EXPLANATION_NOTE",
      evidence: step.evidence,
    };
    args.setDraftWorkbenchState((current) => ({
      ...current,
      draftNotes: current.draftNotes.some((entry) => entry.entryId === nextEntry.entryId)
        ? current.draftNotes
        : current.draftNotes.concat(nextEntry),
    }));
    args.setSelectedDraftEntryId(nextEntry.entryId);
    args.setActiveWorkbenchTab("draft");
  }

  function resolveDraftNodeTitle(nodeId: string) {
    return args.nodes.find((node) => node.id === nodeId)?.title ?? nodeId;
  }

  function handleSelectDraftEntry(entryId: string) {
    args.setSelectedDraftEntryId(entryId);
    const entry = args.draftWorkbenchState.draftChanges.find((item) => item.entryId === entryId)
      ?? args.draftWorkbenchState.draftNotes.find((item) => item.entryId === entryId)
      ?? null;
    const targetNodeId = args.resolveDraftEntryTargetNodeIds(entry)
      .map((nodeId) => args.resolveDisplayedNodeId(nodeId, args.nodes))
      .find(Boolean)
      ?? null;
    if (!targetNodeId) {
      return;
    }
    args.selectExplanationTargetNode(targetNodeId, { focusViewport: true });
  }

  function handleLocateDraftChangeNode(entryId: string) {
    const change = args.draftWorkbenchState.draftChanges.find((entry) => entry.entryId === entryId);
    if (!change) {
      return;
    }
    const targetNodeId = args.resolveDraftEntryTargetNodeIds(change)
      .map((nodeId) => args.resolveDisplayedNodeId(nodeId, args.nodes))
      .find(Boolean)
      ?? null;
    if (!targetNodeId) {
      args.setOperationFeedback({
        level: "WARNING",
        message: "这条草稿变更当前没有可定位的图节点。",
      });
      return;
    }
    args.setSelectedDraftEntryId(entryId);
    args.selectExplanationTargetNode(targetNodeId, { focusViewport: true });
    args.setOperationFeedback({
      level: "INFO",
      message: `已定位到草稿变更对应节点：${resolveDraftNodeTitle(targetNodeId)}`,
    });
  }

  function handleOpenDraftNote(entryId: string) {
    const note = args.draftWorkbenchState.draftNotes.find((entry) => entry.entryId === entryId);
    if (!note) {
      return;
    }
    const targetStepId = note.targetStepIds[0] ?? null;
    if (targetStepId && args.graphBeautificationResult?.steps.some((step) => step.stepId === targetStepId)) {
      args.setActiveWorkbenchTab("explanation");
      args.handleSelectExplanationStep(targetStepId);
      return;
    }
    const targetNodeId = note.targetNodeIds[0] ?? null;
    const displayedTargetNodeId = args.resolveDisplayedNodeId(targetNodeId, args.nodes);
    if (displayedTargetNodeId) {
      args.setActiveWorkbenchTab("explanation");
      args.selectExplanationTargetNode(displayedTargetNodeId, { focusViewport: true });
      args.setOperationFeedback({
        level: "INFO",
        message: `已根据草稿说明定位到图节点：${displayedTargetNodeId}`,
      });
      return;
    }
    args.setOperationFeedback({
      level: "WARNING",
      message: "这条草稿说明当前没有可回到的讲解步骤或图节点。",
    });
  }

  function handleLocateDraftNoteNode(entryId: string) {
    const note = args.draftWorkbenchState.draftNotes.find((entry) => entry.entryId === entryId);
    if (!note) {
      return;
    }
    const targetNodeId = args.resolveDisplayedNodeId(note.targetNodeIds[0] ?? null, args.nodes);
    if (!targetNodeId) {
      args.setOperationFeedback({
        level: "WARNING",
        message: "这条草稿说明当前没有可定位的图节点。",
      });
      return;
    }
    args.selectExplanationTargetNode(targetNodeId, { focusViewport: true });
    const targetNode = args.nodes.find((node) => node.id === targetNodeId) ?? null;
    args.setOperationFeedback({
      level: "INFO",
      message: `已根据草稿说明定位到图节点：${targetNode?.title ?? targetNodeId}`,
    });
  }

  return {
    handleAddExplanationNoteToDraft,
    handleSelectDraftEntry,
    handleLocateDraftChangeNode,
    handleOpenDraftNote,
    handleLocateDraftNoteNode,
  };
}
