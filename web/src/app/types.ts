export type Certainty = "PROVEN" | "RULE_INFERRED" | "LLM_SUGGESTED";

export type BindingStatus =
  | "BOUND"
  | "DESIGN_ONLY"
  | "GENERATABLE"
  | "PARTIALLY_SYNCED"
  | "CONFLICTED";

export type DiffStatus = "MATCHED" | "ONLY_IN_CODE" | "ONLY_IN_MERMAID" | "MODIFIED";
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
export type LlmResultSource = "DISABLED" | "MOCK" | "REMOTE";
export type ResultEvidenceLevel = "DIRECT_SOURCE" | "DIRECT_GRAPH" | "CALLSITE_ONLY" | "NOT_OBSERVED";
export type DraftClaimType = "CODE_FACT" | "RISK_HINT" | "EXPLANATION_NOTE" | "STRUCTURAL_SUGGESTION";
export type DraftPatchPreviewSource = "AUDIT" | "DIFF_REVIEW" | "LAST_APPLIED";
export type AnalysisDisplayMode = "FACT_GRAPH" | "FLOWCHART" | "RESOURCE_RELATION_VIEW";
export type StepGranularity = "BUSINESS" | "METHOD_CALL" | "CODE_SEMANTIC";
export type StepKind = "BUSINESS_ACTION" | "METHOD_CALL" | "CONDITION" | "RETURN" | "RESOURCE_INTERACTION";
export type CandidateDraftChangeStatus = "PENDING_CONFIRMATION" | "CONFIRMED" | "REJECTED" | "SUPERSEDED";
export type AuditInvestigationLeadStatus = "OPEN" | "PROMOTED" | "DISMISSED" | "SUPERSEDED";
export type DraftEntryKind = "CHANGE" | "NOTE";
export type AuditMessageRole = "USER" | "ASSISTANT";

export type NodeType =
  | "METHOD"
  | "FLOW_SCOPE"
  | "FLOW_ACTION"
  | "TERMINAL"
  | "MERGE"
  | "CLASS"
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

export interface FactGraphSummary {
  anchorTitle?: string | null;
  visibleNodeCount: number;
  fullNodeCount: number;
}

export interface FactGraphViewDocument {
  visibleGraph: LinkGraphDocument;
  fullGraph: LinkGraphDocument;
  anchorNodeId?: string | null;
  summary: FactGraphSummary;
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
  summary: FlowchartSummary;
}

export interface ResourceRelationSummary {
  visibleNodeCount: number;
  laneCounts: Record<string, number>;
}

export interface ResourceRelationViewDocument {
  visibleGraph: LinkGraphDocument;
  fullGraph: LinkGraphDocument;
  anchorNodeId?: string | null;
  summary: ResourceRelationSummary;
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
  answer: string;
  promptPreview: string | null;
  promptPreviewArtifactId?: string | null;
  patch?: GraphPatch | null;
  findings: ResultEvidenceFinding[];
  candidateChanges: CandidateDraftChange[];
  newCandidateChanges: CandidateDraftChange[];
  investigationLeads: AuditInvestigationLead[];
  newInvestigationLeads: AuditInvestigationLead[];
  sourceContext?: SourceSnippetContext[];
  evidenceTrace?: EvidenceTraceEntry[];
  auditSession?: AuditConversationSession | null;
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
}

export interface EvidenceTraceEntry {
  nodeId: string;
  filePath: string;
  reason: string;
  startLine?: number | null;
  endLine?: number | null;
  includedInPrompt: boolean;
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
}

export interface AuditConversationMessage {
  messageId: string;
  role: AuditMessageRole;
  content: string;
  focusTargetId?: string | null;
}

export interface AuditConversationSession {
  sessionId: string;
  scopeKey: string;
  messages: AuditConversationMessage[];
  candidateChanges: CandidateDraftChange[];
  investigationLeads: AuditInvestigationLead[];
  focusTargetId?: string | null;
}

export interface AuditInvestigationLead {
  leadId: string;
  status: AuditInvestigationLeadStatus;
  title: string;
  targetStepIds: string[];
  targetNodeIds: string[];
  summary: string;
  evidenceGap: string;
  recommendedQuestion: string;
  claimType?: DraftClaimType | null;
  evidence: ResultEvidenceFinding[];
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

export interface AuditWorkbenchState {
  result: GraphPatchResult | null;
  requestState: AsyncRequestState;
  selectedChangeId?: string | null;
  selectedLeadId?: string | null;
  questionDraft: string;
  scopeLabel?: string | null;
}

export interface DraftWorkbenchViewState {
  draftState: DraftWorkbenchState;
  compareMode: "after" | "compare";
  selectedEntryId?: string | null;
}

export type WorkbenchSectionId =
  | "explanation.step-list"
  | "explanation.step-detail"
  | "audit.request-status"
  | "audit.thread"
  | "audit.composer"
  | "audit.candidate-changes"
  | "audit.investigation-leads"
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

export type GenerationPlanSource = "DISABLED" | "MOCK" | "REMOTE";

export interface GenerationPlanItem {
  id: string;
  title: string;
  description: string;
  risk: "LOW" | "MEDIUM" | "HIGH";
  targetPath?: string | null;
  editScopes?: EditScope[];
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
}

export interface GraphSurfaceExperimentFlags {
  onlyRenderVisibleElements?: boolean;
  dragShielding?: boolean;
}

export interface LinkGraphBootstrapState {
  analysisDisplayMode?: AnalysisDisplayMode | null;
  visibleGraph: LinkGraphDocument;
  workingGraph: LinkGraphDocument;
  referenceFactGraph?: LinkGraphDocument | null;
  designBaselineGraph?: LinkGraphDocument | null;
  factGraphView?: FactGraphViewDocument | null;
  flowchartView?: FlowchartViewDocument | null;
  resourceRelationView?: ResourceRelationViewDocument | null;
  layoutState?: LinkGraphLayoutState | null;
  semanticRevision?: number;
  layoutRevision?: number;
  snapshotRevision?: number;
  draftPatchPreview?: GraphPatch | null;
  draftWorkbenchState?: DraftWorkbenchState | null;
  canUndoDraftPatchApply?: boolean;
  lastAppliedDraftPatchSummary?: string | null;
  auditResult?: GraphPatchResult | null;
  auditRequestState?: AsyncRequestState | null;
  diffReviewResult?: GraphPatchResult | null;
  diffReviewRequestState?: AsyncRequestState | null;
  graphBeautificationResult?: GraphBeautificationResult | null;
  graphBeautificationRequestState?: AsyncRequestState | null;
  mermaidIssues: MermaidIssue[];
  diffItems: DiffItem[];
  syncPreviewItems: SyncPreviewItem[];
  generationPlan?: GenerationPlan | null;
  generationPlanRequestState?: AsyncRequestState | null;
  generatedCodeDrafts?: GeneratedCodeDraft[];
  generatedCodeDraftWarnings?: string[];
  generatedCodeDraftSource?: LlmResultSource | null;
  generatedCodeDraftPromptPreview?: string | null;
  generatedCodeDraftPromptPreviewArtifactId?: string | null;
  generatedCodeDraftWriteReport?: GeneratedCodeDraftWriteReport | null;
  codeDraftRequestState?: AsyncRequestState | null;
  lastDraftPatchApplyResult?: DraftPatchApplyResult | null;
  selectedNodeId?: string | null;
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
  transportType?: LinkGraphIncrementalTransportEnvelope["type"] | "LEGACY_BOOTSTRAP";
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
