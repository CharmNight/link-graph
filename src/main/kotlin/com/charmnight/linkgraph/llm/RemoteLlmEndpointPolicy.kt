package com.charmnight.linkgraph.llm

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * 统一约束远程 LLM endpoint 的协议与目标地址策略。
 *
 * 默认只允许 HTTPS；HTTP 只能通过显式调试开关放行。
 * 同时拒绝指向内网、本地回环、链路本地或云元数据服务的地址，
 * 防止用户在不知情的情况下把 API key 发送到内部网络或元数据服务。
 */
class RemoteLlmEndpointPolicy(
    /** 是否允许明文 HTTP；默认从调试开关读取。 */
    private val allowInsecureHttp: Boolean = debugAllowInsecureHttp(),
) {
    /**
     * 校验给定 endpoint 字符串。
     *
     * @param endpoint 待校验的 endpoint URL
     * @return 错误说明；返回 null 表示通过
     */
    fun validationError(endpoint: String): String? {
        // 解析 URL 失败直接给出格式错误
        val uri = runCatching { URI.create(endpoint.trim()) }.getOrNull()
            ?: return "请求地址格式不正确，请填写以 https:// 开头的地址。"
        // 先校验协议：HTTPS 默认通过，HTTP 仅在调试开关开启时放行，其他 scheme 一律拒绝。
        when (uri.scheme?.lowercase()) {
            "https" -> Unit
            "http" -> if (!allowInsecureHttp) {
                return "请求地址必须使用 https://；http:// 仅允许在调试开关开启时使用。"
            }
            else -> return "请求地址格式不正确，请填写以 https:// 开头的地址。"
        }
        // 再校验 host：拦截 localhost、内网、回环、链路本地与云元数据地址。
        // 注意：URI.getHost() 对 IPv6 字面量返回带方括号的字符串（如 "[::1]"），需要先剥离。
        val rawHost = uri.host ?: return "请求地址缺少 host。"
        val host = rawHost.removePrefix("[").removeSuffix("]")
        val reason = blockReasonFor(host) ?: return null
        return "禁止访问内网或元数据服务地址（host=$rawHost，原因=$reason）。"
    }

    /**
     * 强校验：不通过直接抛 [IllegalStateException]。
     *
     * 给 HTTP 客户端 chokepoint（如 [LlmGatewayClient]）使用：
     * 把 URL 的协议 + host 解析后的 IP 一并通过本策略，命中即 fail-fast，
     * 避免不安全地址流入实际网络请求。
     */
    fun requireValidEndpoint(endpoint: String) {
        validationError(endpoint)?.let { error(it) }
    }

    /**
     * 判断 host 是否属于禁止访问的内网或元数据服务。
     *
     * 校验顺序：先做 O(1) 的字符串黑名单匹配（命中即返回，无需 DNS）；
     * 再走 InetAddress 解析判断是否属于 loopback / site-local / link-local / any-local
     * 或已知的云元数据 IP。
     *
     * DNS 失败时返回 null（不阻塞）——IDE 插件的 endpoint 由用户自己配置，
     * 真到 HTTP 请求时客户端会给出更清晰的错误；离线保存设置场景不应被卡住。
     *
     * TOCTOU：本类校验通过后，[LlmGatewayClient] 真正 send 时 Java HttpClient 会
     * 再做一次自己的 DNS 解析，理论上存在 DNS rebinding（第一次解析到公网 IP 通过校验，
     * 第二次解析到 169.254.169.254 等内网 IP）的残余风险。
     * - 缓解 1：[LlmGatewayClient] 入口（[requireValidEndpoint]）将校验推迟到 send 之前最后一刻，
     *   把窗口从「settings 保存到 send 的数秒/数分钟」压到「校验到 Java DNS 的数微秒」。
     * - 缓解 2：黑名单字符串命中是 O(1) 字符串比较，没有 DNS rebinding 空间。
     * - 残余：要彻底覆盖需要劫持 Java HttpClient 的 DNS 解析或自实现 socket factory，
     *   代价高且对 IDE 插件场景收益边际。本策略保持当前形态，残余窗口可接受。
     *
     * 已知未覆盖的绕过路径（仅在文档登记，不做拦截——本策略是 chokepoint，不是 air-gap）：
     * - DNS rebinding 服务（nip.io / sslip.io）：`127.0.0.1.nip.io` 解析到 127.0.0.1，
     *   黑名单字面量匹配命中不到；只能靠 send-时刻的 IP 校验，但那时 Java 已做 DNS。
     * - FQDN 尾点（`localhost.`）：DNS 视角与 `localhost` 等价，但与本类的字面量集合不相等。
     * - IPv6 等价写法：`::1` / `[::1]` / `0:0:0:0:0:0:0:1` 在 erase 后字符串不同，
     *   靠 InetAddress 解析归一化兜底，但不靠字面量。
     */
    private fun blockReasonFor(host: String): String? {
        if (host.lowercase() in BLOCKED_HOSTNAMES) {
            return "blocklisted hostname"
        }
        // IP 字面量直接解析（无 DNS）；hostname 触发 DNS 解析。
        // InetAddress.getByName 只会抛 UnknownHostException（或运行时 SecurityException），
        // 不抛 PrivilegedActionException——后者只在 Subject.doAs 包装层出现，本调用栈不涉及。
        val ip = try {
            InetAddress.getByName(host)
        } catch (_: UnknownHostException) {
            return null
        }
        return when {
            ip.isAnyLocalAddress -> "any-local address"
            ip.isLoopbackAddress -> "loopback address"
            ip.isLinkLocalAddress -> "link-local address"
            ip.isSiteLocalAddress -> "site-local address"
            ip in CLOUD_METADATA_IPS -> "cloud metadata endpoint"
            else -> null
        }
    }

    companion object {
        /**
         * 从系统属性或环境变量判断是否允许 HTTP。
         * 同时支持两种来源是为了适配不同部署环境（IDE 启动参数 vs 容器 env）。
         */
        private fun debugAllowInsecureHttp(): Boolean {
            val property = System.getProperty("linkgraph.allowInsecureHttp")
            if (property.equals("true", ignoreCase = true)) {
                return true
            }
            return System.getenv("LINKGRAPH_ALLOW_INSECURE_HTTP")?.equals("true", ignoreCase = true) == true
        }

        /**
         * 已知的元数据服务主机名；命中即拒绝，无需走 DNS。
         *
         * - localhost：开发机本机
         * - 169.254.169.254：AWS/GCP/Azure/Oracle/Tencent 共用的 link-local 元数据 IP
         * - metadata.google.internal：GCP 元数据服务 DNS 名
         * - metadata.tencentyun.com：腾讯云元数据服务 DNS 名
         * - 100.100.100.200：阿里云元数据服务 IP（不在 RFC 1918 段，单独列入）
         * - metadata：兜底简写
         */
        private val BLOCKED_HOSTNAMES: Set<String> = setOf(
            "localhost",
            "metadata",
            "metadata.google.internal",
            "metadata.tencentyun.com",
            "169.254.169.254",
            "100.100.100.200",
        )

        /**
         * 已知的云元数据服务 IP；用于兜底 isLinkLocalAddress / isSiteLocalAddress 未覆盖的场景。
         *
         * - 169.254.169.254：AWS/GCP/Azure/Oracle/Tencent 共用
         * - fd00:ec2::254：AWS IPv6 元数据
         * - 100.100.100.200：阿里云元数据（CGNAT 段，isSiteLocalAddress 不覆盖）
         *
         * InetAddress.getByName 对 IP 字面量不触发 DNS，可以在 companion object 初始化时直接构造。
         */
        private val CLOUD_METADATA_IPS: Set<InetAddress> = setOf(
            InetAddress.getByName("169.254.169.254"),
            InetAddress.getByName("fd00:ec2::254"),
            InetAddress.getByName("100.100.100.200"),
        )
    }
}
