import type { ReactNode } from "react";
import { isProjectStructureDisplay } from "../appDisplaySelectors";
import {
  CLASS_DIAGRAM_ASSISTANT_STATUS_LABELS,
  primaryWorkflowActionDisabled,
  primaryWorkflowActionLabel,
  type PrimaryWorkflowActionState,
} from "../appPrimaryWorkflowAction";
import type { HybridWorkbenchLayoutPreference } from "../appWorkbenchPreferences";
import type {
  AnalysisDisplayMode,
  IndexedGraphSummary,
  LinkGraphNode,
  OperationFeedback,
} from "../types";
import type { WorkflowStage, WorkflowStageStatus } from "../workflow/workflowStage";
import { GraphWorkbench } from "../workbench/GraphWorkbench";
import { WorkbenchPropertyDrawer } from "../workbench/WorkbenchPropertyDrawer";
import { ChangeTray } from "./ChangeTray";
import { HybridWorkbenchLayout } from "./HybridWorkbenchLayout";
import { LinkGraphOutline } from "./LinkGraphOutline";
import { WorkflowTaskbar } from "./WorkflowTaskbar";
import type {
  ChangeTrayState,
  LinkGraphOutlineItem,
  LinkGraphOutlineMetrics,
} from "./hybridDerivations";

interface AppWorkbenchChromeProps {
  currentTarget: {
    title: string;
    path: string | null;
  };
  activeWorkflowStage: WorkflowStage;
  workflowStageStates: Record<WorkflowStage, WorkflowStageStatus>;
  analysisDisplayMode: AnalysisDisplayMode;
  indexedArchitectureSummary: IndexedGraphSummary | null;
  changeTrayState: ChangeTrayState;
  toolbarFeedback: OperationFeedback | null;
  primaryWorkflowActionState: PrimaryWorkflowActionState;
  hybridLayoutPreference: HybridWorkbenchLayoutPreference;
  outlineState: {
    metrics: LinkGraphOutlineMetrics;
    items: LinkGraphOutlineItem[];
  };
  outlineQuery: string;
  selectedNodeId: string | null;
  graphStage: ReactNode;
  assistantWorkbench: ReactNode;
  dialogs: ReactNode;
  detailNode: LinkGraphNode | null;
  onImportMermaid: () => void;
  onExportMermaid: () => void;
  onShowDiff: () => void;
  onRequestSync: () => void;
  onOpenSettings: () => void;
  onPrimaryAction: () => void;
  onOutlineCollapsedChange: (collapsed: boolean) => void;
  onWorkbenchWidthChange: (width: number) => void;
  onOutlineQueryChange: (query: string) => void;
  onSelectOutlineItem: (itemId: string) => void;
  onOpenDraft: () => void;
  onOpenDraftCompare: () => void;
  onOpenCode: () => void;
  onApplyChanges: () => void;
  onRevertChanges: () => void;
  onUpdateNode: (node: LinkGraphNode) => void;
  onDeleteNode: (nodeId: string) => void;
  onDeleteNodeSubtree: (nodeId: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onClosePropertyDrawer: () => void;
}

export function AppWorkbenchChrome({
  currentTarget,
  activeWorkflowStage,
  workflowStageStates,
  analysisDisplayMode,
  indexedArchitectureSummary,
  changeTrayState,
  toolbarFeedback,
  primaryWorkflowActionState,
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
  const graphFocusedLayout = analysisDisplayMode === "CLASS_DIAGRAM" ||
    isProjectStructureDisplay(analysisDisplayMode, indexedArchitectureSummary);

  return (
    <GraphWorkbench
      taskbar={(
        <WorkflowTaskbar
          title={currentTarget.title}
          path={currentTarget.path}
          activeStage={activeWorkflowStage}
          stageStates={workflowStageStates}
          assistantStatusLabels={analysisDisplayMode === "CLASS_DIAGRAM" ? CLASS_DIAGRAM_ASSISTANT_STATUS_LABELS : undefined}
          riskCount={changeTrayState.blockingRiskCount}
          draftCandidateCount={changeTrayState.pendingCandidateCount + changeTrayState.confirmedDraftCount}
          operationFeedback={toolbarFeedback}
          primaryActionLabel={primaryWorkflowActionLabel(primaryWorkflowActionState)}
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
