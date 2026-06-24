import type { StageStatusEntry } from "../workflow/stageStatusModel";

/** WorkflowStageNav 组件的入参。 */
interface WorkflowStageNavProps {
  /** 当前所有阶段的状态条目。 */
  entries: StageStatusEntry[];
  /** 点击某阶段的回调。 */
  onSelectStage: (stage: StageStatusEntry["stage"]) => void;
}

/** 阶段状态 → 中文标签 的映射。 */
const STATUS_LABEL: Record<StageStatusEntry["status"], string> = {
  done: "已完成",
  active: "进行中",
  blocked: "阻塞",
  idle: "未开始",
};

/**
 * 左侧阶段引导条：把工作流的 5 个阶段渲染成可点击的步骤导航。
 *
 * 设计取舍：现有 WorkflowTaskbar 里的 `stage-badge` 是只读 pill，
 * 看起来像按钮但不能点，违反"所见即所控"。本组件承担导航职责：
 * - 点击任一阶段触发 onSelectStage（是否真的切换由父组件按门禁决定）；
 * - blocked 阶段带红色提示，但仍可点击（点击后由门禁给出反馈）。
 *
 * 与 WorkflowTaskbar 的关系：本组件负责导航，taskbar 的 badge
 * 仍可作为标题旁的当前位置指示，二者不互斥。
 */
export function WorkflowStageNav({ entries, onSelectStage }: WorkflowStageNavProps) {
  // 统计已完成阶段数，用于在标题旁展示进度
  const doneCount = entries.filter((entry) => entry.status === "done").length;
  const total = entries.length;
  return (
    <nav className="workflow-stage-nav" aria-label="工作流阶段">
      <p className="workflow-stage-nav-title">
        <span>工作流程</span>
        <span className="workflow-stage-nav-progress" aria-label={`已完成 ${doneCount} / ${total} 个阶段`}>
          {doneCount} / {total}
        </span>
      </p>
      <ol className="workflow-stage-list">
        {entries.map((entry) => {
          const statusDescription = STATUS_LABEL[entry.status];
          return (
            <li key={entry.stage} className={entry.status === "done" ? "workflow-stage-li workflow-stage-li-done" : "workflow-stage-li"}>
              <button
                type="button"
                className={[
                  "workflow-stage-item",
                  `workflow-stage-item-${entry.status}`,
                ].join(" ")}
                // 当前阶段标记为 step，便于辅助技术识别
                aria-current={entry.status === "active" ? "step" : undefined}
                aria-label={`${entry.label}（${statusDescription}）`}
                title={entry.meta ?? statusDescription}
                onClick={() => onSelectStage(entry.stage)}
              >
                {/* 已完成显示对勾，否则显示序号 */}
                <span className="workflow-stage-num" aria-hidden="true">
                  {entry.status === "done" ? "✓" : entry.index}
                </span>
                <span className="workflow-stage-body">
                  <span className="workflow-stage-name">{entry.label}</span>
                  {entry.meta ? (
                    <span className="workflow-stage-meta">{entry.meta}</span>
                  ) : null}
                </span>
                {/* blocked 阶段额外加红色标签 */}
                {entry.status === "blocked" ? (
                  <span className="workflow-stage-flag workflow-stage-flag-blocked">阻塞</span>
                ) : null}
              </button>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
