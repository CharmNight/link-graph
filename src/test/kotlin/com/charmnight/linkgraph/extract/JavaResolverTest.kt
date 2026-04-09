package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.testing.addJavaFixture
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaResolverTest : BasePlatformTestCase() {
    fun testExtractsCallerCalleeGraphAndMethodMetadata() {
        loadFixture("simple/SimpleCallChain.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.simple.SimpleCallChain",
            "load",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val loadNode = nodesByTitle["SimpleCallChain.load"] ?: error("missing load node")
        assertEquals(NodeType.METHOD, loadNode.type)
        assertEquals(
            "com.charmnight.linkgraph.fixtures.simple.SimpleCallChain.load(java.lang.String):java.lang.String",
            loadNode.signature,
        )
        assertEquals(listOf("java.lang.String"), loadNode.inputs)
        assertEquals(listOf("java.lang.String"), loadNode.outputs)
        assertEquals("Loads an order summary.", loadNode.doc)
        assertNotNull(loadNode.location)
        assertTrue(loadNode.location!!.contains("SimpleCallChain.java:"))

        assertTrue(nodesByTitle.containsKey("SimpleCallChain.sanitize"))
        assertTrue(nodesByTitle.containsKey("OrderGatewayImpl.fetch"))
        assertTrue(nodesByTitle.containsKey("OrderGatewayImpl.repositoryFetch"))

        val sanitizeAction = result.document.nodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("sanitize(orderId)")
        }
        val fetchAction = result.document.nodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("gateway.fetch(sanitized)")
        }
        val edges = result.document.edges
        assertTrue(
            edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == loadNode.id &&
                    edge.toNodeId == sanitizeAction.id &&
                    edge.metadata["callOrder"] == "0"
            },
        )
        assertTrue(
            edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == sanitizeAction.id &&
                    edge.toNodeId == nodesByTitle.getValue("SimpleCallChain.sanitize").id
            },
        )
        assertTrue(
            edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == loadNode.id &&
                    edge.toNodeId == fetchAction.id &&
                    edge.metadata["callOrder"] == "1"
            },
        )
        assertTrue(
            edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == fetchAction.id &&
                    edge.toNodeId == nodesByTitle.getValue("OrderGatewayImpl.fetch").id
            },
        )
    }

    fun testResolvesInterfaceImplementationsAndBuildsSimplifiedMethodFlow() {
        loadFixture("simple/SimpleCallChain.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.simple.SimpleCallChain",
            "branchy",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val nodes = result.document.nodes
        val branchyNode = nodes.single { it.title == "SimpleCallChain.branchy" }
        val implNode = nodes.single { it.title == "OrderGatewayImpl" && it.type == NodeType.CLASS }
        val interfaceNode = nodes.single { it.title == "OrderGateway" && it.type == NodeType.CLASS }

        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.IMPLEMENTS &&
                    edge.fromNodeId == implNode.id &&
                    edge.toNodeId == interfaceNode.id
            },
        )

        val flow = branchyNode.metadata["flow"]
        assertNotNull(flow)
        assertTrue(flow!!.contains("if (state == null)"))
        assertTrue(flow.contains("switch (state)"))
        assertTrue(flow.contains("case \"NEW\""))
        assertTrue(flow.contains("catch (IllegalArgumentException)"))
    }

    fun testResolvesAnchorMethodActionNodesWithSourceOffsetsAndScopeOrder() {
        loadFixture("simple/ScopedCallChain.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.simple.ScopedCallChain",
            "buildUser",
        )

        val resolvedFlow = JavaResolver().resolveCallGraph(method)

        val ifScope = resolvedFlow.scopeNodes.single { scope ->
            scope.type == NodeType.FLOW_SCOPE && scope.metadata["flow.kind"] == "IF"
        }
        val newUserAction = resolvedFlow.actionNodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("new SysUser()")
        }
        val copyBeanAction = resolvedFlow.actionNodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("BeanUtils.copyBeanProp(user, source)")
        }

        assertEquals(
            "com.charmnight.linkgraph.fixtures.simple.ScopedCallChain.buildUser(java.lang.Object):com.charmnight.linkgraph.fixtures.simple.SysUser",
            newUserAction.metadata["flow.anchorMethod"],
        )
        assertTrue(newUserAction.metadata["source.filePath"]!!.endsWith("ScopedCallChain.java"))
        assertNotNull(newUserAction.metadata["source.startOffset"])
        assertNotNull(newUserAction.metadata["source.endOffset"])

        val actionEdgesByTarget = resolvedFlow.actionEdges.associateBy { edge -> edge.toNodeId }
        assertEquals(ifScope.id, actionEdgesByTarget.getValue(newUserAction.id).fromNodeId)
        assertEquals(ifScope.id, actionEdgesByTarget.getValue(copyBeanAction.id).fromNodeId)
        assertEquals("0", actionEdgesByTarget.getValue(newUserAction.id).metadata["callOrder"])
        assertEquals("1", actionEdgesByTarget.getValue(copyBeanAction.id).metadata["callOrder"])
    }

    fun testAttachesNestedQualifierCallsUnderCurrentMethodActionNode() {
        myFixture.addFileToProject(
            "com/charmnight/linkgraph/fixtures/simple/NestedActionChain.java",
            """
            package com.charmnight.linkgraph.fixtures.simple;

            public class NestedActionChain {
                String render(Names names) {
                    return names.getRealmNames().iterator().next();
                }
            }

            class Names {
                RealmNames getRealmNames() {
                    return new RealmNames();
                }
            }

            class RealmNames {
                RealmIterator iterator() {
                    return new RealmIterator();
                }
            }

            class RealmIterator {
                String next() {
                    return "realm";
                }
            }
            """.trimIndent(),
        )
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.simple.NestedActionChain",
            "render",
        )

        val resolvedFlow = JavaResolver().resolveCallGraph(method)

        val nestedAction = resolvedFlow.actionNodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("getRealmNames().iterator().next()")
        }
        val callsByTargetTitle = resolvedFlow.calls.associateBy { call ->
            "${call.target.containingClass?.name}.${call.target.name}"
        }
        val actualCallSources = resolvedFlow.calls.joinToString(separator = "\n") { call ->
            "${call.sourceNodeId} -> ${call.target.containingClass?.name}.${call.target.name}"
        }

        kotlin.test.assertEquals(nestedAction.id, callsByTargetTitle.getValue("Names.getRealmNames").sourceNodeId, actualCallSources)
        kotlin.test.assertEquals(nestedAction.id, callsByTargetTitle.getValue("RealmNames.iterator").sourceNodeId, actualCallSources)
        kotlin.test.assertEquals(nestedAction.id, callsByTargetTitle.getValue("RealmIterator.next").sourceNodeId, actualCallSources)
    }

    fun testAttachesConstructorArgumentCallsUnderNewActionNode() {
        myFixture.addFileToProject(
            "com/charmnight/linkgraph/fixtures/simple/NestedConstructorAction.java",
            """
            package com.charmnight.linkgraph.fixtures.simple;

            public class NestedConstructorAction {
                Holder build(Factory factory) {
                    return new Holder(factory.create());
                }
            }

            class Factory {
                Payload create() {
                    return new Payload();
                }
            }

            class Holder {
                Holder(Payload payload) {
                }
            }

            class Payload {
            }
            """.trimIndent(),
        )
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.simple.NestedConstructorAction",
            "build",
        )

        val resolvedFlow = JavaResolver().resolveCallGraph(method)

        val newAction = resolvedFlow.actionNodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("new Holder(factory.create())")
        }
        val factoryCreate = resolvedFlow.calls.single { call ->
            call.target.containingClass?.name == "Factory" && call.target.name == "create"
        }

        kotlin.test.assertEquals(
            newAction.id,
            factoryCreate.sourceNodeId,
            resolvedFlow.calls.joinToString(separator = "\n") { call ->
                "${call.sourceNodeId} -> ${call.target.containingClass?.name}.${call.target.name}"
            },
        )
    }

    fun testAttachesGuardConditionAndThrowActionUnderIfScope() {
        myFixture.configureByText(
            "CommonController.java",
            """
            package com.ruoyi.web.controller.common;

            class CommonController {
                void fileDownload(String fileName, Boolean delete, Object response, Object request) {
                    try {
                        if (!FileUtils.checkAllowDownload(fileName)) {
                            throw new Exception(StringUtils.format("文件名称({})非法，不允许下载。 ", fileName));
                        }
                        String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
                        String filePath = RuoYiConfig.getDownloadPath() + fileName;
                        FileUtils.setAttachmentResponseHeader(response, realFileName);
                        FileUtils.writeBytes(filePath, response.toString());
                        if (delete) {
                            FileUtils.deleteFile(filePath);
                        }
                    } catch (Exception e) {
                        log(e);
                    }
                }

                void log(Exception e) {}
            }

            class FileUtils {
                static boolean checkAllowDownload(String fileName) { return true; }
                static void setAttachmentResponseHeader(Object response, String realFileName) {}
                static void writeBytes(String filePath, String output) {}
                static void deleteFile(String filePath) {}
            }

            class StringUtils {
                static String format(String pattern, String fileName) { return pattern + fileName; }
            }

            class RuoYiConfig {
                static String getDownloadPath() { return "/tmp/"; }
            }
            """.trimIndent(),
        )
        val method = findMethod(
            "com.ruoyi.web.controller.common.CommonController",
            "fileDownload",
        )

        val resolvedFlow = JavaResolver().resolveCallGraph(method)

        val guardScope = resolvedFlow.scopeNodes.single { scope ->
            scope.type == NodeType.FLOW_SCOPE &&
                scope.metadata["flow.kind"] == "IF" &&
                scope.title.contains("checkAllowDownload")
        }
        val guardThrowAction = resolvedFlow.actionNodes.single { node ->
            node.type == NodeType.FLOW_ACTION &&
                node.metadata["flow.kind"] == "THROW" &&
                node.title.contains("new Exception")
        }
        val actionEdgesByTarget = resolvedFlow.actionEdges.associateBy { edge -> edge.toNodeId }
        val callsByTargetTitle = resolvedFlow.calls.associateBy { call ->
            "${call.target.containingClass?.name}.${call.target.name}"
        }
        val debugSummary = buildString {
            appendLine("scope:")
            resolvedFlow.scopeNodes.forEach { scope ->
                appendLine("${scope.id} <- ${scope.title}")
            }
            appendLine("actionEdges:")
            resolvedFlow.actionEdges.forEach { edge ->
                appendLine("${edge.fromNodeId} -> ${edge.toNodeId} [${edge.metadata["callOrder"]}]")
            }
            appendLine("calls:")
            resolvedFlow.calls.forEach { call ->
                appendLine("${call.sourceNodeId} -> ${call.target.containingClass?.name}.${call.target.name} [${call.callOrder}]")
            }
        }

        kotlin.test.assertEquals(guardScope.id, actionEdgesByTarget.getValue(guardThrowAction.id).fromNodeId, debugSummary)
        kotlin.test.assertEquals(
            guardScope.id,
            callsByTargetTitle.getValue("FileUtils.checkAllowDownload").sourceNodeId,
            debugSummary,
        )
        kotlin.test.assertEquals(
            GraphNode.stableId(
                NodeType.METHOD,
                "com.ruoyi.web.controller.common.CommonController.fileDownload(java.lang.String,java.lang.Boolean,java.lang.Object,java.lang.Object):void",
            ),
            callsByTargetTitle.getValue("RuoYiConfig.getDownloadPath").sourceNodeId,
            debugSummary,
        )
    }

    fun testCapturesStandaloneAccessorLikeCallsAsActionNodes() {
        myFixture.addFileToProject(
            "com/charmnight/linkgraph/fixtures/simple/StandaloneAccessorAction.java",
            """
            package com.charmnight.linkgraph.fixtures.simple;

            public class StandaloneAccessorAction {
                Subject load(Holder holder) {
                    Subject subject = holder.getSubject();
                    subject.getPrincipals();
                    return subject;
                }
            }

            class Holder {
                Subject getSubject() {
                    return new Subject();
                }
            }

            class Subject {
                Principals getPrincipals() {
                    return new Principals();
                }
            }

            class Principals {
            }
            """.trimIndent(),
        )
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.simple.StandaloneAccessorAction",
            "load",
        )

        val resolvedFlow = JavaResolver().resolveCallGraph(method)

        val getSubjectAction = resolvedFlow.actionNodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("holder.getSubject()")
        }
        val getPrincipalsAction = resolvedFlow.actionNodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("subject.getPrincipals()")
        }
        val callsByTargetTitle = resolvedFlow.calls.associateBy { call ->
            "${call.target.containingClass?.name}.${call.target.name}"
        }

        kotlin.test.assertEquals(getSubjectAction.id, callsByTargetTitle.getValue("Holder.getSubject").sourceNodeId)
        kotlin.test.assertEquals(getPrincipalsAction.id, callsByTargetTitle.getValue("Subject.getPrincipals").sourceNodeId)
    }

    fun testDoesNotBindJdkInterfaceCallsToArbitraryProjectImplementations() {
        installExternalContractLibrary()
        loadFixture("simple/InterfaceDispatchChain.java")
        val contractMethod = JavaPsiFacade.getInstance(project)
            .findClass("external.ExternalList", GlobalSearchScope.allScope(project))
            ?.findMethodsByName("add", false)
            ?.singleOrNull { method -> method.parameterList.parametersCount == 1 }
            ?: error("missing external.ExternalList.add")
        val projectOverrides = OverridingMethodsSearch.search(contractMethod, GlobalSearchScope.projectScope(project), true)
            .findAll()
            .filter { candidate ->
                candidate.containingClass?.qualifiedName ==
                    "com.charmnight.linkgraph.fixtures.simple.InterfaceDispatchChain.ProjectList"
            }

        assertTrue(projectOverrides.isNotEmpty(), "测试前提失败：需要至少一个项目内 List.add 实现")
        assertTrue(
            contractMethod.containingClass?.qualifiedName == "external.ExternalList",
            "测试前提失败：应直接拿到外部契约 ExternalList.add，实际=${contractMethod.containingClass?.qualifiedName}",
        )

        val concreteTargets = JavaResolver::class.java
            .getDeclaredMethod("concreteTargets", PsiMethod::class.java)
            .apply { isAccessible = true }
            .invoke(JavaResolver(), contractMethod) as List<*>

        val targetSignatures = concreteTargets
            .filterIsInstance<PsiMethod>()
            .map { target -> target.containingClass?.qualifiedName.orEmpty() + "." + target.name }
        val expectedSignature = contractMethod.containingClass?.qualifiedName.orEmpty() + "." + contractMethod.name

        assertTrue(
            targetSignatures == listOf(expectedSignature),
            "非项目契约方法不应展开到项目实现，期望=$expectedSignature, 实际目标=$targetSignatures",
        )
        assertTrue(
            concreteTargets.none { target -> target in projectOverrides },
            "JDK 接口调用不应被错误绑定到项目内无关实现",
        )
    }

    private fun installExternalContractLibrary() {
        val libraryRoot = Files.createTempDirectory("external-contract")
        val sourceFile = libraryRoot.resolve("external/ExternalList.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            package external;

            public interface ExternalList<T> {
                boolean add(T element);
            }
            """.trimIndent(),
        )

        val classesDir = libraryRoot.resolve("classes")
        Files.createDirectories(classesDir)
        val compileProcess = ProcessBuilder(
            "javac",
            "-d",
            classesDir.toString(),
            sourceFile.toString(),
        )
            .redirectErrorStream(true)
            .start()
        val compileOutput = compileProcess.inputStream.bufferedReader().use { it.readText() }
        val compileExitCode = compileProcess.waitFor()
        assertEquals(0, compileExitCode, "外部契约库编译失败:\n$compileOutput")

        ModuleRootModificationUtil.addModuleLibrary(
            myFixture.module,
            "external-contract",
            listOf(VfsUtilCore.pathToUrl(classesDir.toString())),
            emptyList(),
        )
    }

    private fun loadFixture(relativePath: String) {
        myFixture.addJavaFixture(relativePath)
    }

    private fun findMethod(className: String, methodName: String): PsiMethod {
        val psiClass = findClass(className)
        return psiClass.findMethodsByName(methodName, false).single()
    }

    private fun findClass(className: String): PsiClass {
        return JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.projectScope(project))
            ?: error("Class not found: $className")
    }
}
