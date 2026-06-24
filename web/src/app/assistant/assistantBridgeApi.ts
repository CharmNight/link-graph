import type { AssistantActionId, AssistantIntent } from "./assistantTypes";
import type {
  AssistantComposerTarget,
  LinkGraphSceneId,
  QaMode,
  StepGranularity,
} from "../types";

/**
 * 助理任务请求结构。
 *
 * 把发起一次助理任务所需的全部信息打包：
 * 动作 ID、场景、意图、提示词、选中节点 / 差异项、目标、粒度、QA 模式等。
 */
export interface AssistantTaskRequest {
  /** 动作 ID（DESCRIBE_CLASS / EXPLAIN_FLOW 等）。 */
  actionId: AssistantActionId;
  /** 当前场景 ID。 */
  sceneId: LinkGraphSceneId;
  /** 助理意图（EXPLAIN_CODE / ASK_CODE 等）。 */
  intent: AssistantIntent;
  /** 用户输入的提示词。 */
  prompt: string;
  /** 选中的节点 ID 列表；可空。 */
  selectedNodeIds?: string[];
  /** 选中的差异项 ID 列表；可空。 */
  selectedDiffItemIds?: string[];
  /** 任务目标（新任务 / 继续当前轮次等）。 */
  target: AssistantComposerTarget;
  /** 链路讲解粒度；可空。 */
  explanationGranularity?: StepGranularity | null;
  /** QA 模式；可空（由后端按 AUTO 处理）。 */
  mode?: QaMode | null;
}

/**
 * 把助理任务请求转换为桥接 payload（弱类型 map）。
 *
 * 序列化时所有可选字段都填默认值（数组为空、对象为 null），
 * 让后端协议格式稳定、不依赖 undefined。
 *
 * @param request 助理任务请求
 * @return 桥接 payload
 */
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
    mode: request.mode ?? null,
  };
}
