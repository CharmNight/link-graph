package com.charmnight.linkgraph.llm.prompt

/**
 * LlmPromptFactory 各场景的 JSON schema 与行为约束文本。
 *
 * 从 LlmPromptFactory.kt 抽出（P2-1）：纯字符串常量，无状态、无依赖，适合单独文件维护。
 * 每个函数返回对应场景的 JSON 输出 schema 说明，被 LlmPromptFactory 的各 buildXxxPromptPackage
 * 作为 PromptSection(priority = SCHEMA) 注入用户提示。
 */

/** 返回实现计划生成场景的 JSON schema 说明文本。 */
internal fun generationPlanSchemaInstruction(): String {
    return """
        仅返回 JSON，结构如下：
        {
          "summary": "简短计划摘要",
          "items": [
            {
              "id": "稳定ID",
              "title": "需要变更的内容",
              "description": "原因与做法",
              "risk": "LOW|MEDIUM|HIGH",
              "targetPath": "可选路径"
            }
          ],
          "warnings": ["可选警告"]
        }
    """.trimIndent()
}

/** 返回实现建议追问场景的 JSON schema 说明文本。 */
internal fun generationPlanDiscussionSchemaInstruction(): String {
    return """
        仅返回 JSON，结构如下：
        {
          "answer": "对当前实现建议的回答",
          "focusItemId": "可选，当前聚焦的实现建议条目 ID",
          "warnings": ["可选警告"]
        }
    """.trimIndent()
}

/** 返回问答场景的行为约束说明，强调先回答问题、只输出有依据的候选变更。 */
internal fun qaBehaviorInstruction(): String {
    return """
        请逐条对照“用户问题”回答。
        如果当前上下文不足以回答用户问题，answer 必须明确说明“当前证据不足以回答该问题”，不要转而输出无关建议。
        你的第一优先级是直接回答“用户问题”。
        禁止输出与用户问题无关的通用安全、性能、规范性建议。
        candidateChanges 只允许保留与“用户问题”直接相关、且已经有 DIRECT_SOURCE / DIRECT_GRAPH 支撑的修改建议；如果当前轮只是解释链路或回答事实问题，请返回 []。
        investigationThreads 用来承接证据不足但值得继续追问的线程；它们必须明确写出“已观察到什么、还缺什么、下一轮建议问什么”。
        请先给出本轮问答回答，再给出 candidateChanges 与 investigationThreads。不要把建议伪装成代码事实，也不要整表重刷已有候选项。
    """.trimIndent()
}

/** 返回问答场景的 JSON schema 说明文本，覆盖 findings、candidateChanges、investigationThreads 等结构。 */
internal fun qaSchemaInstruction(): String {
    return """
        仅返回 JSON，结构如下：
        {
          "answer": "问答回答",
          "findings": [
            {
              "id": "稳定ID",
              "claim": "一条必须可追溯的关键结论",
              "evidenceLevel": "DIRECT_SOURCE|DIRECT_GRAPH|CALLSITE_ONLY|NOT_OBSERVED",
              "references": [
                {
                  "nodeId": "可选节点ID",
                  "filePath": "可选源码路径",
                  "startLine": 1,
                  "endLine": 3
                }
              ]
            }
          ],
          "candidateChanges": [
            {
              "changeId": "稳定ID",
              "status": "PENDING_CONFIRMATION|CONFIRMED|REJECTED|SUPERSEDED",
              "claimType": "CODE_FACT|RISK_HINT|EXPLANATION_NOTE|STRUCTURAL_SUGGESTION",
              "title": "候选变更标题",
              "targetStepIds": ["可选步骤ID"],
              "targetNodeIds": ["可选节点ID"],
              "beforeState": "修改前状态",
              "afterState": "修改后状态",
              "reason": "为什么建议这样改",
              "impactSummary": "影响摘要",
              "supportingFindingIds": ["必须对应 findings[*].id"],
              "patchIntent": {
                "mode": "UPDATE_EXISTING_NODE|INSERT_NEW_DECISION|INSERT_NEW_ACTION|ANNOTATION_ONLY",
                "targetNodeId": "UPDATE_EXISTING_NODE|ANNOTATION_ONLY 时必填",
                "attachEdgeId": "INSERT_NEW_DECISION|INSERT_NEW_ACTION 时必填，必须指向真实 CONTROL_FLOW 边 ID",
                "falseBranchTargetNodeId": "INSERT_NEW_DECISION 时必填，明确 FALSE 分支真实落点"
              },
              "graphPatch": {
                "summary": "可选；当已提供 patchIntent 时允许省略，由后端合成",
                "operations": [
                  {
                    "id": "稳定ID",
                    "action": "ADD_NODE|UPDATE_NODE|DELETE_NODE|ADD_EDGE|UPDATE_EDGE|DELETE_EDGE|ADD_ANNOTATION|MARK_UNCERTAIN",
                    "elementKind": "NODE|EDGE",
                    "elementId": "元素ID",
                    "title": "可选标题",
                    "summary": "可选摘要",
                    "metadata": {
                      "draft.claimType": "CODE_FACT|RISK_HINT|EXPLANATION_NOTE|STRUCTURAL_SUGGESTION"
                    }
                  }
                ],
                "addedNodeIds": [],
                "removedNodeIds": [],
                "addedEdgeIds": [],
                "removedEdgeIds": []
              }
            }
          ],
          "investigationThreads": [
            {
              "threadId": "稳定ID",
              "status": "OPEN|PROMOTED|DISMISSED|BLOCKED|SUPERSEDED",
              "claimType": "RISK_HINT|STRUCTURAL_SUGGESTION",
              "title": "风险线程标题",
              "targetStepIds": ["可选步骤ID"],
              "targetNodeIds": ["可选节点ID"],
              "summary": "当前已经观察到什么",
              "evidenceGap": "还缺什么证据",
              "recommendedQuestion": "下一轮建议追问什么",
              "supportingFindingIds": ["必须对应 findings[*].id"]
            }
          ],
          "warnings": ["可选警告"],
          "patch": null
        }
    """.trimIndent()
}

