import type {
  GraphSurfaceExperimentFlags,
  GraphPosition,
} from "../types";

export interface ViewStageProps {
  selectedNodeId: string | null;
  selectedGroupNodeIds?: string[];
  hiddenNodeIds?: string[];
  collapsedNodeIds?: string[];
  collapsedDescendantCountByNodeId?: Record<string, number>;
  experiments?: GraphSurfaceExperimentFlags | null;
  onAddNode: (kind: "METHOD" | "DOC_PAGE", position?: GraphPosition) => void;
  onSelectNode: (nodeId: string) => void;
  onSelectionGroupChange?: (nodeIds: string[]) => void;
  onInspectNode: (nodeId: string) => void;
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
  onMoveNode: (nodeId: string, position: GraphPosition) => void;
  onMoveNodes?: (updates: Array<{ id: string; position: GraphPosition }>) => void;
  onFormatLayout?: () => void;
  onRequestBeautification?: (selectedNodeId?: string) => void;
  onRequestSourceNavigation: (nodeId: string) => void;
  onRequestAudit?: (selectedNodeId?: string) => void;
  onToggleCollapseNode?: (nodeId: string) => void;
  onOpenAudit?: (selectedNodeId?: string) => void;
  onImportMermaid: () => void;
  onExpandOverflowNode?: (nodeId: string) => void;
}
