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
    const val developmentRepositoryUrl = "https://github.com/rgywd/zhixing"
    const val distributionRepositoryUrl = "https://github.com/rgywd/zhixing-releases"
    const val sourceUrl = "$distributionRepositoryUrl/releases"
    const val issueTrackerUrl = "$developmentRepositoryUrl/issues"
    const val licenseUrl = "$distributionRepositoryUrl/blob/main/LICENSE"
    const val thirdPartyNoticesUrl = "$distributionRepositoryUrl/blob/main/THIRD_PARTY_NOTICES.md"
    const val thirdPartyTelemetryEnabled = false
    val distributionChannel: String = BuildConfig.DISTRIBUTION_CHANNEL
    val updateFeedUrl: String = BuildConfig.UPDATE_FEED_URL
    val stagingTestDriverEnabled: Boolean = BuildConfig.STAGING_TEST_DRIVER_ENABLED
}
