package com.charmnight.linkgraph.extract

import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.testing.addJavaFixture
import com.charmnight.linkgraph.testing.addKotlinFixture
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphExtractorTest : BasePlatformTestCase() {
    fun testExpandsKotlinTopLevelAndExtensionCalls() {
        loadKotlinFixture("kotlin/KotlinAdvancedCalls.kt")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.kotlin.KotlinAdvancedController",
            "run",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val runNode = nodesByTitle.getValue("KotlinAdvancedController.run")
        val normalizeNode = nodesByTitle["KotlinAdvancedCallsKt.normalizeOrderId"]
        val decorateNode = nodesByTitle["KotlinAdvancedCallsKt.decorateSuffix"]

        assertTrue(normalizeNode != null, "expected Kotlin top-level function normalizeOrderId to be included")
        assertTrue(decorateNode != null, "expected Kotlin extension function decorateSuffix to be included")
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == runNode.id &&
                    edge.toNodeId == normalizeNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == runNode.id &&
                    edge.toNodeId == decorateNode.id
            },
        )
    }

    fun testExpandsKotlinPropertyAccessorsAsGetterSetterCalls() {
        loadKotlinFixture("kotlin/KotlinAdvancedCalls.kt")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.kotlin.KotlinAdvancedController",
            "run",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val runNode = nodesByTitle.getValue("KotlinAdvancedController.run")
        val getterNode = nodesByTitle["OrderSnapshotStore.getLastOrderId"]
        val setterNode = nodesByTitle["OrderSnapshotStore.setLastOrderId"]

        assertTrue(getterNode != null, "expected Kotlin property getter to be included")
        assertTrue(setterNode != null, "expected Kotlin property setter to be included")
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == runNode.id &&
                    edge.toNodeId == getterNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == runNode.id &&
                    edge.toNodeId == setterNode.id
            },
        )
    }

    fun testIncludesKotlinCallersForTopLevelAndExtensionMethods() {
        loadKotlinFixture("kotlin/KotlinAdvancedCalls.kt")
        val normalizeMethod = findMethod(
            "com.charmnight.linkgraph.fixtures.kotlin.KotlinAdvancedCallsKt",
            "normalizeOrderId",
        )
        val decorateMethod = findMethod(
            "com.charmnight.linkgraph.fixtures.kotlin.KotlinAdvancedCallsKt",
            "decorateSuffix",
        )

        val normalizeResult = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(normalizeMethod)),
        )
        val decorateResult = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(decorateMethod)),
        )

        assertHasCallerEdge(
            graph = normalizeResult,
            callerTitle = "KotlinAdvancedController.run",
            calleeTitle = "KotlinAdvancedCallsKt.normalizeOrderId",
        )
        assertHasCallerEdge(
            graph = decorateResult,
            callerTitle = "KotlinAdvancedController.run",
            calleeTitle = "KotlinAdvancedCallsKt.decorateSuffix",
        )
    }

    fun testIncludesKotlinCallersForPropertyAccessorMethods() {
        loadKotlinFixture("kotlin/KotlinAdvancedCalls.kt")
        val getterMethod = findMethod(
            "com.charmnight.linkgraph.fixtures.kotlin.OrderSnapshotStore",
            "getLastOrderId",
        )
        val setterMethod = findMethod(
            "com.charmnight.linkgraph.fixtures.kotlin.OrderSnapshotStore",
            "setLastOrderId",
        )

        val getterResult = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(getterMethod)),
        )
        val setterResult = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(setterMethod)),
        )

        assertHasCallerEdge(
            graph = getterResult,
            callerTitle = "KotlinAdvancedController.run",
            calleeTitle = "OrderSnapshotStore.getLastOrderId",
        )
        assertHasCallerEdge(
            graph = setterResult,
            callerTitle = "KotlinAdvancedController.run",
            calleeTitle = "OrderSnapshotStore.setLastOrderId",
        )
    }

    fun testExpandsKotlinCurrentMethodDownstreamCalls() {
        loadKotlinFixture("kotlin/KotlinCallChain.kt")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.kotlin.KotlinOrderFlow",
            "submit",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val submitNode = nodesByTitle.getValue("KotlinOrderFlow.submit")
        val placeNode = nodesByTitle["OrderService.place"]
        val formatNode = nodesByTitle["KotlinOrderFlow.format"]

        assertTrue(placeNode != null, "expected Kotlin call chain to include OrderService.place")
        assertTrue(formatNode != null, "expected Kotlin call chain to include KotlinOrderFlow.format")
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == submitNode.id &&
                    edge.toNodeId == placeNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == submitNode.id &&
                    edge.toNodeId == formatNode.id
            },
        )
    }

    fun testIncludesKotlinUpstreamCallerMethodsForCurrentMethodGraph() {
        loadKotlinFixture("kotlin/KotlinCallChain.kt")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.kotlin.KotlinOrderFlow",
            "submit",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val submitNode = nodesByTitle.getValue("KotlinOrderFlow.submit")
        val triggerNode = nodesByTitle["KotlinOrderController.trigger"]

        assertTrue(triggerNode != null, "expected Kotlin caller trigger to be included")
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == triggerNode.id &&
                    edge.toNodeId == submitNode.id
            },
        )
    }

    fun testDoesNotReportBoundaryForKotlinLeafMethodWithoutProjectCalls() {
        myFixture.addFileToProject(
            "com/charmnight/linkgraph/fixtures/kotlin/KotlinLeafMethod.kt",
            """
            package com.charmnight.linkgraph.fixtures.kotlin;

            class KotlinLeafMethod {
                fun place(value: String): String {
                    return value.trim()
                }
            }
            """.trimIndent(),
        )
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.kotlin.KotlinLeafMethod",
            "place",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        assertTrue(
            result.entryMethodBoundaries.isEmpty(),
            "Kotlin 叶子方法不应被诊断为静态提取边界",
        )
        assertTrue(
            result.document.nodes.none { node -> node.type == NodeType.UNCERTAIN_LINK },
            "提取器不应对正常 Kotlin 叶子方法输出边界节点",
        )
    }

    fun testExpandsKotlinCurrentMethodCustomAccessorCalls() {
        myFixture.addFileToProject(
            "com/charmnight/linkgraph/fixtures/kotlin/KotlinAccessorFlow.kt",
            """
            package com.charmnight.linkgraph.fixtures.kotlin;

            class AccessorFormattingService {
                fun normalize(value: String): String {
                    return value.trim()
                }
            }

            class KotlinAccessorFlow(
                private val formatter: AccessorFormattingService = AccessorFormattingService(),
            ) {
                var raw: String = " seed "
                    get() = formatter.normalize(field)
                    set(value) {
                        field = formatter.normalize(value)
                    }

                fun read(): String {
                    return raw
                }

                fun write(value: String) {
                    raw = value
                }
            }
            """.trimIndent(),
        )
        val getter = findMethod(
            "com.charmnight.linkgraph.fixtures.kotlin.KotlinAccessorFlow",
            "getRaw",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(getter)),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val getterNode = nodesByTitle.getValue("KotlinAccessorFlow.getRaw")
        val normalizeNode = nodesByTitle["AccessorFormattingService.normalize"]
        val readNode = nodesByTitle["KotlinAccessorFlow.read"]

        assertTrue(normalizeNode != null, "expected custom Kotlin getter to include downstream normalize call")
        assertTrue(readNode != null, "expected property getter to keep upstream caller read")
        assertTrue(result.entryMethodBoundaries.isEmpty(), "custom Kotlin getter should no longer be marked as unsupported boundary")
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == getterNode.id &&
                    edge.toNodeId == normalizeNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == readNode.id &&
                    edge.toNodeId == getterNode.id
            },
        )
    }

    fun testIncludesKotlinAccessorMethodsAsCallersOfInvokedMethod() {
        myFixture.addFileToProject(
            "com/charmnight/linkgraph/fixtures/kotlin/KotlinAccessorCallers.kt",
            """
            package com.charmnight.linkgraph.fixtures.kotlin;

            class AccessorFormattingService {
                fun normalize(value: String): String {
                    return value.trim()
                }
            }

            class KotlinAccessorCallers(
                private val formatter: AccessorFormattingService = AccessorFormattingService(),
            ) {
                var raw: String = " seed "
                    get() = formatter.normalize(field)
                    set(value) {
                        field = formatter.normalize(value)
                    }
            }
            """.trimIndent(),
        )
        val normalize = findMethod(
            "com.charmnight.linkgraph.fixtures.kotlin.AccessorFormattingService",
            "normalize",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(normalize)),
        )

        assertHasCallerEdge(
            graph = result,
            callerTitle = "KotlinAccessorCallers.getRaw",
            calleeTitle = "AccessorFormattingService.normalize",
        )
        assertHasCallerEdge(
            graph = result,
            callerTitle = "KotlinAccessorCallers.setRaw",
            calleeTitle = "AccessorFormattingService.normalize",
        )
    }

    fun testExpandsKotlinCurrentMethodPrimaryConstructorInitializationCalls() {
        myFixture.addFileToProject(
            "com/charmnight/linkgraph/fixtures/kotlin/KotlinPrimaryConstructorFlow.kt",
            """
            package com.charmnight.linkgraph.fixtures.kotlin;

            class ConstructorFormattingService {
                fun normalize(value: String): String {
                    return value.trim()
                }

                fun announce(value: String): String {
                    return value.uppercase()
                }
            }

            class KotlinPrimaryConstructorFlow(
                value: String,
                private val formatter: ConstructorFormattingService = ConstructorFormattingService(),
            ) {
                private val normalized = formatter.normalize(value)

                init {
                    formatter.announce(normalized)
                }
            }

            class KotlinPrimaryConstructorFactory {
                fun build(value: String): KotlinPrimaryConstructorFlow {
                    return KotlinPrimaryConstructorFlow(value)
                }
            }
            """.trimIndent(),
        )
        val constructor = findClass(
            "com.charmnight.linkgraph.fixtures.kotlin.KotlinPrimaryConstructorFlow",
        ).constructors.single()

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(constructor)),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val constructorNode = nodesByTitle.values.first { node -> node.signature == JavaResolver().methodKey(constructor) }
        val normalizeNode = nodesByTitle["ConstructorFormattingService.normalize"]
        val announceNode = nodesByTitle["ConstructorFormattingService.announce"]
        val buildNode = nodesByTitle["KotlinPrimaryConstructorFactory.build"]

        assertTrue(normalizeNode != null, "expected primary constructor to include property initializer calls")
        assertTrue(announceNode != null, "expected primary constructor to include init block calls")
        assertTrue(buildNode != null, "expected primary constructor to retain upstream factory caller")
        assertTrue(result.entryMethodBoundaries.isEmpty(), "primary constructor initialization should no longer be marked as unsupported boundary")
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == constructorNode.id &&
                    edge.toNodeId == normalizeNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == constructorNode.id &&
                    edge.toNodeId == announceNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == buildNode.id &&
                    edge.toNodeId == constructorNode.id
            },
        )
    }

    fun testExpandsKotlinCurrentMethodSecondaryConstructorCalls() {
        myFixture.addFileToProject(
            "com/charmnight/linkgraph/fixtures/kotlin/KotlinSecondaryConstructorFlow.kt",
            """
            package com.charmnight.linkgraph.fixtures.kotlin;

            class SecondaryFormattingService {
                fun normalize(value: String): String {
                    return value.trim()
                }
            }

            class KotlinSecondaryConstructorFlow {
                private val formatter = SecondaryFormattingService()
                var normalized: String = ""

                constructor(value: String) {
                    normalized = formatter.normalize(value)
                }
            }

            class KotlinSecondaryConstructorFactory {
                fun build(value: String): KotlinSecondaryConstructorFlow {
                    return KotlinSecondaryConstructorFlow(value)
                }
            }
            """.trimIndent(),
        )
        val constructor = findClass(
            "com.charmnight.linkgraph.fixtures.kotlin.KotlinSecondaryConstructorFlow",
        ).constructors.single()

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(constructor)),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val constructorNode = nodesByTitle.values.first { node -> node.signature == JavaResolver().methodKey(constructor) }
        val normalizeNode = nodesByTitle["SecondaryFormattingService.normalize"]
        val buildNode = nodesByTitle["KotlinSecondaryConstructorFactory.build"]

        assertTrue(normalizeNode != null, "expected secondary constructor body to include downstream normalize call")
        assertTrue(buildNode != null, "expected secondary constructor to retain upstream factory caller")
        assertTrue(result.entryMethodBoundaries.isEmpty(), "secondary constructor body should no longer be marked as unsupported boundary")
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == constructorNode.id &&
                    edge.toNodeId == normalizeNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == buildNode.id &&
                    edge.toNodeId == constructorNode.id
            },
        )
    }

    fun testIncludesUpstreamCallerMethodsForCurrentMethodGraph() {
        loadFixture("simple/SimpleCallChain.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.simple.SimpleCallChain",
            "sanitize",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val sanitizeNode = nodesByTitle.getValue("SimpleCallChain.sanitize")
        val loadNode = nodesByTitle["SimpleCallChain.load"]
        val branchyNode = nodesByTitle["SimpleCallChain.branchy"]

        assertEquals(NodeType.METHOD, sanitizeNode.type)
        assertTrue(loadNode != null, "expected direct caller load to be included")
        assertTrue(branchyNode != null, "expected alternate caller branchy to be included")
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == loadNode.id &&
                    edge.toNodeId == sanitizeNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == branchyNode.id &&
                    edge.toNodeId == sanitizeNode.id
            },
        )
    }

    fun testMethodNodesKeepQualifiedSignatureForSourceNavigationFallback() {
        loadFixture("simple/SimpleCallChain.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.simple.SimpleCallChain",
            "sanitize",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val sanitizeNode = result.document.nodes.first { node -> node.title == "SimpleCallChain.sanitize" }
        assertEquals(
            "com.charmnight.linkgraph.fixtures.simple.SimpleCallChain.sanitize(java.lang.String):java.lang.String",
            sanitizeNode.signature,
        )
    }

    fun testGroupsNestedQualifierCallsAroundCurrentMethodActionNodeInFinalGraph() {
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

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val nestedAction = result.document.nodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("getRealmNames().iterator().next()")
        }
        val namesGetter = result.document.nodes.single { node -> node.title == "Names.getRealmNames" }
        val iteratorMethod = result.document.nodes.single { node -> node.title == "RealmNames.iterator" }
        val nextMethod = result.document.nodes.single { node -> node.title == "RealmIterator.next" }

        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == nestedAction.id &&
                    edge.toNodeId == namesGetter.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == nestedAction.id &&
                    edge.toNodeId == iteratorMethod.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == nestedAction.id &&
                    edge.toNodeId == nextMethod.id
            },
        )
    }

    fun testGroupsConstructorArgumentCallsAroundNewActionNodeInFinalGraph() {
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

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val newAction = result.document.nodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("new Holder(factory.create())")
        }
        val factoryCreate = result.document.nodes.single { node -> node.title == "Factory.create" }
        val actualEdges = result.document.edges.joinToString(separator = "\n") { edge ->
            "${edge.type}:${edge.fromNodeId}->${edge.toNodeId}"
        }

        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == newAction.id &&
                    edge.toNodeId == factoryCreate.id
            },
            actualEdges,
        )
    }

    fun testGroupsStandaloneAccessorLikeCallsAroundActionNodesInFinalGraph() {
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

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val getSubjectAction = result.document.nodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("holder.getSubject()")
        }
        val getPrincipalsAction = result.document.nodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("subject.getPrincipals()")
        }
        val getSubjectMethod = result.document.nodes.single { node -> node.title == "Holder.getSubject" }
        val getPrincipalsMethod = result.document.nodes.single { node -> node.title == "Subject.getPrincipals" }

        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == getSubjectAction.id &&
                    edge.toNodeId == getSubjectMethod.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == getPrincipalsAction.id &&
                    edge.toNodeId == getPrincipalsMethod.id
            },
        )
    }

    fun testKeepsSpringEventBoundaryVisible() {
        loadFixture("spring/SpringEventFlow.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.spring.OrderPublisher",
            "submit",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val publisherNode = nodesByTitle.getValue("OrderPublisher.submit")
        val eventNode = result.document.nodes.firstOrNull { node ->
            node.sourceTag == GraphSourceTag.UNCERTAIN_FACT &&
                node.title.contains("OrderCreatedEvent")
        }
        val listenerNode = nodesByTitle["OrderCreatedListener.onOrderCreated"]

        assertTrue(eventNode != null, "expected spring event boundary node to stay visible")
        assertTrue(listenerNode != null, "expected event listener to be linked into the graph")
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.PUBLISHES_TO &&
                    edge.fromNodeId == publisherNode.id &&
                    edge.toNodeId == eventNode.id &&
                    edge.certainty == Certainty.RULE_INFERRED
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CONSUMES_FROM &&
                    edge.fromNodeId == eventNode.id &&
                    edge.toNodeId == listenerNode.id &&
                    edge.certainty == Certainty.RULE_INFERRED
            },
        )
    }

    fun testCapsDownstreamFanOutAndMarksOverflowBoundary() {
        loadFixture("heavy/HeavyCallGraph.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.heavy.HeavyCallGraph",
            "fanOut",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(
                entryMethods = listOf(method),
                limits = GraphExtractionLimits(
                    maxMethodNodes = 16,
                    maxCallDepthDownstream = 2,
                    maxCallDepthUpstream = 0,
                    maxCallsPerMethod = 3,
                    maxCallersPerMethod = 0,
                    maxResolverMethodsPerMethod = 0,
                ),
            ),
        )

        val overflowNode = result.document.nodes.firstOrNull { node ->
            node.type == NodeType.UNCERTAIN_LINK && node.title.contains("下游调用过多")
        }

        assertTrue(overflowNode != null, "expected a downstream overflow boundary node")
        assertEquals(
            3,
            result.document.nodes.count { node -> node.title.startsWith("HeavyCallGraph.helper") },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL && edge.toNodeId == overflowNode.id
            },
        )
    }

    fun testCapsUpstreamFanInAndMarksOverflowBoundary() {
        loadFixture("heavy/HeavyCallGraph.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.heavy.HeavyCallGraph",
            "joinPoint",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(
                entryMethods = listOf(method),
                limits = GraphExtractionLimits(
                    maxMethodNodes = 16,
                    maxCallDepthDownstream = 0,
                    maxCallDepthUpstream = 2,
                    maxCallsPerMethod = 0,
                    maxCallersPerMethod = 4,
                    maxResolverMethodsPerMethod = 0,
                ),
            ),
        )

        val overflowNode = result.document.nodes.firstOrNull { node ->
            node.type == NodeType.UNCERTAIN_LINK && node.title.contains("上游调用方过多")
        }

        assertTrue(overflowNode != null, "expected an upstream overflow boundary node")
        assertEquals(
            4,
            result.document.nodes.count { node -> node.title.startsWith("HeavyCallGraph.caller") },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL && edge.fromNodeId == overflowNode.id
            },
        )
    }

    fun testKeepsSourceCallOrderWhenLimitingDownstreamCalls() {
        loadFixture("simple/CallOrderChain.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.simple.CallOrderChain",
            "execute",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(
                entryMethods = listOf(method),
                limits = GraphExtractionLimits(
                    maxMethodNodes = 8,
                    maxCallDepthDownstream = 1,
                    maxCallDepthUpstream = 0,
                    maxCallsPerMethod = 2,
                    maxCallersPerMethod = 0,
                    maxResolverMethodsPerMethod = 0,
                ),
            ),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val executeNode = nodesByTitle.getValue("CallOrderChain.execute")
        val gammaNode = nodesByTitle["CallOrderChain.gamma"]
        val alphaNode = nodesByTitle["CallOrderChain.alpha"]
        val betaNode = nodesByTitle["CallOrderChain.beta"]
        val gammaAction = result.document.nodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title == "gamma()"
        }
        val alphaAction = result.document.nodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title == "alpha()"
        }
        val betaAction = result.document.nodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title == "beta()"
        }

        assertTrue(gammaNode != null, "expected first source call gamma to stay visible")
        assertTrue(alphaNode != null, "expected second source call alpha to stay visible")
        assertTrue(betaNode == null, "expected third source call beta to be truncated by the per-method limit")

        val callEdgesByTargetId = result.document.edges
            .filter { edge -> edge.type == EdgeType.CALL && edge.fromNodeId == executeNode.id }
            .associateBy { edge -> edge.toNodeId }

        assertEquals("0", callEdgesByTargetId[gammaAction.id]?.metadata?.get("callOrder"))
        assertEquals("1", callEdgesByTargetId[alphaAction.id]?.metadata?.get("callOrder"))
        assertEquals("2", callEdgesByTargetId[betaAction.id]?.metadata?.get("callOrder"))
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == gammaAction.id &&
                    edge.toNodeId == gammaNode.id
            },
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == alphaAction.id &&
                    edge.toNodeId == alphaNode.id
            },
        )
        assertTrue(
            result.document.edges.none { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == betaAction.id
            },
            "beta 对应的方法节点应在下游数量截断后被隐藏，但本地动作节点仍保留",
        )
        assertTrue(
            result.document.nodes.any { node ->
                node.type == NodeType.UNCERTAIN_LINK && node.title.contains("下游调用过多")
            },
            "expected overflow boundary after truncating later source calls",
        )
    }

    fun testBuildsScopeNodesForLambdaLoopAndBranchBodiesInsteadOfFlatteningThemIntoAnchorMethod() {
        loadFixture("simple/ScopedCallChain.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.simple.ScopedCallChain",
            "render",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val nodesByTitle = result.document.nodes.associateBy { it.title }
        val renderNode = nodesByTitle.getValue("ScopedCallChain.render")
        val forEachAction = result.document.nodes.firstOrNull { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("lines.forEach")
        }
        val forEachScope = result.document.nodes.firstOrNull { node ->
            node.type.name == "FLOW_SCOPE" && node.title.contains("forEach")
        }
        val ifScope = result.document.nodes.firstOrNull { node ->
            node.type.name == "FLOW_SCOPE" && node.title.startsWith("if ")
        }
        val loopScope = result.document.nodes.firstOrNull { node ->
            node.type.name == "FLOW_SCOPE" && node.title.startsWith("for ")
        }
        val activeNode = nodesByTitle["Line.isActive"]

        val nodeTitles = result.document.nodes.map { node -> "${node.type.name}:${node.title}" }.sorted()
        assertTrue(forEachAction != null, "forEach 调用应先生成独立动作节点，实际节点=$nodeTitles")
        assertTrue(forEachScope != null, "forEach lambda 应生成独立作用域节点，避免内部调用被直接挂到当前方法，实际节点=$nodeTitles")
        assertTrue(ifScope != null, "lambda 体里的条件分支应生成独立作用域节点，实际节点=$nodeTitles")
        assertTrue(loopScope != null, "显式 for 循环应生成独立作用域节点，实际节点=$nodeTitles")
        assertTrue(activeNode != null, "作用域内部调用仍应被解析出来")

        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == renderNode.id &&
                    edge.toNodeId == forEachAction.id
            },
            "当前方法应先通过 CALL 连接到 forEach 动作节点",
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type.name == "CONTAINS_FLOW" &&
                    edge.fromNodeId == forEachAction.id &&
                    edge.toNodeId == forEachScope.id
            },
            "forEach lambda 作用域应挂在对应动作节点下，而不是直接挂到当前方法",
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type.name == "CONTAINS_FLOW" &&
                    edge.fromNodeId == renderNode.id &&
                    edge.toNodeId == loopScope.id
            },
            "当前方法应通过 CONTAINS_FLOW 连接到 for 循环作用域",
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type.name == "CONTAINS_FLOW" &&
                    edge.fromNodeId == forEachScope.id &&
                    edge.toNodeId == ifScope.id
            },
            "lambda 作用域下的 if 分支也应通过 CONTAINS_FLOW 建立层级关系",
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type.name == "CALL" &&
                    edge.fromNodeId == ifScope.id &&
                    edge.toNodeId == activeNode!!.id
            },
            "if 作用域内部调用应从 if 作用域发出，而不是从当前方法直接发出",
        )
        assertTrue(
            result.document.edges.none { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == renderNode.id &&
                    edge.toNodeId == activeNode.id
            },
            "当前方法不应再把 lambda / 循环体内部调用直接扁平化为一跳下游",
        )
    }

    fun testIncludesAnchorMethodActionNodesForCurrentMethodLogic() {
        loadFixture("simple/ScopedCallChain.java")
        val method = findMethod(
            "com.charmnight.linkgraph.fixtures.simple.ScopedCallChain",
            "buildUser",
        )

        val result = GraphExtractor().extract(
            GraphExtractionRequest(entryMethods = listOf(method)),
        )

        val buildUserNode = result.document.nodes.single { node -> node.title == "ScopedCallChain.buildUser" }
        val ifScope = result.document.nodes.single { node ->
            node.type == NodeType.FLOW_SCOPE && node.metadata["flow.kind"] == "IF"
        }
        val newUserAction = result.document.nodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("new SysUser()")
        }
        val copyBeanAction = result.document.nodes.single { node ->
            node.type == NodeType.FLOW_ACTION && node.title.contains("BeanUtils.copyBeanProp(user, source)")
        }

        assertEquals(
            "com.charmnight.linkgraph.fixtures.simple.ScopedCallChain.buildUser(java.lang.Object):com.charmnight.linkgraph.fixtures.simple.SysUser",
            newUserAction.metadata["flow.anchorMethod"],
        )
        assertTrue(newUserAction.metadata["source.filePath"]!!.endsWith("ScopedCallChain.java"))
        assertTrue(copyBeanAction.metadata["source.filePath"]!!.endsWith("ScopedCallChain.java"))

        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CONTAINS_FLOW &&
                    edge.fromNodeId == buildUserNode.id &&
                    edge.toNodeId == ifScope.id
            },
            "入口方法应继续保留 if 作用域",
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == ifScope.id &&
                    edge.toNodeId == newUserAction.id &&
                    edge.metadata["callOrder"] == "0"
            },
            "new SysUser() 应作为 if 作用域下的第一个关键动作节点出现",
        )
        assertTrue(
            result.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == ifScope.id &&
                    edge.toNodeId == copyBeanAction.id &&
                    edge.metadata["callOrder"] == "1"
            },
            "BeanUtils.copyBeanProp(...) 应作为 if 作用域下的后续关键动作节点出现",
        )
    }

    private fun loadFixture(relativePath: String) {
        myFixture.addJavaFixture(relativePath)
    }

    private fun loadKotlinFixture(relativePath: String) {
        myFixture.addKotlinFixture(relativePath)
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

    private fun assertHasCallerEdge(
        graph: GraphExtractionResult,
        callerTitle: String,
        calleeTitle: String,
    ) {
        val nodesByTitle = graph.document.nodes.associateBy { it.title }
        val callerNode = nodesByTitle[callerTitle]
        val calleeNode = nodesByTitle[calleeTitle]
        assertTrue(callerNode != null, "expected caller node $callerTitle to be included")
        assertTrue(calleeNode != null, "expected callee node $calleeTitle to be included")
        assertTrue(
            graph.document.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == callerNode.id &&
                    edge.toNodeId == calleeNode.id
            },
        )
    }
}
