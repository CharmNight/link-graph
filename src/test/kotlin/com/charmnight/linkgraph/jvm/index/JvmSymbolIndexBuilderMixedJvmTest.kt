package com.charmnight.linkgraph.jvm.index

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.projection.business.ClassDiagramProjector
import com.charmnight.linkgraph.application.indexed.IndexedClassDiagramOptions
import com.charmnight.linkgraph.application.indexed.IndexedGraphViewportOptions
import com.charmnight.linkgraph.application.indexed.requestClassDiagramRequest
import com.charmnight.linkgraph.jvm.relation.JvmRelationResolverRegistry
import com.charmnight.linkgraph.jvm.relation.JvmResolutionContext
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.source.IdeSourceContentResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmSymbolIndexBuilderMixedJvmTest : BasePlatformTestCase() {
    fun testJavaClassKeepsImportedSuperTypeWhenPsiCannotResolveIt() {
        myFixture.addFileToProject(
            "src/main/java/com/example/runtime/scanner/LinuxPackageManagerScanner.java",
            """
            package com.example.runtime.scanner;

            import com.example.runtime.AbstractPackageManagerScanner;

            public class LinuxPackageManagerScanner extends AbstractPackageManagerScanner {
            }
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val scanner = requireNotNull(symbolIndex.findClass("com.example.runtime.scanner.LinuxPackageManagerScanner"))

        assertEquals("com.example.runtime.AbstractPackageManagerScanner", scanner.superClassName)
    }

    fun testJavaFieldDependencyOnScalaSourceAppearsInClassDiagram() {
        myFixture.addFileToProject(
            "src/main/scala/com/example/mixed/ScalaDependency.scala",
            """
            package com.example.mixed

            class ScalaDependency
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/mixed/JavaOwner.java",
            """
            package com.example.mixed;

            public class JavaOwner {
                private final ScalaDependency dependency;

                public JavaOwner(ScalaDependency dependency) {
                    this.dependency = dependency;
                }
            }
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val owner = requireNotNull(symbolIndex.findClass("com.example.mixed.JavaOwner"))
        val scalaDependency = requireNotNull(symbolIndex.findClass("com.example.mixed.ScalaDependency"))
        val view = ClassDiagramProjector().project(
            index = com.charmnight.linkgraph.architecture.ClassDiagramFastIndex.fromSymbols(symbolIndex),
            scopeNodeId = owner.id,
        )

        assertTrue(view.fullGraph.nodes.any { node -> node.id == scalaDependency.id })
        assertTrue(view.fullGraph.edges.any { edge ->
            edge.fromNodeId == owner.id &&
                edge.toNodeId == scalaDependency.id &&
                edge.metadata["classDiagram.relation.role"] == "FIELD"
        })
    }

    fun testConstructorParameterAssignedToFieldKeepsConstructorRelationRole() {
        myFixture.addFileToProject(
            "src/main/java/com/example/ctor/Validator.java",
            """
            package com.example.ctor;

            public class Validator {
                private final Config config;

                public Validator(Config config, LoaderManifest manifest) {
                    this.config = config;
                }

                public void validate(LoaderManifest manifest) {
                }
            }

            class Config {}
            class LoaderManifest {}
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
        val validator = requireNotNull(symbolIndex.findClass("com.example.ctor.Validator"))
        val config = requireNotNull(symbolIndex.findClass("com.example.ctor.Config"))
        val configRelations = relationIndex.relations.filter { relation ->
            relation.fromSymbolId == validator.id && relation.toSymbolId == config.id
        }

        assertTrue(
            configRelations.any { relation ->
                relation.metadata["classDiagram.relation.role"] == "CONSTRUCTOR_PARAMETER" &&
                    relation.metadata["classDiagram.relation.usedInBody"] == "true"
            },
            configRelations.joinToString("\n") { relation -> "${relation.kind} ${relation.metadata}" },
        )
        assertTrue(
            configRelations.none { relation ->
                relation.metadata["classDiagram.relation.role"] == "METHOD_PARAMETER"
            },
            configRelations.joinToString("\n") { relation -> "${relation.kind} ${relation.metadata}" },
        )
    }

    fun testKafkaMetadataVersionConfigValidatorKeepsKafkaConfigAheadOfUnusedLoaderManifest() {
        val (validator, kafkaConfig, view) = kafkaMetadataVersionConfigValidatorView(
            request = { validatorId ->
                requestClassDiagramRequest(validatorId).copy(
                    classDiagram = IndexedClassDiagramOptions(neighborhoodLimit = 7),
                    viewport = IndexedGraphViewportOptions(maxVisibleNodes = 7, maxVisibleEdges = 18),
                )
            },
        )
        val visibleTitles = view.visibleGraph.nodes.map { node -> node.title }.toSet()

        assertEquals(
            setOf(
                "MetadataVersionConfigValidator",
                "MetadataPublisher",
                "KafkaConfig",
                "FaultHandler",
                "MetadataDelta",
                "MetadataImage",
                "MetadataVersion",
            ),
            visibleTitles,
        )
        assertTrue("LoaderManifest" !in visibleTitles, visibleTitles.toString())
        assertTrue(view.fullGraph.edges.any { edge ->
            edge.fromNodeId == validator.id &&
                edge.toNodeId == kafkaConfig.id &&
                edge.metadata["classDiagram.relation.role"] in setOf("FIELD", "CONSTRUCTOR_PARAMETER", "METHOD_CALL")
        })
        assertVisibleRelationLabel(view.visibleGraph, validator.id, "KafkaConfig", "field config")
        assertVisibleRelationLabel(view.visibleGraph, validator.id, "KafkaConfig", "ctor config")
        assertVisibleRelationLabel(view.visibleGraph, validator.id, "MetadataDelta", "call metadataVersionChanged")
        assertVisibleRelationLabel(view.visibleGraph, validator.id, "MetadataImage", "call features")
        assertVisibleRelationLabel(view.visibleGraph, validator.id, "MetadataVersion", "field latestVersion")
    }

    fun testKafkaMetadataVersionConfigValidatorDefaultClassDiagramKeepsUsedDependenciesVisible() {
        val (validator, kafkaConfig, view) = kafkaMetadataVersionConfigValidatorView(
            request = { validatorId -> requestClassDiagramRequest(validatorId) },
        )
        val visibleTitles = view.visibleGraph.nodes.map { node -> node.title }.toSet()

        assertEquals(validator.id, view.anchorNodeId)
        assertTrue("KafkaConfig" in visibleTitles, visibleTitles.toString())
        assertTrue("FaultHandler" in visibleTitles, visibleTitles.toString())
        assertTrue(view.fullGraph.nodes.any { node -> node.title == "LoaderManifest" })
        assertTrue(view.fullGraph.edges.any { edge ->
            edge.fromNodeId == validator.id &&
                edge.toNodeId == kafkaConfig.id &&
                edge.metadata["classDiagram.relation.role"] in setOf("FIELD", "CONSTRUCTOR_PARAMETER", "METHOD_CALL")
        })
    }

    private data class KafkaValidatorViewFixture(
        val validator: JvmClassSymbol,
        val kafkaConfig: JvmClassSymbol,
        val view: com.charmnight.linkgraph.architecture.ClassDiagramResult,
    )

    private fun assertVisibleRelationLabel(
        graph: GraphDocument,
        fromNodeId: String,
        targetTitle: String,
        expectedLabelPart: String,
    ) {
        val targetNodeId = graph.nodes.firstOrNull { node -> node.title == targetTitle }?.id
        assertTrue(targetNodeId != null, "Expected visible class node $targetTitle")
        val labels = graph.edges
            .filter { edge -> edge.fromNodeId == fromNodeId && edge.toNodeId == targetNodeId }
            .map { edge -> edge.readableLabel() }
        assertTrue(
            labels.any { label -> expectedLabelPart in label },
            "Expected relation to $targetTitle to include <$expectedLabelPart>, labels=$labels",
        )
    }

    private fun GraphEdge.readableLabel(): String =
        listOfNotNull(
            label,
            metadata["uml.relation.aggregate.label"],
            metadata["uml.relation.aggregate.primaryLabel"],
            metadata["uml.relation.aggregate.secondaryLabels"],
            metadata["classDiagram.relation.label"],
            metadata["uml.relation.label"],
        ).joinToString(" ")

    private fun kafkaMetadataVersionConfigValidatorView(
        request: (String) -> com.charmnight.linkgraph.application.indexed.IndexedGraphRequest,
    ): KafkaValidatorViewFixture {
        addKafkaSupportStubs()
        addKafkaValidatorFixtureSources()

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val validator = requireNotNull(symbolIndex.findClass("kafka.server.MetadataVersionConfigValidator"))
        val kafkaConfig = requireNotNull(symbolIndex.findClass("kafka.server.KafkaConfig"))
        val relationIndex = JvmRelationResolverRegistry().resolveAll(
            JvmResolutionContext(
                project = project,
                symbolIndex = symbolIndex,
                sourceResolver = IdeSourceContentResolver(project),
            ),
        )
        val view = ClassDiagramProjector().project(
            index = ArchitectureGraphIndex.from(symbolIndex, relationIndex),
            scopeNodeId = validator.id,
            request = request(validator.id),
        )
        return KafkaValidatorViewFixture(validator, kafkaConfig, view)
    }

    private fun addKafkaValidatorFixtureSources() {
        myFixture.addFileToProject(
            "core/src/main/java/kafka/server/MetadataVersionConfigValidator.java",
            """
            package kafka.server;

            import org.apache.kafka.image.MetadataDelta;
            import org.apache.kafka.image.MetadataImage;
            import org.apache.kafka.image.loader.LoaderManifest;
            import org.apache.kafka.image.publisher.MetadataPublisher;
            import org.apache.kafka.server.common.MetadataVersion;
            import org.apache.kafka.server.fault.FaultHandler;

            public final class MetadataVersionConfigValidator implements MetadataPublisher {
                private final KafkaConfig config;
                private final FaultHandler faultHandler;
                private MetadataVersion latestVersion;

                public MetadataVersionConfigValidator(
                    KafkaConfig config,
                    FaultHandler faultHandler,
                    LoaderManifest manifest
                ) {
                    this.config = config;
                    this.faultHandler = faultHandler;
                }

                public String name() {
                    return "metadata-version-config-validator";
                }

                public void onMetadataUpdate(
                    MetadataDelta delta,
                    MetadataImage newImage
                ) {
                    delta.metadataVersionChanged();
                    MetadataVersion metadataVersion = newImage.features().metadataVersionOrThrow();
                    this.latestVersion = metadataVersion;
                    config.interBrokerProtocolVersion();
                    faultHandler.handleFault("metadata", null);
                    onMetadataVersionChanged(metadataVersion);
                }

                public void onMetadataVersionChanged(MetadataVersion metadataVersion) {
                    config.interBrokerProtocolVersion();
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "core/src/main/scala/kafka/server/KafkaConfig.scala",
            """
            package kafka.server

            import org.apache.kafka.server.common.MetadataVersion

            class KafkaConfig {
              def interBrokerProtocolVersion(): MetadataVersion = new MetadataVersion()
            }
            """.trimIndent(),
        )
    }

    private fun addKafkaSupportStubs() {
        myFixture.addFileToProject(
            "core/src/main/java/org/apache/kafka/image/MetadataDelta.java",
            """
            package org.apache.kafka.image;
            import java.util.Optional;
            public class MetadataDelta {
                public Object featuresDelta() { return null; }
                public Optional<Boolean> metadataVersionChanged() { return Optional.empty(); }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "core/src/main/java/org/apache/kafka/image/MetadataImage.java",
            """
            package org.apache.kafka.image;
            import org.apache.kafka.server.common.MetadataVersion;
            public class MetadataImage {
                public Features features() { return new Features(); }
                public static class Features {
                    public MetadataVersion metadataVersionOrThrow() { return new MetadataVersion(); }
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "core/src/main/java/org/apache/kafka/image/loader/LoaderManifest.java",
            """
            package org.apache.kafka.image.loader;
            public class LoaderManifest {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "core/src/main/java/org/apache/kafka/image/publisher/MetadataPublisher.java",
            """
            package org.apache.kafka.image.publisher;
            import org.apache.kafka.image.MetadataDelta;
            import org.apache.kafka.image.MetadataImage;
            public interface MetadataPublisher {
                String name();
                void onMetadataUpdate(MetadataDelta delta, MetadataImage newImage);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "core/src/main/java/org/apache/kafka/server/common/MetadataVersion.java",
            """
            package org.apache.kafka.server.common;
            public class MetadataVersion {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "core/src/main/java/org/apache/kafka/server/fault/FaultHandler.java",
            """
            package org.apache.kafka.server.fault;
            public interface FaultHandler {
                void handleFault(String message, Throwable error);
            }
            """.trimIndent(),
        )
    }
}
