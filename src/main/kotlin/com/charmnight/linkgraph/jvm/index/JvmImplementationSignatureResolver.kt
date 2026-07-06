package com.charmnight.linkgraph.jvm.index

/** Resolves concrete JVM method implementations for an abstract/interface method symbol. */
interface JvmImplementationSignatureResolver {
    fun implementationSignatures(
        method: JvmMethodSymbol,
        ownerClass: JvmClassSymbol,
    ): List<String>

    fun diagnostic(
        method: JvmMethodSymbol,
        ownerClass: JvmClassSymbol,
    ): String = "psi=resolver:notConfigured"
}

/** No-op implementation for pure index tests and non-IDE callers. */
object NoopJvmImplementationSignatureResolver : JvmImplementationSignatureResolver {
    override fun implementationSignatures(
        method: JvmMethodSymbol,
        ownerClass: JvmClassSymbol,
    ): List<String> = emptyList()
}
