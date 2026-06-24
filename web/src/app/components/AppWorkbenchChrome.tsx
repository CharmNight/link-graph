import type { ReactNode } from "react";
import { isProjectStructureDisplay } from "../appDisplaySelectors";
import {
  primaryWorkflowActionDisabled,
  primaryWorkflowActionLabel,
  primaryWorkflowActionSubtitle,
  type PrimaryWorkflowActionState,
} from "../appPrimaryWorkflowAction";
import type { HybridWorkbenchLayoutPreference } from "../appWorkbenchPreferences";
import type {
  AnalysisDisplayMode,
  IndexedGraphSummary,
  LinkGraphNode,
  OperationFeedback,
} from "../types";
import type { WorkflowStage } from "../workflow/workflowStage";
import { GraphWorkbench } from "../workbench/GraphWorkbench";
import { WorkbenchPropertyDrawer } from "../workbench/WorkbenchPropertyDrawer";
import { ChangeTray } from "./ChangeTray";
import { HybridWorkbenchLayout } from "./HybridWorkbenchLayout";
import { LinkGraphOutline } from "./LinkGraphOutline";
import { WorkflowStageNav } from "./WorkflowStageNav";
import { WorkflowTaskbar } from "./WorkflowTaskbar";
import type {
  ChangeTrayState,
  LinkGraphOutlineItem,
  LinkGraphOutlineMetrics,
} from "./hybridDerivations";
import type { StageStatusEntry } from "../workflow/stageStatusModel";

/**
 * AppWorkbenchChrome 的入参。
 *
 * 把整个工作台外壳所需的全部状态、子组件（作为 ReactNode）与回调打包，
 * 让 App.tsx 不必关心布局细节。
 */
interface AppWorkbenchChromeProps {
  /** 当前目标信息（标题与路径）。 */
  currentTarget: {
    title: string;
    path: string | null;
  };
  /** 当前工作流阶段。 */
  activeWorkflowStage: WorkflowStage;
  /** 当前展示模式。 */
  analysisDisplayMode: AnalysisDisplayMode;
  /** 架构图索引摘要；用于判断是否是项目结构视图。 */
  indexedArchitectureSummary: IndexedGraphSummary | null;
  /** 变更托盘状态。 */
  changeTrayState: ChangeTrayState;
  /** 工具栏反馈消息。 */
  toolbarFeedback: OperationFeedback | null;
  /** 主操作状态（用于派生按钮文本与禁用状态）。 */
  primaryWorkflowActionState: PrimaryWorkflowActionState;
  /** 阶段引导条状态条目。 */
  stageStatusEntries: StageStatusEntry[];
  /** 混合布局偏好（大纲折叠状态 + 助理宽度）。 */
  hybridLayoutPreference: HybridWorkbenchLayoutPreference;
  /** 大纲状态。 */
  outlineState: {
    metrics: LinkGraphOutlineMetrics;
    items: LinkGraphOutlineItem[];
  };
  /** 大纲搜索关键词。 */
  outlineQuery: string;
  /** 当前选中节点 ID。 */
  selectedNodeId: string | null;
  /** 图谱舞台节点（由父组件传入）。 */
  graphStage: ReactNode;
  /** 助理工作台节点（由父组件传入）。 */
  assistantWorkbench: ReactNode;
  /** 弹窗节点（由父组件传入）。 */
  dialogs: ReactNode;
  /** 当前详情面板所编辑的节点。 */
  detailNode: LinkGraphNode | null;
  /** Mermaid 导入回调。 */
  onImportMermaid: () => void;
  /** Mermaid 导出回调。 */
  onExportMermaid: () => void;
  /** 显示差异比对回调。 */
  onShowDiff: () => void;
  /** 请求同步预览回调。 */
  onRequestSync: () => void;
  /** 打开设置回调。 */
  onOpenSettings: () => void;
  /** 主操作按钮回调。 */
  onPrimaryAction: () => void;
  /** 点击阶段引导条回调。 */
  onSelectStage: (stage: WorkflowStage) => void;
  /** 大纲折叠状态变化回调。 */
  onOutlineCollapsedChange: (collapsed: boolean) => void;
  /** 助理宽度变化回调。 */
  onWorkbenchWidthChange: (width: number) => void;
  /** 大纲搜索关键词变化回调。 */
  onOutlineQueryChange: (query: string) => void;
  /** 选中大纲项回调。 */
  onSelectOutlineItem: (itemId: string) => void;
  /** 打开改动列表回调。 */
  onOpenDraft: () => void;
  /** 打开流程变化对比回调。 */
  onOpenDraftCompare: () => void;
  /** 进入代码视图回调。 */
  onOpenCode: () => void;
  /** 写入工程回调。 */
  onApplyChanges: () => void;
  /** 回退回调。 */
  onRevertChanges: () => void;
  /** 更新节点回调。 */
  onUpdateNode: (node: LinkGraphNode) => void;
  /** 删除节点回调。 */
  onDeleteNode: (nodeId: string) => void;
  /** 删除节点及子树回调。 */
  onDeleteNodeSubtree: (nodeId: string) => void;
  /** 请求源码跳转回调。 */
  onRequestSourceNavigation: (nodeId: string) => void;
  /** 关闭属性抽屉回调。 */
  onClosePropertyDrawer: () => void;
}

