import type {
  AssistantActionId,
  AssistantComposerTarget,
  AssistantIntent,
  LinkGraphSceneId,
  StepGranularity,
} from "../types";

export interface AssistantTaskRequest {
  actionId: AssistantActionId;
  sceneId: LinkGraphSceneId;
  intent: AssistantIntent;
  prompt: string;
  selectedNodeIds?: string[];
  selectedDiffItemIds?: string[];
  target: AssistantComposerTarget;
  explanationGranularity?: StepGranularity | null;
}

export function assistantTaskPayload(request: AssistantTaskRequest): Record<string, unknown> {
  return {
    actionId: request.actionId,
    sceneId: request.sceneId,
    intent: request.intent,
    prompt: request.prompt,
    selectedNodeIds: request.selectedNodeIds ?? [],
    selectedDiffItemIds: request.selectedDiffItemIds ?? [],
    target: request.target,
    explanationGranularity: request.explanationGranularity ?? null,
  };
}
