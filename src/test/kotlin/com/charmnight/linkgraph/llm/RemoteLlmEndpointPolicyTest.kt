package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteLlmEndpointPolicyTest {
    @Test
    fun acceptsHttpsEndpoint() {
        // 用 IP 字面量避免依赖测试环境的 DNS；公网 DNS IP 不属于任何禁止段。
        assertNull(RemoteLlmEndpointPolicy().validationError("https://8.8.8.8/v1"))
    }

    @Test
    fun rejectsHttpEndpointByDefault() {
        assertEquals(
            "请求地址必须使用 https://；http:// 仅允许在调试开关开启时使用。",
            RemoteLlmEndpointPolicy().validationError("http://8.8.8.8/v1"),
        )
    }

    @Test
    fun allowsHttpEndpointWhenDebugFlagEnabled() {
        assertNull(
            RemoteLlmEndpointPolicy(allowInsecureHttp = true).validationError("http://8.8.8.8/v1"),
        )
    }

    @Test
    fun rejectsLocalhost() {
        val error = RemoteLlmEndpointPolicy().validationError("https://localhost/v1")
        assertNotNull(error)
        assertTrue(error.contains("禁止访问内网或元数据服务"), "实际：$error")
    }

    @Test
    fun rejectsLoopbackIp() {
        val error = RemoteLlmEndpointPolicy().validationError("https://127.0.0.1/v1")
        assertNotNull(error)
        assertTrue(error.contains("禁止访问内网或元数据服务"), "实际：$error")
    }

    @Test
    fun rejectsLinkLocalMetadataIp() {
        val error = RemoteLlmEndpointPolicy().validationError("https://169.254.169.254/latest/meta-data/")
        assertNotNull(error)
        assertTrue(error.contains("禁止访问内网或元数据服务"), "实际：$error")
    }

    @Test
    fun rejectsSiteLocalIp10() {
        val error = RemoteLlmEndpointPolicy().validationError("https://10.0.0.1/v1")
        assertNotNull(error)
        assertTrue(error.contains("禁止访问内网或元数据服务"), "实际：$error")
    }

    @Test
    fun rejectsSiteLocalIp192() {
        val error = RemoteLlmEndpointPolicy().validationError("https://192.168.1.1/v1")
        assertNotNull(error)
        assertTrue(error.contains("禁止访问内网或元数据服务"), "实际：$error")
    }

    @Test
    fun rejectsSiteLocalIp172() {
        // 172.16/12 段是 RFC 1918 私有；172.16.0.1 是该段内地址。
        val error = RemoteLlmEndpointPolicy().validationError("https://172.16.0.1/v1")
        assertNotNull(error)
        assertTrue(error.contains("禁止访问内网或元数据服务"), "实际：$error")
    }

    @Test
    fun rejectsAnyLocalIp() {
        val error = RemoteLlmEndpointPolicy().validationError("https://0.0.0.0/v1")
        assertNotNull(error)
        assertTrue(error.contains("禁止访问内网或元数据服务"), "实际：$error")
    }

    @Test
    fun rejectsAlibabaMetadataIp() {
        // 阿里云元数据服务 IP 不在 isSiteLocalAddress 覆盖范围（100.64/10 是 CGNAT），
        // 需通过显式 CLOUD_METADATA_IPS 集合拦截。
        val error = RemoteLlmEndpointPolicy().validationError("https://100.100.100.200/latest/meta-data/")
        assertNotNull(error)
        assertTrue(error.contains("禁止访问内网或元数据服务"), "实际：$error")
    }

    @Test
    fun rejectsMetadataHostname() {
        val error = RemoteLlmEndpointPolicy().validationError("https://metadata.google.internal/computeMetadata/v1/")
        assertNotNull(error)
        assertTrue(error.contains("禁止访问内网或元数据服务"), "实际：$error")
    }

    @Test
    fun rejectsIpv6Loopback() {
        // URI.getHost() 对 IPv6 字面量返回带方括号的字符串（"[::1]"）。
        // policy 需要先剥离方括号才能交给 InetAddress.getByName。
        val error = RemoteLlmEndpointPolicy().validationError("https://[::1]/v1")
        assertNotNull(error)
        assertTrue(error.contains("禁止访问内网或元数据服务"), "实际：$error")
    }

    @Test
    fun rejectsMissingHost() {
        val error = RemoteLlmEndpointPolicy().validationError("https:///v1")
        assertNotNull(error)
        assertTrue(error.contains("缺少 host"), "实际：$error")
    }
}
