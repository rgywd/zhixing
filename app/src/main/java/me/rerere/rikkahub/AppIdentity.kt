package me.rerere.rikkahub

/**
 * Product-owned identity and hosted-service policy.
 *
 * Keep these values independent from third-party deployments. The package
 * namespace remains unchanged for now to keep the first rebase small;
 * the install identity is controlled by applicationId in app/build.gradle.kts.
 */
object AppIdentity {
    const val productName = "Zhixing"
    const val userAgentProduct = "Zhixing-Android"
    const val repositoryOwner = "rgywd"
    const val repositoryName = "zhixing"
    const val sourceUrl = "https://github.com/rgywd/zhixing"
    const val issueTrackerUrl = "$sourceUrl/issues"
    const val licenseUrl = "https://github.com/rgywd/zhixing/blob/main/LICENSE"
    const val thirdPartyNoticesUrl = "https://github.com/rgywd/zhixing/blob/main/THIRD_PARTY_NOTICES.md"
    const val thirdPartyTelemetryEnabled = false
    const val updateFeedUrl = "https://github.com/rgywd/zhixing/releases/latest/download/latest.json"
}
