package com.charmnight.linkgraph.architecture.memory

import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmFieldTypeRole
import com.charmnight.linkgraph.jvm.index.JvmResourceKind
import com.charmnight.linkgraph.jvm.index.JvmStereotype
import com.charmnight.linkgraph.jvm.index.effectiveTypeReferences
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.source.SourceOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchitectureIndexFragmentMergerTest {
    @Test
    fun mergesDtoFragmentsIntoJvmIndexesWithoutRuntimeObjects() {
        val merged = ArchitectureIndexFragmentMerger().merge(
            listOf(
                ArchitectureIndexSliceFragment(
                    sliceId = "slice:orders",
                    symbols = listOf(
                        SymbolSliceFragment(
                            id = "class:OrderService",
                            qualifiedName = "com.example.orders.OrderService",
                            simpleName = "OrderService",
                            kind = "CLASS",
                            sourcePath = "src/main/java/com/example/orders/OrderService.java",
                        ),
                    ),
                    relations = listOf(
                        RelationSliceFragment(
                            id = "rel:orders",
                            kind = "USES_TYPE",
                            fromSymbolId = "class:OrderService",
                            toSymbolId = "resource:application.yml",
                        ),
                    ),
                    resources = listOf(
                        ResourceSliceFragment(
                            id = "resource:application.yml",
                            path = "src/main/resources/application.yml",
                            kind = "YAML",
                        ),
                    ),
                ),
            ),
        )

        val symbol = assertNotNull(merged.symbolIndex.findClass("com.example.orders.OrderService"))
        assertEquals(JvmClassKind.CLASS, symbol.kind)
        assertEquals("src/main/java/com/example/orders/OrderService.java", symbol.source?.displayPath)
        assertEquals(JvmResourceKind.YAML, merged.symbolIndex.findResource("src/main/resources/application.yml")?.kind)
        assertEquals(JvmRelationKind.USES_TYPE, merged.relationIndex.relations.single().kind)
    }

    @Test
    fun restoresJvmSymbolFieldsRequiredByRelationResolversAndProjectors() {
        val merged = ArchitectureIndexFragmentMerger().merge(
            listOf(
                ArchitectureIndexSliceFragment(
                    sliceId = "slice:orders",
                    symbols = listOf(
                        SymbolSliceFragment(
                            id = "class:OrderService",
                            qualifiedName = "com.example.orders.OrderService",
                            simpleName = "OrderService",
                            kind = "CLASS",
                            sourcePath = "src/main/java/com/example/orders/OrderService.java",
                            sourceVirtualFileUrl = "file:///repo/src/main/java/com/example/orders/OrderService.java",
                            sourceStartLine = 10,
                            sourceEndLine = 40,
                            sourceDecompiled = false,
                            moduleName = "orders",
                            packageName = "com.example.orders",
                            classKind = "CLASS",
                            stereotype = "SERVICE",
                            external = false,
                            library = false,
                            jdk = false,
                            testSource = true,
                            abstract = true,
                            superClassName = "com.example.orders.BaseService",
                            interfaceNames = listOf("com.example.orders.OrderPort"),
                            docComment = "Handles orders.",
                            origin = "PROJECT_SOURCE",
                        ),
                        SymbolSliceFragment(
                            id = "field:OrderService.clients",
                            qualifiedName = "com.example.orders.OrderService.clients",
                            simpleName = "clients",
                            kind = "FIELD",
                            sourcePath = "src/main/java/com/example/orders/OrderService.java",
                            ownerClassName = "com.example.orders.OrderService",
                            typeName = "java.util.List",
                            typeReferences = listOf(
                                FieldTypeReferenceSliceFragment("java.util.List", "DIRECT_VALUE"),
                                FieldTypeReferenceSliceFragment("com.example.orders.Client", "COLLECTION_ELEMENT"),
                            ),
                            origin = "PROJECT_SOURCE",
                        ),
                    ),
                ),
            ),
        )

        val classSymbol = assertNotNull(merged.symbolIndex.findClass("com.example.orders.OrderService"))
        assertEquals(JvmStereotype.SERVICE, classSymbol.stereotype)
        assertFalse(classSymbol.external)
        assertFalse(classSymbol.library)
        assertFalse(classSymbol.jdk)
        assertTrue(classSymbol.testSource)
        assertTrue(classSymbol.abstract)
        assertEquals("com.example.orders.BaseService", classSymbol.superClassName)
        assertEquals(listOf("com.example.orders.OrderPort"), classSymbol.interfaceNames)
        assertEquals("Handles orders.", classSymbol.docComment)
        assertEquals("file:///repo/src/main/java/com/example/orders/OrderService.java", classSymbol.source?.virtualFileUrl)
        assertEquals(10, classSymbol.source?.startLine)
        assertEquals(40, classSymbol.source?.endLine)
        assertFalse(classSymbol.source?.decompiled ?: true)

        val field = assertNotNull(merged.symbolIndex.findField("com.example.orders.OrderService.clients"))
        assertEquals(
            listOf("java.util.List", "com.example.orders.Client"),
            field.typeReferences.map { it.typeName },
        )
        assertEquals(
            listOf(JvmFieldTypeRole.DIRECT_VALUE, JvmFieldTypeRole.COLLECTION_ELEMENT),
            field.typeReferences.map { it.role },
        )
    }

    @Test
    fun restoresServiceProviderIndexFromFragments() {
        val merged = ArchitectureIndexFragmentMerger().merge(
            listOf(
                ArchitectureIndexSliceFragment(
                    sliceId = "slice:spi",
                    resources = listOf(
                        ResourceSliceFragment(
                            id = "resource:spi",
                            path = "src/main/resources/META-INF/services/com.example.Plugin",
                            kind = "SPI_SERVICE_FILE",
                            sourceVirtualFileUrl = "file:///repo/src/main/resources/META-INF/services/com.example.Plugin",
                            origin = "PROJECT_SOURCE",
                        ),
                    ),
                    serviceProviders = listOf(
                        ServiceProviderSliceFragment(
                            serviceInterfaceName = "com.example.Plugin",
                            providerClassNames = listOf("com.example.DefaultPlugin"),
                            resourceId = "resource:spi",
                            resourcePath = "src/main/resources/META-INF/services/com.example.Plugin",
                            resourceKind = "SPI_SERVICE_FILE",
                            origin = "PROJECT_SOURCE",
                        ),
                    ),
                ),
            ),
        )

        val providerFile = merged.symbolIndex.serviceProviderIndex.providersFor("com.example.Plugin").single()
        assertEquals(listOf("com.example.DefaultPlugin"), providerFile.providerClassNames)
        assertEquals("src/main/resources/META-INF/services/com.example.Plugin", providerFile.resource.path)
        assertEquals(SourceOrigin.PROJECT_SOURCE, providerFile.origin)
    }

    @Test
    fun skipsRelationsWithInvalidKind() {
        val merged = ArchitectureIndexFragmentMerger().merge(
            listOf(
                ArchitectureIndexSliceFragment(
                    sliceId = "slice:bad-relation",
                    relations = listOf(
                        RelationSliceFragment(
                            id = "rel:bad",
                            kind = "BOGUS_KIND",
                            fromSymbolId = "class:From",
                            toSymbolId = "class:To",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(merged.relationIndex.relations.isEmpty())
    }

    @Test
    fun ignoresBlankSymbolSourcePath() {
        val merged = ArchitectureIndexFragmentMerger().merge(
            listOf(
                ArchitectureIndexSliceFragment(
                    sliceId = "slice:blank-source",
                    symbols = listOf(
                        SymbolSliceFragment(
                            id = "class:BlankSource",
                            qualifiedName = "com.example.BlankSource",
                            simpleName = "BlankSource",
                            kind = "CLASS",
                            sourcePath = "   ",
                        ),
                    ),
                ),
            ),
        )

        assertNull(assertNotNull(merged.symbolIndex.findClass("com.example.BlankSource")).source)
    }

    @Test
    fun mergesDuplicateServiceProviderFragmentsForSameResource() {
        val resourcePath = "src/main/resources/META-INF/services/com.example.Plugin"
        val merged = ArchitectureIndexFragmentMerger().merge(
            listOf(
                ArchitectureIndexSliceFragment(
                    sliceId = "slice:spi",
                    serviceProviders = listOf(
                        ServiceProviderSliceFragment(
                            serviceInterfaceName = "com.example.Plugin",
                            providerClassNames = listOf("com.example.A"),
                            resourceId = "resource:spi",
                            resourcePath = resourcePath,
                            resourceKind = "SPI_SERVICE_FILE",
                            origin = "PROJECT_SOURCE",
                        ),
                        ServiceProviderSliceFragment(
                            serviceInterfaceName = "com.example.Plugin",
                            providerClassNames = listOf("com.example.B", "com.example.A"),
                            resourceId = "resource:spi",
                            resourcePath = resourcePath,
                            resourceKind = "SPI_SERVICE_FILE",
                            origin = "PROJECT_SOURCE",
                        ),
                    ),
                ),
            ),
        )

        val providerFile = merged.symbolIndex.serviceProviderIndex.providersFor("com.example.Plugin").single()
        assertEquals(listOf("com.example.A", "com.example.B"), providerFile.providerClassNames)
    }

    @Test
    fun skipsInvalidFieldTypeReferenceRoleWithoutFallingBackToTypeName() {
        val merged = ArchitectureIndexFragmentMerger().merge(
            listOf(
                ArchitectureIndexSliceFragment(
                    sliceId = "slice:bad-field-role",
                    symbols = listOf(
                        SymbolSliceFragment(
                            id = "field:OrderService.client",
                            qualifiedName = "com.example.orders.OrderService.client",
                            simpleName = "client",
                            kind = "FIELD",
                            ownerClassName = "com.example.orders.OrderService",
                            typeName = "com.example.orders.Client",
                            typeReferences = listOf(
                                FieldTypeReferenceSliceFragment("com.example.orders.Client", "BOGUS_ROLE"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val field = assertNotNull(merged.symbolIndex.findField("com.example.orders.OrderService.client"))
        assertTrue(field.effectiveTypeReferences().isEmpty())
    }

    @Test
    fun skipsResourcesWithBlankPath() {
        val merged = ArchitectureIndexFragmentMerger().merge(
            listOf(
                ArchitectureIndexSliceFragment(
                    sliceId = "slice:blank-resource",
                    resources = listOf(
                        ResourceSliceFragment(
                            id = "resource:blank",
                            path = "   ",
                            kind = "YAML",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(merged.symbolIndex.resourcesByPath.isEmpty())
    }

    @Test
    fun skipsClassWithInvalidNonNullClassKind() {
        val merged = ArchitectureIndexFragmentMerger().merge(
            listOf(
                ArchitectureIndexSliceFragment(
                    sliceId = "slice:bad-class-kind",
                    symbols = listOf(
                        SymbolSliceFragment(
                            id = "class:BadKind",
                            qualifiedName = "com.example.BadKind",
                            simpleName = "BadKind",
                            kind = "CLASS",
                            classKind = "BOGUS_KIND",
                        ),
                    ),
                ),
            ),
        )

        assertNull(merged.symbolIndex.findClass("com.example.BadKind"))
    }

    @Test
    fun skipsResourceWithInvalidKind() {
        val merged = ArchitectureIndexFragmentMerger().merge(
            listOf(
                ArchitectureIndexSliceFragment(
                    sliceId = "slice:bad-resource-kind",
                    resources = listOf(
                        ResourceSliceFragment(
                            id = "resource:bad-kind",
                            path = "src/main/resources/application.yml",
                            kind = "BOGUS_KIND",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(merged.symbolIndex.resourcesByPath.isEmpty())
    }

    @Test
    fun fallsBackToDirectTypeNameForSparseFieldFragment() {
        val merged = ArchitectureIndexFragmentMerger().merge(
            listOf(
                ArchitectureIndexSliceFragment(
                    sliceId = "slice:sparse-field",
                    symbols = listOf(
                        SymbolSliceFragment(
                            id = "field:OrderService.client",
                            qualifiedName = "com.example.orders.OrderService.client",
                            simpleName = "client",
                            kind = "FIELD",
                            ownerClassName = "com.example.orders.OrderService",
                            typeName = "com.example.orders.Client",
                            typeReferences = emptyList(),
                        ),
                    ),
                ),
            ),
        )

        val reference = assertNotNull(merged.symbolIndex.findField("com.example.orders.OrderService.client"))
            .effectiveTypeReferences()
            .single()
        assertEquals("com.example.orders.Client", reference.typeName)
        assertEquals(JvmFieldTypeRole.DIRECT_VALUE, reference.role)
    }

    @Test
    fun trimsAndFiltersServiceProviderFragments() {
        val resourcePath = "src/main/resources/META-INF/services/com.example.Plugin"
        val merged = ArchitectureIndexFragmentMerger().merge(
            listOf(
                ArchitectureIndexSliceFragment(
                    sliceId = "slice:trimmed-spi",
                    serviceProviders = listOf(
                        ServiceProviderSliceFragment(
                            serviceInterfaceName = " com.example.Plugin ",
                            providerClassNames = listOf(" com.example.A ", "", "  ", "com.example.B"),
                            resourceId = "resource:spi",
                            resourcePath = resourcePath,
                            resourceKind = "SPI_SERVICE_FILE",
                            origin = "PROJECT_SOURCE",
                        ),
                        ServiceProviderSliceFragment(
                            serviceInterfaceName = "   ",
                            providerClassNames = listOf("com.example.EmptyInterface"),
                            resourceId = "resource:empty-interface",
                            resourcePath = "src/main/resources/META-INF/services/blank",
                            resourceKind = "SPI_SERVICE_FILE",
                            origin = "PROJECT_SOURCE",
                        ),
                        ServiceProviderSliceFragment(
                            serviceInterfaceName = "com.example.EmptyProviders",
                            providerClassNames = listOf("", "  "),
                            resourceId = "resource:empty-providers",
                            resourcePath = "src/main/resources/META-INF/services/com.example.EmptyProviders",
                            resourceKind = "SPI_SERVICE_FILE",
                            origin = "PROJECT_SOURCE",
                        ),
                    ),
                ),
            ),
        )

        val providerFile = merged.symbolIndex.serviceProviderIndex.providersFor("com.example.Plugin").single()
        assertEquals(listOf("com.example.A", "com.example.B"), providerFile.providerClassNames)
        assertTrue(merged.symbolIndex.serviceProviderIndex.providersFor("").isEmpty())
        assertTrue(merged.symbolIndex.serviceProviderIndex.providersFor("com.example.EmptyProviders").isEmpty())
    }
}