/** 返回差异审查场景的 JSON schema 说明文本。 */
internal fun diffReviewSchemaInstruction(): String {
    return """
        仅返回 JSON，结构如下：
        {
          "answer": "差异解释",
          "findings": [
            {
              "id": "稳定ID",
              "claim": "一条必须可追溯的关键结论",
              "evidenceLevel": "DIRECT_SOURCE|DIRECT_GRAPH|CALLSITE_ONLY|NOT_OBSERVED",
              "references": [
                {
                  "nodeId": "可选节点ID",
                  "filePath": "可选源码路径",
                  "startLine": 1,
                  "endLine": 3
                }
              ]
            }
          ],
          "warnings": ["可选警告"],
          "patch": {
            "summary": "patch 摘要",
            "operations": [
              {
                "id": "稳定ID",
                "action": "ADD_NODE|UPDATE_NODE|DELETE_NODE|ADD_EDGE|UPDATE_EDGE|DELETE_EDGE|ADD_ANNOTATION|MARK_UNCERTAIN",
                "elementKind": "NODE|EDGE",
                "elementId": "元素ID",
                "title": "可选标题",
                "summary": "可选摘要",
                "metadata": {
                  "draft.claimType": "CODE_FACT|RISK_HINT|EXPLANATION_NOTE|STRUCTURAL_SUGGESTION"
                },
                "node": {
                  "id": "节点ID",
                  "type": "METHOD|CLASS|SQL|HTTP_ENDPOINT|FEIGN_CLIENT|DUBBO_SERVICE|MQ_TOPIC|MQ_CONSUMER|CONFIG_ITEM|XML_RESOURCE|DOC_PAGE|UNCERTAIN_LINK",
                  "title": "节点标题",
                  "doc": "可选说明"
                }
              }
            ],
            "addedNodeIds": [],
            "removedNodeIds": [],
            "addedEdgeIds": [],
            "removedEdgeIds": []
          }
        }
    """.trimIndent()
}

/** 返回代码生成场景的行为约束说明，强调对现有文件只允许结构化编辑操作。 */
internal fun codeGenerationBehaviorInstruction(): String {
    return """
        如果目标文件已经明确指向现有源码，请返回结构化 editOperations，而不是整文件内容。
        operation.kind 必须严格从对应 scope.allowedChangeKinds 中选择；如果 scope 没授权，就不要生成该 operation。
        保留与本次确认项无关的现有逻辑、成员、注释和 import，不要删除未提及的成员，也不要凭空改名或迁移到别的文件。
    """.trimIndent()
}

/** 返回代码生成场景的 JSON schema 说明文本，scope 授权只允许由本地确认链路回填。 */
internal fun codeGenerationSchemaInstruction(): String {
    return """
        仅返回 JSON，结构如下：
        {
          "summary": "本次生成摘要",
          "warnings": ["可选警告"],
          "drafts": [
            {
              "id": "稳定ID",
              "sourceNodeId": "来源节点ID",
              "title": "文件名",
              "targetPath": "项目内相对路径",
              "content": "仅 CREATE_FILE 时返回完整文件内容",
              "editOperations": [
                {
                  "operationId": "稳定ID",
                  "filePath": "项目内相对路径",
                  "scopeId": "必须对应既有 edit scope",
                  "kind": "REPLACE_METHOD_BLOCK|REPLACE_METHOD_BODY|INSERT_METHOD_AFTER|ADD_IMPORT|ADD_FIELD|CREATE_FILE",
                  "payload": "纯源码片段，不要放 methodSignature/changeType/existingCodeSnippet 等元数据包装",
                  "warnings": ["可选警告"]
                }
              ],
              "warnings": ["可选警告"]
            }
          ]
        }
    """.trimIndent()
}

/** 返回链路讲解场景的 JSON schema 说明文本，覆盖步骤化讲解结构。 */
internal fun beautificationSchemaInstruction(): String {
    return """
        仅返回 JSON，结构如下：
        {
          "steps": [
            {
              "stepId": "稳定ID",
              "title": "步骤标题",
              "kind": "BUSINESS_ACTION|METHOD_CALL|CONDITION|RETURN|RESOURCE_INTERACTION|STRUCTURE_OVERVIEW",
              "description": "说明这一步在做什么",
              "followUpQuestions": ["可继续追问的问题"],
              "evidence": [
                {
                  "id": "稳定ID",
                  "claim": "一条必须可追溯的关键结论",
                  "evidenceLevel": "DIRECT_SOURCE|DIRECT_GRAPH|CALLSITE_ONLY|NOT_OBSERVED",
                  "references": [
                    {
                      "nodeId": "可选节点ID",
                      "filePath": "可选源码路径",
                      "startLine": 1,
                      "endLine": 3
                    }
                  ]
                }
              ],
              "downstreamTargets": ["可继续下钻的目标ID"]
            }
          ],
          "warnings": ["可选警告"]
        }
    """.trimIndent()
}
