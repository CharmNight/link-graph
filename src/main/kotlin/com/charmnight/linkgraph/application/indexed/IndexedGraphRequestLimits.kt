package com.charmnight.linkgraph.application.indexed

import com.charmnight.linkgraph.projection.GraphProjectionLimits

/**
 * 索引图请求可接受的资源预算上限。
 *
 * 这些限制同时用于 bridge 边界和应用层工厂，避免绕过 JSON 解析后构造超大请求。
 */
object IndexedGraphRequestLimits {
    const val MAX_VIEWPORT_NODES: Int = GraphProjectionLimits.MAX_VISIBLE_NODES
    const val MAX_VIEWPORT_EDGES: Int = GraphProjectionLimits.MAX_VISIBLE_EDGES
    const val MAX_CLASS_NEIGHBORHOOD: Int = 200
    const val MAX_CLASS_MEMBERS: Int = 100
    const val MAX_REVIEW_BUCKET: Int = 500

    fun clampViewportNodes(value: Int?): Int? = value?.coerceIn(1, MAX_VIEWPORT_NODES)

    fun clampViewportEdges(value: Int?): Int? = value?.coerceIn(0, MAX_VIEWPORT_EDGES)

    fun clampClassNeighborhood(value: Int): Int = value.coerceIn(1, MAX_CLASS_NEIGHBORHOOD)

    fun clampClassMembers(value: Int): Int = value.coerceIn(0, MAX_CLASS_MEMBERS)

    fun clampReviewBucket(value: Int): Int = value.coerceIn(0, MAX_REVIEW_BUCKET)
}
