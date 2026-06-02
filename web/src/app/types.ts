export type Certainty = "PROVEN" | "RULE_INFERRED" | "LLM_SUGGESTED";

export type BindingStatus =
  | "BOUND"
  | "DESIGN_ONLY"
  | "GENERATABLE"
  | "PARTIALLY_SYNCED"
  | "CONFLICTED";

export type DiffStatus = "MATCHED" | "ONLY_IN_CODE" | "ONLY_IN_MERMAID" | "MODIFIED";
export type DraftCompareStatus = "MODIFIED" | "ADDED" | "REMOVED";
export type GraphSourceTag = "FACT" | "DESIGN_BASELINE" | "DRAFT_MANUAL" | "DRAFT_AI" | "UNCERTAIN_FACT";
export type GraphDiffElementKind = "NODE" | "EDGE";
export type GraphPatchAction =
  | "ADD_NODE"
  | "UPDATE_NODE"
  | "DELETE_NODE"
  | "ADD_EDGE"
  | "UPDATE_EDGE"
  | "DELETE_EDGE"
  | "ADD_ANNOTATION"
  | "MARK_UNCERTAIN";
export type LlmResultSource = "DISABLED" | "LOCAL_RULE" | "REMOTE";
export type ResultEvidenceLevel = "DIRECT_SOURCE" | "DIRECT_GRAPH" | "CALLSITE_ONLY" | "NOT_OBSERVED";
export type DraftClaimType = "CODE_FACT" | "RISK_HINT" | "EXPLANATION_NOTE" | "STRUCTURAL_SUGGESTION";
export type DraftPatchPreviewSource = "QA" | "DIFF_REVIEW" | "LAST_APPLIED";
export type AnalysisDisplayMode =
  | "FACT_GRAPH"
  | "FLOWCHART"
  | "RESOURCE_RELATION_VIEW"
  | "ARCHITECTURE_GRAPH"
  | "CLASS_DIAGRAM"
  | "REVIEW_GRAPH";
export type StepGranularity = "BUSINESS" | "METHOD_CALL" | "CODE_SEMANTIC";
export type StepKind = "BUSINESS_ACTION" | "METHOD_CALL" | "CONDITION" | "RETURN" | "RESOURCE_INTERACTION";
export type CandidateDraftChangeStatus = "PENDING_CONFIRMATION" | "CONFIRMED" | "REJECTED" | "SUPERSEDED";
export type InvestigationThreadStatus = "OPEN" | "PROMOTED" | "DISMISSED" | "BLOCKED" | "SUPERSEDED";
export type RiskResolutionStatus =
  | "UNRESOLVED"
  | "DEFERRED"
  | "ACCEPTED_RISK"
  | "EVIDENCE_EXHAUSTED"
  | "DISMISSED"
  | "PROMOTED";
export type InvestigationTurnOutcomeStatus =
  | "PROMOTED_TO_CANDIDATE"
  | "OPEN_WITH_PROGRESS"
  | "OPEN_NO_PROGRESS"
  | "DISMISSED"
  | "BLOCKED";
export type DraftEntryKind = "CHANGE" | "NOTE";
export type QaMessageRole = "USER" | "ASSISTANT";
export type QaRequestKind = "ASK" | "INVESTIGATE_THREAD";
export type QaMode = "AUTO" | "ANSWER" | "REVIEW" | "CHANGE" | "INVESTIGATE";
export type StageEligibilityTarget = "PLAN" | "CODE";

export type NodeType =
  | "METHOD"
  | "FLOW_SCOPE"
  | "FLOW_ACTION"
  | "TERMINAL"
  | "MERGE"
  | "CLASS"
  | "MODULE"
  | "PACKAGE"
  | "INTERFACE"
  | "ENUM"
  | "ANNOTATION"
  | "RECORD"
  | "OBJECT"
  | "EXTERNAL_CLASS"
  | "LIBRARY"
  | "SERVICE"
  | "COMPONENT"
  | "LAYER"
  | "RESOURCE"
  | "SQL"
  | "HTTP_ENDPOINT"
  | "FEIGN_CLIENT"
  | "DUBBO_SERVICE"
  | "MQ_TOPIC"
  | "MQ_CONSUMER"
  | "CONFIG_ITEM"
  | "XML_RESOURCE"
  | "DOC_PAGE"
  | "UNCERTAIN_LINK";

export type EdgeType =
  | "CALL"
  | "CONTAINS_FLOW"
  | "CONTROL_FLOW"
  | "IMPLEMENTS"
  | "EXTENDS"
  | "USES_TYPE"
  | "INJECT"
  | "ROUTES_TO"
  | "MAPS_TO_SQL"
  | "PUBLISHES_TO"
  | "CONSUMES_FROM"
  | "BINDS_CONFIG"
  | "LINKS_DOC"
  | "USES_PROXY"
  | "REFLECTS_TO"
  | "SPI_RESOLVES_TO"
  | "TESTS"
  | "GENERATES";

