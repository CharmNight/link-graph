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
 * 对结构化 existing-file 改写做本地 authoritative 校验。
 */
class CodeEditValidationService(
    private val project: Project,
) {
    fun validateExistingFileRewrite(
        filePath: String,
        beforeText: String,
        afterText: String,
        allowedScopes: List<EditScope>,
        operations: List<CodeEditOperation> = emptyList(),
    ): CodeEditValidationResult {
        val normalizedPath = filePath.replace('\\', '/')
        val relevantScopes = allowedScopes.filter { scope ->
            scope.filePath.replace('\\', '/') == normalizedPath
        }
        val allowedKinds = relevantScopes.flatMap(EditScope::allowedChangeKinds).toSet()
        if (relevantScopes.isEmpty()) {
            return CodeEditValidationResult(
                isValid = false,
                warnings = listOf("existing-file writeback 缺少与目标文件匹配的 validated scope。"),
            )
        }
        if (!normalizedPath.endsWith(".java", ignoreCase = true)) {
            if (!normalizedPath.endsWith(".kt", ignoreCase = true)) {
                return CodeEditValidationResult(
                    isValid = false,
                    warnings = listOf("当前结构化 apply gate 仅支持 Java/Kotlin existing-file writeback。"),
                )
            }
            val changedSymbols = changedKotlinFunctionSignatures(beforeText, afterText)
            val allowedSymbols = relevantScopes.mapNotNull(EditScope::symbolSignature).toSet()
            if (changedSymbols.isEmpty()) {
                return CodeEditValidationResult(
                    isValid = false,
                    changedSymbols = changedSymbols,
                    warnings = listOf("结构化改写没有产生任何受控函数变更。"),
                )
            }
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
        val beforeSnapshot = javaStructureSnapshot(beforeText)
        val afterSnapshot = javaStructureSnapshot(afterText)
        val changedSymbols = changedExistingJavaMethodSignatures(beforeSnapshot, afterSnapshot)
        val allowedSymbols = relevantScopes.mapNotNull(EditScope::symbolSignature).toSet()
        val addedMethods = afterSnapshot.methods.keys - beforeSnapshot.methods.keys
        val removedMethods = beforeSnapshot.methods.keys - afterSnapshot.methods.keys
        val addedImports = afterSnapshot.imports - beforeSnapshot.imports
        val removedImports = beforeSnapshot.imports - afterSnapshot.imports
        val addedFields = afterSnapshot.fields - beforeSnapshot.fields
        val removedFields = beforeSnapshot.fields - afterSnapshot.fields
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
        val outOfScope = changedSymbols - allowedSymbols
        if (outOfScope.isNotEmpty()) {
            return CodeEditValidationResult(
                isValid = false,
                changedSymbols = changedSymbols,
                warnings = listOf("检测到越界符号改写：${outOfScope.joinToString()}"),
            )
        }
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

    private fun changedExistingJavaMethodSignatures(
        before: JavaStructureSnapshot,
        after: JavaStructureSnapshot,
    ): Set<String> {
        return (before.methods.keys intersect after.methods.keys).filterTo(linkedSetOf()) { signature ->
            before.methods[signature] != after.methods[signature]
        }
    }

    private fun changedKotlinFunctionSignatures(
        before: String,
        after: String,
    ): Set<String> {
        val psiFactory = KtPsiFactory(project)
        val beforeFile = psiFactory.createFile("Before.kt", before)
        val afterFile = psiFactory.createFile("After.kt", after)
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

    private fun methodSignature(method: PsiMethod): String {
        return "${method.containingClass?.qualifiedName}.${method.name}(" +
            method.parameterList.parameters.joinToString(",") { parameter -> normalizeTypeName(parameter.type.canonicalText) } +
            "):${normalizeTypeName(method.returnType?.canonicalText ?: "void")}"
    }

    private fun normalizedMethodText(method: PsiMethod): String {
        return method.text.replace("\r\n", "\n").replace(Regex("\\s+"), " ").trim()
    }

    private fun javaStructureSnapshot(source: String): JavaStructureSnapshot {
        val fileFactory = PsiFileFactory.getInstance(project)
        val psiFile = fileFactory.createFileFromText("Snapshot.java", JavaFileType.INSTANCE, source) as PsiJavaFile
        val imports = psiFile.importList?.allImportStatements
            ?.map { statement -> statement.text.replace("\r\n", "\n").trim() }
            ?.toSet()
            .orEmpty()
        val fields = PsiTreeUtil.findChildrenOfType(psiFile, PsiField::class.java)
            .map { field -> field.text.replace("\r\n", "\n").replace(Regex("\\s+"), " ").trim() }
            .toSet()
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

    private fun kotlinFunctionSignature(function: KtNamedFunction): String {
        val ownerPrefix = PsiTreeUtil.getParentOfType(function, KtClassOrObject::class.java, true)
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
        return "$ownerPrefix($parameters):$returnType"
    }

    private fun normalizedKotlinFunctionText(function: KtNamedFunction): String {
        return function.text.replace("\r\n", "\n").replace(Regex("\\s+"), " ").trim()
    }

    private fun normalizeTypeName(typeName: String): String {
        return when (typeName) {
            "String" -> "java.lang.String"
            "Object" -> "java.lang.Object"
            "Integer" -> "java.lang.Integer"
            "Long" -> "java.lang.Long"
            "Boolean" -> "java.lang.Boolean"
            "Double" -> "java.lang.Double"
            "Float" -> "java.lang.Float"
            "Short" -> "java.lang.Short"
            "Byte" -> "java.lang.Byte"
            "Character" -> "java.lang.Character"
            "Void" -> "java.lang.Void"
            else -> typeName
        }
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

    private data class JavaStructureSnapshot(
        val imports: Set<String>,
        val fields: Set<String>,
        val methods: Map<String, String>,
    )
}
