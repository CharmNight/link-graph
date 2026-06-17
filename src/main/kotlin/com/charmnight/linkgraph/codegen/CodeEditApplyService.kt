package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.EditScope
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
 * 把结构化 edit op 应用到内存文本，并交给 validation gate 判定是否允许落盘。
 */
class CodeEditApplyService(
    private val project: Project,
    private val scopeResolver: CodeEditScopeResolver = CodeEditScopeResolver(),
    private val validationService: CodeEditValidationService = CodeEditValidationService(project),
) {
    private fun normalizedPayload(operation: CodeEditOperation): String = CodeEditPayloadNormalizer.normalize(operation.payload)

    private data class OrderedOperation(
        val operation: CodeEditOperation,
        val scope: EditScope?,
        val originalIndex: Int,
        val sortKey: Int?,
    )

    fun prepareEdits(
        filePath: String,
        beforeText: String,
        operations: List<CodeEditOperation>,
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

    private fun resolveScopeSortKey(scope: EditScope): Int? {
        scope.endOffset?.let { return it }
        scope.endLine?.let { return it * 1_000_000 }
        scope.startOffset?.let { return it }
        scope.startLine?.let { return it * 1_000_000 }
        return null
    }

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

    private fun normalizeJavaCodeBlockPayload(payload: String): String {
        val trimmed = payload.trim()
        return if (trimmed.startsWith("{")) {
            trimmed
        } else {
            "{\n$trimmed\n}"
        }
    }

    private fun shouldApplyScopedJavaSnippetReplacement(
        scope: EditScope,
        operation: CodeEditOperation,
    ): Boolean {
        if (operation.kind !in setOf(CodeEditOperationKind.REPLACE_METHOD_BLOCK, CodeEditOperationKind.REPLACE_METHOD_BODY)) {
            return false
        }
        return scope.symbolKind in setOf("FLOW_SCOPE", "FLOW_ACTION", "TERMINAL")
    }

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

    private fun resolveScopedJavaReplacementRange(
        currentText: String,
        operation: CodeEditOperation,
        scope: EditScope,
    ): Pair<Int, Int>? {
        val rawRange = resolveScopedTextRange(currentText, scope) ?: return null
        resolveScopedJavaControlStatementRange(currentText, operation, scope)?.let { return it }
        return rawRange
    }

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

    private fun normalizeScopedJavaBodyPayload(payload: String): String {
        val trimmed = payload.trim()
        return if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed.removeSurrounding("{", "}").trim().trimIndent()
        } else {
            trimmed.trimIndent()
        }
    }

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

    private fun javaMethodSignature(method: PsiMethod): String {
        return JavaMethodSignatureFormatter.methodSignature(method)
    }

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
