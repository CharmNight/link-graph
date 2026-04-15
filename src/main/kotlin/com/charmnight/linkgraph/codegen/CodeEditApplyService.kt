package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.EditScope
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiMethod
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
    fun applyToText(
        filePath: String,
        beforeText: String,
        operations: List<CodeEditOperation>,
        editScopes: List<EditScope>,
    ): CodeEditApplyResult {
        if (operations.isEmpty()) {
            return CodeEditApplyResult(
                applied = false,
                updatedText = beforeText,
                warnings = listOf("当前没有可应用的结构化 edit operation。"),
            )
        }
        var updatedText = beforeText
        val warnings = mutableListOf<String>()
        val appliedScopes = mutableListOf<EditScope>()
        operations.forEach { operation ->
            val scope = scopeResolver.resolveScope(operation, editScopes)
            if (scope == null) {
                warnings += "操作 '${operation.operationId}' 缺少与目标文件匹配的 validated scope。"
                return CodeEditApplyResult(applied = false, updatedText = beforeText, warnings = warnings)
            }
            if (!scopeResolver.isOperationAllowed(operation, scope)) {
                warnings += "操作 '${operation.operationId}' 未授权，scope='${scope.scopeId}'，kind=${operation.kind.name}。"
                return CodeEditApplyResult(applied = false, updatedText = beforeText, warnings = warnings)
            }
            updatedText = applyOperation(updatedText, operation, scope)
                ?: return CodeEditApplyResult(
                    applied = false,
                    updatedText = beforeText,
                    warnings = warnings + "无法根据 scope '${scope.scopeId}' 精确定位并应用受控改写。",
                )
            appliedScopes += scope
            warnings += operation.warnings
        }

        val validation = validationService.validateExistingFileRewrite(
            filePath = filePath,
            beforeText = beforeText,
            afterText = updatedText,
            allowedScopes = appliedScopes,
            operations = operations,
        )
        if (!validation.isValid) {
            return CodeEditApplyResult(
                applied = false,
                updatedText = beforeText,
                warnings = warnings + validation.warnings,
            )
        }
        return CodeEditApplyResult(
            applied = true,
            updatedText = updatedText,
            warnings = warnings + validation.warnings,
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
        val replacement = JavaPsiFacade.getElementFactory(project).createMethodFromText(
            operation.payload,
            targetMethod.containingClass,
        )
        WriteCommandAction.runWriteCommandAction(project) {
            targetMethod.replace(replacement)
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
        val replacementBody = JavaPsiFacade.getElementFactory(project).createCodeBlockFromText(operation.payload, targetMethod)
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
        val importLine = operation.payload.trim().removeSuffix(";").let { "${it.trim()};" }
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
        val field = JavaPsiFacade.getElementFactory(project).createFieldFromText(operation.payload, targetClass)
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
        val newMethod = JavaPsiFacade.getElementFactory(project).createMethodFromText(operation.payload, targetClass)
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
        val replacement = psiFactory.createFunction(operation.payload)
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
        val replacement = psiFactory.createFunction(
            buildString {
                append("fun ")
                append(targetFunction.name)
                append(targetFunction.valueParameterList?.text ?: "()")
                targetFunction.typeReference?.text?.let { append(": ").append(it) }
                append(" ")
                append(operation.payload.trim())
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

    private fun javaMethodSignature(method: PsiMethod): String {
        return "${method.containingClass?.qualifiedName}.${method.name}(" +
            method.parameterList.parameters.joinToString(",") { parameter ->
                parameter.type.canonicalText
                    .replace("String", "java.lang.String")
                    .replace("Object", "java.lang.Object")
            } +
            "):${method.returnType?.canonicalText
                ?.replace("String", "java.lang.String")
                ?.replace("Object", "java.lang.Object")
                ?: "void"}"
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
