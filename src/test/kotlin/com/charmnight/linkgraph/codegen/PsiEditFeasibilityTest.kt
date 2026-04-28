package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.services.DebugMethodSignatureLocator
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PsiEditFeasibilityTest : BasePlatformTestCase() {
    fun testJavaMethodLevelPsiEditCanChangeOnlyTargetMethod() {
        val psiFile = myFixture.configureByText(
            "CommonController.java",
            """
                package com.example;

                public class CommonController {
                    public String download(String resource) {
                        return resource;
                    }

                    public String uploadFile(String fileName) {
                        return fileName;
                    }
                }
            """.trimIndent(),
        )
        val signature = "com.example.CommonController.uploadFile(java.lang.String):java.lang.String"
        val method = DebugMethodSignatureLocator.find(project, signature)
        val originalText = psiFile.text
        val targetMethod = requireNotNull(method)
        val replacement = JavaPsiFacade.getElementFactory(project).createMethodFromText(
            """
                public String uploadFile(String fileName) {
                    if (fileName == null || fileName.isBlank()) {
                        throw new IllegalArgumentException("fileName");
                    }
                    return fileName.trim();
                }
            """.trimIndent(),
            targetMethod.containingClass,
        )

        WriteCommandAction.runWriteCommandAction(project) {
            targetMethod.replace(replacement)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }

        val written = psiFile.text
        assertTrue(written.contains("""throw new IllegalArgumentException("fileName")"""))
        assertTrue(written.contains("return fileName.trim();"))
        assertTrue(written.contains("public String download(String resource) {\n        return resource;\n    }"))
        assertFalse(written.contains("return fileName;\n    }"))
        assertTrue(originalText.contains("public String download(String resource)"))
    }

    fun testKotlinFunctionLevelPsiEditCanChangeOnlyTargetFunction() {
        val psiFile = myFixture.configureByText(
            "CommonController.kt",
            """
                package com.example

                class CommonController {
                    fun download(resource: String): String {
                        return resource
                    }

                    fun uploadFile(fileName: String): String {
                        return fileName
                    }
                }
            """.trimIndent(),
        ) as KtFile
        val targetFunction = PsiTreeUtil.findChildrenOfType(psiFile, KtNamedFunction::class.java)
            .firstOrNull { function -> function.name == "uploadFile" }
        val originalText = psiFile.text
        val replacement = KtPsiFactory(project).createFunction(
            """
                fun uploadFile(fileName: String): String {
                    require(fileName.isNotBlank()) { "fileName" }
                    return fileName.trim()
                }
            """.trimIndent(),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            requireNotNull(targetFunction).replace(replacement)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }

        val written = psiFile.text
        assertTrue(written.contains("""require(fileName.isNotBlank()) { "fileName" }"""))
        assertTrue(written.contains("return fileName.trim()"))
        assertTrue(written.contains("fun download(resource: String): String {\n        return resource\n    }"))
        assertFalse(written.contains("return fileName\n    }"))
        assertTrue(originalText.contains("fun download(resource: String): String"))
    }

    fun testJavaMethodCanBeResolvedFromSourceOffsetsAndEditedPrecisely() {
        val psiFile = myFixture.configureByText(
            "CommonController.java",
            """
                package com.example;

                public class CommonController {
                    public String download(String resource) {
                        return resource;
                    }

                    public String uploadFile(String fileName) {
                        return fileName;
                    }
                }
            """.trimIndent(),
        )
        val startOffset = psiFile.text.indexOf("public String uploadFile")
        val targetMethod = resolveJavaMethodAtOffset(psiFile.text, startOffset)
        val replacement = JavaPsiFacade.getElementFactory(project).createMethodFromText(
            """
                public String uploadFile(String fileName) {
                    return fileName.trim();
                }
            """.trimIndent(),
            targetMethod.containingClass,
        )

        WriteCommandAction.runWriteCommandAction(project) {
            targetMethod.replace(replacement)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }

        val written = psiFile.text
        assertTrue(written.contains("return fileName.trim();"))
        assertTrue(written.contains("public String download(String resource) {\n        return resource;\n    }"))
        assertFalse(written.contains("return fileName;\n    }"))
    }

    fun testKotlinFunctionCanBeResolvedFromSourceOffsetsAndEditedPrecisely() {
        val psiFile = myFixture.configureByText(
            "CommonController.kt",
            """
                package com.example

                class CommonController {
                    fun download(resource: String): String {
                        return resource
                    }

                    fun uploadFile(fileName: String): String {
                        return fileName
                    }
                }
            """.trimIndent(),
        ) as KtFile
        val startOffset = psiFile.text.indexOf("fun uploadFile")
        val targetFunction = resolveKotlinFunctionAtOffset(psiFile, startOffset)
        val replacement = KtPsiFactory(project).createFunction(
            """
                fun uploadFile(fileName: String): String {
                    return fileName.trim()
                }
            """.trimIndent(),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            targetFunction.replace(replacement)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }

        val written = psiFile.text
        assertTrue(written.contains("return fileName.trim()"))
        assertTrue(written.contains("fun download(resource: String): String {\n        return resource\n    }"))
        assertFalse(written.contains("return fileName\n    }"))
    }

    fun testJavaMethodLocatorStillReturnsSingleTargetBeforeEditing() {
        myFixture.configureByText(
            "CommonController.java",
            """
                package com.example;

                public class CommonController {
                    public String download(String resource) {
                        return resource;
                    }

                    public String uploadFile(String fileName) {
                        return fileName;
                    }
                }
            """.trimIndent(),
        )

        val method = DebugMethodSignatureLocator.find(
            project,
            "com.example.CommonController.uploadFile(java.lang.String):java.lang.String",
        )

        val targetMethod = requireNotNull(method)
        assertEquals("uploadFile", targetMethod.name)
        assertEquals("CommonController", targetMethod.containingClass?.name)
    }

    fun testPsiCanIdentifyExactlyWhichJavaMethodsChanged() {
        val before = """
            package com.example;

            public class CommonController {
                public String download(String resource) {
                    return resource;
                }

                public String uploadFile(String fileName) {
                    return fileName;
                }
            }
        """.trimIndent()
        val after = """
            package com.example;

            public class CommonController {
                public String download(String resource) {
                    return resource + "-changed";
                }

                public String uploadFile(String fileName) {
                    return fileName.trim();
                }
            }
        """.trimIndent()

        val changedMethods = changedJavaMethodSignatures(before, after)

        assertEquals(
            setOf(
                "com.example.CommonController.download(java.lang.String):java.lang.String",
                "com.example.CommonController.uploadFile(java.lang.String):java.lang.String",
            ),
            changedMethods,
        )
    }

    fun testPsiCanConfirmWhenOnlyAllowedJavaMethodChanged() {
        val before = """
            package com.example;

            public class CommonController {
                public String download(String resource) {
                    return resource;
                }

                public String uploadFile(String fileName) {
                    return fileName;
                }
            }
        """.trimIndent()
        val after = """
            package com.example;

            public class CommonController {
                public String download(String resource) {
                    return resource;
                }

                public String uploadFile(String fileName) {
                    return fileName.trim();
                }
            }
        """.trimIndent()

        val changedMethods = changedJavaMethodSignatures(before, after)

        assertEquals(
            setOf("com.example.CommonController.uploadFile(java.lang.String):java.lang.String"),
            changedMethods,
        )
    }

    private fun resolveJavaMethodAtOffset(
        fileText: String,
        offset: Int,
    ): PsiMethod {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(myFixture.editor.document)
        val anchor = psiFile?.findElementAt(offset.coerceIn(0, fileText.lastIndex))
        return requireNotNull(PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java, false))
    }

    private fun resolveKotlinFunctionAtOffset(
        psiFile: KtFile,
        offset: Int,
    ): KtNamedFunction {
        val anchor = psiFile.findElementAt(offset.coerceIn(0, psiFile.text.lastIndex))
        return requireNotNull(PsiTreeUtil.getParentOfType(anchor, KtNamedFunction::class.java, false))
    }

    private fun changedJavaMethodSignatures(
        before: String,
        after: String,
    ): Set<String> {
        val fileFactory = PsiFileFactory.getInstance(project)
        val beforeFile = fileFactory.createFileFromText("Before.java", JavaFileType.INSTANCE, before)
        val afterFile = fileFactory.createFileFromText("After.java", JavaFileType.INSTANCE, after)
        val beforeMethods = PsiTreeUtil.findChildrenOfType(beforeFile, PsiMethod::class.java)
            .associateBy(
                keySelector = { method -> requireNotNull(methodSignature(method)) },
                valueTransform = { method -> normalizedMethodText(method) },
            )
        val afterMethods = PsiTreeUtil.findChildrenOfType(afterFile, PsiMethod::class.java)
            .associateBy(
                keySelector = { method -> requireNotNull(methodSignature(method)) },
                valueTransform = { method -> normalizedMethodText(method) },
            )
        return (beforeMethods.keys + afterMethods.keys).filterTo(linkedSetOf()) { signature ->
            beforeMethods[signature] != afterMethods[signature]
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
}
