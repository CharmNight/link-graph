package com.charmnight.linkgraph.llm

/**
 * 集中维护远程结构化响应场景使用的 machine-readable JSON Schema。
 * 这些 schema 会直接下发给支持原生结构化输出的 provider，而不是只作为 prompt 文本展示。
 */
internal object LlmStructuredSchemas {
    private const val NULLABLE_STRING_SCHEMA = """
{
  "type": ["string", "null"]
}
"""

    private const val NULLABLE_INTEGER_SCHEMA = """
{
  "type": ["integer", "null"]
}
"""

    private const val EMPTY_OBJECT_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {},
  "required": []
}
"""

    private const val STRING_ARRAY_SCHEMA = """
{
  "type": "array",
  "items": {
    "type": "string"
  }
}
"""

    private const val EVIDENCE_REFERENCE_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "nodeId": $NULLABLE_STRING_SCHEMA,
    "filePath": $NULLABLE_STRING_SCHEMA,
    "startLine": $NULLABLE_INTEGER_SCHEMA,
    "endLine": $NULLABLE_INTEGER_SCHEMA
  },
  "required": ["nodeId", "filePath", "startLine", "endLine"]
}
"""

    private const val EVIDENCE_FINDING_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "id": { "type": "string" },
    "claim": { "type": "string" },
    "evidenceLevel": { "type": "string" },
    "references": {
      "type": "array",
      "items": $EVIDENCE_REFERENCE_SCHEMA
    }
  },
  "required": ["id", "claim", "evidenceLevel", "references"]
}
"""

    private const val PATCH_INTENT_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "mode": { "type": "string" },
    "targetNodeId": $NULLABLE_STRING_SCHEMA,
    "attachEdgeId": $NULLABLE_STRING_SCHEMA,
    "falseBranchTargetNodeId": $NULLABLE_STRING_SCHEMA
  },
  "required": ["mode", "targetNodeId", "attachEdgeId", "falseBranchTargetNodeId"]
}
"""

    private const val NULLABLE_PATCH_INTENT_SCHEMA = """
{
  "type": ["object", "null"],
  "additionalProperties": false,
  "properties": {
    "mode": { "type": "string" },
    "targetNodeId": $NULLABLE_STRING_SCHEMA,
    "attachEdgeId": $NULLABLE_STRING_SCHEMA,
    "falseBranchTargetNodeId": $NULLABLE_STRING_SCHEMA
  },
  "required": ["mode", "targetNodeId", "attachEdgeId", "falseBranchTargetNodeId"]
}
"""

    private const val GRAPH_NODE_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "id": { "type": "string" },
    "type": { "type": "string" },
    "title": { "type": "string" },
    "location": $NULLABLE_STRING_SCHEMA,
    "signature": $NULLABLE_STRING_SCHEMA,
    "inputs": $STRING_ARRAY_SCHEMA,
    "outputs": $STRING_ARRAY_SCHEMA,
    "doc": $NULLABLE_STRING_SCHEMA,
    "bindingStatus": { "type": "string" },
    "certainty": { "type": "string" },
    "metadata": $EMPTY_OBJECT_SCHEMA,
    "sourceTag": { "type": "string" }
  },
  "required": [
    "id",
    "type",
    "title",
    "location",
    "signature",
    "inputs",
    "outputs",
    "doc",
    "bindingStatus",
    "certainty",
    "metadata",
    "sourceTag"
  ]
}
"""

    private const val NULLABLE_GRAPH_NODE_SCHEMA = """
{
  "type": ["object", "null"],
  "additionalProperties": false,
  "properties": {
    "id": { "type": "string" },
    "type": { "type": "string" },
    "title": { "type": "string" },
    "location": $NULLABLE_STRING_SCHEMA,
    "signature": $NULLABLE_STRING_SCHEMA,
    "inputs": $STRING_ARRAY_SCHEMA,
    "outputs": $STRING_ARRAY_SCHEMA,
    "doc": $NULLABLE_STRING_SCHEMA,
    "bindingStatus": { "type": "string" },
    "certainty": { "type": "string" },
    "metadata": $EMPTY_OBJECT_SCHEMA,
    "sourceTag": { "type": "string" }
  },
  "required": [
    "id",
    "type",
    "title",
    "location",
    "signature",
    "inputs",
    "outputs",
    "doc",
    "bindingStatus",
    "certainty",
    "metadata",
    "sourceTag"
  ]
}
"""

    private const val GRAPH_EDGE_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "id": { "type": "string" },
    "type": { "type": "string" },
    "fromNodeId": { "type": "string" },
    "toNodeId": { "type": "string" },
    "label": $NULLABLE_STRING_SCHEMA,
    "bindingStatus": { "type": "string" },
    "certainty": { "type": "string" },
    "metadata": $EMPTY_OBJECT_SCHEMA,
    "sourceTag": { "type": "string" }
  },
  "required": [
    "id",
    "type",
    "fromNodeId",
    "toNodeId",
    "label",
    "bindingStatus",
    "certainty",
    "metadata",
    "sourceTag"
  ]
}
"""

    private const val NULLABLE_GRAPH_EDGE_SCHEMA = """
{
  "type": ["object", "null"],
  "additionalProperties": false,
  "properties": {
    "id": { "type": "string" },
    "type": { "type": "string" },
    "fromNodeId": { "type": "string" },
    "toNodeId": { "type": "string" },
    "label": $NULLABLE_STRING_SCHEMA,
    "bindingStatus": { "type": "string" },
    "certainty": { "type": "string" },
    "metadata": $EMPTY_OBJECT_SCHEMA,
    "sourceTag": { "type": "string" }
  },
  "required": [
    "id",
    "type",
    "fromNodeId",
    "toNodeId",
    "label",
    "bindingStatus",
    "certainty",
    "metadata",
    "sourceTag"
  ]
}
"""

    private const val GRAPH_PATCH_OPERATION_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "id": { "type": "string" },
    "action": { "type": "string" },
    "elementKind": { "type": "string" },
    "elementId": { "type": "string" },
    "title": $NULLABLE_STRING_SCHEMA,
    "summary": $NULLABLE_STRING_SCHEMA,
    "node": $NULLABLE_GRAPH_NODE_SCHEMA,
    "edge": $NULLABLE_GRAPH_EDGE_SCHEMA,
    "metadata": $EMPTY_OBJECT_SCHEMA
  },
  "required": [
    "id",
    "action",
    "elementKind",
    "elementId",
    "title",
    "summary",
    "node",
    "edge",
    "metadata"
  ]
}
"""

    private const val GRAPH_PATCH_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "summary": $NULLABLE_STRING_SCHEMA,
    "operations": {
      "type": "array",
      "items": $GRAPH_PATCH_OPERATION_SCHEMA
    },
    "addedNodeIds": $STRING_ARRAY_SCHEMA,
    "removedNodeIds": $STRING_ARRAY_SCHEMA,
    "addedEdgeIds": $STRING_ARRAY_SCHEMA,
    "removedEdgeIds": $STRING_ARRAY_SCHEMA
  },
  "required": [
    "summary",
    "operations",
    "addedNodeIds",
    "removedNodeIds",
    "addedEdgeIds",
    "removedEdgeIds"
  ]
}
"""

    private const val NULLABLE_GRAPH_PATCH_SCHEMA = """
{
  "type": ["object", "null"],
  "additionalProperties": false,
  "properties": {
    "summary": $NULLABLE_STRING_SCHEMA,
    "operations": {
      "type": "array",
      "items": $GRAPH_PATCH_OPERATION_SCHEMA
    },
    "addedNodeIds": $STRING_ARRAY_SCHEMA,
    "removedNodeIds": $STRING_ARRAY_SCHEMA,
    "addedEdgeIds": $STRING_ARRAY_SCHEMA,
    "removedEdgeIds": $STRING_ARRAY_SCHEMA
  },
  "required": [
    "summary",
    "operations",
    "addedNodeIds",
    "removedNodeIds",
    "addedEdgeIds",
    "removedEdgeIds"
  ]
}
"""

    private const val CANDIDATE_CHANGE_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "changeId": { "type": "string" },
    "status": { "type": "string" },
    "title": { "type": "string" },
    "targetStepIds": $STRING_ARRAY_SCHEMA,
    "targetNodeIds": $STRING_ARRAY_SCHEMA,
    "beforeState": $NULLABLE_STRING_SCHEMA,
    "afterState": $NULLABLE_STRING_SCHEMA,
    "reason": { "type": "string" },
    "impactSummary": { "type": "string" },
    "claimType": $NULLABLE_STRING_SCHEMA,
    "supportingFindingIds": $STRING_ARRAY_SCHEMA,
    "evidence": {
      "type": "array",
      "items": $EVIDENCE_FINDING_SCHEMA
    },
    "patchIntent": $NULLABLE_PATCH_INTENT_SCHEMA,
    "graphPatch": $NULLABLE_GRAPH_PATCH_SCHEMA
  },
  "required": [
    "changeId",
    "status",
    "title",
    "targetStepIds",
    "targetNodeIds",
    "beforeState",
    "afterState",
    "reason",
    "impactSummary",
    "claimType",
    "supportingFindingIds",
    "evidence",
    "patchIntent",
    "graphPatch"
  ]
}
"""

    private const val INVESTIGATION_THREAD_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "threadId": { "type": "string" },
    "status": { "type": "string" },
    "title": { "type": "string" },
    "targetStepIds": $STRING_ARRAY_SCHEMA,
    "targetNodeIds": $STRING_ARRAY_SCHEMA,
    "summary": { "type": "string" },
    "evidenceGap": { "type": "string" },
    "recommendedQuestion": { "type": "string" },
    "claimType": $NULLABLE_STRING_SCHEMA,
    "supportingFindingIds": $STRING_ARRAY_SCHEMA,
    "evidence": {
      "type": "array",
      "items": $EVIDENCE_FINDING_SCHEMA
    }
  },
  "required": [
    "threadId",
    "status",
    "title",
    "targetStepIds",
    "targetNodeIds",
    "summary",
    "evidenceGap",
    "recommendedQuestion",
    "claimType",
    "supportingFindingIds",
    "evidence"
  ]
}
"""

    private const val CODE_EDIT_OPERATION_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "operationId": { "type": "string" },
    "filePath": { "type": "string" },
    "scopeId": $NULLABLE_STRING_SCHEMA,
    "kind": { "type": "string" },
    "payload": { "type": "string" },
    "warnings": $STRING_ARRAY_SCHEMA
  },
  "required": ["operationId", "filePath", "scopeId", "kind", "payload", "warnings"]
}
"""

    private const val EDIT_SCOPE_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "scopeId": { "type": "string" },
    "targetNodeId": { "type": "string" },
    "filePath": { "type": "string" },
    "language": { "type": "string" },
    "symbolKind": { "type": "string" },
    "symbolSignature": $NULLABLE_STRING_SCHEMA,
    "startOffset": $NULLABLE_INTEGER_SCHEMA,
    "endOffset": $NULLABLE_INTEGER_SCHEMA,
    "startLine": $NULLABLE_INTEGER_SCHEMA,
    "endLine": $NULLABLE_INTEGER_SCHEMA,
    "allowedChangeKinds": $STRING_ARRAY_SCHEMA,
    "supportingFindingIds": $STRING_ARRAY_SCHEMA
  },
  "required": [
    "scopeId",
    "targetNodeId",
    "filePath",
    "language",
    "symbolKind",
    "symbolSignature",
    "startOffset",
    "endOffset",
    "startLine",
    "endLine",
    "allowedChangeKinds",
    "supportingFindingIds"
  ]
}
"""

    private const val CODE_DRAFT_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "id": { "type": "string" },
    "sourceNodeId": { "type": "string" },
    "title": { "type": "string" },
    "targetPath": { "type": "string" },
    "content": $NULLABLE_STRING_SCHEMA,
    "editOperations": {
      "type": "array",
      "items": $CODE_EDIT_OPERATION_SCHEMA
    },
    "editScopes": {
      "type": "array",
      "items": $EDIT_SCOPE_SCHEMA
    },
    "warnings": $STRING_ARRAY_SCHEMA
  },
  "required": ["id", "sourceNodeId", "title", "targetPath", "content", "editOperations", "editScopes", "warnings"]
}
"""

    const val GENERATION_PLAN: String = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "summary": { "type": "string" },
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "id": { "type": "string" },
          "title": { "type": "string" },
          "description": { "type": "string" },
          "risk": { "type": "string" },
          "targetPath": $NULLABLE_STRING_SCHEMA
        },
        "required": ["id", "title", "description", "risk", "targetPath"]
      }
    },
    "warnings": $STRING_ARRAY_SCHEMA
  },
  "required": ["summary", "items", "warnings"]
}
"""

    const val DISCUSSION: String = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "answer": { "type": "string" },
    "focusItemId": $NULLABLE_STRING_SCHEMA,
    "warnings": $STRING_ARRAY_SCHEMA
  },
  "required": ["answer", "focusItemId", "warnings"]
}
"""

    const val CODE_GENERATION_RESULT: String = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "summary": { "type": "string" },
    "warnings": $STRING_ARRAY_SCHEMA,
    "drafts": {
      "type": "array",
      "items": $CODE_DRAFT_SCHEMA
    }
  },
  "required": ["summary", "warnings", "drafts"]
}
"""

    const val PATCH_RESULT: String = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "answer": { "type": "string" },
    "warnings": $STRING_ARRAY_SCHEMA,
    "findings": {
      "type": "array",
      "items": $EVIDENCE_FINDING_SCHEMA
    },
    "patch": $NULLABLE_GRAPH_PATCH_SCHEMA,
    "candidateChanges": {
      "type": "array",
      "items": $CANDIDATE_CHANGE_SCHEMA
    },
    "investigationThreads": {
      "type": "array",
      "items": $INVESTIGATION_THREAD_SCHEMA
    }
  },
  "required": [
    "answer",
    "warnings",
    "findings",
    "patch",
    "candidateChanges",
    "investigationThreads"
  ]
}
"""

    const val BEAUTIFICATION: String = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "steps": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "stepId": { "type": "string" },
          "title": { "type": "string" },
          "description": { "type": "string" },
          "followUpQuestions": $STRING_ARRAY_SCHEMA,
          "evidence": {
            "type": "array",
            "items": $EVIDENCE_FINDING_SCHEMA
          },
          "downstreamTargets": $STRING_ARRAY_SCHEMA
        },
        "required": [
          "stepId",
          "title",
          "description",
          "followUpQuestions",
          "evidence",
          "downstreamTargets"
        ]
      }
    },
    "warnings": $STRING_ARRAY_SCHEMA
  },
  "required": ["steps", "warnings"]
}
"""
}
