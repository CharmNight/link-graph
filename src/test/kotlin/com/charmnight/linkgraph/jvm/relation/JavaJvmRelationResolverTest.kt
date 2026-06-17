package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmSymbolIndexBuilder
import com.charmnight.linkgraph.source.IdeSourceContentResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaJvmRelationResolverTest : BasePlatformTestCase() {
    fun testServiceLoaderReflectionConfigDubboAndMqRelations() {
        myFixture.addFileToProject(
            "src/main/java/com/example/spi/PaymentPlugin.java",
            """
            package com.example.spi;
            public interface PaymentPlugin {
                void pay();
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/spi/CardPaymentPlugin.java",
            """
            package com.example.spi;
            public class CardPaymentPlugin implements PaymentPlugin {
                public void pay() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/app/PaymentRunner.java",
            """
            package com.example.app;
            import java.util.ServiceLoader;
            import com.example.spi.PaymentPlugin;
            @ReflectiveTarget("com.example.plugin.PluginEntry")
            public class PaymentRunner {
                static final String ORDER_LISTENER = "order-listener";
                public void run() throws Exception {
                    ServiceLoader.load(PaymentPlugin.class);
                    Class.forName("com.example.plugin.PluginEntry").getMethod("start");
                }
            }
            @interface ReflectiveTarget { String value(); }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/plugin/PluginEntry.java",
            """
            package com.example.plugin;
            public class PluginEntry {
                public void start() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/dubbo/DubboApi.java",
            """
            package com.example.dubbo;
            public interface DubboApi {
                void call();
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/dubbo/DubboProvider.java",
            """
            package com.example.dubbo;
            @DubboService
            public class DubboProvider implements DubboApi {
                public void call() {}
            }
            @interface DubboService {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/dubbo/DubboConsumer.java",
            """
            package com.example.dubbo;
            public class DubboConsumer {
                @DubboReference DubboApi api;
            }
            @interface DubboReference {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/mq/OrderListener.java",
            """
            package com.example.mq;
            public class OrderListener {
                @KafkaListener(topics = "orders.created")
                public void onOrder(String value) {}
            }
            @interface KafkaListener { String[] topics() default {}; }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/resources/META-INF/services/com.example.spi.PaymentPlugin",
            "com.example.spi.CardPaymentPlugin\n",
        )
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "plugin.class=com.example.plugin.PluginEntry\n",
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val relationIndex = JvmRelationResolverRegistry().resolveAll(
            JvmResolutionContext(
                project = project,
                symbolIndex = symbolIndex,
                sourceResolver = IdeSourceContentResolver(project),
            ),
        )
        val paymentPlugin = requireNotNull(symbolIndex.findClass("com.example.spi.PaymentPlugin"))
        val cardProvider = requireNotNull(symbolIndex.findClass("com.example.spi.CardPaymentPlugin"))
        val runner = requireNotNull(symbolIndex.findClass("com.example.app.PaymentRunner"))
        val pluginEntry = requireNotNull(symbolIndex.findClass("com.example.plugin.PluginEntry"))
        val dubboApi = requireNotNull(symbolIndex.findClass("com.example.dubbo.DubboApi"))
        val dubboProvider = requireNotNull(symbolIndex.findClass("com.example.dubbo.DubboProvider"))
        val dubboConsumer = requireNotNull(symbolIndex.findClass("com.example.dubbo.DubboConsumer"))

        val relationSummary = relationIndex.relations.joinToString("\n") { relation ->
            "${relation.kind} ${relation.fromSymbolId} -> ${relation.toSymbolId} ${relation.confidence} ${relation.metadata}"
        }
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.SPI_PROVIDES &&
                relation.fromSymbolId == cardProvider.id &&
                relation.toSymbolId == paymentPlugin.id &&
                relation.confidence == JvmRelationConfidence.PROVEN
        }, relationSummary)
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.SERVICE_LOADER_LOADS &&
                relation.toSymbolId == paymentPlugin.id
        })
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.REFLECTS_TO &&
                relation.fromSymbolId == runner.id &&
                relation.toSymbolId == pluginEntry.id &&
                relation.metadata["reflect.kind"] in setOf("CLASS_FOR_NAME", "ANNOTATION_VALUE")
        })
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.REFLECTS_TO &&
                relation.fromSymbolId.startsWith("jvm:resource:") &&
                relation.toSymbolId == pluginEntry.id &&
                relation.metadata["reflect.kind"] == "CONFIG_VALUE"
        })
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.REFLECTS_TO &&
                relation.toSymbolId == requireNotNull(symbolIndex.findClass("com.example.mq.OrderListener")).id &&
                relation.metadata["reflect.kind"] == "ENUM_CONSTANT_NAME"
        })
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.DUBBO_PROVIDES &&
                relation.fromSymbolId == dubboProvider.id &&
                relation.toSymbolId == dubboApi.id
        })
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.DUBBO_REFERENCES &&
                relation.fromSymbolId == dubboConsumer.id &&
                relation.toSymbolId == dubboApi.id
        }, relationSummary)
        assertEquals(1, relationIndex.byKind(JvmRelationKind.MQ_CONSUMES).size)
    }

    fun testFeignRelationsAreResolvedByJvmFrameworkIndex() {
        myFixture.addFileToProject(
            "src/main/java/com/charmnight/linkgraph/fixtures/http/FeignOrderClient.java",
            """
            package com.charmnight.linkgraph.fixtures.http;

            @org.springframework.cloud.openfeign.FeignClient(name = "order-service", path = "/orders")
            public interface FeignOrderClient {
                @org.springframework.web.bind.annotation.GetMapping("/{id}")
                String getOrder(String id);
            }

            class OrderGatewayService {
                private final FeignOrderClient orderClient;

                OrderGatewayService(FeignOrderClient orderClient) {
                    this.orderClient = orderClient;
                }

                public String loadOrder(String id) {
                    return orderClient.getOrder(id);
                }
            }

            @org.springframework.web.bind.annotation.RestController
            @org.springframework.web.bind.annotation.RequestMapping("/orders")
            class OrderProviderController {
                @org.springframework.web.bind.annotation.GetMapping("/{id}")
                public String getOrder(String id) {
                    return "provider:" + id;
                }
            }
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val relationIndex = JvmRelationResolverRegistry().resolveAll(
            JvmResolutionContext(
                project = project,
                symbolIndex = symbolIndex,
                sourceResolver = IdeSourceContentResolver(project),
            ),
        )
        val gateway = requireNotNull(symbolIndex.findClass("com.charmnight.linkgraph.fixtures.http.OrderGatewayService"))
        val feignClient = requireNotNull(symbolIndex.findClass("com.charmnight.linkgraph.fixtures.http.FeignOrderClient"))
        val provider = requireNotNull(symbolIndex.findClass("com.charmnight.linkgraph.fixtures.http.OrderProviderController"))

        val relationSummary = relationIndex.relations.joinToString("\n") { relation ->
            "${relation.kind} ${relation.fromSymbolId} -> ${relation.toSymbolId} ${relation.metadata}"
        } + "\nmethods=" + symbolIndex.methodsBySignature.keys.sorted().joinToString("|") +
            "\nlistenerAnnotations=" + (
                com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(
                        "com.example.event.SearchIndexListener",
                        com.intellij.psi.search.GlobalSearchScope.projectScope(project),
                    )
                    ?.methods
                    ?.flatMap { method -> method.annotations.map { annotation -> "${annotation.qualifiedName}:${annotation.text}" } }
                    ?.joinToString("|")
                    ?: "<missing>"
                ) +
            "\npublisherBody=" + (
                com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(
                        "com.example.event.EventPublisher",
                        com.intellij.psi.search.GlobalSearchScope.projectScope(project),
                    )
                    ?.methods
                    ?.joinToString("|") { method -> "${method.name}:${method.body?.text}" }
                    ?: "<missing>"
                )
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.FEIGN_CLIENT_CALLS &&
                relation.fromSymbolId == gateway.id &&
                relation.toSymbolId == feignClient.id &&
                relation.metadata["feign.sourceMethod"] ==
                "com.charmnight.linkgraph.fixtures.http.OrderGatewayService.loadOrder(java.lang.String):java.lang.String" &&
                relation.metadata["http.method"] == "GET" &&
                relation.metadata["http.path"] == "/orders/{id}"
        }, relationSummary)
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.FEIGN_ROUTES_TO &&
                relation.fromSymbolId == feignClient.id &&
                relation.toSymbolId == provider.id &&
                relation.metadata["http.providerMethod"] ==
                "com.charmnight.linkgraph.fixtures.http.OrderProviderController.getOrder(java.lang.String):java.lang.String"
        }, relationSummary)
    }

    fun testSpringEventRelationsAcceptFullyQualifiedSpringListenerAnnotation() {
        myFixture.addFileToProject(
            "src/main/java/com/example/event/OrderCreatedEvent.java",
            """
            package com.example.event;

            public class OrderCreatedEvent {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/event/EventPublisher.java",
            """
            package com.example.event;

            class EventPublisher {
                private final org.springframework.context.ApplicationEventPublisher publisher = null;

                void publish() {
                    publisher.publishEvent(new OrderCreatedEvent());
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/event/SearchIndexListener.java",
            """
            package com.example.event;

            class SearchIndexListener {
                @org.springframework.context.event.EventListener
                public void onEvent(OrderCreatedEvent event) {}
            }
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val relationIndex = JvmRelationResolverRegistry().resolveAll(
            JvmResolutionContext(
                project = project,
                symbolIndex = symbolIndex,
                sourceResolver = IdeSourceContentResolver(project),
            ),
        )
        val publisher = requireNotNull(symbolIndex.findClass("com.example.event.EventPublisher"))
        val listener = requireNotNull(symbolIndex.findClass("com.example.event.SearchIndexListener"))

        val relationSummary = relationIndex.relations.joinToString("\n") { relation ->
            "${relation.kind} ${relation.fromSymbolId} -> ${relation.toSymbolId} ${relation.metadata}"
        }
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.SPRING_EVENT_LISTENS &&
                relation.fromSymbolId == publisher.id &&
                relation.toSymbolId == listener.id &&
                relation.metadata["spring.event"] == "com.example.event.OrderCreatedEvent" &&
                relation.metadata["spring.listenerMethod"] ==
                "com.example.event.SearchIndexListener.onEvent(com.example.event.OrderCreatedEvent):void"
        }, relationSummary)
    }

    fun testMethodLevelCallMetadataAndTestRelationAreIndexed() {
        myFixture.addFileToProject(
            "src/main/java/com/example/billing/InvoiceService.java",
            """
            package com.example.billing;
            public class InvoiceService {
                public void bill() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/test/java/com/example/billing/InvoiceServiceTest.java",
            """
            package com.example.billing;
            import org.junit.jupiter.api.Test;
            public class InvoiceServiceTest {
                @Test
                public void coversBilling() {
                    new InvoiceService().bill();
                }
            }
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val relationIndex = JvmRelationResolverRegistry().resolveAll(
            JvmResolutionContext(
                project = project,
                symbolIndex = symbolIndex,
                sourceResolver = IdeSourceContentResolver(project),
            ),
        )
        val service = requireNotNull(symbolIndex.findClass("com.example.billing.InvoiceService"))
        val test = requireNotNull(symbolIndex.findClass("com.example.billing.InvoiceServiceTest"))
        val targetMethod = requireNotNull(symbolIndex.findMethod("com.example.billing.InvoiceService.bill():void"))
        val testMethod = requireNotNull(symbolIndex.findMethod("com.example.billing.InvoiceServiceTest.coversBilling():void"))

        val relationSummary = relationIndex.relations.joinToString("\n") { relation ->
            "${relation.kind} ${relation.fromSymbolId} -> ${relation.toSymbolId} ${relation.metadata}"
        }
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.CALLS &&
                relation.fromSymbolId == test.id &&
                relation.toSymbolId == service.id &&
                relation.metadata["call.sourceMethodSignatures"] == testMethod.signature &&
                relation.metadata["call.targetMethodSignatures"] == targetMethod.signature &&
                relation.metadata["test.framework"] == "true"
        }, relationSummary)
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.TESTS &&
                relation.fromSymbolId == testMethod.id &&
                relation.toSymbolId == targetMethod.id &&
                relation.metadata["test.reason"] == "CALL_PATH"
        }, relationSummary)
    }

    fun testInterfaceCallAggregationKeepsRuntimeDispatchConfidence() {
        myFixture.addFileToProject(
            "src/main/java/com/example/payment/PaymentGateway.java",
            """
            package com.example.payment;
            public interface PaymentGateway {
                String fetch(String id);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/payment/PaymentService.java",
            """
            package com.example.payment;
            public class PaymentService {
                public String load(PaymentGateway gateway, String id) {
                    return gateway.fetch(id);
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/payment/StripeGateway.java",
            """
            package com.example.payment;
            public class StripeGateway implements PaymentGateway {
                public String fetch(String id) { return id; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/payment/PaypalGateway.java",
            """
            package com.example.payment;
            public class PaypalGateway implements PaymentGateway {
                public String fetch(String id) { return id; }
            }
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val relationIndex = JvmRelationResolverRegistry().resolveAll(
            JvmResolutionContext(
                project = project,
                symbolIndex = symbolIndex,
                sourceResolver = IdeSourceContentResolver(project),
            ),
        )
        val service = requireNotNull(symbolIndex.findClass("com.example.payment.PaymentService"))
        val gateway = requireNotNull(symbolIndex.findClass("com.example.payment.PaymentGateway"))
        val stripe = requireNotNull(symbolIndex.findClass("com.example.payment.StripeGateway"))
        val paypal = requireNotNull(symbolIndex.findClass("com.example.payment.PaypalGateway"))
        val loadMethod = requireNotNull(symbolIndex.findMethod("com.example.payment.PaymentService.load(com.example.payment.PaymentGateway,java.lang.String):java.lang.String"))
        val interfaceMethod = requireNotNull(symbolIndex.findMethod("com.example.payment.PaymentGateway.fetch(java.lang.String):java.lang.String"))

        val relationSummary = relationIndex.relations.joinToString("\n") { relation ->
            "${relation.kind} ${relation.fromSymbolId} -> ${relation.toSymbolId} ${relation.confidence} ${relation.metadata}"
        }
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.CALLS &&
                relation.fromSymbolId == service.id &&
                relation.toSymbolId == gateway.id &&
                relation.confidence == JvmRelationConfidence.RUNTIME_REQUIRED &&
                relation.metadata["jvm.dispatch.kind"] == "INTERFACE_DISPATCH" &&
                relation.metadata["call.sourceMethodSignatures"] == loadMethod.signature &&
                relation.metadata["call.targetMethodSignatures"] == interfaceMethod.signature
        }, relationSummary)
        assertTrue(relationIndex.relations.none { relation ->
            relation.kind == JvmRelationKind.CALLS &&
                relation.fromSymbolId == service.id &&
                relation.toSymbolId in setOf(stripe.id, paypal.id)
        }, relationSummary)
    }

    fun testCallAggregationOnlyScansBudgetedSourceClasses() {
        myFixture.addFileToProject(
            "src/main/java/com/example/scope/TargetService.java",
            """
            package com.example.scope;
            public class TargetService {
                public void execute() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/scope/AllowedCaller.java",
            """
            package com.example.scope;
            public class AllowedCaller {
                public void run(TargetService target) {
                    target.execute();
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/scope/BlockedCaller.java",
            """
            package com.example.scope;
            public class BlockedCaller {
                public void run(TargetService target) {
                    target.execute();
                }
            }
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val allowed = requireNotNull(symbolIndex.findClass("com.example.scope.AllowedCaller"))
        val blocked = requireNotNull(symbolIndex.findClass("com.example.scope.BlockedCaller"))
        val target = requireNotNull(symbolIndex.findClass("com.example.scope.TargetService"))
        val relations = CallAggregationRelationResolver().resolve(
            JvmResolutionContext(
                project = project,
                symbolIndex = symbolIndex,
                sourceResolver = IdeSourceContentResolver(project),
                budget = JvmResolutionBudget(
                    methodBodySourceClassIds = setOf(allowed.id),
                    maxMethodBodiesScanned = 10,
                    maxMethodCallExpressionsResolved = 10,
                ),
            ),
        )

        val relationSummary = relations.joinToString("\n") { relation ->
            "${relation.kind} ${relation.fromSymbolId} -> ${relation.toSymbolId} ${relation.metadata}"
        }
        assertTrue(
            relations.any { relation ->
                relation.kind == JvmRelationKind.CALLS &&
                    relation.fromSymbolId == allowed.id &&
                    relation.toSymbolId == target.id
            },
            relationSummary,
        )
        assertTrue(
            relations.none { relation ->
                relation.kind == JvmRelationKind.CALLS &&
                    relation.fromSymbolId == blocked.id
            },
            relationSummary,
        )
    }

    fun testStaticReflectionConstantsAndClassLiteralsAreProven() {
        myFixture.addFileToProject(
            "src/main/java/com/example/reflect/StaticReflectionTarget.java",
            """
            package com.example.reflect;
            public class StaticReflectionTarget {
                public void execute() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/reflect/StaticReflectionCaller.java",
            """
            package com.example.reflect;
            public class StaticReflectionCaller {
                private static final String TARGET = "com.example.reflect." + "StaticReflectionTarget";
                public void invoke() throws Exception {
                    String method = "exec" + "ute";
                    Class.forName(TARGET).getMethod(method);
                    Class<?> targetClass = StaticReflectionTarget.class;
                }
            }
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val relationIndex = JvmRelationResolverRegistry().resolveAll(
            JvmResolutionContext(
                project = project,
                symbolIndex = symbolIndex,
                sourceResolver = IdeSourceContentResolver(project),
            ),
        )
        val caller = requireNotNull(symbolIndex.findClass("com.example.reflect.StaticReflectionCaller"))
        val target = requireNotNull(symbolIndex.findClass("com.example.reflect.StaticReflectionTarget"))
        val targetMethod = requireNotNull(symbolIndex.findMethod("com.example.reflect.StaticReflectionTarget.execute():void"))
        val relationSummary = relationIndex.relations.joinToString("\n") { relation ->
            "${relation.kind} ${relation.fromSymbolId} -> ${relation.toSymbolId} ${relation.confidence} ${relation.metadata}"
        }

        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.REFLECTS_TO &&
                relation.fromSymbolId == caller.id &&
                relation.toSymbolId == target.id &&
                relation.confidence == JvmRelationConfidence.PROVEN &&
                relation.metadata["reflect.kind"] == "CLASS_FOR_NAME" &&
                relation.metadata["confidence.label"] == "STATIC_DERIVED_CONSTANT"
        }, relationSummary)
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.REFLECTS_TO &&
                relation.fromSymbolId == caller.id &&
                relation.toSymbolId == targetMethod.id &&
                relation.confidence == JvmRelationConfidence.PROVEN &&
                relation.metadata["reflect.kind"] == "GETMETHOD" &&
                relation.metadata["confidence.label"] == "STATIC_DERIVED_CONSTANT"
        }, relationSummary)
        assertTrue(relationIndex.relations.any { relation ->
            relation.kind == JvmRelationKind.REFLECTS_TO &&
                relation.fromSymbolId == caller.id &&
                relation.toSymbolId == target.id &&
                relation.confidence == JvmRelationConfidence.PROVEN &&
                relation.metadata["reflect.kind"] == "CLASS_LITERAL"
        }, relationSummary)
    }
}