export interface GraphPosition {
  x: number;
  y: number;
}

export interface GraphFocusRequest {
  nodeId: string;
  nonce: number;
}

export interface LinkGraphEdgeRouteSection {
  startPoint: GraphPosition;
  endPoint: GraphPosition;
  bendPoints?: GraphPosition[];
}

export interface LinkGraphEdgeRoute {
  sections: LinkGraphEdgeRouteSection[];
}

export interface LinkGraphLayoutState {
  positions: Record<string, GraphPosition>;
}

export type LinkGraphSceneId =
  | "WORKSPACE_FACT"
  | "WORKSPACE_FLOWCHART"
  | "WORKSPACE_RESOURCE_RELATION"
  | "WORKSPACE_ARCHITECTURE_GRAPH"
  | "WORKSPACE_CLASS_DIAGRAM"
  | "WORKSPACE_REVIEW_GRAPH"
  | "DIFF";

export interface LinkGraphSceneState {
  selectedNodeId?: string | null;
  anchorNodeId?: string | null;
  layoutState: LinkGraphLayoutState;
  layoutRevision: number;
  collapsedNodeIds: string[];
}

export interface LinkGraphNode {
  id: string;
  type: NodeType;
  title: string;
  location?: string;
  signature?: string;
  inputs: string[];
  outputs: string[];
  doc?: string;
  certainty: Certainty;
  bindingStatus: BindingStatus;
  diffStatus?: DiffStatus;
  position?: GraphPosition;
  metadata?: Record<string, string>;
  sourceTag?: GraphSourceTag;
}

export interface LinkGraphEdge {
  id: string;
  type: EdgeType;
  source: string;
  target: string;
  sourceHandle?: string | null;
  targetHandle?: string | null;
  label?: string;
  route?: LinkGraphEdgeRoute;
  metadata?: Record<string, string>;
  sourceTag?: GraphSourceTag;
}

export interface LinkGraphDocument {
  nodes: LinkGraphNode[];
  edges: LinkGraphEdge[];
  patch?: GraphPatch | null;
  nodeCount?: number;
  edgeCount?: number;
  truncated?: boolean;
}

export interface GraphViewPresentation {
  target: GraphPresentationTarget;
  lanes: GraphPresentationLane[];
  hiddenBuckets: GraphHiddenBucket[];
  controls: GraphPresentationControls;
}

export interface GraphPresentationTarget {
  nodeId: string | null;
  title: string;
  subtitle: string;
  location?: string | null;
}

export interface GraphPresentationLane {
  id: string;
  label: string;
  axis: "COLUMN" | "ROW" | "ZONE";
  order: number;
  role: string;
}

export interface GraphHiddenBucket {
  id: string;
  label: string;
  count: number;
  nodeIds: string[];
  edgeIds: string[];
}

export interface GraphPresentationControls {
  primaryScope: string;
  availableScopes: string[];
  searchable: boolean;
  expandable: boolean;
}

export interface FactGraphSummary {
  anchorTitle?: string | null;
  visibleNodeCount: number;
  fullNodeCount: number;
  hiddenNodeCount?: number;
  hiddenEdgeCount?: number;
  truncated?: boolean;
}

export interface FactGraphViewDocument {
  visibleGraph: LinkGraphDocument;
  fullGraph: LinkGraphDocument;
  anchorNodeId?: string | null;
  projectionIndex?: GraphProjectionIndex;
  summary: FactGraphSummary;
  presentation: GraphViewPresentation;
}

export interface FlowchartSummary {
  nodeCount: number;
  branchCount: number;
  exceptionPathCount: number;
  fullNodeCount?: number;
  fullEdgeCount?: number;
  hiddenNodeCount?: number;
  hiddenEdgeCount?: number;
  truncated?: boolean;
  incompleteNodeCount?: number;
  incompleteEdgeCount?: number;
  semanticallyIncomplete?: boolean;
  syntheticEdgeCount?: number;
  syntheticEntryEdgeCount?: number;
}

export interface FlowchartViewDocument {
  visibleGraph: LinkGraphDocument;
  fullGraph: LinkGraphDocument;
  anchorNodeId?: string | null;
  projectionIndex?: GraphProjectionIndex;
  summary: FlowchartSummary;
}

export interface ResourceRelationSummary {
  visibleNodeCount: number;
  relationCount: number;
  resourceCount: number;
  fallbackReason: "NONE" | "NO_RESOURCE_UNITS" | "NO_BINDING_RELATIONS" | string;
  laneCounts: Record<string, number>;
}

export interface ResourceRelationViewDocument {
  visibleGraph: LinkGraphDocument;
  fullGraph: LinkGraphDocument;
  anchorNodeId?: string | null;
  projectionIndex?: GraphProjectionIndex;
  summary: ResourceRelationSummary;
}