/**
 * 应用工作台外壳：把所有顶层组件（任务栏、布局、大纲、阶段引导、变更托盘、属性抽屉）组合起来。
 *
 * 这是 App.tsx 的主要 UI 入口——把复杂的布局逻辑封装在这里，
 * App.tsx 只需要传入数据和回调。本组件本身不持有状态，所有状态由父组件管理。
 *
 * 在"类图模式"或"项目结构视图"下自动折叠大纲，让画布获得最大空间。
 */
export function AppWorkbenchChrome({
  currentTarget,
  activeWorkflowStage,
  analysisDisplayMode,
  indexedArchitectureSummary,
  changeTrayState,
  toolbarFeedback,
  primaryWorkflowActionState,
  stageStatusEntries,
  hybridLayoutPreference,
  outlineState,
  outlineQuery,
  selectedNodeId,
  graphStage,
  assistantWorkbench,
  dialogs,
  detailNode,
  onImportMermaid,
  onExportMermaid,
  onShowDiff,
  onRequestSync,
  onOpenSettings,
  onPrimaryAction,
  onSelectStage,
  onOutlineCollapsedChange,
  onWorkbenchWidthChange,
  onOutlineQueryChange,
  onSelectOutlineItem,
  onOpenDraft,
  onOpenDraftCompare,
  onOpenCode,
  onApplyChanges,
  onRevertChanges,
  onUpdateNode,
  onDeleteNode,
  onDeleteNodeSubtree,
  onRequestSourceNavigation,
  onClosePropertyDrawer,
}: AppWorkbenchChromeProps) {
  // 类图或项目结构视图下：折叠大纲，给画布更多空间
  const graphFocusedLayout = analysisDisplayMode === "CLASS_DIAGRAM" ||
    isProjectStructureDisplay(analysisDisplayMode, indexedArchitectureSummary);

  return (
    <GraphWorkbench
      taskbar={(
        <WorkflowTaskbar
          title={currentTarget.title}
          path={currentTarget.path}
          activeStage={activeWorkflowStage}
          riskCount={changeTrayState.blockingRiskCount}
          draftCandidateCount={changeTrayState.pendingCandidateCount + changeTrayState.confirmedDraftCount}
          operationFeedback={toolbarFeedback}
          // 主操作按钮的文案/副标题/禁用状态由 primaryWorkflowAction 模块派生
          primaryActionLabel={primaryWorkflowActionLabel(primaryWorkflowActionState)}
          primaryActionSubtitle={primaryWorkflowActionSubtitle(primaryWorkflowActionState)}
          primaryActionDisabled={primaryWorkflowActionDisabled(primaryWorkflowActionState)}
          onImportMermaid={onImportMermaid}
          onExportMermaid={onExportMermaid}
          onShowDiff={onShowDiff}
          onRequestSync={onRequestSync}
          onOpenSettings={onOpenSettings}
          onPrimaryAction={onPrimaryAction}
        />
      )}
      dialogs={dialogs}
      body={(
        <HybridWorkbenchLayout
          // 图优先布局时强制折叠大纲
          outlineCollapsed={graphFocusedLayout ? true : hybridLayoutPreference.outlineCollapsed}
          onOutlineCollapsedChange={onOutlineCollapsedChange}
          workbenchWidth={hybridLayoutPreference.workbenchWidth}
          onWorkbenchWidthChange={onWorkbenchWidthChange}
          outline={(
            <LinkGraphOutline
              metrics={outlineState.metrics}
              items={outlineState.items}
              activeItemId={selectedNodeId}
              query={outlineQuery}
              onQueryChange={onOutlineQueryChange}
              onSelectItem={onSelectOutlineItem}
            />
          )}
          stageNav={(
            <WorkflowStageNav
              entries={stageStatusEntries}
              onSelectStage={onSelectStage}
            />
          )}
          graphStage={graphStage}
          assistantWorkbench={assistantWorkbench}
        />
      )}
      tray={(
        <ChangeTray
          {...changeTrayState}
          onOpenDraft={onOpenDraft}
          onOpenDraftCompare={onOpenDraftCompare}
          onOpenCode={onOpenCode}
          onApply={onApplyChanges}
          onRevert={onRevertChanges}
        />
      )}
      propertyDrawer={(
        <WorkbenchPropertyDrawer
          selectedNode={detailNode}
          onUpdateNode={onUpdateNode}
          onDeleteNode={onDeleteNode}
          onDeleteNodeSubtree={onDeleteNodeSubtree}
          onRequestSourceNavigation={onRequestSourceNavigation}
          onClose={onClosePropertyDrawer}
        />
      )}
    />
  );
}
