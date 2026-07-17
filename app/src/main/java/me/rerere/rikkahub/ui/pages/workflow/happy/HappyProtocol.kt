package me.rerere.rikkahub.ui.pages.workflow.happy

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object HappyProtocol {
    const val SERVER_URL = "https://api.cluster-fluster.com"
    const val CLIENT_PLATFORM = "android"
    const val PROTOCOL_SNAPSHOT = "2026-07-17"
    const val UPSTREAM_COMMIT = "3f161de70541b1cedaf0b7547ed70889d8dae22d"

    fun clientId(versionName: String): String = "$CLIENT_PLATFORM/$versionName"

    /**
     * 中继必须是一个无路径的 HTTPS origin。Socket.IO 与 HTTP API 都使用根路径协议，
     * 因此这里拒绝 query、fragment、账号信息和反向代理子路径，避免保存一个看似有效、
     * 实际只有部分请求能工作的地址。
     */
    fun normalizeServerUrl(raw: String): String {
        val url = raw.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("中继地址格式不正确")
        val isLoopback = url.host == "localhost" || url.host == "127.0.0.1" || url.host == "::1"
        require(url.scheme == "https" || isLoopback) { "公网中继地址必须使用 HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "中继地址不能包含账号信息" }
        require(url.query == null && url.fragment == null) { "中继地址不能包含查询参数或片段" }
        require(url.encodedPath == "/") { "中继地址不能包含子路径" }
        return url.toString().trimEnd('/')
    }
}
