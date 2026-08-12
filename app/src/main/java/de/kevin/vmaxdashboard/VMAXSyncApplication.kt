package de.kevin.vmaxdashboard

import android.app.Application
import android.content.Context

class VMAXSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val enabled = getSharedPreferences("vmax_github_sync", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        if (enabled) {
            GitHubTelemetrySync.get(this).start()
            DecoderAiCloudSync.get(this).start()
        }
    }
}