export interface IndexedGraphSummary {
  view: "ARCHITECTURE" | "CLASS_DIAGRAM" | "REVIEW" | string;
  anchorKind?: string | null;
  anchorNodeId?: string | null;
  anchorTitle?: string | null;
  anchorQualifiedName?: string | null;
  scopeKind: string;
  scopeLabel: string;
  relationKinds: string[];
  depth: number;
  projectNodeCount: number;
  projectClassCount: number;
  externalNodeCount: number;
  jdkNodeCount: number;
  scopedNodeCount: number;
  visibleNodeCount: number;
  hiddenNodeCount: number;
  hiddenEdgeCount: number;
  candidateNodeCount: number;
  candidateEdgeCount: number;
  truncated: boolean;
  completeness: string;
  cacheState: string;
  includeExternalLibraries?: boolean;
  includeJdk?: boolean;
  projectSourceNodeCount?: number;
  externalLibraryNodeCount?: number;
  resourceNodeCount?: number;
  aggregateNodeCount?: number;
  projectLayerCounts?: IndexedGraphLayerCounts | null;
  visibleLayerCounts?: IndexedGraphLayerCounts | null;
  scopedLayerCounts?: IndexedGraphLayerCounts | null;
  candidateLayerCounts?: IndexedGraphLayerCounts | null;
  hiddenLayerCounts?: IndexedGraphLayerCounts | null;
  collapsedLayerCounts?: IndexedGraphLayerCounts | null;
}

export interface IndexedGraphLayerCounts {
  projectSource?: number;
  externalLibrary?: number;
  jdk?: number;
  resource?: number;
  aggregate?: number;
}

export type IndexedGraphView = "ARCHITECTURE" | "CLASS_DIAGRAM" | "REVIEW";
export type IndexedGraphRequestStates = Partial<Record<IndexedGraphView, AsyncRequestState>>;

export interface IndexedGraphViewportOptions {
  maxVisibleNodes?: number | null;
  maxVisibleEdges?: number | null;
}

export interface IndexedClassDiagramOptions {
  neighborhoodLimit: number;
  memberLimit: number;
}

export interface IndexedReviewGraphOptions {
  maxChangedNodes: number;
  maxRelatedTestNodes: number;
  maxUpstreamNodes: number;
  maxDownstreamNodes: number;
}

export interface ArchitectureGraphSummary {
  moduleCount: number;
  packageCount: number;
  serviceCount: number;
  componentCount?: number;
  resourceCount: number;
  layerCount: number;
  libraryCount?: number;
  jdkCount?: number;
  relationCount: number;
  classCount: number;
  relationshipNodeCount?: number;
  inventoryOnlyNodeCount?: number;
  unconnectedPackageCount?: number;
  unconnectedComponentCount?: number;
  unconnectedServiceBoundaryCount?: number;
  unconnectedResourceCount?: number;
  externalDependencyGroupCount?: number;
  jdkGroupCount?: number;
  truncated?: boolean;
  hiddenNodeCount?: number;
  hiddenEdgeCount?: number;
  indexed?: IndexedGraphSummary | null;
}

export interface ArchitectureGraphViewDocument {
  visibleGraph: LinkGraphDocument;
  fullGraph: LinkGraphDocument;
  anchorNodeId?: string | null;
  projectionIndex?: GraphProjectionIndex;
  summary: ArchitectureGraphSummary;
  presentation: GraphViewPresentation;
}

export interface ClassDiagramSummary {
  classCount: number;
  fieldCount?: number;
  interfaceCount: number;
  enumCount: number;
  annotationCount: number;
  recordCount: number;
  objectCount: number;
  relationCount: number;
  spiProviderCount?: number;
  reflectionRelationCount?: number;
  relationCompleteness?: "COMPLETE" | "STRUCTURE_ONLY" | string;
  scopeTypeCount?: number;
  projectTypeCount?: number;
  projectClassCount?: number;
  scopeBasis?: "CLASS_NEIGHBORHOOD" | "EXPLICIT_SCOPE" | string;
  anchorTypeNodeId?: string | null;
  anchorTypeTitle?: string | null;
  anchorTypeQualifiedName?: string | null;
  neighborhoodLimit?: number;
  memberLimit?: number;
  neighborhoodCandidateTypeCount?: number;
  neighborhoodTruncated?: boolean;
  truncated?: boolean;
  hiddenNodeCount?: number;
  hiddenEdgeCount?: number;
  indexed?: IndexedGraphSummary | null;
}

export interface ClassDiagramViewDocument {
  visibleGraph: LinkGraphDocument;
  fullGraph: LinkGraphDocument;
  anchorNodeId?: string | null;
  projectionIndex?: GraphProjectionIndex;
  summary: ClassDiagramSummary;
  presentation: GraphViewPresentation;
}

