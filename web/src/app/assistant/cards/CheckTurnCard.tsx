import type { AssistantTurn, ResultEvidenceReference } from "../../types";
import type { AssistantArtifactAccess } from "../assistantArtifacts";
import { AssistantFailureNotice } from "./AssistantFailureNotice";
import { AssistantPromptDisclosure } from "./AssistantPromptDisclosure";
import { AssistantQuestionAnswer, AssistantTurnHeader } from "./AssistantTurnFrame";
import { EvidenceFindings } from "./QaTurnCard";

/** CheckTurnCard 组件的入参（继承自产物访问能力接口）。 */
interface CheckTurnCardProps extends AssistantArtifactAccess {
  /** 待渲染的轮次对象。 */
  turn: AssistantTurn;
  /** 轮次序号；可空。 */
  turnIndex?: number;
  /** 是否为最新轮次；影响 UI 上的视觉标记。 */
  isLatest?: boolean;
  /** 用户点击引用"显示来源"的回调。 */
  onRevealReference: (reference: ResultEvidenceReference) => void;
}

/**
 * "检查改动"轮次卡片。
 *
 * 在助理面板中渲染一次 CHECK_RESULT 轮次的结果。
 * 三种分支：
 * - 有失败信息 → 渲染失败通知；
 * - 无结果无失败 → 渲染"检查结果尚未返回"占位；
 * - 有结果 → 渲染问答 + 证据 + 调试提示词。
 */
export function CheckTurnCard({
  turn,
  turnIndex,
  isLatest = false,
  onRevealReference,
  resolveArtifactText,
  onRequestArtifact,
}: CheckTurnCardProps) {
  const result = turn.check;
  return (
    <article className="assistant-turn-card assistant-turn-check">
      <AssistantTurnHeader turn={turn} turnIndex={turnIndex} isLatest={isLatest} />
      {/* 分支 1：有失败 → 失败通知 */}
      {!result && turn.failure ? (
        <AssistantFailureNotice failure={turn.failure} />
      ) : !result ? (
        /* 分支 2：无结果无失败 → 占位文案 */
        <p className="muted assistant-result-text">检查结果尚未返回。</p>
      ) : (
        /* 分支 3：有结果 → 问答 + 证据 + 提示词 */
        <div className="assistant-card-flow">
          <AssistantQuestionAnswer question={result.question} answer={result.answer} />
          <EvidenceFindings findings={result.findings} onRevealReference={onRevealReference} />
          <AssistantPromptDisclosure
            promptPreview={result.promptPreview}
            promptPreviewArtifactId={result.promptPreviewArtifactId}
            resolveArtifactText={resolveArtifactText}
            onRequestArtifact={onRequestArtifact}
          />
        </div>
      )}
    </article>
  );
}
