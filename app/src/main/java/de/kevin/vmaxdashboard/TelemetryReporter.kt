package de.kevin.vmaxdashboard

import android.content.Context
import android.os.Build
import com.google.firebase.Timestamp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import java.security.MessageDigest
import java.util.UUID

class TelemetryReporter(private val context: Context) {

    private val prefs =
        context.getSharedPreferences("stvx_telemetry", Context.MODE_PRIVATE)

    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val crashlytics by lazy { FirebaseCrashlytics.getInstance() }

    val testerId: String =
        prefs.getString("tester_id", null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("tester_id", it).apply()
            }

    var uploadEnabled: Boolean
        get() = prefs.getBoolean("telemetry_upload", false)
        set(value) {
            prefs.edit().putBoolean("telemetry_upload", value).apply()
            crashlytics.setCustomKey("telemetry_upload_enabled", value)
        }

    private val lastUploadAt = mutableMapOf<String, Long>()
    private val lastUploadedHex = mutableMapOf<String, String>()

    fun recordPacket(
        channel: String,
        service: String,
        hex: String,
        packetLength: Int,
        packetCount: Int,
        packetsPerSecond: Double,
        analysisPhase: String,
        deviceName: String,
        deviceAddress: String
    ) {
        if (!uploadEnabled) return

        val now = System.currentTimeMillis()
        val previousTime = lastUploadAt[channel] ?: 0L
        val previousHex = lastUploadedHex[channel]

        // Pro Kanal höchstens alle fünf Sekunden senden.
        if (now - previousTime < 5_000L) return

        // Identische Pakete höchstens einmal pro Minute erneut senden.
        if (previousHex == hex && now - previousTime < 60_000L) return

        lastUploadAt[channel] = now
        lastUploadedHex[channel] = hex

        val data = hashMapOf<String, Any>(
            "testerId" to testerId,
            "timestamp" to Timestamp.now(),
            "channel" to channel,
            "service" to service,
            "hex" to hex,
            "packetLength" to packetLength,
            "packetCount" to packetCount,
            "packetsPerSecond" to packetsPerSecond,
            "analysisPhase" to analysisPhase,
            "deviceName" to deviceName,
            "deviceAddressHash" to sha256(deviceAddress),
            "phoneManufacturer" to Build.MANUFACTURER,
            "phoneModel" to Build.MODEL,
            "androidVersion" to Build.VERSION.RELEASE,
            "androidSdk" to Build.VERSION.SDK_INT,
            "appVersion" to BuildConfig.VERSION_NAME,
            "appVersionCode" to BuildConfig.VERSION_CODE
        )

        firestore.collection("bleTelemetry")
            .add(data)
            .addOnFailureListener { error ->
                crashlytics.recordException(error)
            }
    }

    fun reportProblem(
        title: String,
        details: String,
        scooterState: ScooterState
    ) {
        crashlytics.log("Testerbericht: $title")
        crashlytics.setCustomKey("tester_id", testerId)
        crashlytics.setCustomKey("scooter_name", scooterState.deviceName)
        crashlytics.setCustomKey("ble_channel", scooterState.lastCharacteristic)
        crashlytics.setCustomKey("analysis_phase", scooterState.analysisPhase)
        crashlytics.recordException(
            TesterReportException("$title: $details")
        )

        if (!uploadEnabled) return

        val data = hashMapOf<String, Any>(
            "testerId" to testerId,
            "timestamp" to Timestamp.now(),
            "title" to title,
            "details" to details,
            "deviceName" to scooterState.deviceName,
            "deviceAddressHash" to sha256(scooterState.address),
            "lastCharacteristic" to scooterState.lastCharacteristic,
            "lastRawHex" to scooterState.lastRawHex,
            "analysisPhase" to scooterState.analysisPhase,
            "packetTotal" to scooterState.packetTotal,
            "phoneModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "androidVersion" to Build.VERSION.RELEASE,
            "appVersion" to BuildConfig.VERSION_NAME
        )

        firestore.collection("testerReports")
            .add(data)
            .addOnFailureListener { crashlytics.recordException(it) }
    }

    private fun sha256(value: String): String {
        if (value.isBlank()) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

class TesterReportException(message: String) : Exception(message)
