package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.application.workflow.InvocationExpansionTargetResolver
import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.application.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndexBuilder
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationResolverRegistry
import com.charmnight.linkgraph.jvm.relation.JvmResolutionContext
import com.charmnight.linkgraph.source.IdeSourceContentResolver
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1 JavaOverrideResolver / InvocationExpansionTargetResolver 夹具复现测试。
 *
 * 直接观察当前实现是否能正确解析：
 * 1. 协变返回（参数相同 → 期望能匹配）
 * 2. 泛型接口 + 具体类型实现（接口 T 擦除后为 Object，实现 String，描述符不一致）
 * 3. Kotlin suspend 接口 + 实现（两端 Continuation 一致 → 期望能匹配）
 *
 * 测试只观察当前行为，先不加任何修复 ——
 * 如果实际解析失败，证明问题真实存在；
 * 如果实际能解析，则问题不真实。
 */
class JavaOverrideResolverBridgeFixtureTest : BasePlatformTestCase() {
    private lateinit var resolver: JavaOverrideResolver
    private lateinit var expansionResolver: InvocationExpansionTargetResolver

    override fun setUp() {
        super.setUp()
        resolver = JavaOverrideResolver()
        expansionResolver = InvocationExpansionTargetResolver()
    }

    fun testResolvesImplementationWithCovariantReturn() {
        myFixture.addFileToProject(
            "src/main/java/com/example/covariant/Base.java",
            """
            package com.example.covariant;

            public interface Base {
                Object clone();
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/covariant/Concrete.java",
            """
            package com.example.covariant;

            public class Concrete implements Base {
                @Override
                public Concrete clone() {
                    return new Concrete();
                }
            }
            """.trimIndent(),
        )

        val outcome = resolveOverride(project, "com.example.covariant.Base", "clone")
        assertIsResolvedSingle(outcome, "Concrete.clone")
    }

    fun testResolvesGenericInterfaceWithConcreteImplementation() {
        // 泛型接口 <T>，接口字节码描述符是 Object
        // 实现类用 String 特化，描述符是 String
        // 当前按参数类型严格匹配会漏匹配
        myFixture.addFileToProject(
            "src/main/java/com/example/generic/Processor.java",
            """
            package com.example.generic;

            public interface Processor<T> {
                T process(T input);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/generic/StringProcessor.java",
            """
            package com.example.generic;

            public class StringProcessor implements Processor<String> {
                @Override
                public String process(String input) {
                    return input;
                }
            }
            """.trimIndent(),
        )

        val outcome = resolveOverride(project, "com.example.generic.Processor", "process")
        // 期望：能解析到 StringProcessor.process
        // 如果失败 → 问题真实
        assertIsResolvedSingle(outcome, "StringProcessor.process")
    }

    fun testResolvesKotlinSuspendInterfaceImplementation() {
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/suspend/Service.kt",
            """
            package com.example.suspend

            interface Service {
                suspend fun run(): String
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/suspend/ServiceImpl.kt",
            """
            package com.example.suspend

            class ServiceImpl : Service {
                override suspend fun run(): String = "ok"
            }
            """.trimIndent(),
        )

        val outcome = resolveOverride(project, "com.example.suspend.Service", "run")
        // suspend 两端 Continuation 描述符一致 → 期望能解析
        assertIsResolvedSingle(outcome, "ServiceImpl.run")
    }

    fun testInvocationExpansionResolvesGenericImplementation() {
        myFixture.addFileToProject(
            "src/main/java/com/example/expansion/Handler.java",
            """
            package com.example.expansion;

            public interface Handler<T> {
                T handle(T input);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/expansion/StringHandler.java",
            """
            package com.example.expansion;

            public class StringHandler implements Handler<String> {
                @Override
                public String handle(String input) {
                    return input;
                }
            }
            """.trimIndent(),
        )

        val index = buildIndex(project)
        // 真实场景：调用点是接口签名（PSI 取源码泛型名 T）
        val target = expansionResolver.resolve(
            "com.example.expansion.Handler.handle(T):T",
            index,
        )

        // 期望：能解析到 StringHandler.handle
        assertEquals(
            com.charmnight.linkgraph.application.usecase.InvocationExpansionTargetKind.PROJECT_SOURCE,
            target.kind,
            "InvocationExpansionTargetResolver 应能解析泛型实现；实际 kind=${target.kind}",
        )
        assertTrue(
            target.signature?.contains("StringHandler") == true,
            "解析后签名应指向 StringHandler；实际=${target.signature}",
        )
    }

    fun testResolverRejectsSameArityDifferentTypeOverload() {
        // 修复 P1 提交引入的过匹配：实现类有同名同参数数量但类型不同的方法（重载），
        // 不应被当作接口方法的重写。
        // - 接口 Processor 有 process(String)
        // - FooImpl 实现了 Processor，并自己另定义了 process(Integer)（不是重写）
        // 解析 Processor.process(String) 应只返回 FooImpl.process(String)，不返回 process(Integer)。
        myFixture.addFileToProject(
            "src/main/java/com/example/overload/Processor.java",
            """
            package com.example.overload;

            public interface Processor {
                String process(String input);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/overload/FooImpl.java",
            """
            package com.example.overload;

            public class FooImpl implements Processor {
                @Override
                public String process(String input) {
                    return input;
                }
                // 同名同参数数量但类型不同：不是 Processor.process 的重写
                public Integer process(Integer input) {
                    return input;
                }
            }
            """.trimIndent(),
        )

        val outcome = resolveOverride(project, "com.example.overload.Processor", "process")
        // 期望：单实现 FooImpl.process(String)，不会被 process(Integer) 污染成 MultipleCandidates
        assertIsResolvedSingle(outcome, "FooImpl.process")
        // 进一步断言事实签名里只有 String 参数版本
        val signatures = (outcome as ResolutionOutcome.Resolved).facts
            .mapNotNull { it.symbolSignature }
            .joinToString(";")
        assertTrue(
            !signatures.contains("Integer"),
            "解析结果不应包含 process(Integer) 的重载；实际 fact: $signatures",
        )
    }

    // ---------- 辅助函数 ----------

    private fun resolveOverride(
        project: Project,
        ownerClassName: String,
        methodName: String,
    ): ResolutionOutcome {
        val index = buildIndex(project)
        requireNotNull(index.findClass(ownerClassName)) {
            "找不到测试夹具类 $ownerClassName — 检查测试夹具是否被索引"
        }
        val baseMethod = index.symbolIndex.methodsBySignature.values.firstOrNull {
            it.ownerClassName == ownerClassName && it.simpleName == methodName
        } ?: error("找不到测试夹具方法 $ownerClassName.$methodName — 检查测试夹具是否被索引")
        val goal = EvidenceGoal(
            goalId = "test-goal",
            kind = EvidenceGoalKind.METHOD_OVERRIDE,
            sourceThreadId = "test-thread",
            claim = "resolve override",
            ownerClassName = ownerClassName,
            methodName = methodName,
            symbolSignature = baseMethod.signature,
        )
        val context = InvestigationContext(project = project, jvmEvidenceIndex = index)
        return resolver.resolve(goal, context)
    }

    private fun buildIndex(project: Project): ArchitectureGraphIndex {
        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val relationIndex = JvmRelationResolverRegistry().resolveAll(
            JvmResolutionContext(
                project = project,
                symbolIndex = symbolIndex,
                sourceResolver = IdeSourceContentResolver(project),
            ),
        )
        return ArchitectureGraphIndex.from(symbolIndex, relationIndex)
    }

    private fun assertIsResolvedSingle(
        outcome: ResolutionOutcome,
        expectedImplClassPrefix: String,
    ) {
        when (outcome) {
            is ResolutionOutcome.Resolved -> {
                val sig = outcome.facts.joinToString { it.symbolSignature }
                assertTrue(
                    sig.contains(expectedImplClassPrefix),
                    "期望解析到 $expectedImplClassPrefix；实际 fact: $sig",
                )
            }
            is ResolutionOutcome.Unresolved ->
                error("期望 Resolved 但实际 Unresolved: ${outcome.reason}")
            is ResolutionOutcome.MultipleCandidates ->
                error("期望 Resolved 但实际 MultipleCandidates: ${outcome.reason}")
            is ResolutionOutcome.CandidateOnly ->
                error("期望 Resolved 但实际 CandidateOnly: ${outcome.reason}")
            is ResolutionOutcome.Failed ->
                error("期望 Resolved 但实际 Failed: ${outcome.error}")
        }
    }
}
