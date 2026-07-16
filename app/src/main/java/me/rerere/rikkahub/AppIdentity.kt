package me.rerere.rikkahub

/**
 * Product-owned identity and hosted-service policy.
 *
 * Keep these values independent from the upstream RikkaHub deployment. The
 * package namespace remains unchanged for now to keep the first rebase small;
 * the install identity is controlled by applicationId in app/build.gradle.kts.
 */
object AppIdentity {
    const val productName = "Zhixing"
    const val userAgentProduct = "Zhixing-Android"
    const val sourceUrl = "https://visiongrid.top/rgywd133/zhixing-assistant"
    const val upstreamSourceUrl = "https://github.com/rikkahub/rikkahub"
    const val upstreamLicenseUrl = "https://github.com/rikkahub/rikkahub/blob/master/LICENSE"
    const val thirdPartyTelemetryEnabled = false
    val updateFeedUrl: String? = null
}
