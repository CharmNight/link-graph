import type {
  DraftCompareProjection,
  GraphFocusRequest,
  GraphSurfaceExperimentFlags,
  IndexedClassDiagramOptions,
  IndexedGraphRequestStates,
  GraphPosition,
} from "../types";

export interface BaseStageProps {
  selectedNodeId: string | null;
  focusNodeRequest?: GraphFocusRequest | null;
  explanationFocusNodeId?: string | null;
  draftChangedNodeIds?: string[];
  draftCompareProjection?: DraftCompareProjection | null;
  selectedGroupNodeIds?: string[];
  hiddenNodeIds?: string[];
  collapsedNodeIds?: string[];
  collapsedDescendantCountByNodeId?: Record<string, number>;
  experiments?: GraphSurfaceExperimentFlags | null;
  onSelectNode: (nodeId: string) => void;
  onSelectionGroupChange?: (nodeIds: string[]) => void;
  onInspectNode: (nodeId: string) => void;
  onMoveNode: (nodeId: string, position: GraphPosition) => void;
  onMoveNodes?: (updates: Array<{ id: string; position: GraphPosition }>) => void;
  onFormatLayout?: () => void;
  onRequestBeautification?: (selectedNodeId?: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onPrimeQuestionComposer?: (selectedNodeId?: string) => void;
  onToggleCollapseNode?: (nodeId: string) => void;
  onOpenQa?: (selectedNodeId?: string) => void;
  onExpandOverflowNode?: (nodeId: string) => void;
  onExpandInvocation?: (nodeId: string) => void;
  onRemoveInvocationExpansion?: (expansionId: string) => void;
}

export interface EditableStageProps extends BaseStageProps {
  onAddNode: (kind: "METHOD" | "DOC_PAGE", position?: GraphPosition) => void;
  onDeleteNode: (nodeId: string) => void;
  onDeleteNodeSubtree?: (nodeId: string) => void;
  onCreateEdge: (
    sourceId: string,
    targetId: string,
    sourceHandle?: string | null,
    targetHandle?: string | null,
  ) => void;
  onDeleteEdge: (edgeId: string) => void;
  onInsertNodeIntoEdge?: (edgeId: string, kind: "METHOD" | "DOC_PAGE") => void;
  onImportMermaid: () => void;
}

export interface IndexedReadonlyStageProps extends BaseStageProps {
  indexedGraphRequestStates?: IndexedGraphRequestStates | null;
  onRequestClassDiagram?: (scopeNodeId?: string | null) => void;
  onRequestClassDiagramWithOptions?: (
    scopeNodeId: string | null | undefined,
    options: Partial<IndexedClassDiagramOptions>,
  ) => void;
  onRequestClassUsages?: (
    targetNodeId: string,
    options?: {
      targetQualifiedName?: string | null;
      sourceVirtualFileUrl?: string | null;
      sourcePath?: string | null;
      maxUsageGroups?: number | null;
      maxUsageEntries?: number | null;
      includeImports?: boolean | null;
    },
  ) => void;
  onRequestPackageDependencyGraph?: (
    packageName?: string | null,
    options?: { includeExternalLibraries?: boolean; includeJdk?: boolean },
  ) => void;
  onRequestArchitectureGraph?: (options?: { includeExternalLibraries?: boolean; includeJdk?: boolean }) => void;
  onRequestReviewGraphWithOptions?: (
    selectedDiffItemIds: string[],
    options: {
      maxChangedNodes?: number;
      maxRelatedTestNodes?: number;
      maxUpstreamNodes?: number;
      maxDownstreamNodes?: number;
    },
  ) => void;
}

export type EditableDisplayMode = "FACT_GRAPH" | "FLOWCHART" | "RESOURCE_RELATION_VIEW";
export type IndexedReadonlyDisplayMode = "ARCHITECTURE_GRAPH" | "CLASS_DIAGRAM" | "REVIEW_GRAPH";
export type AppGraphStagePropsByMode =
  | { analysisDisplayMode: EditableDisplayMode; stageProps: EditableStageProps }
  | { analysisDisplayMode: IndexedReadonlyDisplayMode; stageProps: IndexedReadonlyStageProps };
