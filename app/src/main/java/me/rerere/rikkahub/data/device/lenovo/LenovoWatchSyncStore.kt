package me.rerere.rikkahub.data.device.lenovo

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Device-private cache for the latest published dashboard values and incremental-sync cursor. */
internal class LenovoWatchSyncStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isRememberedDevice(): Boolean =
        preferences.getBoolean(KEY_REMEMBERED_DEVICE, false) || preferences.contains(KEY_LAST_SYNC)

    fun rememberDevice() {
        preferences.edit().putBoolean(KEY_REMEMBERED_DEVICE, true).apply()
    }

    fun lastSuccessfulSync(): LocalDateTime? {
        val epochMillis = preferences.getLong(KEY_LAST_SYNC, NO_TIMESTAMP)
        if (epochMillis == NO_TIMESTAMP) return null
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
    }

    fun lastSuccessfulSyncCompletedAt(): Instant? {
        val epochMillis = preferences.getLong(
            KEY_LAST_SYNC_COMPLETED_AT,
            preferences.getLong(KEY_LAST_SYNC, NO_TIMESTAMP),
        )
        if (epochMillis == NO_TIMESTAMP) return null
        return Instant.ofEpochMilli(epochMillis)
    }

    fun cachedSnapshot(): LenovoWatchHealthSnapshot = LenovoWatchHealthSnapshot(
        steps = preferences.nullableInt(KEY_STEPS),
        calories = preferences.nullableInt(KEY_CALORIES),
        totalSleepMinutes = preferences.nullableInt(KEY_TOTAL_SLEEP),
        shallowSleepMinutes = preferences.nullableInt(KEY_SHALLOW_SLEEP),
        deepSleepMinutes = preferences.nullableInt(KEY_DEEP_SLEEP),
        awakeCount = preferences.nullableInt(KEY_AWAKE_COUNT),
        exerciseSeconds = preferences.nullableInt(KEY_EXERCISE_SECONDS),
        exerciseCount = preferences.nullableInt(KEY_EXERCISE_COUNT),
        heartRate = preferences.nullableInt(KEY_HEART_RATE),
        bloodOxygen = preferences.nullableInt(KEY_BLOOD_OXYGEN),
        systolic = preferences.nullableInt(KEY_SYSTOLIC),
        diastolic = preferences.nullableInt(KEY_DIASTOLIC),
        temperatureCelsius = preferences.nullableDouble(KEY_TEMPERATURE),
        immunity = preferences.nullableInt(KEY_IMMUNITY),
    )

    fun saveSuccessfulSync(
        startedAt: LocalDateTime,
        completedAt: Instant,
        snapshot: LenovoWatchHealthSnapshot,
    ) {
        preferences.edit()
            .putSnapshot(snapshot)
            .putLong(KEY_LAST_SYNC, startedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            .putLong(KEY_LAST_SYNC_COMPLETED_AT, completedAt.toEpochMilli())
            .apply()
    }

    /**
     * Persists recoverable dashboard values during a long history replay without advancing the
     * incremental-sync cursor. If Android kills the process, the next run can show this checkpoint
     * immediately and safely replay the same history again.
     */
    fun saveCheckpoint(snapshot: LenovoWatchHealthSnapshot) {
        preferences.edit()
            .putSnapshot(snapshot)
            .apply()
    }

    private fun SharedPreferences.Editor.putSnapshot(snapshot: LenovoWatchHealthSnapshot): SharedPreferences.Editor =
        putNullableInt(KEY_STEPS, snapshot.steps)
            .putNullableInt(KEY_CALORIES, snapshot.calories)
            .putNullableInt(KEY_TOTAL_SLEEP, snapshot.totalSleepMinutes)
            .putNullableInt(KEY_SHALLOW_SLEEP, snapshot.shallowSleepMinutes)
            .putNullableInt(KEY_DEEP_SLEEP, snapshot.deepSleepMinutes)
            .putNullableInt(KEY_AWAKE_COUNT, snapshot.awakeCount)
            .putNullableInt(KEY_EXERCISE_SECONDS, snapshot.exerciseSeconds)
            .putNullableInt(KEY_EXERCISE_COUNT, snapshot.exerciseCount)
            .putNullableInt(KEY_HEART_RATE, snapshot.heartRate)
            .putNullableInt(KEY_BLOOD_OXYGEN, snapshot.bloodOxygen)
            .putNullableInt(KEY_SYSTOLIC, snapshot.systolic)
            .putNullableInt(KEY_DIASTOLIC, snapshot.diastolic)
            .putNullableDouble(KEY_TEMPERATURE, snapshot.temperatureCelsius)
            .putNullableInt(KEY_IMMUNITY, snapshot.immunity)

    private fun SharedPreferences.nullableInt(key: String): Int? = if (contains(key)) getInt(key, 0) else null

    private fun SharedPreferences.nullableDouble(key: String): Double? =
        if (contains(key)) Double.fromBits(getLong(key, 0L)) else null

    private fun SharedPreferences.Editor.putNullableInt(key: String, value: Int?): SharedPreferences.Editor =
        if (value == null) remove(key) else putInt(key, value)

    private fun SharedPreferences.Editor.putNullableDouble(key: String, value: Double?): SharedPreferences.Editor =
        if (value == null) remove(key) else putLong(key, value.toBits())

    private companion object {
        const val PREFERENCES_NAME = "zhixing.lenovo_watch.sync"
        const val NO_TIMESTAMP = Long.MIN_VALUE
        const val KEY_REMEMBERED_DEVICE = "remembered_device"
        const val KEY_LAST_SYNC = "last_successful_sync"
        const val KEY_LAST_SYNC_COMPLETED_AT = "last_successful_sync_completed_at"
        const val KEY_STEPS = "steps"
        const val KEY_CALORIES = "calories"
        const val KEY_TOTAL_SLEEP = "total_sleep_minutes"
        const val KEY_SHALLOW_SLEEP = "shallow_sleep_minutes"
        const val KEY_DEEP_SLEEP = "deep_sleep_minutes"
        const val KEY_AWAKE_COUNT = "awake_count"
        const val KEY_EXERCISE_SECONDS = "exercise_seconds"
        const val KEY_EXERCISE_COUNT = "exercise_count"
        const val KEY_HEART_RATE = "heart_rate"
        const val KEY_BLOOD_OXYGEN = "blood_oxygen"
        const val KEY_SYSTOLIC = "systolic"
        const val KEY_DIASTOLIC = "diastolic"
        const val KEY_TEMPERATURE = "temperature_celsius"
        const val KEY_IMMUNITY = "immunity"
    }
}
