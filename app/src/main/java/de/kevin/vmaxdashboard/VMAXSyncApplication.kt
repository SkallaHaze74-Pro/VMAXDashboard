package de.kevin.vmaxdashboard

import android.app.Application
import android.content.Context

class VMAXSyncApplication : Application() {
    companion object {
        @Volatile
        internal lateinit var appContext: Context
            private set
    }

    internal lateinit var scooterRuntime: ScooterRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        AdaptiveDecoderRuntime.initialize(this)
        scooterRuntime = ScooterRuntime(this)
        AutomaticBleReconnectSupervisor.install(this)
        DiagnosticReadArchive.get(this).start()

        val enabled = getSharedPreferences("vmax_github_sync", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        if (enabled) {
            GitHubTelemetrySync.get(this).start()
            // DecoderAiCloudSync releases the initial review only after its
            // first cloud-profile attempt, so a stale local profile is not
            // reviewed immediately before the current cloud profile.
            DecoderAiCloudSync.get(this).start()
        } else {
            ExternalAiAutoReviewCoordinator.get(this).start()
        }
    }
}