export interface ReviewGraphSummary {
  changedSymbolCount: number;
  upstreamCount: number;
  downstreamCount: number;
  relatedTestCount: number;
  affectedPackageCount: number;
  affectedModuleCount: number;
  evidenceRefCount: number;
  truncated?: boolean;
  hiddenNodeCount?: number;
  hiddenEdgeCount?: number;
  selectedDiffItemIds?: string[];
  maxChangedNodes?: number;
  maxUpstreamNodes?: number;
  maxDownstreamNodes?: number;
  maxRelatedTestNodes?: number;
  indexed?: IndexedGraphSummary | null;
}

export interface ReviewGraphChangedFile {
  oldPath?: string | null;
  newPath?: string | null;
  changeKind: string;
  hunkCount: number;
  similarity?: number | null;
}

export interface ReviewGraphChangedHunk {
  filePath: string;
  oldFilePath?: string | null;
  newFilePath?: string | null;
  changeKind: string;
  header: string;
  oldStartLine?: number | null;
  oldLineCount?: number | null;
  newStartLine?: number | null;
  newLineCount?: number | null;
  matchedSymbolIds: string[];
}

export interface ReviewGraphChangedSymbolDetail {
  symbolId: string;
  qualifiedName: string;
  filePath?: string | null;
  startLine?: number | null;
  endLine?: number | null;
  changeKind: string;
  blastRadiusIncomplete: boolean;
  unavailableReason?: string | null;
}

export interface ReviewGraphRelatedTestDetail {
  symbolId: string;
  qualifiedName: string;
  reason: string;
  filePath?: string | null;
  startLine?: number | null;
}

export interface ReviewGraphEvidenceSnippet {
  title: string;
  kind: string;
  filePath?: string | null;
  startLine?: number | null;
  endLine?: number | null;
  snippet?: string | null;
  unavailableReason?: string | null;
}

export interface ReviewGraphViewDocument {
  visibleGraph: LinkGraphDocument;
  fullGraph: LinkGraphDocument;
  anchorNodeId?: string | null;
  projectionIndex?: GraphProjectionIndex;
  summary: ReviewGraphSummary;
  changedFiles?: ReviewGraphChangedFile[];
  changedHunks?: ReviewGraphChangedHunk[];
  unmatchedHunks?: ReviewGraphChangedHunk[];
  baselineOnlySymbols?: ReviewGraphChangedSymbolDetail[];
  relatedTests?: ReviewGraphRelatedTestDetail[];
  affectedPackages?: string[];
  affectedModules?: string[];
  evidenceSnippets?: ReviewGraphEvidenceSnippet[];
}

export type GraphProjectionMappingKind =
  | "EXACT"
  | "MERGED_ALIAS"
  | "PATH_ALIAS"
  | "INDEXED_READONLY"
  | "SYNTHETIC_READONLY"
  | "OVERFLOW_READONLY";

export type GraphEditCommandKind =
  | "ADD_NODE"
  | "UPDATE_NODE"
  | "DELETE_NODE"
  | "DELETE_NODE_SUBTREE"
  | "CONNECT_NODES"
  | "DELETE_EDGE"
  | "INSERT_NODE_INTO_EDGE";

export interface GraphProjectionNodeMapping {
  projectedNodeId: string;
  mappingKind: GraphProjectionMappingKind;
  canonicalNodeIds: string[];
  editableCommandKinds: GraphEditCommandKind[];
}

export interface GraphProjectionEdgeMapping {
  projectedEdgeId: string;
  mappingKind: GraphProjectionMappingKind;
  canonicalEdgeIds: string[];
  canonicalPathNodeIds: string[];
  editableCommandKinds: GraphEditCommandKind[];
}

export interface GraphProjectionIndex {
  nodeMappings: Record<string, GraphProjectionNodeMapping>;
  edgeMappings: Record<string, GraphProjectionEdgeMapping>;
}

export interface GraphPatchOperation {
  id: string;
  action: GraphPatchAction;
  elementKind: GraphDiffElementKind;
  elementId: string;
  title?: string | null;
  summary?: string | null;
  node?: LinkGraphNode | null;
  edge?: LinkGraphEdge | null;
  metadata?: Record<string, string>;
}

export interface GraphPatch {
  summary?: string | null;
  operations: GraphPatchOperation[];
  addedNodeIds: string[];
  removedNodeIds: string[];
  addedEdgeIds: string[];
  removedEdgeIds: string[];
}

export type GraphEditOperation =
  | {
      type: "UPSERT_NODE";
      node: LinkGraphNode;
    }
  | {
      type: "REMOVE_NODE";
      nodeId: string;
    }
  | {
      type: "UPSERT_EDGE";
      edge: LinkGraphEdge;
    }
  | {
      type: "REMOVE_EDGE";
      edgeId: string;
    };

