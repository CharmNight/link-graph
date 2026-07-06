package com.charmnight.linkgraph.docs

import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val removedInternalPlanPath = removedInternalDocsPath()

        assertTrue(overview.contains("linkgraph/index.html"))
        assertTrue(overview.contains("JCEF"))
        assertTrue(overview.contains("本地规则"))
        assertTrue(overview.contains("工作区视图"))
        assertTrue(overview.contains("架构图"))
        assertTrue(overview.contains("类图"))
        assertTrue(overview.contains("Review Graph"))
        assertTrue(overview.contains("diagrams/link-graph-architecture.svg"))
        assertFalse(overview.contains(".mmd"))
        assertFalse(overview.contains(machineLocalPathMarker()))

        assertFalse(structure.contains(removedInternalPlanPath))
        assertFalse(structure.contains("docs/archive"))
        assertTrue(structure.contains("docs/diagrams/"))
        assertTrue(structure.contains("docs/diagrams/src/"))
        assertTrue(structure.contains("projection/business"))
        assertTrue(structure.contains("frontendInstall"))
        assertTrue(structure.contains("frontendPackResources"))
        assertTrue(structure.contains("controllers"))
        assertTrue(structure.contains("`workbench`"))
        assertTrue(structure.contains("views/architecture"))
        assertTrue(structure.contains("views/class-diagram"))
        assertTrue(structure.contains("views/review"))
        assertTrue(structure.contains("问答会话"))
        assertTrue(structure.contains("草稿确认"))
        assertTrue(structure.contains("证据闸门"))
        assertTrue(structure.contains("resolver 实现"))

        assertTrue(readme.contains("IntelliJ Platform 插件"))
        assertTrue(readme.contains("Mermaid"))
        assertTrue(readme.contains("本地规则"))
        assertTrue(readme.contains("六类工作区视图"))
        assertTrue(readme.contains("Review Graph"))
        assertTrue(readme.contains("docs/getting-started.md"))
        assertTrue(readme.contains("docs/usage.md"))

        assertTrue(gettingStarted.contains("./gradlew runIde"))
        assertTrue(gettingStarted.contains("最小验证步骤"))
        assertTrue(gettingStarted.contains("加载架构图、类图或 Review Graph"))

        assertTrue(usage.contains("事实图"))
        assertTrue(usage.contains("流程图"))
        assertTrue(usage.contains("资源关系视图"))
        assertTrue(usage.contains("架构图"))
        assertTrue(usage.contains("类图"))
        assertTrue(usage.contains("Review Graph"))
        assertTrue(usage.contains("导入 Mermaid"))
        assertTrue(usage.contains("实现建议"))
        assertTrue(usage.contains("代码 diff"))

        assertTrue(development.contains("frontendPackResources"))
        assertTrue(development.contains("文档必须使用中文"))

        assertTrue(limitations.contains("远程 LLM"))
        assertTrue(limitations.contains("回退到本地规则"))
        assertTrue(limitations.contains("架构图、类图和 Review Graph"))
    }

    @Test
    fun publicDocsCoverCurrentWorkspaceDisplayModes() {
        val publicDocs = listOf(
            Path.of("README.md"),
            Path.of("docs/architecture.md"),
            Path.of("docs/project-structure.md"),
            Path.of("docs/getting-started.md"),
            Path.of("docs/usage.md"),
            Path.of("docs/features-and-limitations.md"),
        ).joinToString("\n") { path -> Files.readString(path) }

        AnalysisDisplayMode.entries.forEach { mode ->
            assertTrue(publicDocs.contains(mode.documentationLabel()), "公开文档缺少 display mode 口径: $mode")
        }

        val workspaceSceneIds = GraphSceneId.entries.filter { sceneId -> sceneId != GraphSceneId.DIFF }
        workspaceSceneIds.forEach { sceneId ->
            assertTrue(publicDocs.contains(sceneId.documentationLabel()), "公开文档缺少 workspace scene 口径: $sceneId")
        }

        assertFalse(publicDocs.contains("三种视图"), "公开文档不应继续使用过期的三种视图口径。")
        assertFalse(publicDocs.contains("三视图阅读"), "公开文档不应继续使用过期的三视图阅读口径。")
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
        val removedInternalPlanPath = removedInternalDocsPath()
        val publicDocs = Files.walk(docsRoot)
            .filter { path -> Files.isRegularFile(path) }
            .filter { path -> path.toString().endsWith(".md") }
            .filter { path -> isPublicDocsPath(path, docsRoot) }
            .toList()

        assertTrue(publicDocs.isNotEmpty())
        publicDocs.forEach { path ->
            val source = Files.readString(path)
            assertFalse(source.contains(machineLocalPathMarker()), "公开文档不能包含本机绝对路径: $path")
            assertFalse(source.contains(removedInternalPlanPath), "公开文档不能链接内部计划: $path")
        }
    }

    @Test
    fun publicRepositoryFilesExcludeProcessPlansAndMachineLocalPaths() {
        val docsRoot = Path.of("docs")
        val publicDocsRootFiles = Files.list(docsRoot)
            .filter { path -> Files.isRegularFile(path) }
            .map { path -> path.fileName.toString() }
            .toList()
            .toSet()
        assertEquals(
            setOf(
                "architecture.md",
                "development.md",
                "features-and-limitations.md",
                "getting-started.md",
                "installation.md",
                "project-structure.md",
                "usage.md",
            ),
            publicDocsRootFiles,
            "公开 docs 根目录只能保留入口文档；过程、计划、原型文档应移入 docs/internal。",
        )

        val marker = machineLocalPathMarker()
        val publicRoots = listOf(
            Path.of("README.md"),
            Path.of("CONTRIBUTING.md"),
            Path.of(".github"),
            docsRoot,
            Path.of("src/main"),
            Path.of("src/test"),
            Path.of("src/integrationTest"),
            Path.of("web/src/app"),
            Path.of("web/src/test"),
            Path.of("build.gradle.kts"),
            Path.of("gradle.properties"),
        )
        val publicFiles = publicRoots.flatMap(::repositoryFilesForPublicScan)

        publicFiles.forEach { path ->
            val source = Files.readString(path)
            assertFalse(source.contains(marker), "公开源码/测试不能包含本机绝对路径: $path")
            assertFalse(
                source.contains(missingInternalDesignDocPath()),
                "公开源码/测试不能引用不存在的内部设计文档: $path",
            )
        }
    }

    @Test
    fun ciWorkflowMatchesDocumentedFullCheckContract() {
        val contributing = Files.readString(Path.of("CONTRIBUTING.md"))
        val development = Files.readString(Path.of("docs/development.md"))
        val ci = Files.readString(Path.of(".github/workflows/ci.yml"))

        assertTrue(contributing.contains("`./gradlew check`"))
        assertTrue(development.contains("`check` 会执行后端检查、集成测试"))
        assertTrue(ci.contains("./gradlew check"), "文档把 ./gradlew check 作为 CI 主流程时, workflow 也必须跑 check。")
    }
}

