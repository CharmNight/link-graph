package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTargetKind
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationSource
import com.charmnight.linkgraph.source.SourceOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

class InvocationExpansionTargetResolverTest {
    private val resolver = InvocationExpansionTargetResolver()

    @Test
    fun resolvesPlainProjectMethod() {
        val service = classSymbol("com.example.Service")
        val method = methodSymbol(service, "run")
        val target = resolver.resolve(method.signature, index(classes = listOf(service), methods = listOf(method)))

        assertEquals(InvocationExpansionTargetKind.PROJECT_SOURCE, target.kind)
        assertEquals(method.signature, target.signature)
    }

    @Test
    fun resolvesSingleInterfaceImplementation() {
        val api = classSymbol("com.example.Api", kind = JvmClassKind.INTERFACE, abstract = true)
        val impl = classSymbol("com.example.ApiImpl")
        val apiMethod = methodSymbol(api, "run", abstract = true)
        val implMethod = methodSymbol(impl, "run")
        val target = resolver.resolve(
            apiMethod.signature,
            index(
                classes = listOf(api, impl),
                methods = listOf(apiMethod, implMethod),
                relations = listOf(relation(JvmRelationKind.IMPLEMENTS, impl, api)),
            ),
        )

        assertEquals(InvocationExpansionTargetKind.PROJECT_SOURCE, target.kind)
        assertEquals(implMethod.signature, target.signature)
    }

    @Test
    fun reportsMultipleImplementations() {
        val api = classSymbol("com.example.Api", kind = JvmClassKind.INTERFACE, abstract = true)
        val a = classSymbol("com.example.AApi")
        val b = classSymbol("com.example.BApi")
        val apiMethod = methodSymbol(api, "run", abstract = true)
        val aMethod = methodSymbol(a, "run")
        val bMethod = methodSymbol(b, "run")
        val target = resolver.resolve(
            apiMethod.signature,
            index(
                classes = listOf(api, a, b),
                methods = listOf(apiMethod, aMethod, bMethod),
                relations = listOf(
                    relation(JvmRelationKind.IMPLEMENTS, a, api),
                    relation(JvmRelationKind.IMPLEMENTS, b, api),
                ),
            ),
        )

        assertEquals(InvocationExpansionTargetKind.MULTIPLE_IMPLEMENTATIONS, target.kind)
        assertEquals(listOf(aMethod.signature, bMethod.signature).sorted(), target.candidateSignatures)
    }

    @Test
    fun reportsNoImplementation() {
        val api = classSymbol("com.example.Api", kind = JvmClassKind.INTERFACE, abstract = true)
        val apiMethod = methodSymbol(api, "run", abstract = true)
        val target = resolver.resolve(apiMethod.signature, index(classes = listOf(api), methods = listOf(apiMethod)))

        assertEquals(InvocationExpansionTargetKind.NO_IMPLEMENTATION, target.kind)
    }

    @Test
    fun classifiesJdkAndExternalByOrigin() {
        val jdk = classSymbol("java.lang.String", origin = SourceOrigin.JDK_CLASS, jdk = true)
        val library = classSymbol("com.fasterxml.jackson.databind.ObjectMapper", origin = SourceOrigin.LIBRARY_CLASS_JAR, library = true)

        assertEquals(
            InvocationExpansionTargetKind.EXTERNAL_JDK,
            resolver.resolve("java.lang.String.trim():java.lang.String", index(classes = listOf(jdk))).kind,
        )
        assertEquals(
            InvocationExpansionTargetKind.EXTERNAL_LIBRARY,
            resolver.resolve(
                "com.fasterxml.jackson.databind.ObjectMapper.writeValueAsString(java.lang.Object):java.lang.String",
                index(classes = listOf(library)),
            ).kind,
        )
    }

    @Test
    fun reportsNotFoundWhenIndexHasNoCandidate() {
        assertEquals(
            InvocationExpansionTargetKind.NOT_FOUND,
            resolver.resolve("com.example.Missing.run():void", index()).kind,
        )
    }

    private fun index(
        classes: List<JvmClassSymbol> = emptyList(),
        methods: List<JvmMethodSymbol> = emptyList(),
        relations: List<JvmRelation> = emptyList(),
    ): ArchitectureGraphIndex =
        ArchitectureGraphIndex.from(
            JvmSymbolIndex(
                classesByQualifiedName = classes.associateBy(JvmClassSymbol::qualifiedName),
                methodsBySignature = methods.associateBy(JvmMethodSymbol::signature),
            ),
            JvmRelationIndex(relations),
        )

    private fun classSymbol(
        qualifiedName: String,
        kind: JvmClassKind = JvmClassKind.CLASS,
        origin: SourceOrigin = SourceOrigin.PROJECT_SOURCE,
        library: Boolean = false,
        jdk: Boolean = false,
        abstract: Boolean = false,
    ): JvmClassSymbol =
        JvmClassSymbol(
            id = stableJvmId("class", qualifiedName),
            qualifiedName = qualifiedName,
            simpleName = qualifiedName.substringAfterLast('.'),
            packageName = qualifiedName.substringBeforeLast('.', missingDelimiterValue = ""),
            moduleName = null,
            kind = kind,
            external = origin != SourceOrigin.PROJECT_SOURCE,
            library = library,
            jdk = jdk,
            abstract = abstract,
            source = JvmSourceRef(
                displayPath = qualifiedName.replace('.', '/') + ".java",
                virtualFileUrl = null,
                startLine = 1,
                endLine = 5,
                decompiled = false,
            ),
            origin = origin,
        )

    private fun methodSymbol(
        owner: JvmClassSymbol,
        name: String,
        abstract: Boolean = false,
    ): JvmMethodSymbol {
        val signature = "${owner.qualifiedName}.$name():void"
        return JvmMethodSymbol(
            id = stableJvmId("method", signature),
            qualifiedName = signature,
            simpleName = name,
            ownerClassName = owner.qualifiedName,
            signature = signature,
            parameterTypes = emptyList(),
            returnType = "void",
            abstract = abstract,
            source = owner.source,
            origin = owner.origin,
        )
    }

    private fun relation(
        kind: JvmRelationKind,
        from: JvmClassSymbol,
        to: JvmClassSymbol,
    ): JvmRelation =
        JvmRelation(
            id = "${kind.name}:${from.id}->${to.id}",
            kind = kind,
            fromSymbolId = from.id,
            toSymbolId = to.id,
            confidence = JvmRelationConfidence.PROVEN,
            source = JvmRelationSource.PSI,
        )
}