export interface GraphEditScript {
  sceneId: LinkGraphSceneId;
  baseWorkspaceRevision: number;
  operations: GraphEditOperation[];
}

export interface ResultEvidenceReference {
  nodeId?: string | null;
  filePath?: string | null;
  startLine?: number | null;
  endLine?: number | null;
}

export interface ResultEvidenceFinding {
  id: string;
  claim: string;
  evidenceLevel: ResultEvidenceLevel;
  references: ResultEvidenceReference[];
}

export interface GraphPatchResult {
  source: LlmResultSource;
  question: string;
  requestedMode?: QaMode | null;
  effectiveMode?: QaMode | null;
  answer: string;
  promptPreview: string | null;
  promptPreviewArtifactId?: string | null;
  patch?: GraphPatch | null;
  findings: ResultEvidenceFinding[];
  candidateChanges: CandidateDraftChange[];
  newCandidateChanges: CandidateDraftChange[];
  investigationThreads?: InvestigationThread[];
  latestTurnOutcome?: InvestigationTurnOutcome | null;
  recentTurnOutcomes?: InvestigationTurnOutcome[];
  sourceContext?: SourceSnippetContext[];
  evidenceTrace?: EvidenceTraceEntry[];
  qaSession?: QaConversationSession | null;
  warnings: string[];
}

export interface GraphPresentationContext {
  graph: LinkGraphDocument;
  fullGraph?: LinkGraphDocument | null;
  anchorNodeId?: string | null;
  selectedNodeIds?: string[];
  hiddenCurrentMethodNodeCount: number;
  hiddenCrossMethodNodeCount: number;
}

export interface SourceSnippetContext {
  nodeId: string;
  filePath: string;
  startOffset?: number | null;
  endOffset?: number | null;
  startLine?: number | null;
  endLine?: number | null;
  snippet?: string | null;
  origin?: string | null;
  decompiled?: boolean | null;
  virtualFileUrl?: string | null;
}

export interface EvidenceTraceEntry {
  nodeId: string;
  resolvedNodeId?: string | null;
  filePath: string;
  reason: string;
  startLine?: number | null;
  endLine?: number | null;
  includedInPrompt: boolean;
  mappingTrace?: string[];
}

export interface EditScope {
  scopeId: string;
  targetNodeId: string;
  filePath: string;
  language: string;
  symbolKind: string;
  symbolSignature?: string | null;
  startOffset?: number | null;
  endOffset?: number | null;
  startLine?: number | null;
  endLine?: number | null;
  allowedChangeKinds: string[];
  supportingFindingIds: string[];
}

export interface CodeEditOperation {
  operationId: string;
  filePath: string;
  scopeId?: string | null;
  kind: "REPLACE_METHOD_BODY" | "REPLACE_METHOD_BLOCK" | "INSERT_METHOD_AFTER" | "ADD_IMPORT" | "ADD_FIELD" | "CREATE_FILE";
  payload: string;
  warnings: string[];
}

export interface GraphBeautificationContext {
  presentationContext: GraphPresentationContext;
  sourceContext: SourceSnippetContext[];
  userGoal: string;
  preferredStyle?: string | null;
  explanationFocus?: string | null;
  followUp?: GraphBeautificationFollowUpRequest | null;
}

export interface GraphBeautificationFollowUpRequest {
  stepId: string;
  stepTitle: string;
  question: string;
}

export interface GraphBeautificationRequest {
  goal?: string;
  preferredStyle?: string | null;
  explanationFocus?: string | null;
  focusNodeId?: string | null;
  granularity?: StepGranularity;
  followUp?: GraphBeautificationFollowUpRequest | null;
}

export interface GraphBeautificationStep {
  stepId: string;
  title: string;
  granularity: StepGranularity;
  kind: StepKind;
  description: string;
  primaryNodeId?: string | null;
  codeSnippet?: string | null;
  evidence: ResultEvidenceFinding[];
  followUpQuestions: string[];
  downstreamTargets: string[];
}

export interface GraphBeautificationResult {
  source: LlmResultSource;
  granularity: StepGranularity;
  steps: GraphBeautificationStep[];
  promptPreview: string | null;
  promptPreviewArtifactId?: string | null;
  warnings: string[];
}

export interface CandidateDraftChange {
  changeId: string;
  status: CandidateDraftChangeStatus;
  title: string;
  targetStepIds: string[];
  targetNodeIds: string[];
  beforeState?: string | null;
  afterState?: string | null;
  reason: string;
  impactSummary: string;
  claimType?: DraftClaimType | null;
  evidence?: ResultEvidenceFinding[];
  editScopes?: EditScope[];
  patchIntent?: CandidatePatchIntent | null;
  graphPatch?: GraphPatch | null;
}

