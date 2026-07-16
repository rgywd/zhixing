package me.rerere.rikkahub.telemetry

/** Product-owned analytics boundary. The default build deliberately records nothing. */
fun interface AppTelemetry {
    fun logEvent(name: String)
}

object NoOpAppTelemetry : AppTelemetry {
    override fun logEvent(name: String) = Unit
}
