package me.rerere.rikkahub.ui.pages.workflow.happy

object HappyProtocol {
    const val SERVER_URL = "https://api.cluster-fluster.com"
    const val CLIENT_PLATFORM = "android"
    const val PROTOCOL_SNAPSHOT = "2026-07-17"
    const val UPSTREAM_COMMIT = "3f161de70541b1cedaf0b7547ed70889d8dae22d"

    fun clientId(versionName: String): String = "$CLIENT_PLATFORM/$versionName"
}
