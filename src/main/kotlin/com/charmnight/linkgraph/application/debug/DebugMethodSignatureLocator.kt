package com.charmnight.linkgraph.application.debug

import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.PsiShortNamesCache

/**
 * 调试场景下按“方法签名字符串”回到真实 PSI 方法。
 * 主要用于 runIde 自动复现当前项目里的真实方法链路，而不是依赖人工点击。
 */
internal object DebugMethodSignatureLocator {
    internal fun collectLookupDiagnostics(
        project: Project,
        signature: String,
    ): LookupDiagnostics? {
        val normalizedSignature = signature.trim()
        if (normalizedSignature.isEmpty()) {
            return null
        }
        val parsedSignature = parse(normalizedSignature) ?: return null
        return collectLookupDiagnostics(project, parsedSignature)
    }

    /**
     * 根据方法签名在项目中查找真实的 PSI 方法。
     */
    fun find(
        project: Project,
        signature: String,
    ): PsiMethod? {
        // 调试输入先做 trim，避免无效空白影响解析。
        val normalizedSignature = signature.trim()
        if (normalizedSignature.isEmpty()) {
            logFailure(
                signature = normalizedSignature,
                reason = "emptySignature",
            )
            return null
        }
        // 先把签名拆成类与方法信息，解析失败则无法继续查找。
        val parsedSignature = parse(normalizedSignature)
        if (parsedSignature == null) {
            logFailure(
                signature = normalizedSignature,
                reason = "parseFailed",
            )
            return null
        }
        val scope = GlobalSearchScope.projectScope(project)
        val psiFacade = JavaPsiFacade.getInstance(project)
        // 用有序集合收集候选类，支持全限定类名与简单类名两种匹配方式。
        val candidateClasses = linkedSetOf<PsiClass>()

        parsedSignature.qualifiedOwner?.let { qualifiedOwner ->
            psiFacade.findClass(qualifiedOwner, scope)?.let(candidateClasses::add)
        }
        parsedSignature.simpleOwner?.let { simpleOwner ->
            PsiShortNamesCache.getInstance(project)
                .getClassesByName(simpleOwner, scope)
                .forEach(candidateClasses::add)
        }
        if (candidateClasses.isEmpty()) {
            collectClassesFromMatchingFiles(project, parsedSignature)
                .forEach(candidateClasses::add)
        }

        if (candidateClasses.isEmpty()) {
            logFailure(
                project = project,
                signature = normalizedSignature,
                reason = "candidateClassesEmpty",
                parsedSignature = parsedSignature,
            )
            return null
        }

        // 统一把签名转换为可比较形式，抹平限定名与简单类名的差异。
        val comparableExpectedSignature = comparableMethodSignature(normalizedSignature)
        val candidateMethods = candidateClasses
            .asSequence()
            .flatMap { psiClass -> psiClass.findMethodsByName(parsedSignature.methodName, false).asSequence() }
            .sortedBy(::methodSignature)
            .toList()
        val matchedMethod = candidateMethods.firstOrNull { method ->
                val candidateSignature = methodSignature(method)
                candidateSignature == normalizedSignature ||
                    comparableMethodSignature(candidateSignature) == comparableExpectedSignature
            }
        if (matchedMethod == null) {
            logFailure(
                project = project,
                signature = normalizedSignature,
                reason = "methodNotMatched",
                parsedSignature = parsedSignature,
                comparableExpectedSignature = comparableExpectedSignature,
                candidateClasses = candidateClasses.toList(),
                candidateMethods = candidateMethods,
            )
        }
        return matchedMethod
    }

