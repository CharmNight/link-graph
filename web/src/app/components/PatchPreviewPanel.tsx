import { useEffect, useState } from "react";
import {
  draftClaimTypeDescription,
  draftClaimTypeLabel,
  graphPatchActionLabel,
  patchPreviewBoundaryDescription,
  sourceTagLabel,
} from "../labels";
import type { DraftPatchApplyResult, GraphPatch, GraphPatchOperation } from "../types";

interface PatchPreviewPanelProps {
  patch?: GraphPatch | null;
  lastApplyResult?: DraftPatchApplyResult | null;
  canUndoLastApply?: boolean;
  lastAppliedSummary?: string | null;
  canRestoreAuditPreview?: boolean;
  canRestoreDiffPreview?: boolean;
  canRestoreLastAppliedPreview?: boolean;
  onApplySelected: (operationIds: string[]) => void;
  onClearPreview: () => void;
  onUndoLastApply: () => void;
  onRestoreAuditPreview: () => void;
  onRestoreDiffPreview: () => void;
  onRestoreLastAppliedPreview: () => void;
}

function operationTarget(operation: GraphPatchOperation): string {
  return operation.node?.title ?? operation.edge?.label ?? operation.elementId;
}

function operationSource(operation: GraphPatchOperation): string | null {
  const sourceTag = operation.node?.sourceTag ?? operation.edge?.sourceTag;
  return sourceTag ? sourceTagLabel(sourceTag) : null;
}

function operationClaimType(operation: GraphPatchOperation): string | null {
  return (
    operation.metadata?.["draft.claimType"] ??
    operation.node?.metadata?.["draft.claimType"] ??
    operation.edge?.metadata?.["draft.claimType"] ??
    null
  );
}

export function PatchPreviewPanel({
  patch,
  lastApplyResult,
  canUndoLastApply = false,
  lastAppliedSummary,
  canRestoreAuditPreview = false,
  canRestoreDiffPreview = false,
  canRestoreLastAppliedPreview = false,
  onApplySelected,
  onClearPreview,
  onUndoLastApply,
  onRestoreAuditPreview,
  onRestoreDiffPreview,
  onRestoreLastAppliedPreview,
}: PatchPreviewPanelProps) {
  const [selectedIds, setSelectedIds] = useState<string[]>([]);

  useEffect(() => {
    setSelectedIds(patch?.operations.map((operation) => operation.id) ?? []);
  }, [patch]);

  if (!patch) {
    const canRestoreAnyPreview =
      canRestoreAuditPreview || canRestoreDiffPreview || canRestoreLastAppliedPreview || canUndoLastApply;
    return (
      <section className="side-panel patch-preview-panel">
        <p className="eyebrow">草稿预览</p>
        <h2>尚未生成 patch</h2>
        <p className="muted">{lastApplyResult?.summary ?? lastAppliedSummary ?? "AI 审计或差异问答生成草稿后，会在这里列出逐条可应用的变更。"}</p>
        {lastApplyResult ? (
          <article className="preview-card">
            <strong>最近一次应用结果</strong>
            <p className="muted">
              已应用 {lastApplyResult.appliedOperationCount} 条变更，涉及 {lastApplyResult.appliedNodeIds.length} 个节点 / {lastApplyResult.appliedEdgeIds.length} 条连线。
            </p>
            {lastApplyResult.appliedTargets.length > 0 ? (
              <div className="warning-list">
                {lastApplyResult.appliedTargets.map((target) => (
                  <p key={target} className="muted">
                    {target}
                  </p>
                ))}
              </div>
            ) : null}
          </article>
        ) : null}
        {canRestoreAnyPreview ? (
          <div className="panel-actions">
            {canRestoreAuditPreview ? (
              <button type="button" className="ghost-button" onClick={onRestoreAuditPreview}>
                恢复审计草稿
              </button>
            ) : null}
            {canRestoreDiffPreview ? (
              <button type="button" className="ghost-button" onClick={onRestoreDiffPreview}>
                恢复差异草稿
              </button>
            ) : null}
            {canRestoreLastAppliedPreview ? (
              <button type="button" className="ghost-button" onClick={onRestoreLastAppliedPreview}>
                恢复上次应用前预览
              </button>
            ) : null}
            {canUndoLastApply ? (
              <button type="button" className="ghost-button" onClick={onUndoLastApply}>
                撤销上次应用
              </button>
            ) : null}
          </div>
        ) : null}
      </section>
    );
  }

  return (
    <section className="side-panel patch-preview-panel">
      <p className="eyebrow">草稿预览</p>
      <h2>待应用图变更</h2>
      <p className="muted">{patch.summary ?? "当前 patch 没有附加摘要。"}</p>
      <p className="muted">{patchPreviewBoundaryDescription()}</p>

      <div className="preview-list">
        {patch.operations.map((operation) => {
          const checked = selectedIds.includes(operation.id);
          const claimType = operationClaimType(operation);
          return (
            <label key={operation.id} className="patch-operation-card">
              <input
                type="checkbox"
                checked={checked}
                onChange={(event) =>
                  setSelectedIds((current) =>
                    event.target.checked
                      ? [...current, operation.id]
                      : current.filter((item) => item !== operation.id),
                  )
                }
              />
              <div className="patch-operation-body">
                <div className="preview-head">
                  <strong>{operation.title ?? graphPatchActionLabel(operation.action)}</strong>
                  <span className="toolbar-chip">{graphPatchActionLabel(operation.action)}</span>
                </div>
                <p>{operation.summary ?? operationTarget(operation)}</p>
                <span className="muted">归类：{draftClaimTypeLabel(claimType)}</span>
                <span className="muted">{draftClaimTypeDescription(claimType)}</span>
                {operationSource(operation) ? <span className="muted">写入层：{operationSource(operation)}</span> : null}
              </div>
            </label>
          );
        })}
      </div>

      <div className="panel-actions">
        <button
          type="button"
          className="primary-button"
          disabled={selectedIds.length === 0}
          onClick={() => onApplySelected(selectedIds)}
        >
          应用选中变更
        </button>
        <button type="button" className="ghost-button" onClick={onClearPreview}>
          清空预览
        </button>
        {canRestoreAuditPreview ? (
          <button type="button" className="ghost-button" onClick={onRestoreAuditPreview}>
            恢复审计草稿
          </button>
        ) : null}
        {canRestoreDiffPreview ? (
          <button type="button" className="ghost-button" onClick={onRestoreDiffPreview}>
            恢复差异草稿
          </button>
        ) : null}
        {canRestoreLastAppliedPreview ? (
          <button type="button" className="ghost-button" onClick={onRestoreLastAppliedPreview}>
            恢复上次应用前预览
          </button>
        ) : null}
        {canUndoLastApply ? (
          <button type="button" className="ghost-button" onClick={onUndoLastApply}>
            撤销上次应用
          </button>
        ) : null}
      </div>
    </section>
  );
}
