package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class AppServerCatalogRequestsTest {
    @Test
    fun `startup plugin catalog excludes remote marketplaces`() {
        val params = AppServerCatalogRequests.installedPluginCatalog("C:\\workspace\\zhixing")

        assertEquals(
            listOf("local", "workspace-directory"),
            (params["marketplaceKinds"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals(
            listOf("C:\\workspace\\zhixing"),
            (params["cwds"] as JsonArray).map { (it as JsonPrimitive).content },
        )
    }
}
