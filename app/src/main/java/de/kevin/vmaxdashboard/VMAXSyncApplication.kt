package de.kevin.vmaxdashboard

import android.app.Application
import android.content.Context

class VMAXSyncApplication : Application() {
    companion object {
        @Volatile
        internal lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        AdaptiveDecoderRuntime.initialize(this)
        AutomaticBleReconnectSupervisor.install(this)
        ExternalAiAutoReviewCoordinator.get(this).start()
        DiagnosticReadArchive.get(this).start()

        val enabled = getSharedPreferences("vmax_github_sync", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        if (enabled) {
            GitHubTelemetrySync.get(this).start()
            DecoderAiCloudSync.get(this).start()
        }
    }
}