private fun removedInternalDocsDirName(): String =
    listOf("super", "powers").joinToString("")

private fun removedInternalDocsPath(): String =
    listOf("docs", removedInternalDocsDirName()).joinToString("/")

private fun machineLocalPathMarker(): String =
    listOf("", "Users", "").joinToString("/")

private fun missingInternalDesignDocPath(): String =
    listOf("design", "IMPLEMENTATION.md").joinToString("/")

private fun repositoryFilesForPublicScan(root: Path): List<Path> {
    if (!Files.exists(root)) {
        return emptyList()
    }
    if (Files.isRegularFile(root)) {
        return listOf(root).filter(::isPublicTextFile)
    }
    return Files.walk(root)
        .filter { path -> Files.isRegularFile(path) }
        .filter(::isPublicDocsPath)
        .filter(::isPublicTextFile)
        .toList()
}

private fun isPublicDocsPath(path: Path, docsRoot: Path = Path.of("docs")): Boolean =
    internalDocsRoots(docsRoot).none { internalRoot -> path.startsWith(internalRoot) }

private fun internalDocsRoots(docsRoot: Path): List<Path> =
    listOf("internal", removedInternalDocsDirName()).map { dirName -> docsRoot.resolve(dirName) }

private fun isPublicTextFile(path: Path): Boolean {
    val name = path.fileName.toString()
    return name.endsWith(".kt") ||
        name.endsWith(".kts") ||
        name.endsWith(".java") ||
        name.endsWith(".ts") ||
        name.endsWith(".tsx") ||
        name.endsWith(".md") ||
        name.endsWith(".yml") ||
        name.endsWith(".yaml") ||
        name.endsWith(".html") ||
        name.endsWith(".xml") ||
        name.endsWith(".properties") ||
        name.endsWith(".json")
}

private fun AnalysisDisplayMode.documentationLabel(): String = when (this) {
    AnalysisDisplayMode.FACT_GRAPH -> "事实图"
    AnalysisDisplayMode.FLOWCHART -> "流程图"
    AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> "资源关系视图"
    AnalysisDisplayMode.ARCHITECTURE_GRAPH -> "架构图"
    AnalysisDisplayMode.CLASS_DIAGRAM -> "类图"
    AnalysisDisplayMode.REVIEW_GRAPH -> "Review Graph"
}

private fun GraphSceneId.documentationLabel(): String = when (this) {
    GraphSceneId.WORKSPACE_FACT -> "事实图"
    GraphSceneId.WORKSPACE_FLOWCHART -> "流程图"
    GraphSceneId.WORKSPACE_RESOURCE_RELATION -> "资源关系视图"
    GraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH -> "架构图"
    GraphSceneId.WORKSPACE_CLASS_DIAGRAM -> "类图"
    GraphSceneId.WORKSPACE_REVIEW_GRAPH -> "Review Graph"
    GraphSceneId.DIFF -> "代码 diff"
}
