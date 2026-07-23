package me.rerere.rikkahub.service

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.RouteActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ChatGenerationForegroundServiceTest {
    @Test
    fun generationLeaseKeepsForegroundServiceAliveAfterActivityMovesToBackground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controller = GlobalContext.get().get<ChatGenerationForegroundController>()
        val generationId = Uuid.random()
        val conversationId = Uuid.random()
        var activity: Activity? = null

        try {
            activity = instrumentation.startActivitySync(
                Intent(context, RouteActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                controller.acquire(generationId, conversationId)
            }

            assertTrue(
                "foreground service did not start",
                awaitCondition { foregroundServiceState(context) == true },
            )

            instrumentation.runOnMainSync {
                activity.moveTaskToBack(true)
            }

            assertTrue(
                "foreground service stopped when the Activity moved to the background",
                awaitCondition { foregroundServiceState(context) == true },
            )
        } finally {
            controller.release(generationId)
            assertTrue(
                "foreground service did not stop after the final lease was released",
                awaitCondition { foregroundServiceState(context) == null },
            )
            activity?.let { launchedActivity ->
                instrumentation.runOnMainSync { launchedActivity.finish() }
            }
        }
    }

    private fun awaitCondition(
        timeoutMillis: Long = 5_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return condition()
    }

    @Suppress("DEPRECATION")
    private fun foregroundServiceState(context: Context): Boolean? {
        val serviceClassName = ChatGenerationForegroundService::class.java.name
        return context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service.className == serviceClassName }
            ?.foreground
    }
}