export type CandidatePatchIntentMode =
  | "UPDATE_EXISTING_NODE"
  | "INSERT_NEW_DECISION"
  | "INSERT_NEW_ACTION"
  | "ANNOTATION_ONLY";

export interface CandidatePatchIntent {
  mode: CandidatePatchIntentMode;
  targetNodeId?: string | null;
  attachEdgeId?: string | null;
  falseBranchTargetNodeId?: string | null;
}

export interface QaConversationMessage {
  messageId: string;
  role: QaMessageRole;
  content: string;
  focusTargetId?: string | null;
  turnOutcomeId?: string | null;
}

export interface QaConversationSession {
  sessionId: string;
  scopeKey: string;
  messages: QaConversationMessage[];
  candidateChanges: CandidateDraftChange[];
  investigationThreads?: InvestigationThread[];
  turnOutcomes?: InvestigationTurnOutcome[];
  focusTargetId?: string | null;
}

export interface InvestigationEvidenceDelta {
  addedNodeIds: string[];
  addedFilePaths: string[];
  previousStrongestEvidenceLevel?: ResultEvidenceLevel | null;
  currentStrongestEvidenceLevel?: ResultEvidenceLevel | null;
  hitRecommendedQuestion: boolean;
}

export interface InvestigationTurnOutcome {
  outcomeId: string;
  threadId: string;
  status: InvestigationTurnOutcomeStatus;
  summary: string;
  detail: string;
  candidateChangeId?: string | null;
  blockedReason?: string | null;
  evidenceDelta: InvestigationEvidenceDelta;
  observedNodeIds: string[];
  observedFilePaths: string[];
  strongestEvidenceLevel?: ResultEvidenceLevel | null;
}

export interface InvestigationThread {
  threadId: string;
  status: InvestigationThreadStatus;
  title: string;
  targetStepIds: string[];
  targetNodeIds: string[];
  summary: string;
  evidenceGap: string;
  recommendedQuestion: string;
  claimType?: DraftClaimType | null;
  evidence: ResultEvidenceFinding[];
  latestTurnOutcomeId?: string | null;
  resolution?: RiskResolution | null;
}

export interface RiskResolution {
  threadId: string;
  status: RiskResolutionStatus;
  note: string;
}

export interface ReplayableQaRequest {
  requestId: string;
  kind: QaRequestKind;
  question: string;
  mode?: QaMode;
  selectedNodeIds: string[];
  sourceThreadId?: string | null;
  baseSessionId?: string | null;
}

export interface QaRequestRecoveryState {
  lastSubmittedRequest?: ReplayableQaRequest | null;
  lastFailedRequest?: ReplayableQaRequest | null;
}

export type DraftValidationStatus = "EMPTY" | "REVIEW_REQUIRED" | "READY";

export interface DraftValidationState {
  status: DraftValidationStatus;
  message: string;
  detailMessage?: string | null;
  unresolvedThreadIds: string[];
  unresolvedThreads: InvestigationThread[];
}

export interface StageEligibilityDecision {
  target: StageEligibilityTarget;
  stageLabel: string;
  allowed: boolean;
  message: string;
  detailMessage?: string | null;
  blockingThreadIds: string[];
  unresolvedThreadIds: string[];
}

export interface DraftWorkbenchEntry {
  entryId: string;
  kind: DraftEntryKind;
  title: string;
  sourceChangeId?: string | null;
  targetStepIds: string[];
  targetNodeIds: string[];
  beforeState?: string | null;
  afterState?: string | null;
  reason: string;
  impactSummary: string;
  claimType?: DraftClaimType | null;
  evidence: ResultEvidenceFinding[];
  editScopes?: EditScope[];
  patchIntent?: CandidatePatchIntent | null;
  graphPatch?: GraphPatch | null;
}

export interface DraftWorkbenchState {
  draftChanges: DraftWorkbenchEntry[];
  draftNotes: DraftWorkbenchEntry[];
}

export interface ExplanationWorkbenchState {
  result: GraphBeautificationResult | null;
  requestState: AsyncRequestState;
  selectedStepId?: string | null;
  granularity: StepGranularity;
  historyDepth: number;
  canReturnToPrevious: boolean;
  historyTrail: string[];
  currentSessionLabel?: string | null;
  previousSessionLabel?: string | null;
}

export interface QaWorkbenchState {
  result: GraphPatchResult | null;
  requestState: AsyncRequestState;
  qaRequestRecoveryState?: QaRequestRecoveryState | null;
  selectedChangeId?: string | null;
  selectedThreadId?: string | null;
  questionDraft: string;
  selectedMode?: QaMode;
  scopeLabel?: string | null;
}

export interface DraftWorkbenchViewState {
  draftState: DraftWorkbenchState;
  compareMode: "after" | "compare";
  selectedEntryId?: string | null;
}

