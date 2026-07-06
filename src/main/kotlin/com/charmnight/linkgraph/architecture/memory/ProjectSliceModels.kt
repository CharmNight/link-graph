package com.charmnight.linkgraph.architecture.memory

/**
 * 项目 slice 清单。
 *
 * 描述某次扫描下项目被切分为多少个 slice、每个 slice 的元数据。
 * 通过 [projectLocationHash] 区分不同项目位置，避免索引相互污染。
 */
data class ProjectSliceManifest(
    /** 项目位置哈希；作为索引隔离的依据。 */
    val projectLocationHash: String,
    /** 清单 schema 版本；不匹配时需要重建。 */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** 项目所有 slice 的列表。 */
    val slices: List<ProjectSlice> = emptyList(),
) {
    companion object {
        /** 当前 schema 版本号；升级时递增。 */
        const val CURRENT_SCHEMA_VERSION = 5
    }
}

/**
 * 单个 slice 的元数据。
 *
 * slice 是项目的一个子集（按模块 + sourceSet + 包前缀切分），
 * 让增量索引可以按 slice 粒度失效，而不是全量重建。
 */
data class ProjectSlice(
    /** slice 唯一 ID。 */
    val id: String,
    /** 所属模块名；null 表示根模块或未知。 */
    val moduleName: String?,
    /** 内容根路径（content root）。 */
    val contentRoot: String,
    /** source set（main/test 等）。 */
    val sourceSet: String,
    /** 包前缀；用于细分子集。 */
    val packagePrefix: String?,
    /** slice 种类（源码 / 资源 / 附带 jar）。 */
    val kind: String,
    /** slice 内文件指纹列表。 */
    val files: List<ProjectFileFingerprint> = emptyList(),
)

/**
 * 单个文件的指纹。
 *
 * 用于在增量索引时判断文件是否变化（size/修改时间/SHA-256），
 * 不需要重新读取文件内容即可判断是否需要重建。
 */
data class ProjectFileFingerprint(
    /** 相对路径。 */
    val relativePath: String,
    /** 文件大小（字节）。 */
    val size: Long,
    /** 最后修改时间（毫秒）。 */
    val modifiedAtMillis: Long,
    /** 内容 SHA-256；可空，因为不是所有文件都会算哈希。 */
    val contentSha256: String? = null,
)

/**
 * 待入库的 slice 输入文件。
 *
 * 与 [ProjectFileFingerprint] 类似，但携带额外信息（attachedJarFingerprint），
 * 用于把 jar 文件归属到某个 slice 的输入侧。
 */
data class ProjectSliceInputFile(
    /** 所属模块名。 */
    val moduleName: String?,
    /** 内容根。 */
    val contentRoot: String,
    /** 相对路径。 */
    val relativePath: String,
    /** 文件大小。 */
    val size: Long,
    /** 最后修改时间。 */
    val modifiedAtMillis: Long,
    /** 内容 SHA-256。 */
    val contentSha256: String? = null,
    /** 附带 jar 文件指纹；用于关联到外部依赖。 */
    val attachedJarFingerprint: String? = null,
)

/** slice 种类枚举。 */
enum class ProjectSliceKind {
    /** JVM 源码 slice（Java/Kotlin）。 */
    JVM_SOURCE,

    /** 资源 slice（XML/properties/yaml 等）。 */
    RESOURCE,

    /** 附带 jar slice（外部依赖）。 */
    ATTACHED_JAR,
}
