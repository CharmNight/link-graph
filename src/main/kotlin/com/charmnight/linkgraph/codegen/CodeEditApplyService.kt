package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.agent.model.EditScope
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiLoopStatement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiSwitchStatement
import com.intellij.psi.PsiSynchronizedStatement
import com.intellij.psi.PsiTryStatement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * 把结构化编辑操作应用到内存文本，并交给校验门禁判定是否允许落盘。
 */
class CodeEditApplyService(
    /** 当前 IntelliJ 项目实例，用于获取 PSI 工厂与执行写入命令。 */
    private val project: Project,
    /** 把操作映射到已授权 EditScope 的解析器。 */
    private val scopeResolver: CodeEditScopeResolver = CodeEditScopeResolver(),
    /** 校验改写后文本是否仍符合可控改写边界的校验服务。 */
    private val validationService: CodeEditValidationService = CodeEditValidationService(project),
) {
    /** 统一对操作载荷做换行/空白归一化，避免 PSI 解析偏差。 */
    private fun normalizedPayload(operation: CodeEditOperation): String = CodeEditPayloadNormalizer.normalize(operation.payload)

    /**
     * 参与排序的操作包装结构。
     * 携带匹配到的作用域、原始顺序、按作用域位置生成的排序键，用于稳定应用多个操作。
     */
    private data class OrderedOperation(
        /** 原始编辑操作。 */
        val operation: CodeEditOperation,
        /** 解析出的授权作用域，可能为 null。 */
        val scope: EditScope?,
        /** 在原始操作列表中的下标，用于稳定排序。 */
        val originalIndex: Int,
        /** 基于作用域起止位置算出的排序键，越大越先应用。 */
        val sortKey: Int?,
    )

    /**
     * 把一串操作顺序应用到 [beforeText]，并构造可落盘的预览批次。
     * 任意操作失败或越界都会立即终止，并把批次标记为不可应用。
     */
    fun prepareEdits(
        /** 目标文件相对路径，用于派发与校验。 */
        filePath: String,
        /** 应用前的完整源码文本。 */
        beforeText: String,
        /** 待应用的结构化操作列表。 */
        operations: List<CodeEditOperation>,
        /** 已授权的作用域列表，用于校验每个操作。 */
        editScopes: List<EditScope>,
    ): PreparedCodeEditBatch {
        if (operations.isEmpty()) {
            return PreparedCodeEditBatch(
                canApply = false,
                previewText = beforeText,
                warnings = listOf("当前没有可应用的结构化 edit operation。"),
            )
        }
        var previewText = beforeText
        val warnings = mutableListOf<String>()
        val appliedScopes = mutableListOf<EditScope>()
        val preparedEdits = mutableListOf<PreparedCodeEdit>()
        orderOperationsForStableApply(operations, editScopes).forEach { ordered ->
            val operation = ordered.operation
            val scope = ordered.scope
            if (scope == null) {
                warnings += "操作 '${operation.operationId}' 缺少与目标文件匹配的 validated scope。"
                return PreparedCodeEditBatch(canApply = false, previewText = beforeText, warnings = warnings)
            }
            if (!scopeResolver.isOperationAllowed(operation, scope)) {
                warnings += "操作 '${operation.operationId}' 未授权，scope='${scope.scopeId}'，kind=${operation.kind.name}。"
                return PreparedCodeEditBatch(canApply = false, previewText = beforeText, warnings = warnings)
            }
            val nextText = applyOperation(previewText, operation, scope)
                ?: return PreparedCodeEditBatch(
                    canApply = false,
                    previewText = beforeText,
                    warnings = warnings + "无法根据 scope '${scope.scopeId}' 精确定位并应用受控改写。",
                )
            val preparedEdit = buildPreparedEdit(
                operation = operation,
                scope = scope,
                beforeText = previewText,
                afterText = nextText,
            ) ?: return PreparedCodeEditBatch(
                canApply = false,
                previewText = beforeText,
                warnings = warnings + "操作 '${operation.operationId}' 没有产生可验证的局部 patch。",
            )
            previewText = nextText
            appliedScopes += scope
            preparedEdits += preparedEdit
            warnings += operation.warnings
        }

        val validation = validationService.validateExistingFileRewrite(
            filePath = filePath,
            beforeText = beforeText,
            afterText = previewText,
            allowedScopes = appliedScopes,
            operations = operations,
        )
        if (!validation.isValid) {
            return PreparedCodeEditBatch(
                canApply = false,
                previewText = beforeText,
                warnings = warnings + validation.warnings,
            )
        }
        return PreparedCodeEditBatch(
            canApply = true,
            preparedEdits = preparedEdits,
            previewText = previewText,
            warnings = warnings + validation.warnings,
        )
    }

    /** 把操作与作用域配对，并按作用域在文件中的位置倒序排序，避免后续偏移量被前序操作偏移。 */
    private fun orderOperationsForStableApply(
        operations: List<CodeEditOperation>,
        editScopes: List<EditScope>,
    ): List<OrderedOperation> {
        return operations.mapIndexed { index, operation ->
            val scope = scopeResolver.resolveScope(operation, editScopes)
            OrderedOperation(
                operation = operation,
                scope = scope,
                originalIndex = index,
                sortKey = scope?.let(::resolveScopeSortKey),
            )
        }.sortedWith(
            compareByDescending<OrderedOperation> { it.sortKey != null }
                .thenByDescending { it.sortKey ?: Int.MIN_VALUE }
                .thenBy { it.originalIndex },
        )
    }

    /** 优先使用 endOffset/endLine，再退化到 startOffset/startLine，得到作用域在文件中的排序键。 */
    private fun resolveScopeSortKey(scope: EditScope): Int? {
        scope.endOffset?.let { return it }
        scope.endLine?.let { return it * 1_000_000 }
        scope.startOffset?.let { return it }
        scope.startLine?.let { return it * 1_000_000 }
        return null
    }

    /** 对比应用前后两份文本，提取出最小局部差异并构造可送审的 PreparedCodeEdit；若文本未变化返回 null。 */
    private fun buildPreparedEdit(
        operation: CodeEditOperation,
        scope: EditScope,
        beforeText: String,
        afterText: String,
    ): PreparedCodeEdit? {
        if (beforeText == afterText) {
            return null
        }
        var prefix = 0
        val prefixLimit = minOf(beforeText.length, afterText.length)
        while (prefix < prefixLimit && beforeText[prefix] == afterText[prefix]) {
            prefix += 1
        }
        var suffix = 0
        while (
            suffix < beforeText.length - prefix &&
            suffix < afterText.length - prefix &&
            beforeText[beforeText.length - 1 - suffix] == afterText[afterText.length - 1 - suffix]
        ) {
            suffix += 1
        }
        val beforeEnd = beforeText.length - suffix
        val afterEnd = afterText.length - suffix
        return PreparedCodeEdit(
            operationId = operation.operationId,
            filePath = operation.filePath,
            scopeId = operation.scopeId,
            kind = operation.kind,
            targetSymbolSignature = scope.symbolSignature,
            startOffset = prefix,
            endOffset = beforeEnd,
            beforeText = beforeText.substring(prefix, beforeEnd),
            afterText = afterText.substring(prefix, afterEnd),
            warnings = operation.warnings,
        )
    }

    /** 按目标文件类型分发到 Java 或 Kotlin 的具体实现；返回应用后的完整文本，失败时返回 null。 */
    private fun applyOperation(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): String? {
        return when {
            operation.filePath.endsWith(".kt", ignoreCase = true) -> applyKotlinOperation(currentText, operation, scope)
            else -> applyJavaOperation(currentText, operation, scope)
        }
    }

    /** 应用单个 Java 文件操作；对流程类作用域走代码块片段替换，其余按操作类型分发。 */
    private fun applyJavaOperation(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): String? {
        if (shouldApplyScopedJavaSnippetReplacement(scope, operation)) {
            return applyJavaScopedSnippetReplacement(currentText, operation, scope)
        }
        return when (operation.kind) {
            CodeEditOperationKind.REPLACE_METHOD_BLOCK -> applyJavaMethodBlockReplacement(currentText, operation, scope)
            CodeEditOperationKind.REPLACE_METHOD_BODY -> applyJavaMethodBodyReplacement(currentText, operation, scope)
            CodeEditOperationKind.ADD_IMPORT -> applyJavaImportAddition(currentText, operation)
            CodeEditOperationKind.ADD_FIELD -> applyJavaFieldAddition(currentText, operation, scope)
            CodeEditOperationKind.INSERT_METHOD_AFTER -> applyJavaMethodInsertionAfter(currentText, operation, scope)
            CodeEditOperationKind.CREATE_FILE -> operation.payload
        }
    }

    /** 应用单个 Kotlin 文件操作；当前仅支持整体函数替换与函数体替换。 */
    private fun applyKotlinOperation(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): String? {
        return when (operation.kind) {
            CodeEditOperationKind.REPLACE_METHOD_BLOCK -> applyKotlinFunctionReplacement(currentText, operation, scope)
            CodeEditOperationKind.REPLACE_METHOD_BODY -> applyKotlinFunctionBodyReplacement(currentText, operation, scope)
            else -> null
        }
    }

    /** 用载荷提供的新方法整体替换目标 Java 方法；载荷解析失败时退化为仅替换方法体。 */
    private fun applyJavaMethodBlockReplacement(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): String? {
        val (psiFile, targetMethod) = locateJavaTargetMethod(currentText, operation, scope) ?: return null
        val psiFactory = JavaPsiFacade.getElementFactory(project)
        val payload = normalizedPayload(operation)
        val replacement = runCatching {
            psiFactory.createMethodFromText(
                payload,
                targetMethod.containingClass,
            )
        }.getOrNull()
        WriteCommandAction.runWriteCommandAction(project) {
            if (replacement != null) {
                targetMethod.replace(replacement)
            } else {
                val existingBody = targetMethod.body ?: return@runWriteCommandAction
                val replacementBody = psiFactory.createCodeBlockFromText(
                    normalizeJavaCodeBlockPayload(payload),
                    targetMethod,
                )
                existingBody.replace(replacementBody)
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        return psiFile.text
    }

    /** 仅替换目标 Java 方法的方法体，保留方法签名与注解。 */
    private fun applyJavaMethodBodyReplacement(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): String? {
        val (psiFile, targetMethod) = locateJavaTargetMethod(currentText, operation, scope) ?: return null
        val replacementBody = JavaPsiFacade.getElementFactory(project)
            .createCodeBlockFromText(normalizedPayload(operation), targetMethod)
        WriteCommandAction.runWriteCommandAction(project) {
            val existingBody = targetMethod.body ?: return@runWriteCommandAction
            existingBody.replace(replacementBody)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        return psiFile.text
    }

    /** 在现有 import 区追加一行新 import 并保持去重、排序，若 import 不存在则在 package 之后插入新区。 */
    private fun applyJavaImportAddition(
        currentText: String,
        operation: CodeEditOperation,
    ): String {
        val importLine = normalizedPayload(operation).trim().removeSuffix(";").let { "${it.trim()};" }
        val importRegex = Regex("""(?m)^import\s+[^;]+;\s*$""")
        val existingImports = importRegex.findAll(currentText).map { it.value.trim() }.toMutableSet()
        if (!existingImports.add(importLine)) {
            return currentText
        }
        val sortedImports = existingImports.sorted()
        val matches = importRegex.findAll(currentText).toList()
        return if (matches.isNotEmpty()) {
            val start = matches.first().range.first
            val end = matches.last().range.last + 1
            buildString(currentText.length + importLine.length + 2) {
                append(currentText.substring(0, start).trimEnd())
                append("\n\n")
                append(sortedImports.joinToString("\n"))
                append("\n\n")
                append(currentText.substring(end).trimStart('\n', '\r'))
            }
        } else {
            val packageMatch = Regex("""(?m)^package\s+[^;]+;\s*$""").find(currentText)
            val insertionPoint = packageMatch?.range?.last?.plus(1) ?: 0
            buildString(currentText.length + importLine.length + 4) {
                if (insertionPoint > 0) {
                    append(currentText.substring(0, insertionPoint).trimEnd())
                    append("\n\n")
                }
                append(sortedImports.joinToString("\n"))
                append("\n\n")
                append(currentText.substring(insertionPoint).trimStart('\n', '\r'))
            }
        }
    }

    /** 在目标方法所属类中插入由载荷描述的新字段，位置插在目标方法之前。 */
    private fun applyJavaFieldAddition(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): String? {
        val (psiFile, targetMethod) = locateJavaTargetMethod(currentText, operation, scope) ?: return null
        val targetClass = targetMethod.containingClass ?: return null
        val field = JavaPsiFacade.getElementFactory(project).createFieldFromText(normalizedPayload(operation), targetClass)
        WriteCommandAction.runWriteCommandAction(project) {
            targetClass.addBefore(field, targetMethod)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        return psiFile.text
    }

    /** 把载荷中描述的新 Java 方法插入到目标方法之后，保持类内顺序可读。 */
    private fun applyJavaMethodInsertionAfter(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): String? {
        val (psiFile, targetMethod) = locateJavaTargetMethod(currentText, operation, scope) ?: return null
        val targetClass = targetMethod.containingClass ?: return null
        val newMethod = JavaPsiFacade.getElementFactory(project).createMethodFromText(normalizedPayload(operation), targetClass)
        WriteCommandAction.runWriteCommandAction(project) {
            targetClass.addAfter(newMethod, targetMethod)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        return psiFile.text
    }

    /** 用载荷中的完整函数定义整体替换目标 Kotlin 函数；签名不匹配则视为失败。 */
    private fun applyKotlinFunctionReplacement(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): String? {
        val psiFactory = KtPsiFactory(project)
        val psiFile = psiFactory.createFile(operation.filePath.substringAfterLast('/'), currentText)
        val offset = resolveOffset(currentText, scope) ?: return null
        val anchor = psiFile.findElementAt(offset.coerceIn(0, currentText.lastIndex))
        val targetFunction = PsiTreeUtil.getParentOfType(anchor, KtNamedFunction::class.java, false) ?: return null
        if (!matchesKotlinScopeSignature(targetFunction, scope)) {
            return null
        }
        val replacement = psiFactory.createFunction(normalizedPayload(operation))
        WriteCommandAction.runWriteCommandAction(project) {
            targetFunction.replace(replacement)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        return psiFile.text
    }

    /** 仅替换目标 Kotlin 函数的函数体，签名、参数与返回类型保留原状。 */
    private fun applyKotlinFunctionBodyReplacement(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): String? {
        val psiFactory = KtPsiFactory(project)
        val psiFile = psiFactory.createFile(operation.filePath.substringAfterLast('/'), currentText)
        val offset = resolveOffset(currentText, scope) ?: return null
        val anchor = psiFile.findElementAt(offset.coerceIn(0, currentText.lastIndex))
        val targetFunction = PsiTreeUtil.getParentOfType(anchor, KtNamedFunction::class.java, false) ?: return null
        if (!matchesKotlinScopeSignature(targetFunction, scope)) {
            return null
        }
        val payload = normalizedPayload(operation)
        val replacement = psiFactory.createFunction(
            buildString {
                append("fun ")
                append(targetFunction.name)
                append(targetFunction.valueParameterList?.text ?: "()")
                targetFunction.typeReference?.text?.let { append(": ").append(it) }
                append(" ")
                append(payload.trim())
            },
        )
        val replacementBody = replacement.bodyExpression ?: return null
        WriteCommandAction.runWriteCommandAction(project) {
            val bodyExpression = targetFunction.bodyExpression ?: return@runWriteCommandAction
            bodyExpression.replace(replacementBody)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        return psiFile.text
    }

    /** 当载荷不以 `{` 开头时，补齐方法体大括号，方便后续 PSI 直接解析为代码块。 */
    private fun normalizeJavaCodeBlockPayload(payload: String): String {
        val trimmed = payload.trim()
        return if (trimmed.startsWith("{")) {
            trimmed
        } else {
            "{\n$trimmed\n}"
        }
    }

    /** 判断是否应走控制流片段替换路径：仅针对流程类作用域且为方法块/方法体替换操作。 */
    private fun shouldApplyScopedJavaSnippetReplacement(
        scope: EditScope,
        operation: CodeEditOperation,
    ): Boolean {
        if (operation.kind !in setOf(CodeEditOperationKind.REPLACE_METHOD_BLOCK, CodeEditOperationKind.REPLACE_METHOD_BODY)) {
            return false
        }
        return scope.symbolKind in setOf("FLOW_SCOPE", "FLOW_ACTION", "TERMINAL")
    }

    /** 对流程类作用域做局部代码块片段替换，保留外层 if/try 等包装结构并按需扩展到完整语句。 */
    private fun applyJavaScopedSnippetReplacement(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): String? {
        val range = resolveScopedJavaReplacementRange(currentText, operation, scope) ?: return null
        val methodBodyRange = resolveJavaMethodBodyContentRange(currentText, operation, scope)
        val clampedRange = methodBodyRange?.let { clampRangeToBounds(range, it) } ?: range
        val expandedRange = expandScopedJavaTextRange(
            currentText,
            clampedRange,
            methodBodyRange?.second,
        )
        val (startOffset, endOffset) = expandedRange
        val existingSnippet = currentText.substring(startOffset, endOffset)
        val payload = normalizedPayload(operation)
        val replacement = preserveScopedJavaWrapper(existingSnippet, payload)
            ?: normalizeScopedJavaSnippetPayload(payload, existingSnippet)
        return buildString(currentText.length - existingSnippet.length + replacement.length) {
            append(currentText, 0, startOffset)
            append(replacement)
            append(currentText, endOffset, currentText.length)
        }
    }

    /** 优先返回控制语句范围，否则退化到作用域的纯文本范围，用于定位片段替换区间。 */
    private fun resolveScopedJavaReplacementRange(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): Pair<Int, Int>? {
        val rawRange = resolveScopedTextRange(currentText, scope) ?: return null
        resolveScopedJavaControlStatementRange(currentText, operation, scope)?.let { return it }
        return rawRange
    }

    /** 在内存中构造临时 Java PSI 文件，并通过签名或偏移量定位目标 PsiMethod；找不到时返回 null。 */
    private fun locateJavaTargetMethod(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): Pair<com.intellij.psi.PsiFile, PsiMethod>? {
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText(
            operation.filePath.substringAfterLast('/'),
            JavaFileType.INSTANCE,
            currentText,
        )
        if (scope.symbolSignature != null) {
            PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)
                .firstOrNull { method -> matchesScopeSignature(method, scope) }
                ?.let { method -> return psiFile to method }
        }
        val offset = resolveOffset(currentText, scope) ?: return null
        val anchor = psiFile.findElementAt(offset.coerceIn(0, currentText.lastIndex))
        val targetMethod = PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java, false) ?: return null
        if (!matchesScopeSignature(targetMethod, scope)) {
            return null
        }
        return psiFile to targetMethod
    }

    /** 把作用域的 offset/startLine 解析为字符偏移量；起始行号小于等于 1 时返回首个非空白字符位置。 */
    private fun resolveOffset(
        text: String,
        scope: EditScope,
    ): Int? {
        scope.startOffset?.let { return it.coerceIn(0, text.length) }
        val startLine = scope.startLine ?: return null
        if (startLine <= 1) {
            return text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: 0
        }
        var currentLine = 1
        for (index in text.indices) {
            if (currentLine == startLine) {
                var cursor = index
                while (cursor < text.length && text[cursor] != '\n' && text[cursor].isWhitespace()) {
                    cursor += 1
                }
                return cursor.coerceIn(0, text.lastIndex)
            }
            if (text[index] == '\n') {
                currentLine += 1
            }
        }
        return if (currentLine == startLine) text.length else null
    }

    /** 基于作用域的行号或偏移量计算替换区间；参数越界或倒置时返回 null。 */
    private fun resolveScopedTextRange(
        text: String,
        scope: EditScope,
    ): Pair<Int, Int>? {
        if (scope.startLine != null && scope.endLine != null) {
            val start = lineStartOffset(text, scope.startLine)
            val end = lineEndOffset(text, scope.endLine)
            if (start == null || end == null || start > end) {
                return null
            }
            return start to end
        }
        val startOffset = scope.startOffset ?: return null
        val endOffset = scope.endOffset ?: return null
        val safeStart = startOffset.coerceIn(0, text.length)
        val safeEnd = endOffset.coerceIn(safeStart, text.length)
        return safeStart to safeEnd
    }

    /** 返回指定行号的起始字符偏移量，超出实际行数时返回 null。 */
    private fun lineStartOffset(text: String, lineNumber: Int): Int? {
        if (lineNumber <= 1) {
            return 0
        }
        var currentLine = 1
        for (index in text.indices) {
            if (currentLine == lineNumber) {
                return index
            }
            if (text[index] == '\n') {
                currentLine += 1
                if (currentLine == lineNumber) {
                    return index + 1
                }
            }
        }
        return null
    }

    /** 返回指定行号末尾的字符偏移量（不包含换行符），超出实际行数时返回 null。 */
    private fun lineEndOffset(text: String, lineNumber: Int): Int? {
        if (lineNumber < 1) {
            return null
        }
        var currentLine = 1
        for (index in text.indices) {
            if (currentLine == lineNumber && text[index] == '\n') {
                return index
            }
            if (text[index] == '\n') {
                currentLine += 1
            }
        }
        return if (currentLine == lineNumber) text.length else null
    }

    /** 把流程类载荷归一化为去掉外层大括号与缩进的纯片段，并按现有片段缩进重新对齐。 */
    private fun normalizeScopedJavaSnippetPayload(
        payload: String,
        existingSnippet: String,
    ): String {
        val existingIndent = existingSnippet
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.takeWhile(Char::isWhitespace)
            .orEmpty()
        val trimmed = payload.trim()
        val body = if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed.removeSurrounding("{", "}").trim()
        } else {
            trimmed
        }.trimIndent()
        if (body.isBlank()) {
            return existingIndent
        }
        return body.lineSequence().joinToString("\n") { line ->
            if (line.isBlank()) {
                ""
            } else {
                existingIndent + line
            }
        }
    }

    /** 当片段大括号不闭合且后面紧跟 `} else / catch / finally` 等延续结构时，扩展替换区间到延续结构之前，避免漏改。 */
    private fun expandScopedJavaTextRange(
        text: String,
        range: Pair<Int, Int>,
        endLimitExclusive: Int? = null,
    ): Pair<Int, Int> {
        val (startOffset, endOffset) = range
        val existingSnippet = text.substring(startOffset, endOffset)
        val wrapperKeyword = scopedJavaWrapperKeyword(existingSnippet) ?: return range
        if (braceBalance(existingSnippet) <= 0) {
            return range
        }
        val safeEndLimit = endLimitExclusive?.coerceIn(endOffset, text.length) ?: text.length
        val trailingText = text.substring(endOffset, safeEndLimit)
        val continuationRegex = when (wrapperKeyword) {
            "if", "else if", "else" -> Regex("""^(\s*})\s*else\b""")
            "try" -> Regex("""^(\s*})\s*(catch\b|finally\b)?""")
            else -> Regex("""^(\s*})""")
        }
        val match = continuationRegex.find(trailingText) ?: return range
        val closingBracePrefix = match.groups[1]?.value ?: return range
        return startOffset to (endOffset + closingBracePrefix.length)
    }

    /** 解析目标方法体内层（去掉外层大括号）的字符范围，用于把片段替换范围限制在方法体内。 */
    private fun resolveJavaMethodBodyContentRange(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): Pair<Int, Int>? {
        val (_, targetMethod) = locateJavaTargetMethod(currentText, operation, scope) ?: return null
        val bodyRange = targetMethod.body?.textRange ?: return null
        val start = (bodyRange.startOffset + 1).coerceIn(0, currentText.length)
        val end = (bodyRange.endOffset - 1).coerceIn(start, currentText.length)
        return start to end
    }

    /** 锚点向上回溯到最近的 if/try/loop 等控制语句，返回其字符范围；找不到时返回 null。 */
    private fun resolveScopedJavaControlStatementRange(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): Pair<Int, Int>? {
        val (psiFile, targetMethod) = locateJavaTargetMethod(currentText, operation, scope) ?: return null
        val anchorOffset = resolveOffset(currentText, scope) ?: return null
        val anchor = psiFile.findElementAt(anchorOffset.coerceIn(0, currentText.lastIndex)) ?: return null
        val controlElement = nearestScopedJavaControlElement(anchor, targetMethod) ?: return null
        val range = controlElement.textRange ?: return null
        return range.startOffset to range.endOffset
    }

    /** 从锚点元素向上查找，返回首个 if/try/loop/switch/synchronized 控制语句；超出目标方法边界时停止。 */
    private fun nearestScopedJavaControlElement(
        anchor: PsiElement,
        targetMethod: PsiMethod,
    ): PsiElement? {
        var cursor: PsiElement? = anchor
        while (cursor != null && cursor != targetMethod) {
            when (cursor) {
                is PsiIfStatement,
                is PsiTryStatement,
                is PsiLoopStatement,
                is PsiSwitchStatement,
                is PsiSynchronizedStatement,
                -> return cursor
            }
            cursor = cursor.parent
        }
        return null
    }

    /** 把目标区间夹取到 [bounds] 范围内，确保片段替换不会越界覆盖方法体外内容。 */
    private fun clampRangeToBounds(
        range: Pair<Int, Int>,
        bounds: Pair<Int, Int>,
    ): Pair<Int, Int> {
        val (rangeStart, rangeEnd) = range
        val (boundStart, boundEnd) = bounds
        val safeStart = rangeStart.coerceIn(boundStart, boundEnd)
        val safeEnd = rangeEnd.coerceIn(safeStart, boundEnd)
        return safeStart to safeEnd
    }

    /** 统计 `{` 与 `}` 的差值；正值表示存在未闭合大括号。 */
    private fun braceBalance(text: String): Int {
        var balance = 0
        for (char in text) {
            when (char) {
                '{' -> balance += 1
                '}' -> balance -= 1
            }
        }
        return balance
    }

    /** 在载荷仅是片段时，尝试保留原 if/try 等包装头，并把片段注入到包装体内部；若载荷已含完整包装返回 null 表示无操作。 */
    private fun preserveScopedJavaWrapper(
        existingSnippet: String,
        payload: String,
    ): String? {
        val trimmedExisting = existingSnippet.trim()
        val trimmedPayload = payload.trim()
        val wrapperKeyword = scopedJavaWrapperKeyword(trimmedExisting) ?: return null
        mergeScopedJavaWrapperHeaderOnly(trimmedExisting, trimmedPayload, wrapperKeyword)?.let { merged ->
            return indentScopedJavaSnippet(existingSnippet, merged)
        }
        val normalizedPayloadBody = normalizeScopedJavaBodyPayload(trimmedPayload)
        if (
            payloadAlreadyContainsScopedWrapper(trimmedPayload, wrapperKeyword) ||
            (normalizedPayloadBody != trimmedPayload &&
                payloadAlreadyContainsScopedWrapper(normalizedPayloadBody, wrapperKeyword))
        ) {
            return null
        }
        val braceRange = topLevelBraceRange(trimmedExisting) ?: return null
        val existingIndent = existingSnippet
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.takeWhile(Char::isWhitespace)
            .orEmpty()
        val existingBodyIndent = existingSnippet
            .lineSequence()
            .drop(1)
            .firstOrNull { line -> line.isNotBlank() && line.trim() != "}" }
            ?.takeWhile(Char::isWhitespace)
            ?: "$existingIndent    "
        val prefix = trimmedExisting.substring(0, braceRange.first + 1)
        val suffix = trimmedExisting.substring(braceRange.last)
        val formattedBody = normalizedPayloadBody.lineSequence().joinToString("\n") { line ->
            if (line.isBlank()) {
                ""
            } else {
                existingBodyIndent + line
            }
        }
        return buildString {
            append(existingIndent)
            append(prefix)
            if (formattedBody.isNotBlank()) {
                append('\n')
                append(formattedBody)
                append('\n')
            } else {
                append('\n')
            }
            append(existingIndent)
            append(suffix)
        }
    }

    /** 当载荷仅是包装头（如 `if (cond)`）而片段已含方法体时，把载荷作为新条件合并到原包装上。 */
    private fun mergeScopedJavaWrapperHeaderOnly(
        existingSnippet: String,
        payload: String,
        wrapperKeyword: String,
    ): String? {
        val normalizedPayload = payload.trim().removeSuffix(";").trim()
        if (normalizedPayload.isBlank()) {
            return null
        }
        val payloadStartsWithWrapper = when (wrapperKeyword) {
            "else if" -> normalizedPayload.startsWith("else if") || normalizedPayload.startsWith("if")
            else -> normalizedPayload.startsWith(wrapperKeyword)
        }
        if (!payloadStartsWithWrapper || normalizedPayload.contains('{')) {
            return null
        }
        val braceRange = topLevelBraceRange(existingSnippet) ?: return null
        val bodyWithBraces = existingSnippet.substring(braceRange.first, braceRange.last + 1)
        val trailingSuffix = existingSnippet.substring(braceRange.last + 1)
        return buildString {
            append(normalizedPayload)
            append(' ')
            append(bodyWithBraces)
            append(trailingSuffix)
        }
    }

    /** 用原片段首行的缩进把替换片段整体重新缩进，保持视觉对齐。 */
    private fun indentScopedJavaSnippet(
        existingSnippet: String,
        replacementSnippet: String,
    ): String {
        val existingIndent = existingSnippet
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.takeWhile(Char::isWhitespace)
            .orEmpty()
        return replacementSnippet.lineSequence().joinToString("\n") { line ->
            if (line.isBlank()) {
                ""
            } else {
                existingIndent + line
            }
        }
    }

    /** 去掉载荷外层大括号与首尾空白，得到流程类包装体内的纯片段。 */
    private fun normalizeScopedJavaBodyPayload(payload: String): String {
        val trimmed = payload.trim()
        return if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed.removeSurrounding("{", "}").trim().trimIndent()
        } else {
            trimmed.trimIndent()
        }
    }

    /** 识别 Java 流程控制包装关键字（if/else/try/for/while/switch/synchronized/catch/finally），便于包装保留逻辑分支。 */
    private fun scopedJavaWrapperKeyword(snippet: String): String? {
        val normalized = snippet.trimStart()
        return when {
            normalized.startsWith("try") -> "try"
            normalized.startsWith("if") -> "if"
            normalized.startsWith("else if") -> "else if"
            normalized.startsWith("else") -> "else"
            normalized.startsWith("for") -> "for"
            normalized.startsWith("while") -> "while"
            normalized.startsWith("switch") -> "switch"
            normalized.startsWith("synchronized") -> "synchronized"
            normalized.startsWith("catch") -> "catch"
            normalized.startsWith("finally") -> "finally"
            else -> null
        }
    }

    /** 检测载荷是否已自带同类型包装关键字，避免重复包装。 */
    private fun payloadAlreadyContainsScopedWrapper(
        payload: String,
        wrapperKeyword: String,
    ): Boolean {
        return when (wrapperKeyword) {
            "else if" -> payload.startsWith("else if") || payload.startsWith("if")
            "else" -> payload.startsWith("else")
            else -> payload.startsWith(wrapperKeyword)
        }
    }

    /** 找到片段第一个 `{` 到其配对 `}` 的字符范围，未闭合时返回 null。 */
    private fun topLevelBraceRange(snippet: String): IntRange? {
        val openBrace = snippet.indexOf('{')
        if (openBrace < 0) {
            return null
        }
        var depth = 0
        for (index in openBrace until snippet.length) {
            when (snippet[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return openBrace..index
                    }
                }
            }
        }
        return null
    }

    /** 通过统一签名格式化器输出 PsiMethod 的标准签名文本，便于跨工具比较。 */
    private fun javaMethodSignature(method: PsiMethod): String {
        return JavaMethodSignatureFormatter.methodSignature(method)
    }

    /** 判断 Java 方法签名是否匹配作用域的预期签名；签名缺失时退化为方法名匹配。 */
    private fun matchesScopeSignature(
        method: PsiMethod,
        scope: EditScope,
    ): Boolean {
        val expected = scope.symbolSignature ?: return true
        val actual = javaMethodSignature(method)
        if (actual == expected) {
            return true
        }
        val expectedMethodName = expected.substringBefore('(').substringAfterLast('.')
        return method.name == expectedMethodName
    }

    /** 构造 Kotlin 函数的统一签名文本，使用 fqName 拼出 owner，并对基础类型做 kotlin 命名空间归一化。 */
    private fun kotlinFunctionSignature(function: KtNamedFunction): String {
        val owner = function.fqName?.asString()
            ?: PsiTreeUtil.getParentOfType(function, KtClassOrObject::class.java, true)
                ?.fqName
                ?.asString()
                ?.let { classFqName -> "$classFqName.${function.name}" }
            ?: function.containingKtFile.packageFqName.asString().takeIf { it.isNotBlank() }
                ?.let { packageName -> "${packageName}.${function.name}" }
            ?: function.name
            ?: "<anonymous>"
        val parameters = function.valueParameters.joinToString(",") { parameter ->
            normalizeKotlinTypeName(parameter.typeReference?.text ?: "kotlin.Any")
        }
        val returnType = normalizeKotlinTypeName(function.typeReference?.text ?: "kotlin.Unit")
        return "$owner($parameters):$returnType"
    }

    /** 判断 Kotlin 函数签名是否匹配作用域；签名缺失时退化为函数名匹配。 */
    private fun matchesKotlinScopeSignature(
        function: KtNamedFunction,
        scope: EditScope,
    ): Boolean {
        val expected = scope.symbolSignature ?: return true
        val actual = kotlinFunctionSignature(function)
        if (actual == expected) {
            return true
        }
        val expectedFunctionName = expected.substringBefore('(').substringAfterLast('.')
        return function.name == expectedFunctionName
    }

    /** 把 Kotlin 基础类型（去 `?`）补齐 kotlin. 前缀，便于跨上下文比对签名一致性。 */
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
}