export interface GenerationPlanDiscussionMessage {
  messageId: string;
  role: QaMessageRole;
  content: string;
  focusItemId?: string | null;
}

export interface GenerationPlanDiscussionSession {
  sessionId: string;
  messages: GenerationPlanDiscussionMessage[];
  focusItemId?: string | null;
  promptPreview?: string | null;
  promptPreviewArtifactId?: string | null;
}

export interface DraftImplementationSuggestionState {
  status: "MISSING" | "RUNNING" | "FRESH" | "STALE" | "FAILED";
  summary?: string | null;
  items: GenerationPlanItem[];
  source?: GenerationPlanSource | null;
  warnings: string[];
  promptPreview?: string | null;
  promptPreviewArtifactId?: string | null;
  draftVersion?: number | null;
  generationPlanDraftVersion?: number | null;
}

export interface DraftCompareSummary {
  scopeNodeCount: number;
  visibleNodeCount: number;
  visibleEdgeCount: number;
  hiddenNodeCount: number;
  hiddenEdgeCount: number;
}

export interface DraftCompareProjection {
  entryId: string;
  entryTitle: string;
  compareGraph: LinkGraphDocument;
  nodeStatuses: Record<string, DraftCompareStatus>;
  edgeStatuses: Record<string, DraftCompareStatus>;
  summary: DraftCompareSummary;
}

export type WorkbenchSectionId =
  | "explanation.step-list"
  | "explanation.step-detail"
  | "qa.request-status"
  | "qa.thread"
  | "qa.composer"
  | "qa.candidate-changes"
  | "qa.investigation-threads"
  | "draft.change-list"
  | "draft.note-list"
  | "draft.detail";

export type WorkbenchSectionPreferences = Partial<Record<WorkbenchSectionId, boolean>>;

export interface SyncPreviewItem {
  id: string;
  title: string;
  description: string;
  risk: "LOW" | "MEDIUM" | "HIGH";
}

export type GenerationPlanSource = "DISABLED" | "LOCAL_RULE" | "REMOTE";

export interface GenerationPlanItem {
  id: string;
  title: string;
  description: string;
  risk: "LOW" | "MEDIUM" | "HIGH";
  targetPath?: string | null;
}

export interface GenerationPlan {
  source: GenerationPlanSource;
  summary: string;
  warnings: string[];
  promptPreview: string | null;
  promptPreviewArtifactId?: string | null;
  items: GenerationPlanItem[];
}

export interface GeneratedCodeDraft {
  id: string;
  sourceNodeId: string;
  title: string;
  targetPath: string;
  content: string | null;
  contentArtifactId?: string | null;
  editOperations?: CodeEditOperation[];
  editScopes?: EditScope[];
  preparedEdits?: PreparedCodeEdit[];
  warnings: string[];
}

export interface PreparedCodeEdit {
  operationId: string;
  filePath: string;
  scopeId?: string | null;
  kind: CodeEditOperation["kind"];
  targetSymbolSignature?: string | null;
  startOffset: number;
  endOffset: number;
  beforeText: string;
  afterText: string;
  warnings: string[];
}

export interface GeneratedCodeDraftWriteReport {
  writtenFiles: string[];
  skippedFiles: string[];
  warnings: string[];
}

export interface DraftPatchApplyResult {
  summary: string;
  appliedOperationCount: number;
  appliedNodeIds: string[];
  appliedEdgeIds: string[];
  focusNodeId?: string | null;
  appliedTargets: string[];
}

export interface DiffItem {
  id: string;
  title: string;
  status: DiffStatus;
  description: string;
}

export type MermaidIssueCategory = "SYNTAX" | "STRUCTURE" | "SEMANTIC" | "BINDING";

export interface MermaidIssue {
  category: MermaidIssueCategory;
  code: string;
  message: string;
  line?: number | null;
  nodeId?: string | null;
  edgeId?: string | null;
}

export type OperationFeedbackLevel = "INFO" | "SUCCESS" | "WARNING" | "ERROR";

export interface OperationFeedback {
  level: OperationFeedbackLevel;
  message: string;
}

export type SourceNavigationPhase = "IDLE" | "RUNNING" | "SUCCEEDED" | "NOT_FOUND" | "FAILED";
export type SourceNavigationResult = "OPENED";

export interface SourceNavigationState {
  nodeId?: string | null;
  phase: SourceNavigationPhase;
  result?: SourceNavigationResult | null;
  targetPath?: string | null;
  line?: number | null;
  column?: number | null;
  errorMessage?: string | null;
}

export type AsyncRequestPhase = "IDLE" | "RUNNING" | "SUCCEEDED" | "FAILED" | "TIMED_OUT";
export type AsyncRequestExecutionMode = "DISABLED" | "LOCAL_RULE" | "REMOTE_READY" | "REMOTE_FALLBACK";