    /**
     * 解析方法签名中的类名和方法名。
     */
    private fun parse(signature: String): ParsedMethodSignature? {
        // 必须先找到参数起始位置，才能拆出前面的类名和方法名。
        val argumentsStart = signature.indexOf('(')
        if (argumentsStart <= 0) {
            return null
        }
        val ownerAndMethod = signature.substring(0, argumentsStart)
        val owner = ownerAndMethod.substringBeforeLast('.', missingDelimiterValue = "")
        val methodName = ownerAndMethod.substringAfterLast('.', missingDelimiterValue = "")
        if (owner.isBlank() || methodName.isBlank()) {
            return null
        }
        return ParsedMethodSignature(
            qualifiedOwner = owner.takeIf { '.' in it },
            simpleOwner = owner.substringAfterLast('.').takeIf(String::isNotBlank),
            methodName = methodName,
        )
    }

    /**
     * 把方法签名转换为便于宽松比较的形式。
     */
    private fun comparableMethodSignature(signature: String): String {
        val argumentsStart = signature.indexOf('(')
        if (argumentsStart <= 0) {
            return signature
        }
        val ownerAndMethod = signature.substring(0, argumentsStart)
        val owner = ownerAndMethod.substringBeforeLast('.', missingDelimiterValue = ownerAndMethod)
        val methodName = ownerAndMethod.substringAfterLast('.')
        val simpleOwner = owner.substringAfterLast('.')
        val argumentsEnd = signature.indexOf(')', startIndex = argumentsStart)
        if (argumentsEnd < argumentsStart) {
            return "$simpleOwner.$methodName${signature.substring(argumentsStart)}"
        }
        val parameters = signature.substring(argumentsStart + 1, argumentsEnd)
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(",") { type -> comparableType(type) }
        val returnType = signature
            .substring(argumentsEnd + 1)
            .removePrefix(":")
            .takeIf(String::isNotBlank)
            ?.let(::comparableType)
            ?: ""
        return "$simpleOwner.$methodName($parameters):$returnType"
    }

    /**
     * 把类型文本转换成宽松比较形式：
     * - 去掉泛型参数，`List<String>` 与 `List` 可匹配；
     * - 去掉包名前缀，`java.util.List` 与 `List` 可匹配；
     * - 保留数组维度，避免 `String` 与 `String[]` 误匹配。
     */
    private fun comparableType(type: String): String {
        val trimmed = type.trim().removeSuffix("?")
        val arraySuffix = buildString {
            var rest = trimmed
            while (rest.endsWith("[]")) {
                append("[]")
                rest = rest.removeSuffix("[]")
            }
        }
        val withoutArrays = trimmed.removeSuffix(arraySuffix)
        val erased = withoutArrays.substringBefore('<').substringAfterLast('.').trim()
        return erased + arraySuffix
    }

    /**
     * 表示拆解后的方法签名结构。
     */
    private data class ParsedMethodSignature(
        /** 保存全限定所属类名。 */
        val qualifiedOwner: String?,
        /** 保存简单类名。 */
        val simpleOwner: String?,
        /** 保存方法名。 */
        val methodName: String,
    ) {
        fun matchesClass(psiClass: PsiClass): Boolean {
            val expectedQualifiedOwner = qualifiedOwner
            if (!expectedQualifiedOwner.isNullOrBlank()) {
                return psiClass.qualifiedName == expectedQualifiedOwner
            }
            val expectedSimpleOwner = simpleOwner
            return !expectedSimpleOwner.isNullOrBlank() && psiClass.name == expectedSimpleOwner
        }
    }

    internal data class LookupDiagnostics(
        val dumbMode: Boolean,
        val moduleNames: List<String>,
        val contentRoots: List<String>,
        val sourceRoots: List<String>,
        val projectScope: ScopeLookup,
        val allScope: ScopeLookup,
        val projectAndLibrariesScope: ScopeLookup,
        val filenameHits: List<FileLookup>,
    )

    internal data class ScopeLookup(
        val qualifiedHit: String?,
        val shortNameCount: Int,
        val shortNameSample: List<String>,
    )

    internal data class FileLookup(
        val path: String,
        val moduleName: String?,
        val inContent: Boolean,
        val inSource: Boolean,
        val inLibrary: Boolean,
    )

