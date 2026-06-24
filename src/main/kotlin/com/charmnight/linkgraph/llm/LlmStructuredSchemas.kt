package com.charmnight.linkgraph.llm

/**
 * 集中维护远程结构化响应场景使用的 machine-readable JSON Schema。
 * 这些 schema 会直接下发给支持原生结构化输出的 provider，而不是只作为 prompt 文本展示。
 */
internal object LlmStructuredSchemas {
    /** 可空字符串类型，常用于可选文本字段。 */
    private const val NULLABLE_STRING_SCHEMA = """
{
  "type": ["string", "null"]
}
"""

    /** 可空整数类型，常用于可选行号或偏移字段。 */
    private const val NULLABLE_INTEGER_SCHEMA = """
{
  "type": ["integer", "null"]
}
"""

    /** 不允许任何属性的空对象，用于 metadata 占位字段。 */
    private const val EMPTY_OBJECT_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {},
  "required": []
}
"""

    /** 字符串数组类型。 */
    private const val STRING_ARRAY_SCHEMA = """
{
  "type": "array",
  "items": {
    "type": "string"
  }
}
"""

    /** 证据引用 schema：节点 ID、文件路径、起止行号均可空。 */
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

    /** 单条结构化证据的 schema：包含结论文本、证据等级和引用列表。 */
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

    /** 候选变更 patchIntent 的 schema：表达更新/新增节点、新增判断/动作、仅注释等模式。 */
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

    /** 可空的 patchIntent schema：候选变更允许在没有 patchIntent 时省略。 */
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

    /** 图节点 schema：覆盖 ID、类型、标题、签名、输入输出、文档与绑定状态等字段。 */
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

    /** 可空图节点 schema：用于 patch operation 中允许为空的 node 字段。 */
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

    /** 图边 schema：覆盖 ID、类型、起止节点、标签、绑定状态等字段。 */
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

    /** 可空图边 schema：用于 patch operation 中允许为空的 edge 字段。 */
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

    /** 单条 patch 操作 schema：表示节点/边的新增、更新或删除动作。 */
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

    /** 图补丁 schema：包含一组操作和增删节点/边的 ID 汇总。 */
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

    /** 可空图补丁 schema：问答结果允许没有 patch，因此 patch 字段使用此可空版本。 */
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

    /** 单条待确认候选变更 schema：携带目标节点、修改前后状态、理由、证据和 patchIntent 等。 */
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

    /** 单条风险线索 schema：表示当前证据不足以确认、需要继续追问的线程。 */
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

    /** 单条代码编辑操作 schema：标识目标文件、作用域、操作类型与负载。 */
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

    /** 单个 edit scope schema：表示一段被证据锚定的精确代码作用域。 */
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

    /** 单个代码草稿 schema：包含目标文件、整文件内容或结构化编辑操作列表。 */
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

    /** 实现计划生成场景使用的根 schema：包含摘要、计划项列表与可选警告。 */
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

    /** 实现建议追问场景使用的根 schema：包含回答、聚焦条目 ID 与可选警告。 */
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

    /** 代码草稿生成场景使用的根 schema：包含摘要、警告与代码草稿列表。 */
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

    /** 图问答场景使用的根 schema：包含回答、证据、可选补丁、候选变更与风险线程。 */
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

    /** 链路讲解场景使用的根 schema：包含步骤化讲解列表和可选警告。 */
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
          "kind": { "type": "string" },
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
          "kind",
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
