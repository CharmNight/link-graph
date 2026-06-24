package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.EditScope
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * 对结构化 existing-file 改写做本地 authoritative 校验：
 * 在落盘之前基于 PSI 解析对比改写前后的源码，判定本次改写是否落在允许的 scope 范围内、
 * 是否触发了未授权的删除/新增/越界，从而避免 LLM 输出越权改动真实工程文件。
 */
class CodeEditValidationService(
    /** 当前 IntelliJ 项目，用于获取 PSI 工厂与文件解析能力 */
    private val project: Project,
) {
    /**
     * 校验一次 existing-file 改写是否合规。
     *
     * 整体策略：先按目标文件路径过滤出相关的 scope；再根据后缀走 Java 或 Kotlin 校验分支，
     * 通过 PSI 提取结构快照（imports/fields/methods）对比前后差异，结合 scope 中允许的变更种类与
     * 符号签名白名单做最终判定；任何一项失败都会以 [CodeEditValidationResult] 形式返回告警。
     *
     * @param filePath 目标文件路径
     * @param beforeText 改写前的源码文本
     * @param afterText 改写后的源码文本
     * @param allowedScopes 上层预授权的 scope 集合（文件 + 允许的变更类型 + 符号签名）
     * @param operations 当前拟执行的结构化操作列表（用于校验操作类型是否在白名单内）
     * @return 校验结果，包含是否有效、变更符号签名与告警列表
     */
    fun validateExistingFileRewrite(
        filePath: String,
        beforeText: String,
        afterText: String,
        allowedScopes: List<EditScope>,
        operations: List<CodeEditOperation> = emptyList(),
    ): CodeEditValidationResult {
        // 统一路径分隔符，便于与 scope 中的路径比较
        val normalizedPath = filePath.replace('\\', '/')
        val relevantScopes = allowedScopes.filter { scope ->
            scope.filePath.replace('\\', '/') == normalizedPath
        }
        // 收集所有相关 scope 允许的变更种类，作为后续判定的白名单
        val allowedKinds = relevantScopes.flatMap(EditScope::allowedChangeKinds).toSet()
        if (relevantScopes.isEmpty()) {
            return CodeEditValidationResult(
                isValid = false,
                warnings = listOf("existing-file writeback 缺少与目标文件匹配的 validated scope。"),
            )
        }
        // 按语言分支：先走 Kotlin 校验路径，再走 Java 校验路径
        if (!normalizedPath.endsWith(".java", ignoreCase = true)) {
            if (!normalizedPath.endsWith(".kt", ignoreCase = true)) {
                return CodeEditValidationResult(
                    isValid = false,
                    warnings = listOf("当前结构化 apply gate 仅支持 Java/Kotlin existing-file writeback。"),
                )
            }
            // Kotlin 分支：提取函数签名差异
            val changedSymbols = changedKotlinFunctionSignatures(beforeText, afterText)
            val allowedSymbols = relevantScopes.mapNotNull(EditScope::symbolSignature).toSet()
            if (changedSymbols.isEmpty()) {
                return CodeEditValidationResult(
                    isValid = false,
                    changedSymbols = changedSymbols,
                    warnings = listOf("结构化改写没有产生任何受控函数变更。"),
                )
            }
            // 当前 Kotlin 写回只允许函数级替换，其它操作一律拒绝
            if (operations.any { operation ->
                    operation.kind !in setOf(CodeEditOperationKind.REPLACE_METHOD_BLOCK, CodeEditOperationKind.REPLACE_METHOD_BODY)
                }
            ) {
                return CodeEditValidationResult(
                    isValid = false,
                    changedSymbols = changedSymbols,
                    warnings = listOf("Kotlin existing-file writeback 当前仅支持函数级替换操作。"),
                )
            }
            // scope 未授予函数替换权限则拒绝
            if (changedSymbols.isNotEmpty() &&
                allowedKinds.isNotEmpty() &&
                allowedKinds.none { it in setOf("REPLACE_METHOD_BLOCK", "REPLACE_METHOD_BODY", "REPLACE_SYMBOL_BODY") }
            ) {
                return CodeEditValidationResult(
                    isValid = false,
                    changedSymbols = changedSymbols,
                    warnings = listOf("当前 scope 未授权 Kotlin 函数替换。"),
                )
            }
            // 出现在白名单之外的符号改动一律视为越界
            val outOfScope = changedSymbols - allowedSymbols
            if (outOfScope.isNotEmpty()) {
                return CodeEditValidationResult(
                    isValid = false,
                    changedSymbols = changedSymbols,
                    warnings = listOf("检测到越界符号改写：${outOfScope.joinToString()}"),
                )
            }
            return CodeEditValidationResult(
                isValid = true,
                changedSymbols = changedSymbols,
            )
        }
        // Java 分支：构建前后结构快照并比较差异
        val beforeSnapshot = javaStructureSnapshot(beforeText)
        val afterSnapshot = javaStructureSnapshot(afterText)
        val changedSymbols = changedExistingJavaMethodSignatures(beforeSnapshot, afterSnapshot)
        val allowedSymbols = relevantScopes.mapNotNull(EditScope::symbolSignature).toSet()
        // 新增/删除的方法、import、字段，用于后续授权检查
        val addedMethods = afterSnapshot.methods.keys - beforeSnapshot.methods.keys
        val removedMethods = beforeSnapshot.methods.keys - afterSnapshot.methods.keys
        val addedImports = afterSnapshot.imports - beforeSnapshot.imports
        val removedImports = beforeSnapshot.imports - afterSnapshot.imports
        val addedFields = afterSnapshot.fields - beforeSnapshot.fields
        val removedFields = beforeSnapshot.fields - afterSnapshot.fields
        // 完全没有任何受控变化时直接拒绝，避免空操作绕过校验
        if (
            changedSymbols.isEmpty() &&
            addedMethods.isEmpty() &&
            addedImports.isEmpty() &&
            addedFields.isEmpty()
        ) {
            return CodeEditValidationResult(
                isValid = false,
                changedSymbols = changedSymbols,
                warnings = listOf("结构化改写没有产生任何受控方法变更。"),
            )
        }
        // 现有方法/import/字段的删除属于高风险操作，必须有显式授权；当前实现一律拒绝
        if (removedMethods.isNotEmpty()) {
            return CodeEditValidationResult(
                isValid = false,
                changedSymbols = changedSymbols + removedMethods,
                warnings = listOf("检测到未授权的方法删除：${removedMethods.joinToString()}"),
            )
        }
        if (removedImports.isNotEmpty()) {
            return CodeEditValidationResult(
                isValid = false,
                changedSymbols = changedSymbols + addedMethods,
                warnings = listOf("检测到未授权的 import 删除：${removedImports.joinToString()}"),
            )
        }
        if (removedFields.isNotEmpty()) {
            return CodeEditValidationResult(
                isValid = false,
                changedSymbols = changedSymbols + addedMethods,
                warnings = listOf("检测到未授权的字段删除：${removedFields.joinToString()}"),
            )
        }
        // 检查方法内容替换是否越界
        val outOfScope = changedSymbols - allowedSymbols
        if (outOfScope.isNotEmpty()) {
            return CodeEditValidationResult(
                isValid = false,
                changedSymbols = changedSymbols,
                warnings = listOf("检测到越界符号改写：${outOfScope.joinToString()}"),
            )
        }
        // 即便符号在白名单内，scope 也必须显式授权"方法内容替换"
        if (changedSymbols.isNotEmpty() &&
            allowedKinds.isNotEmpty() &&
            allowedKinds.none { it in setOf("REPLACE_METHOD_BLOCK", "REPLACE_METHOD_BODY", "REPLACE_SYMBOL_BODY") }
        ) {
            return CodeEditValidationResult(
                isValid = false,
                changedSymbols = changedSymbols,
                warnings = listOf("当前 scope 未授权方法内容替换。"),
            )
        }
        // 新增方法/import/字段需要对应类别的显式授权
        if (addedMethods.isNotEmpty() && "INSERT_METHOD_AFTER" !in allowedKinds) {
            return CodeEditValidationResult(
                isValid = false,
                changedSymbols = changedSymbols + addedMethods,
                warnings = listOf("当前 scope 未授权新增方法：${addedMethods.joinToString()}"),
            )
        }
        if (addedImports.isNotEmpty() && "ADD_IMPORT" !in allowedKinds) {
            return CodeEditValidationResult(
                isValid = false,
                changedSymbols = changedSymbols + addedMethods,
                warnings = listOf("当前 scope 未授权新增 import：${addedImports.joinToString()}"),
            )
        }
        if (addedFields.isNotEmpty() && "ADD_FIELD" !in allowedKinds) {
            return CodeEditValidationResult(
                isValid = false,
                changedSymbols = changedSymbols + addedMethods,
                warnings = listOf("当前 scope 未授权新增字段：${addedFields.joinToString()}"),
            )
        }
        return CodeEditValidationResult(
            isValid = true,
            changedSymbols = changedSymbols + addedMethods,
        )
    }

    /** 计算前后结构快照之间真正发生内容变化的方法签名集合（仅限双方都存在的方法） */
    private fun changedExistingJavaMethodSignatures(
        before: JavaStructureSnapshot,
        after: JavaStructureSnapshot,
    ): Set<String> {
        return (before.methods.keys intersect after.methods.keys).filterTo(linkedSetOf()) { signature ->
            before.methods[signature] != after.methods[signature]
        }
    }

    /** 用 PSI 解析 Kotlin 源码并提取函数签名差异，比较前后两个版本中存在签名但函数体不同的函数集合 */
    private fun changedKotlinFunctionSignatures(
        before: String,
        after: String,
    ): Set<String> {
        val psiFactory = KtPsiFactory(project)
        val beforeFile = psiFactory.createFile("Before.kt", before)
        val afterFile = psiFactory.createFile("After.kt", after)
        // 以签名为键、规范化文本为值，便于按签名比较前后差异
        val beforeFunctions = PsiTreeUtil.findChildrenOfType(beforeFile, KtNamedFunction::class.java)
            .associateBy(
                keySelector = { function -> kotlinFunctionSignature(function) },
                valueTransform = { function -> normalizedKotlinFunctionText(function) },
            )
        val afterFunctions = PsiTreeUtil.findChildrenOfType(afterFile, KtNamedFunction::class.java)
            .associateBy(
                keySelector = { function -> kotlinFunctionSignature(function) },
                valueTransform = { function -> normalizedKotlinFunctionText(function) },
            )
        return (beforeFunctions.keys + afterFunctions.keys).filterTo(linkedSetOf()) { signature ->
            beforeFunctions[signature] != afterFunctions[signature]
        }
    }

    /** 委托给统一的 Java 方法签名格式化器生成稳定签名 */
    private fun methodSignature(method: PsiMethod): String {
        return JavaMethodSignatureFormatter.methodSignature(method)
    }

    /** 规范化方法源码文本：统一换行、压缩空白，避免格式差异被误识别为内容变更 */
    private fun normalizedMethodText(method: PsiMethod): String {
        return method.text.replace("\r\n", "\n").replace(Regex("\\s+"), " ").trim()
    }

    /** 把一段 Java 源码解析成结构快照，包含 import 集合、字段集合与方法签名→方法文本映射 */
    private fun javaStructureSnapshot(source: String): JavaStructureSnapshot {
        val fileFactory = PsiFileFactory.getInstance(project)
        val psiFile = fileFactory.createFileFromText("Snapshot.java", JavaFileType.INSTANCE, source) as PsiJavaFile
        // 提取 import 语句文本并规范化空白
        val imports = psiFile.importList?.allImportStatements
            ?.map { statement -> statement.text.replace("\r\n", "\n").trim() }
            ?.toSet()
            .orEmpty()
        // 提取字段并规范化空白
        val fields = PsiTreeUtil.findChildrenOfType(psiFile, PsiField::class.java)
            .map { field -> field.text.replace("\r\n", "\n").replace(Regex("\\s+"), " ").trim() }
            .toSet()
        // 提取方法，按签名建立映射
        val methods = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)
            .associateBy(
                keySelector = { method -> requireNotNull(methodSignature(method)) },
                valueTransform = { method -> normalizedMethodText(method) },
            )
        return JavaStructureSnapshot(
            imports = imports,
            fields = fields,
            methods = methods,
        )
    }

    /** 生成 Kotlin 函数的稳定签名：包含所属类/包前缀、参数类型列表与返回类型，便于跨版本比对 */
    private fun kotlinFunctionSignature(function: KtNamedFunction): String {
        // 优先使用所属类的 fqName 作为前缀，没有则退到包名，再退到纯函数名
        val ownerPrefix = PsiTreeUtil.getParentOfType(function, KtClassOrObject::class.java, true)
            ?.fqName
            ?.asString()
            ?.let { classFqName -> "$classFqName.${function.name}" }
            ?: function.containingKtFile.packageFqName.asString().takeIf { it.isNotBlank() }
                ?.let { packageName -> "${packageName}.${function.name}" }
            ?: function.name
            ?: "<anonymous>"
        // 参数列表逐个规范化类型名，避免基本类型简写带来的签名漂移
        val parameters = function.valueParameters.joinToString(",") { parameter ->
            normalizeKotlinTypeName(parameter.typeReference?.text ?: "kotlin.Any")
        }
        val returnType = normalizeKotlinTypeName(function.typeReference?.text ?: "kotlin.Unit")
        return "$ownerPrefix($parameters):$returnType"
    }

    /** 规范化 Kotlin 函数源码文本：统一换行、压缩空白，便于按函数体内容做差异比较 */
    private fun normalizedKotlinFunctionText(function: KtNamedFunction): String {
        return function.text.replace("\r\n", "\n").replace(Regex("\\s+"), " ").trim()
    }

    /** 把 Kotlin 内建基本类型/常用类型补全为带 kotlin. 前缀的全名，保证签名稳定且不依赖 import */
    private fun normalizeKotlinTypeName(typeName: String): String {
        return when (typeName.removeSuffix("?")) {
            "String" -> "kotlin.String"
            "Int" -> "kotlin.Int"
            "Long" -> "kotlin.Long"
            "Boolean" -> "kotlin.Boolean"
            "Double" -> "kotlin.Double"
            "Float" -> "kotlin.Float"
            "Short" -> "kotlin.Short"
            "Byte" -> "kotlin.Byte"
            "Char" -> "kotlin.Char"
            "Unit" -> "kotlin.Unit"
            "Any" -> "kotlin.Any"
            else -> typeName
        }
    }

    /** Java 源码的结构快照：保存 import、字段、方法签名→方法文本的映射，供前后差异比较使用 */
    private data class JavaStructureSnapshot(
        /** 文件中所有 import 语句的规范化文本集合 */
        val imports: Set<String>,
        /** 文件中所有字段的规范化文本集合 */
        val fields: Set<String>,
        /** 方法签名到方法规范化文本的映射，用于检测方法体是否发生变化 */
        val methods: Map<String, String>,
    )
}
