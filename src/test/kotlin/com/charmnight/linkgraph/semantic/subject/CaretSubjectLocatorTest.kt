package com.charmnight.linkgraph.semantic.subject

import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CaretSubjectLocatorTest : BasePlatformTestCase() {
    fun testLocateJavaMethodSubject() {
        myFixture.configureByText(
            "OrderService.java",
            """
                package com.example;

                class OrderService {
                    void place(String value) {
                        System.out.println(<caret>value);
                    }
                }
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)
        assertEquals(CodeSubjectKind.JAVA_METHOD, codeHandle.kind)
        assertEquals("OrderService.place", codeHandle.displayName)
    }

    fun testLocateKotlinAccessorSubject() {
        myFixture.configureByText(
            "AccessorService.kt",
            """
                package com.example

                class AccessorService {
                    var raw: String = " seed "
                        get() = <caret>field.trim()
                }
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)
        assertEquals(CodeSubjectKind.KOTLIN_PROPERTY_ACCESSOR, codeHandle.kind)
        assertEquals("AccessorService.getRaw", codeHandle.displayName)
    }

    fun testLocateKotlinFunctionSubject() {
        myFixture.configureByText(
            "OrderService.kt",
            """
                package com.example

                class OrderService {
                    fun pla<caret>ce(value: String) {
                        println(value)
                    }
                }
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val codeHandle = assertInstanceOf(handle, CodeSubjectHandle::class.java)
        assertEquals(CodeSubjectKind.KOTLIN_FUNCTION, codeHandle.kind)
        assertEquals("OrderService.place", codeHandle.displayName)
    }

    fun testPreviewKindSupportsKotlinFunctionDuringDumbMode() {
        myFixture.configureByText(
            "OrderService.kt",
            """
                package com.example

                class OrderService {
                    fun pla<caret>ce(value: String) {
                        println(value)
                    }
                }
            """.trimIndent(),
        )

        var previewKind: SubjectPreviewKind? = null
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            previewKind = CaretSubjectLocator().previewKind(project, myFixture.editor, commitDocument = false)
        }

        assertEquals(SubjectPreviewKind.CODE_SUBJECT, previewKind)
    }

    fun testPreviewKindSupportsMarkdownResource() {
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                调用入口：com.example.OrderService#sub<caret>mit
            """.trimIndent(),
        )

        val previewKind = CaretSubjectLocator().previewKind(project, myFixture.editor, commitDocument = false)

        assertEquals(SubjectPreviewKind.RESOURCE_SUBJECT, previewKind)
    }

    fun testPreviewKindDoesNotRequireFullResourceDecode() {
        myFixture.configureByText(
            "notes.txt",
            """
                plain <caret>text
            """.trimIndent(),
        )
        var decodeCallCount = 0
        val locator = CaretSubjectLocator(
            resourceDecoders = listOf(
                object : ResourceDecoder {
                    override fun supports(file: com.intellij.psi.PsiFile): Boolean = true

                    override fun preview(
                        file: com.intellij.psi.PsiFile,
                        editor: com.intellij.openapi.editor.Editor,
                        caretOffset: Int,
                    ): Boolean = true

                    override fun decode(
                        file: com.intellij.psi.PsiFile,
                        editor: com.intellij.openapi.editor.Editor,
                        caretOffset: Int,
                    ): ResourceSubjectHandle? {
                        decodeCallCount += 1
                        return null
                    }
                },
            ),
        )

        val previewKind = locator.previewKind(project, myFixture.editor, commitDocument = false)

        assertEquals(SubjectPreviewKind.RESOURCE_SUBJECT, previewKind)
        assertEquals(0, decodeCallCount)
    }
}