    private fun collectClassesFromMatchingFiles(
        project: Project,
        parsedSignature: ParsedMethodSignature,
    ): List<PsiClass> {
        val simpleOwner = parsedSignature.simpleOwner ?: return emptyList()
        return FilenameIndex.getFilesByName(project, "$simpleOwner.java", GlobalSearchScope.allScope(project))
            .asSequence()
            .filterIsInstance<PsiJavaFile>()
            .flatMap { javaFile -> javaFile.classes.asSequence().flatMap(::selfAndInnerClasses) }
            .filter(parsedSignature::matchesClass)
            .distinctBy { psiClass -> psiClass.qualifiedName ?: psiClass.name ?: psiClass.text }
            .toList()
    }

    private fun selfAndInnerClasses(psiClass: PsiClass): Sequence<PsiClass> =
        sequenceOf(psiClass) + psiClass.innerClasses.asSequence().flatMap(::selfAndInnerClasses)

    private fun logFailure(
        project: Project? = null,
        signature: String,
        reason: String,
        parsedSignature: ParsedMethodSignature? = null,
        comparableExpectedSignature: String? = null,
        candidateClasses: List<PsiClass> = emptyList(),
        candidateMethods: List<PsiMethod> = emptyList(),
    ) {
        if (!LinkGraphDebugEnvironment.isEnabled(DEBUG_TRACE_ENV)) {
            return
        }
        logger.warn(
            "debug 方法签名定位失败: " +
                "signature=$signature, " +
                "reason=$reason, " +
                "owner=${parsedSignature?.qualifiedOwner.orEmpty()}, " +
                "simpleOwner=${parsedSignature?.simpleOwner.orEmpty()}, " +
                "methodName=${parsedSignature?.methodName.orEmpty()}, " +
                "comparableExpected=${comparableExpectedSignature.orEmpty()}, " +
                "candidateClasses=${candidateClasses.size}[${sampleClasses(candidateClasses)}], " +
                "candidateMethods=${candidateMethods.size}[${sampleMethods(candidateMethods)}], " +
                "lookupDiagnostics=${project?.let { currentProject ->
                    parsedSignature?.let { parsed -> formatLookupDiagnostics(collectLookupDiagnostics(currentProject, parsed)) }
                }.orEmpty()}",
        )
    }

    private fun collectLookupDiagnostics(
        project: Project,
        parsedSignature: ParsedMethodSignature,
    ): LookupDiagnostics {
        val psiFacade = JavaPsiFacade.getInstance(project)
        val shortNamesCache = PsiShortNamesCache.getInstance(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)
        val everythingScope = GlobalSearchScope.everythingScope(project)
        val rootManager = ProjectRootManager.getInstance(project)
        val fileIndex = rootManager.fileIndex

        return LookupDiagnostics(
            dumbMode = com.intellij.openapi.project.DumbService.isDumb(project),
            moduleNames = ModuleManager.getInstance(project).modules.map { module -> module.name }.sorted(),
            contentRoots = rootManager.contentRoots.map { file -> file.path }.filter(String::isNotBlank).sorted(),
            sourceRoots = rootManager.contentSourceRoots.map { file -> file.path }.filter(String::isNotBlank).sorted(),
            projectScope = collectScopeLookup(psiFacade, shortNamesCache, parsedSignature, projectScope),
            allScope = collectScopeLookup(psiFacade, shortNamesCache, parsedSignature, allScope),
            projectAndLibrariesScope = collectScopeLookup(psiFacade, shortNamesCache, parsedSignature, everythingScope),
            filenameHits = parsedSignature.simpleOwner
                ?.let { simpleOwner ->
                    FilenameIndex.getFilesByName(project, "$simpleOwner.java", allScope)
                        .map(PsiFile::getVirtualFile)
                        .filterNotNull()
                        .map { virtualFile ->
                            FileLookup(
                                path = virtualFile.path,
                                moduleName = ModuleUtilCore.findModuleForFile(virtualFile, project)?.name,
                                inContent = fileIndex.isInContent(virtualFile),
                                inSource = fileIndex.isInSourceContent(virtualFile),
                                inLibrary = fileIndex.isInLibrary(virtualFile),
                            )
                        }
                        .sortedBy(FileLookup::path)
                }
                .orEmpty(),
        )
    }

    private fun collectScopeLookup(
        psiFacade: JavaPsiFacade,
        shortNamesCache: PsiShortNamesCache,
        parsedSignature: ParsedMethodSignature,
        scope: GlobalSearchScope,
    ): ScopeLookup {
        val qualifiedHit = parsedSignature.qualifiedOwner
            ?.let { qualifiedOwner -> psiFacade.findClass(qualifiedOwner, scope) }
            ?.let { psiClass -> psiClass.qualifiedName ?: psiClass.name }
        val shortNameMatches = parsedSignature.simpleOwner
            ?.let { simpleOwner -> shortNamesCache.getClassesByName(simpleOwner, scope).toList() }
            .orEmpty()
        return ScopeLookup(
            qualifiedHit = qualifiedHit,
            shortNameCount = shortNameMatches.size,
            shortNameSample = shortNameMatches.take(SAMPLE_LIMIT).map { psiClass ->
                psiClass.qualifiedName ?: psiClass.name ?: "<anonymous>"
            },
        )
    }

    private fun formatLookupDiagnostics(diagnostics: LookupDiagnostics): String =
        buildString {
            append("dumb=").append(diagnostics.dumbMode)
            append(", modules=").append(diagnostics.moduleNames.size)
                .append("[").append(diagnostics.moduleNames.take(SAMPLE_LIMIT).joinToString("|")).append("]")
            append(", contentRoots=").append(diagnostics.contentRoots.size)
                .append("[").append(diagnostics.contentRoots.take(SAMPLE_LIMIT).joinToString("|")).append("]")
            append(", sourceRoots=").append(diagnostics.sourceRoots.size)
                .append("[").append(diagnostics.sourceRoots.take(SAMPLE_LIMIT).joinToString("|")).append("]")
            append(", projectScope=").append(formatScopeLookup(diagnostics.projectScope))
            append(", allScope=").append(formatScopeLookup(diagnostics.allScope))
            append(", projectAndLibrariesScope=").append(formatScopeLookup(diagnostics.projectAndLibrariesScope))
            append(", filenameHits=").append(diagnostics.filenameHits.size)
                .append("[").append(
                    diagnostics.filenameHits.take(SAMPLE_LIMIT).joinToString("|") { hit ->
                        "${hit.path}(module=${hit.moduleName.orEmpty()},content=${hit.inContent},source=${hit.inSource},library=${hit.inLibrary})"
                    },
                ).append("]")
        }

    private fun formatScopeLookup(scopeLookup: ScopeLookup): String =
        "qualified=${scopeLookup.qualifiedHit.orEmpty()}, " +
            "shortNameCount=${scopeLookup.shortNameCount}, " +
            "shortNameSample=[${scopeLookup.shortNameSample.joinToString("|")}]"

    private fun sampleClasses(classes: List<PsiClass>): String =
        classes.take(SAMPLE_LIMIT).joinToString("|") { psiClass ->
            psiClass.qualifiedName ?: psiClass.name ?: "<anonymous>"
        }

    private fun sampleMethods(methods: List<PsiMethod>): String =
        methods.take(SAMPLE_LIMIT).joinToString("|") { method ->
            runCatching {
                val candidateSignature = methodSignature(method)
                "$candidateSignature=>${comparableMethodSignature(candidateSignature)}"
            }.getOrElse { throwable ->
                "${method.name}(signatureError=${throwable.javaClass.simpleName}:${throwable.message})"
            }
        }

    private const val DEBUG_TRACE_ENV = "LINKGRAPH_DEBUG_TRACE"
    private const val SAMPLE_LIMIT = 8
    private val logger = Logger.getInstance(DebugMethodSignatureLocator::class.java)
}
