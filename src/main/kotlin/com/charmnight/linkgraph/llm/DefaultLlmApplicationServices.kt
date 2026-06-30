package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

import com.charmnight.linkgraph.application.port.GenerationPlanDiscussionPort
import com.charmnight.linkgraph.application.port.GraphBeautificationPort
import com.charmnight.linkgraph.application.port.GraphDiffPatchPort
import com.charmnight.linkgraph.application.port.GraphGenerationPort
import com.charmnight.linkgraph.application.port.GraphQaPatchPort
import com.charmnight.linkgraph.application.port.LlmApplicationServices
import com.charmnight.linkgraph.codegen.CodeGenerationService
import com.intellij.openapi.project.Project

/** 项目级默认 LLM 应用服务装配，负责把应用端口连接到具体 LLM 服务。 */
class DefaultLlmApplicationServices(
    private val project: Project,
) : LlmApplicationServices {
    private val gateway by lazy(LazyThreadSafetyMode.PUBLICATION) {
        LlmGatewayCompositionRoot.createGateway(project)
    }

    private val graphGenerationService by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GraphGenerationService(gateway = gateway)
    }

    private val graphQaPatchService by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GraphQaPatchService(gateway = gateway)
    }

    private val graphDiffPatchService by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GraphDiffPatchService(gateway = gateway)
    }

    private val graphBeautificationService by lazy(LazyThreadSafetyMode.PUBLICATION) {
        DefaultGraphBeautificationService(gateway = gateway)
    }

    private val generationPlanDiscussionService by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GenerationPlanDiscussionService(gateway = gateway)
    }

    private val codeGenerationService by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CodeGenerationService(gateway = gateway)
    }

    override fun graphGenerationService(): GraphGenerationPort = graphGenerationService

    override fun graphQaPatchService(): GraphQaPatchPort = graphQaPatchService

    override fun graphDiffPatchService(): GraphDiffPatchPort = graphDiffPatchService

    override fun graphBeautificationService(): GraphBeautificationPort = graphBeautificationService

    override fun generationPlanDiscussionService(): GenerationPlanDiscussionPort = generationPlanDiscussionService

    override fun codeGenerationService(): CodeGenerationService = codeGenerationService
}
