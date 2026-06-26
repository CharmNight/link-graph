package com.charmnight.linkgraph.application.composition

import com.charmnight.linkgraph.foundation.LoggedFailures
import com.charmnight.linkgraph.llm.runtime.AgentRunCoordinator
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcomeFactory
import com.charmnight.linkgraph.semantic.provider.SemanticProviderRegistry
import com.charmnight.linkgraph.semantic.provider.code.CodeSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.MarkdownSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.MyBatisXmlSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.SqlSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.XmlResourceSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.YamlPropertiesSemanticProvider
import com.charmnight.linkgraph.semantic.subject.CaretSubjectLocator
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandleFactory
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectTestOverrides
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 共享语义基础设施工厂（P2-1 拆分）。
 *
 * 从 WorkflowComposition 抽出的独立 object，负责创建语义分析链路的共享组件：
 * - CodeSubjectHandleFactory
 * - 默认 SubjectLocator（CaretSubjectLocator）
 * - 默认 SemanticAnalyzer（注册所有 SemanticProvider）
 * - 默认 AnalysisOutcomeFactory
 *
 * WorkflowComposition 通过此 object 的工厂方法获取实例，
 * 并在测试覆盖（testOverrides）存在时优先使用覆盖。
 */
internal object CompositionSharedInfrastructure {
    /** 创建 CodeSubjectHandleFactory。 */
    fun createCodeSubjectHandleFactory(): CodeSubjectHandleFactory = CodeSubjectHandleFactory()

    /** 创建默认 SubjectLocator（基于光标位置）。 */
    fun createDefaultSubjectLocator(): SubjectLocator = CaretSubjectLocator()

    /**
     * 创建默认 SemanticAnalyzer，注册所有内置 SemanticProvider。
     */
    fun createDefaultSemanticAnalyzer(
        project: Project,
        logger: Logger,
        infrastructure: InfrastructureComposition,
    ): SemanticAnalyzer = SemanticAnalyzer(
        registry = SemanticProviderRegistry(
            listOf(
                CodeSemanticProvider(
                    architectureIndexProvider = {
                        LoggedFailures.orNull(logger, "CodeSemanticProvider architectureIndexSupport.currentIndex") {
                            infrastructure.architectureIndexSupport.currentIndex()
                        }
                    },
                ),
                MyBatisXmlSemanticProvider(),
                XmlResourceSemanticProvider(),
                YamlPropertiesSemanticProvider(),
                MarkdownSemanticProvider(),
                SqlSemanticProvider(),
            ),
        ),
        project = project,
    )

    /** 创建默认 AnalysisOutcomeFactory。 */
    fun createDefaultAnalysisOutcomeFactory(
        infrastructure: InfrastructureComposition,
    ): AnalysisOutcomeFactory = AnalysisOutcomeFactory(
        runtimeTrace = infrastructure.runtimeSupport.runtimeTraceSink(),
    )

    /**
     * 根据测试覆盖决定使用默认实例还是覆盖实例。
     */
    fun resolveSubjectLocator(testOverrides: LinkGraphProjectTestOverrides): SubjectLocator =
        testOverrides.subjectLocator ?: createDefaultSubjectLocator()

    fun resolveSemanticAnalyzer(
        testOverrides: LinkGraphProjectTestOverrides,
        project: Project,
        logger: Logger,
        infrastructure: InfrastructureComposition,
    ): SemanticAnalyzer =
        testOverrides.semanticAnalyzer ?: createDefaultSemanticAnalyzer(project, logger, infrastructure)

    fun resolveAnalysisOutcomeFactory(
        testOverrides: LinkGraphProjectTestOverrides,
        infrastructure: InfrastructureComposition,
    ): AnalysisOutcomeFactory =
        testOverrides.analysisOutcomeFactory ?: createDefaultAnalysisOutcomeFactory(infrastructure)
}
