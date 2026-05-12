package com.charmnight.linkgraph.docs

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureDocumentationSanityTest {
    @Test
    fun architectureDocsMatchCurrentToolwindowAndFrontendModuleReality() {
        val overview = Files.readString(Path.of("docs/architecture.md"))
        val structure = Files.readString(Path.of("docs/project-structure.md"))
        val readme = Files.readString(Path.of("README.md"))
        val gettingStarted = Files.readString(Path.of("docs/getting-started.md"))
        val usage = Files.readString(Path.of("docs/usage.md"))
        val development = Files.readString(Path.of("docs/development.md"))
        val limitations = Files.readString(Path.of("docs/features-and-limitations.md"))

        assertTrue(overview.contains("linkgraph/index.html"))
        assertTrue(overview.contains("JCEF"))
        assertTrue(overview.contains("本地规则"))
        assertTrue(overview.contains("三种视图"))
        assertTrue(overview.contains("diagrams/link-graph-architecture.svg"))
        assertFalse(overview.contains(".mmd"))
        assertFalse(overview.contains("/Users/"))

        assertFalse(structure.contains("docs/superpowers"))
        assertFalse(structure.contains("docs/archive"))
        assertTrue(structure.contains("docs/diagrams/"))
        assertTrue(structure.contains("docs/diagrams/src/"))
        assertTrue(structure.contains("frontendInstall"))
        assertTrue(structure.contains("frontendPackResources"))
        assertTrue(structure.contains("controllers"))
        assertTrue(structure.contains("`workbench`"))
        assertTrue(structure.contains("问答会话"))
        assertTrue(structure.contains("草稿确认"))
        assertTrue(structure.contains("证据闸门"))
        assertTrue(structure.contains("resolver 实现"))

        assertTrue(readme.contains("IntelliJ Platform 插件"))
        assertTrue(readme.contains("Mermaid"))
        assertTrue(readme.contains("本地规则"))
        assertTrue(readme.contains("docs/getting-started.md"))
        assertTrue(readme.contains("docs/usage.md"))

        assertTrue(gettingStarted.contains("./gradlew runIde"))
        assertTrue(gettingStarted.contains("最小验证步骤"))

        assertTrue(usage.contains("事实图"))
        assertTrue(usage.contains("流程图"))
        assertTrue(usage.contains("资源关系视图"))
        assertTrue(usage.contains("导入 Mermaid"))
        assertTrue(usage.contains("实现建议"))
        assertTrue(usage.contains("代码 diff"))

        assertTrue(development.contains("frontendPackResources"))
        assertTrue(development.contains("文档必须使用中文"))

        assertTrue(limitations.contains("远程 LLM"))
        assertTrue(limitations.contains("回退到本地规则"))
    }

    @Test
    fun investigationResolvingDocumentsPackageOrganizationRules() {
        val resolvingReadme = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/README.md"),
        )

        assertTrue(resolvingReadme.contains("java/"))
        assertTrue(resolvingReadme.contains("spring/"))
        assertTrue(resolvingReadme.contains("Java PSI"))
        assertTrue(resolvingReadme.contains("Spring 语义"))
    }

    @Test
    fun publicDocsExcludeInternalPlansAndMachineLocalPaths() {
        val docsRoot = Path.of("docs")
        val publicDocs = Files.walk(docsRoot)
            .filter { path -> Files.isRegularFile(path) }
            .filter { path -> path.toString().endsWith(".md") }
            .filter { path -> !path.startsWith(docsRoot.resolve("internal")) }
            .toList()

        assertFalse(Files.exists(docsRoot.resolve("superpowers")), "内部 superpowers 计划不应保留在公开 docs 入口下。")
        assertTrue(publicDocs.isNotEmpty())
        publicDocs.forEach { path ->
            val source = Files.readString(path)
            assertFalse(source.contains("/Users/"), "公开文档不能包含本机绝对路径: $path")
            assertFalse(source.contains("docs/superpowers"), "公开文档不能链接内部 superpowers 计划: $path")
        }
    }
}