export interface AsyncRequestState {
  phase: AsyncRequestPhase;
  requestId?: number | null;
  scene?: string | null;
  executionMode?: AsyncRequestExecutionMode | null;
  statusMessage?: string | null;
  errorMessage?: string | null;
  detailMessage?: string | null;
  startedAtEpochMillis?: number | null;
  finishedAtEpochMillis?: number | null;
  streaming?: boolean;
  fallbackUsed?: boolean;
  streamPhase?: string | null;
  previewText?: string | null;
  previewUpdatedAtEpochMillis?: number | null;
  finalizingStructuredResult?: boolean;
  providerLabel?: string | null;
  model?: string | null;
  endpointSummary?: string | null;
  promptPreviewAvailable?: boolean;
  requestedMode?: QaMode | null;
  effectiveMode?: QaMode | null;
}

export interface GraphSurfaceExperimentFlags {
  onlyRenderVisibleElements?: boolean;
  dragShielding?: boolean;
}

export interface LinkGraphBootstrapState {
  analysisDisplayMode?: AnalysisDisplayMode | null;
  currentSceneId: LinkGraphSceneId;
  sceneStates: Record<LinkGraphSceneId, LinkGraphSceneState>;
  workspaceGraph: LinkGraphDocument;
  workspaceBaseGraph: LinkGraphDocument;
  semanticFactGraph: LinkGraphDocument;
  designBaselineGraph?: LinkGraphDocument | null;
  factGraphView?: FactGraphViewDocument | null;
  flowchartView?: FlowchartViewDocument | null;
  resourceRelationView?: ResourceRelationViewDocument | null;
  architectureGraphView?: ArchitectureGraphViewDocument | null;
  classDiagramView?: ClassDiagramViewDocument | null;
  reviewGraphView?: ReviewGraphViewDocument | null;
  indexedGraphRequestStates?: IndexedGraphRequestStates | null;
  semanticRevision?: number;
  workspaceRevision?: number;
  snapshotRevision?: number;
  draftPatchPreview?: GraphPatch | null;
  draftWorkbenchState?: DraftWorkbenchState | null;
  canUndoDraftPatchApply?: boolean;
  lastAppliedDraftPatchSummary?: string | null;
  qaResult?: GraphPatchResult | null;
  qaRequestState?: AsyncRequestState | null;
  qaRequestRecoveryState?: QaRequestRecoveryState | null;
  diffReviewResult?: GraphPatchResult | null;
  diffReviewRequestState?: AsyncRequestState | null;
  graphBeautificationResult?: GraphBeautificationResult | null;
  graphBeautificationRequestState?: AsyncRequestState | null;
  mermaidIssues: MermaidIssue[];
  diffItems: DiffItem[];
  syncPreviewItems: SyncPreviewItem[];
  draftVersion?: number;
  generationPlan?: GenerationPlan | null;
  generationPlanDraftVersion?: number | null;
  generationPlanRequestState?: AsyncRequestState | null;
  draftValidationState?: DraftValidationState | null;
  generationPlanDiscussionSession?: GenerationPlanDiscussionSession | null;
  generationPlanDiscussionRequestState?: AsyncRequestState | null;
  generatedCodeDrafts?: GeneratedCodeDraft[];
  generatedCodeDraftVersion?: number | null;
  generatedCodeDraftWarnings?: string[];
  generatedCodeDraftSource?: LlmResultSource | null;
  generatedCodeDraftPromptPreview?: string | null;
  generatedCodeDraftPromptPreviewArtifactId?: string | null;
  generatedCodeDraftWriteReport?: GeneratedCodeDraftWriteReport | null;
  codeDraftRequestState?: AsyncRequestState | null;
  codeEligibilityDecision?: StageEligibilityDecision | null;
  lastDraftPatchApplyResult?: DraftPatchApplyResult | null;
  sourceNavigationState?: SourceNavigationState | null;
  operationFeedback?: OperationFeedback | null;
  graphSurfaceExperiments?: GraphSurfaceExperimentFlags | null;
  workbenchSectionPreferences?: WorkbenchSectionPreferences | null;
  artifactContents?: Record<string, string>;
  lastMessageType?: string | null;
  lastGraphSource?: string | null;
}

export interface LinkGraphSnapshotEnvelope {
  sessionId: string;
  revision: number;
  state: LinkGraphBootstrapState;
  transportType?: LinkGraphIncrementalTransportEnvelope["type"];
}

export interface LinkGraphTransportEnvelopeBase {
  sessionId: string;
  revision: number;
}

export interface LinkGraphArtifactSliceEnvelope extends LinkGraphTransportEnvelopeBase {
  type: "ARTIFACT_SLICE";
  state: Partial<LinkGraphBootstrapState>;
}

export type LinkGraphIncrementalTransportEnvelope = LinkGraphArtifactSliceEnvelope;
