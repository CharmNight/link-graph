package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.investigation.domain.EvidenceFact
import com.charmnight.linkgraph.investigation.domain.EvidenceGoal
import com.charmnight.linkgraph.investigation.domain.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.domain.EvidenceLevel
import com.charmnight.linkgraph.investigation.domain.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.charmnight.linkgraph.investigation.resolving.ReadActionEvidenceResolver
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets

/**
 * 解析 Java SPI 的 `META-INF/services/<接口全限定名>` 配置绑定。
 */
class JavaSpiResolver : ReadActionEvidenceResolver() {
    /** 保存解析器稳定标识。 */
    override val id: String = "java-spi-binding"

    /**
     * 仅处理 SPI 绑定目标。
     */
    override fun supports(goal: EvidenceGoal): Boolean {
        return goal.kind == EvidenceGoalKind.SPI_BINDING
    }

    /**
     * 读取 SPI 配置并验证 provider 类实现接口。
     */
    override fun resolveInReadAction(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): ResolutionOutcome {
        val interfaceName = goal.interfaceName?.takeIf(String::isNotBlank)
            ?: return unresolved(goal, "缺少 SPI 接口全限定名。")
        val serviceFiles = serviceFiles(context, interfaceName)
        if (serviceFiles.isEmpty()) {
            return unresolved(goal, "未找到 META-INF/services/$interfaceName。")
        }
        val interfaceClass = JavaPsiEvidenceSupport.resolveClassCandidates(context, interfaceName).singleOrNull()
            ?: return unresolved(goal, "未找到 SPI 接口源码 $interfaceName。")
        val providerNames = serviceFiles.flatMap(::providerNames)
            .distinct()
        if (providerNames.isEmpty()) {
            return unresolved(goal, "SPI 配置文件存在但没有 provider 条目。")
        }
        val providerFacts = providerNames.mapNotNull { providerName ->
            val providerClass = JavaPsiEvidenceSupport.resolveClassCandidates(context, providerName).singleOrNull()
                ?: return@mapNotNull null
            if (!implementsInterface(providerClass, interfaceClass, interfaceName)) {
                return@mapNotNull null
            }
            JavaPsiEvidenceSupport.classFact(
                goal = goal,
                resolverId = id,
                psiClass = providerClass,
                claim = "已确认 SPI provider $providerName 实现 $interfaceName。",
                whyResolved = "META-INF/services 配置指向 provider，PSI 验证 provider 实现目标接口。",
            )
        }
        if (providerFacts.isEmpty()) {
            return unresolved(goal, "SPI provider 未能解析为实现 $interfaceName 的项目源码类。")
        }
        return ResolutionOutcome.Resolved(
            resolverId = id,
            facts = serviceFiles.map { file ->
                configFact(goal, interfaceName, file.path)
            } + providerFacts,
        )
    }

    /**
     * 验证 provider 是否实现 SPI 接口。
     */
    private fun implementsInterface(
        providerClass: com.intellij.psi.PsiClass,
        interfaceClass: com.intellij.psi.PsiClass,
        interfaceName: String,
    ): Boolean {
        return providerClass.isInheritor(interfaceClass, true) ||
            providerClass.implementsListTypes.any { type ->
                val canonicalText = type.canonicalText
                canonicalText == interfaceName ||
                    canonicalText.substringAfterLast('.') == interfaceName.substringAfterLast('.') ||
                    type.resolve()?.qualifiedName == interfaceName
            }
    }

    /**
     * 在项目内容根下精确定位 Java SPI 服务文件。
     */
    private fun serviceFiles(
        context: InvestigationContext,
        interfaceName: String,
    ): List<VirtualFile> {
        val matches = mutableListOf<VirtualFile>()
        ProjectRootManager.getInstance(context.project).contentRoots.forEach { root ->
            VfsUtilCore.iterateChildrenRecursively(
                root,
                null,
            ) { file ->
                if (!file.isDirectory && file.path.endsWith("META-INF/services/$interfaceName")) {
                    matches += file
                }
                true
            }
        }
        return matches.distinctBy(VirtualFile::getPath).sortedBy(VirtualFile::getPath)
    }

    /**
     * 从 SPI 配置文件读取 provider 类名。
     */
    private fun providerNames(file: VirtualFile): List<String> {
        val content = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
        return content.lineSequence()
            .map { line -> line.substringBefore('#').trim() }
            .filter(String::isNotBlank)
            .toList()
    }

    /**
     * 构造 SPI 配置文件事实。
     */
    private fun configFact(
        goal: EvidenceGoal,
        interfaceName: String,
        filePath: String,
    ): EvidenceFact {
        val document = FileDocumentManager.getInstance().getDocument(
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: return EvidenceFact(
                    factId = "${goal.goalId}-$id-config",
                    level = EvidenceLevel.CONFIG_RESOLVED,
                    resolverId = id,
                    symbolSignature = "META-INF/services/$interfaceName",
                    filePath = filePath,
                    startLine = null,
                    endLine = null,
                    claim = "已确认 SPI 配置文件 META-INF/services/$interfaceName 存在。",
                    whyResolved = "按 Java SPI 规范精确读取服务文件。",
                ),
        )
        val endLine = document?.lineCount?.coerceAtLeast(1)
        return EvidenceFact(
            factId = "${goal.goalId}-$id-config",
            level = EvidenceLevel.CONFIG_RESOLVED,
            resolverId = id,
            symbolSignature = "META-INF/services/$interfaceName",
            filePath = filePath,
            startLine = 1,
            endLine = endLine,
            claim = "已确认 SPI 配置文件 META-INF/services/$interfaceName 存在。",
            whyResolved = "按 Java SPI 规范精确读取服务文件。",
        )
    }

    /**
     * 构造未解析结果。
     */
    private fun unresolved(
        goal: EvidenceGoal,
        reason: String,
    ): ResolutionOutcome.Unresolved {
        return ResolutionOutcome.Unresolved(
            resolverId = id,
            reason = reason,
            requiredEvidence = listOf("补充 META-INF/services/${goal.interfaceName ?: "目标接口"}、provider 类或运行时 ServiceLoader trace。"),
        )
    }
}
