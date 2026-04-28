package com.charmnight.linkgraph.codegen

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod

internal object JavaMethodSignatureTextSupport {
    private val implicitJavaLangTypes = setOf(
        "String",
        "Object",
        "Integer",
        "Long",
        "Boolean",
        "Double",
        "Float",
        "Short",
        "Byte",
        "Character",
        "Void",
    )

    private val primitiveTypes = setOf(
        "boolean",
        "byte",
        "char",
        "double",
        "float",
        "int",
        "long",
        "short",
        "void",
    )

    private val typeAnnotationRegex = Regex("""@[\w$.]+(?:\([^)]*\))?\s*""")

    fun methodSignature(method: PsiMethod): String {
        val javaFile = method.containingFile as? PsiJavaFile
        val ownerName = method.containingClass?.qualifiedName
            ?: method.containingClass?.name
            ?: method.name
        val context = TypeContext(
            packageName = javaFile?.packageName.orEmpty(),
            explicitImports = explicitImports(javaFile),
            typeParameters = collectTypeParameterNames(method),
        )
        val parameters = method.parameterList.parameters.joinToString(",") { parameter ->
            normalizeType(parameter.typeElement?.text ?: parameter.type.presentableText, context)
        }
        val returnType = normalizeType(method.returnTypeElement?.text ?: "void", context)
        return "$ownerName.${method.name}($parameters):$returnType"
    }

    private fun explicitImports(file: PsiJavaFile?): Map<String, String> {
        val importStatements = file?.importList?.allImportStatements.orEmpty()
        return buildMap {
            importStatements.forEach { statement ->
                val qualifiedName = statement.importReference?.qualifiedName ?: return@forEach
                if (statement.isOnDemand || statement.text.startsWith("import static ")) {
                    return@forEach
                }
                put(qualifiedName.substringAfterLast('.'), qualifiedName)
            }
        }
    }

    private fun collectTypeParameterNames(method: PsiMethod): Set<String> {
        val names = linkedSetOf<String>()
        var ownerClass: PsiClass? = method.containingClass
        while (ownerClass != null) {
            ownerClass.typeParameters.mapNotNullTo(names) { parameter -> parameter.name }
            ownerClass = ownerClass.containingClass
        }
        method.typeParameters.mapNotNullTo(names) { parameter -> parameter.name }
        return names
    }

    private fun normalizeType(
        rawType: String,
        context: TypeContext,
    ): String {
        val sanitized = rawType
            .replace(typeAnnotationRegex, "")
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (sanitized.isEmpty()) {
            return "void"
        }
        return TypeParser(sanitized, context).parse()
    }

    private fun qualifySegments(
        segments: List<String>,
        context: TypeContext,
    ): String {
        if (segments.isEmpty()) {
            return ""
        }
        val head = segments.first()
        if (head in primitiveTypes || head in context.typeParameters || head.firstOrNull()?.isLowerCase() == true) {
            return segments.joinToString(".")
        }
        val base = context.explicitImports[head]
            ?: head.takeIf { it in implicitJavaLangTypes }?.let { "java.lang.$it" }
            ?: context.packageName.takeIf(String::isNotBlank)?.let { packageName -> "$packageName.$head" }
            ?: head
        return if (segments.size == 1) {
            base
        } else {
            "$base.${segments.drop(1).joinToString(".")}"
        }
    }

    private data class TypeContext(
        val packageName: String,
        val explicitImports: Map<String, String>,
        val typeParameters: Set<String>,
    )

    private class TypeParser(
        private val source: String,
        private val context: TypeContext,
    ) {
        private var index: Int = 0

        fun parse(): String {
            val normalized = parseType()
            skipWhitespace()
            return normalized
        }

        private fun parseType(): String {
            skipWhitespace()
            if (peek() == '?') {
                index += 1
                skipWhitespace()
                return when {
                    consumeKeyword("extends") -> "? extends ${parseType()}"
                    consumeKeyword("super") -> "? super ${parseType()}"
                    else -> "?"
                }
            }

            val segments = mutableListOf<String>()
            segments += readIdentifier()
            while (true) {
                skipWhitespace()
                if (peek() != '.') {
                    break
                }
                index += 1
                skipWhitespace()
                segments += readIdentifier()
            }

            val builder = StringBuilder(qualifySegments(segments, context))
            skipWhitespace()
            if (peek() == '<') {
                builder.append(parseTypeArguments())
            }
            skipWhitespace()
            while (true) {
                when {
                    source.startsWith("...", index) -> {
                        builder.append("...")
                        index += 3
                    }
                    source.startsWith("[]", index) -> {
                        builder.append("[]")
                        index += 2
                    }
                    else -> return builder.toString()
                }
                skipWhitespace()
            }
        }

        private fun parseTypeArguments(): String {
            val builder = StringBuilder()
            builder.append('<')
            index += 1
            while (true) {
                builder.append(parseType())
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        builder.append(',')
                        index += 1
                    }
                    '>' -> {
                        builder.append('>')
                        index += 1
                        return builder.toString()
                    }
                    else -> return builder.toString()
                }
            }
        }

        private fun consumeKeyword(keyword: String): Boolean {
            if (!source.startsWith(keyword, index)) {
                return false
            }
            val end = index + keyword.length
            val next = source.getOrNull(end)
            if (next != null && next.isJavaIdentifierPart()) {
                return false
            }
            index = end
            skipWhitespace()
            return true
        }

        private fun readIdentifier(): String {
            skipWhitespace()
            val start = index
            while (source.getOrNull(index)?.isJavaIdentifierPart() == true || source.getOrNull(index) == '$') {
                index += 1
            }
            return if (start == index) {
                ""
            } else {
                source.substring(start, index)
            }
        }

        private fun skipWhitespace() {
            while (source.getOrNull(index)?.isWhitespace() == true) {
                index += 1
            }
        }

        private fun peek(): Char? = source.getOrNull(index)
    }
}
