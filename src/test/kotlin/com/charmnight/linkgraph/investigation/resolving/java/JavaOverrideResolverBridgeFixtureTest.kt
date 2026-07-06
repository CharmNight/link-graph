package com.charmnight.linkgraph.investigation.resolving.java

import com.charmnight.linkgraph.application.workflow.InvocationExpansionTargetResolver
import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.EvidenceGoalKind
import com.charmnight.linkgraph.investigation.application.ResolutionOutcome
import com.charmnight.linkgraph.investigation.resolving.InvestigationContext
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndexBuilder
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.PsiJvmImplementationSignatureResolver
import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationResolverRegistry
import com.charmnight.linkgraph.jvm.relation.JvmResolutionContext
import com.charmnight.linkgraph.source.SourceOrigin
import com.charmnight.linkgraph.source.IdeSourceContentResolver
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun psiImplementationResolver(): PsiJvmImplementationSignatureResolver =
        PsiJvmImplementationSignatureResolver(project)

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

    fun testInvocationExpansionFallsBackToPsiWhenArchitectureRelationsMissImplementations() {
        myFixture.addFileToProject(
            "src/main/java/com/example/package_manager/AbstractPackageManagerScanner.java",
            """
            package com.example.package_manager;

            import java.util.List;

            public abstract class AbstractPackageManagerScanner {
                protected abstract List<String> detectPackageManagers();
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/package_manager/scanner/LinuxPackageManagerScanner.java",
            """
            package com.example.package_manager.scanner;

            import com.example.package_manager.AbstractPackageManagerScanner;
            import java.util.List;

            public class LinuxPackageManagerScanner extends AbstractPackageManagerScanner {
                @Override
                protected List<String> detectPackageManagers() {
                    return java.util.List.of("apt");
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/package_manager/scanner/MacPackageManagerScanner.java",
            """
            package com.example.package_manager.scanner;

            import com.example.package_manager.AbstractPackageManagerScanner;
            import java.util.List;

            public class MacPackageManagerScanner extends AbstractPackageManagerScanner {
                @Override
                protected List<String> detectPackageManagers() {
                    return java.util.List.of("brew");
                }
            }
            """.trimIndent(),
        )

        val abstractClass = classSymbol(
            qualifiedName = "com.example.package_manager.AbstractPackageManagerScanner",
            abstract = true,
        )
        val abstractMethod = methodSymbol(
            owner = abstractClass,
            name = "detectPackageManagers",
            returnType = "java.util.List<java.lang.String>",
            abstract = true,
        )
        val target = expansionResolver.resolve(
            abstractMethod.signature,
            ArchitectureGraphIndex.from(
                JvmSymbolIndex(
                    classesByQualifiedName = listOf(abstractClass).associateBy(JvmClassSymbol::qualifiedName),
                    methodsBySignature = listOf(abstractMethod).associateBy(JvmMethodSymbol::signature),
                ),
                JvmRelationIndex(),
            ),
            psiImplementationResolver(),
        )

        assertEquals(
            com.charmnight.linkgraph.application.usecase.InvocationExpansionTargetKind.MULTIPLE_IMPLEMENTATIONS,
            target.kind,
        )
        assertEquals(
            listOf(
                "com.example.package_manager.scanner.LinuxPackageManagerScanner.detectPackageManagers():List<String>",
                "com.example.package_manager.scanner.MacPackageManagerScanner.detectPackageManagers():List<String>",
            ),
            target.candidateSignatures,
        )
    }

    fun testInvocationExpansionFallsBackToPsiForCpePackageManagerScannerShape() {
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/package_manager/AbstractPackageManagerScanner.java",
            """
            package com.cpescan.core.package_manager;

            import com.cpescan.model.Software;
            import java.io.IOException;
            import java.util.List;

            public abstract class AbstractPackageManagerScanner implements PackageManagerScanner {
                @Override
                public List<Software> scan() throws IOException {
                    List<String> availablePackageManagers = detectPackageManagers();
                    return scanPackages(availablePackageManagers.get(0));
                }

                protected abstract List<String> detectPackageManagers();
                protected abstract List<Software> scanPackages(String packageManager) throws IOException;
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/package_manager/PackageManagerScanner.java",
            """
            package com.cpescan.core.package_manager;

            import com.cpescan.model.Software;
            import java.io.IOException;
            import java.util.List;

            public interface PackageManagerScanner {
                List<Software> scan() throws IOException;
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/model/Software.java",
            """
            package com.cpescan.model;

            public class Software {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/scanner/MacScanner.java",
            """
            package com.cpescan.core.scanner;

            public interface MacScanner {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/scanner/WindowsScanner.java",
            """
            package com.cpescan.core.scanner;

            public interface WindowsScanner {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/package_manager/scanner/LinuxPackageManagerScanner.java",
            """
            package com.cpescan.core.package_manager.scanner;

            import com.cpescan.core.package_manager.AbstractPackageManagerScanner;
            import com.cpescan.model.Software;
            import java.io.IOException;
            import java.util.List;

            public class LinuxPackageManagerScanner extends AbstractPackageManagerScanner {
                @Override
                protected List<String> detectPackageManagers() {
                    return java.util.List.of("apt");
                }

                @Override
                protected List<Software> scanPackages(String packageManager) throws IOException {
                    return java.util.List.of();
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/package_manager/scanner/MacPackageManagerScanner.java",
            """
            package com.cpescan.core.package_manager.scanner;

            import com.cpescan.core.package_manager.AbstractPackageManagerScanner;
            import com.cpescan.core.scanner.MacScanner;
            import com.cpescan.model.Software;
            import java.io.IOException;
            import java.util.List;

            public class MacPackageManagerScanner extends AbstractPackageManagerScanner implements MacScanner {
                @Override
                protected List<String> detectPackageManagers() {
                    return java.util.List.of("brew");
                }

                @Override
                protected List<Software> scanPackages(String packageManager) throws IOException {
                    return java.util.List.of();
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/package_manager/scanner/WindowsPackageManagerScanner.java",
            """
            package com.cpescan.core.package_manager.scanner;

            import com.cpescan.core.package_manager.AbstractPackageManagerScanner;
            import com.cpescan.core.scanner.WindowsScanner;
            import com.cpescan.model.Software;
            import java.io.IOException;
            import java.util.List;

            public class WindowsPackageManagerScanner extends AbstractPackageManagerScanner implements WindowsScanner {
                @Override
                protected List<String> detectPackageManagers() {
                    return java.util.List.of("winget");
                }

                @Override
                protected List<Software> scanPackages(String packageManager) throws IOException {
                    return java.util.List.of();
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/package_manager/PackageManagerScannerFactory.java",
            """
            package com.cpescan.core.package_manager;

            import com.cpescan.model.Software;
            import java.io.IOException;
            import java.util.List;

            public class PackageManagerScannerFactory {
                public AbstractPackageManagerScanner createScanner() {
                    return new AbstractPackageManagerScanner() {
                        @Override
                        protected List<String> detectPackageManagers() {
                            return java.util.List.of();
                        }

                        @Override
                        protected List<Software> scanPackages(String packageManager) throws IOException {
                            return java.util.List.of();
                        }
                    };
                }
            }
            """.trimIndent(),
        )

        val abstractClass = classSymbol(
            qualifiedName = "com.cpescan.core.package_manager.AbstractPackageManagerScanner",
            abstract = true,
        )
        val abstractMethod = methodSymbol(
            owner = abstractClass,
            name = "detectPackageManagers",
            returnType = "List<String>",
            abstract = true,
        )
        val target = expansionResolver.resolve(
            "com.cpescan.core.package_manager.AbstractPackageManagerScanner.detectPackageManagers():List<String>",
            ArchitectureGraphIndex.from(
                JvmSymbolIndex(
                    classesByQualifiedName = listOf(abstractClass).associateBy(JvmClassSymbol::qualifiedName),
                    methodsBySignature = listOf(abstractMethod).associateBy(JvmMethodSymbol::signature),
                ),
                JvmRelationIndex(),
            ),
            psiImplementationResolver(),
        )

        assertEquals(
            com.charmnight.linkgraph.application.usecase.InvocationExpansionTargetKind.MULTIPLE_IMPLEMENTATIONS,
            target.kind,
        )
        assertEquals(
            listOf(
                "com.cpescan.core.package_manager.scanner.LinuxPackageManagerScanner.detectPackageManagers():List<String>",
                "com.cpescan.core.package_manager.scanner.MacPackageManagerScanner.detectPackageManagers():List<String>",
                "com.cpescan.core.package_manager.scanner.WindowsPackageManagerScanner.detectPackageManagers():List<String>",
            ),
            target.candidateSignatures,
            "只应包含可通过稳定类签名重新定位的命名实现，实际候选=${target.candidateSignatures}",
        )
    }

    fun testInvocationExpansionPsiFallbackDoesNotRequireCallerReadAction() {
        addCpePackageManagerScannerShape()
        val abstractClass = classSymbol(
            qualifiedName = "com.cpescan.core.package_manager.AbstractPackageManagerScanner",
            abstract = true,
        )
        val abstractMethod = methodSymbol(
            owner = abstractClass,
            name = "detectPackageManagers",
            returnType = "List<String>",
            abstract = true,
        )
        val future =
            ApplicationManager.getApplication().executeOnPooledThread<com.charmnight.linkgraph.application.usecase.InvocationExpansionTarget> {
                assertFalse(
                    ApplicationManager.getApplication().isReadAccessAllowed,
                    "回归前提不成立：测试线程已经持有读锁，无法覆盖真实问题路径。",
                )
                expansionResolver.resolve(
                    "com.cpescan.core.package_manager.AbstractPackageManagerScanner.detectPackageManagers():List<String>",
                    ArchitectureGraphIndex.from(
                        JvmSymbolIndex(
                            classesByQualifiedName = listOf(abstractClass).associateBy(JvmClassSymbol::qualifiedName),
                            methodsBySignature = listOf(abstractMethod).associateBy(JvmMethodSymbol::signature),
                        ),
                        JvmRelationIndex(),
                    ),
                    psiImplementationResolver(),
                )
            }
        repeat(100) {
            if (future.isDone) {
                return@repeat
            }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        val target = future.get()

        assertEquals(
            com.charmnight.linkgraph.application.usecase.InvocationExpansionTargetKind.MULTIPLE_IMPLEMENTATIONS,
            target.kind,
        )
        assertEquals(
            listOf(
                "com.cpescan.core.package_manager.scanner.LinuxPackageManagerScanner.detectPackageManagers():List<String>",
                "com.cpescan.core.package_manager.scanner.MacPackageManagerScanner.detectPackageManagers():List<String>",
                "com.cpescan.core.package_manager.scanner.WindowsPackageManagerScanner.detectPackageManagers():List<String>",
            ),
            target.candidateSignatures,
        )
    }

    fun testInvocationExpansionResolvesCpePackageManagerScannerFromRealIndex() {
        addCpePackageManagerScannerShape()

        val index = buildIndex(project)
        val target = expansionResolver.resolve(
            "com.cpescan.core.package_manager.AbstractPackageManagerScanner.detectPackageManagers():List<String>",
            index,
            psiImplementationResolver(),
        )

        assertEquals(
            com.charmnight.linkgraph.application.usecase.InvocationExpansionTargetKind.MULTIPLE_IMPLEMENTATIONS,
            target.kind,
            "真实索引应解析出多个项目内实现；实际 kind=${target.kind}, candidates=${target.candidateSignatures}, ${cpeIndexDiagnostic(index)}",
        )
        val candidateText = target.candidateSignatures.joinToString("|")
        assertTrue(
            candidateText.contains("LinuxPackageManagerScanner.detectPackageManagers"),
            "缺少 Linux 实现；实际 candidates=${target.candidateSignatures}, ${cpeIndexDiagnostic(index)}",
        )
        assertTrue(
            candidateText.contains("MacPackageManagerScanner.detectPackageManagers"),
            "缺少 Mac 实现；实际 candidates=${target.candidateSignatures}, ${cpeIndexDiagnostic(index)}",
        )
        assertTrue(
            candidateText.contains("WindowsPackageManagerScanner.detectPackageManagers"),
            "缺少 Windows 实现；实际 candidates=${target.candidateSignatures}, ${cpeIndexDiagnostic(index)}",
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

    private fun addCpePackageManagerScannerShape() {
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/package_manager/AbstractPackageManagerScanner.java",
            """
            package com.cpescan.core.package_manager;

            import com.cpescan.model.Software;
            import java.io.IOException;
            import java.util.List;

            public abstract class AbstractPackageManagerScanner implements PackageManagerScanner {
                @Override
                public List<Software> scan() throws IOException {
                    List<String> availablePackageManagers = detectPackageManagers();
                    return scanPackages(availablePackageManagers.get(0));
                }

                protected abstract List<String> detectPackageManagers();
                protected abstract List<Software> scanPackages(String packageManager) throws IOException;
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/package_manager/PackageManagerScanner.java",
            """
            package com.cpescan.core.package_manager;

            import com.cpescan.model.Software;
            import java.io.IOException;
            import java.util.List;

            public interface PackageManagerScanner {
                List<Software> scan() throws IOException;
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/model/Software.java",
            """
            package com.cpescan.model;

            public class Software {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/scanner/MacScanner.java",
            """
            package com.cpescan.core.scanner;

            public interface MacScanner {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/scanner/WindowsScanner.java",
            """
            package com.cpescan.core.scanner;

            public interface WindowsScanner {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/package_manager/scanner/LinuxPackageManagerScanner.java",
            """
            package com.cpescan.core.package_manager.scanner;

            import com.cpescan.core.package_manager.AbstractPackageManagerScanner;
            import com.cpescan.model.Software;
            import java.io.IOException;
            import java.util.List;

            public class LinuxPackageManagerScanner extends AbstractPackageManagerScanner {
                @Override
                protected List<String> detectPackageManagers() {
                    return java.util.List.of("apt");
                }

                @Override
                protected List<Software> scanPackages(String packageManager) throws IOException {
                    return java.util.List.of();
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/package_manager/scanner/MacPackageManagerScanner.java",
            """
            package com.cpescan.core.package_manager.scanner;

            import com.cpescan.core.package_manager.AbstractPackageManagerScanner;
            import com.cpescan.core.scanner.MacScanner;
            import com.cpescan.model.Software;
            import java.io.IOException;
            import java.util.List;

            public class MacPackageManagerScanner extends AbstractPackageManagerScanner implements MacScanner {
                @Override
                protected List<String> detectPackageManagers() {
                    return java.util.List.of("brew");
                }

                @Override
                protected List<Software> scanPackages(String packageManager) throws IOException {
                    return java.util.List.of();
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/cpescan/core/package_manager/scanner/WindowsPackageManagerScanner.java",
            """
            package com.cpescan.core.package_manager.scanner;

            import com.cpescan.core.package_manager.AbstractPackageManagerScanner;
            import com.cpescan.core.scanner.WindowsScanner;
            import com.cpescan.model.Software;
            import java.io.IOException;
            import java.util.List;

            public class WindowsPackageManagerScanner extends AbstractPackageManagerScanner implements WindowsScanner {
                @Override
                protected List<String> detectPackageManagers() {
                    return java.util.List.of("winget");
                }

                @Override
                protected List<Software> scanPackages(String packageManager) throws IOException {
                    return java.util.List.of();
                }
            }
            """.trimIndent(),
        )
    }

    private fun cpeIndexDiagnostic(index: ArchitectureGraphIndex): String {
        val ownerClassName = "com.cpescan.core.package_manager.AbstractPackageManagerScanner"
        val ownerClass = index.findClass(ownerClassName)
        val methods = index.symbolIndex.methodsBySignature.values
            .filter { method -> method.simpleName == "detectPackageManagers" }
            .sortedBy(JvmMethodSymbol::signature)
            .joinToString("|") { method ->
                "${method.ownerClassName}:${method.signature}:abstract=${method.abstract}:origin=${method.origin}"
            }
        val incoming = ownerClass
            ?.let { cls -> index.relationIndex.incoming(cls.id) }
            .orEmpty()
            .filter { relation -> relation.kind == com.charmnight.linkgraph.jvm.relation.JvmRelationKind.EXTENDS || relation.kind == com.charmnight.linkgraph.jvm.relation.JvmRelationKind.IMPLEMENTS }
            .joinToString("|") { relation ->
                "${relation.kind}:${relation.fromSymbolId}->${relation.toSymbolId}"
            }
        val classes = index.symbolIndex.classesByQualifiedName.values
            .filter { cls -> cls.qualifiedName.contains("PackageManagerScanner") }
            .sortedBy(JvmClassSymbol::qualifiedName)
            .joinToString("|") { cls ->
                "${cls.qualifiedName}:id=${cls.id}:kind=${cls.kind}:abstract=${cls.abstract}:super=${cls.superClassName}:interfaces=${cls.interfaceNames}"
            }
        return "cpeIndexDiagnostic{owner=$ownerClass, classes=$classes, methods=$methods, incoming=$incoming, relationCount=${index.relationIndex.relations.size}}"
    }

    private fun classSymbol(
        qualifiedName: String,
        abstract: Boolean = false,
    ): JvmClassSymbol =
        JvmClassSymbol(
            id = stableJvmId("class", qualifiedName),
            qualifiedName = qualifiedName,
            simpleName = qualifiedName.substringAfterLast('.'),
            packageName = qualifiedName.substringBeforeLast('.', missingDelimiterValue = ""),
            moduleName = null,
            kind = JvmClassKind.CLASS,
            abstract = abstract,
            source = JvmSourceRef(
                displayPath = qualifiedName.replace('.', '/') + ".java",
                virtualFileUrl = null,
                startLine = 1,
                endLine = 5,
                decompiled = false,
            ),
            origin = SourceOrigin.PROJECT_SOURCE,
        )

    private fun methodSymbol(
        owner: JvmClassSymbol,
        name: String,
        returnType: String,
        abstract: Boolean = false,
    ): JvmMethodSymbol {
        val signature = "${owner.qualifiedName}.$name():$returnType"
        return JvmMethodSymbol(
            id = stableJvmId("method", signature),
            qualifiedName = signature,
            simpleName = name,
            ownerClassName = owner.qualifiedName,
            signature = signature,
            parameterTypes = emptyList(),
            returnType = returnType,
            abstract = abstract,
            source = owner.source,
            origin = owner.origin,
        )
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
