package me.rerere.rikkahub.data.location

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import me.rerere.rikkahub.data.ai.tools.local.LocationTravelErrorCode
import me.rerere.rikkahub.data.ai.tools.local.LocationTravelException
import me.rerere.rikkahub.data.ai.tools.local.NavigationLauncher
import me.rerere.rikkahub.data.ai.tools.local.NavigationRequest
import me.rerere.rikkahub.data.ai.tools.local.NavigationTarget

private const val AMAP_PACKAGE = "com.autonavi.minimap"

internal class AndroidAmapNavigationLauncher(context: Context) : NavigationLauncher {
    private val appContext = context.applicationContext

    override fun open(request: NavigationRequest): NavigationTarget {
        val appIntent = navigationIntent(buildAmapAppNavigationUri(request)).apply {
            setPackage(AMAP_PACKAGE)
        }
        if (appContext.packageManager.resolveActivity(appIntent, 0) != null) {
            try {
                appContext.startActivity(appIntent)
                return NavigationTarget.AMAP_APP
            } catch (_: ActivityNotFoundException) {
                // Fall through to the HTTPS route.
            } catch (_: SecurityException) {
                // Fall through to the HTTPS route.
            }
        }

        val webIntent = navigationIntent(buildAmapWebNavigationUri(request))
        if (appContext.packageManager.resolveActivity(webIntent, 0) == null) {
            throw LocationTravelException(LocationTravelErrorCode.NO_HANDLER)
        }
        try {
            appContext.startActivity(webIntent)
        } catch (error: ActivityNotFoundException) {
            throw LocationTravelException(LocationTravelErrorCode.NO_HANDLER, cause = error)
        } catch (error: SecurityException) {
            throw LocationTravelException(LocationTravelErrorCode.NO_HANDLER, cause = error)
        }
        return NavigationTarget.AMAP_WEB
    }

    private fun navigationIntent(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
