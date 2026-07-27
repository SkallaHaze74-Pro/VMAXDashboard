package de.kevin.vmaxdashboard

import android.content.Context
import android.os.Build
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.security.MessageDigest
import java.util.UUID

data class TesterReportItem(
    val id: String,
    val title: String,
    val reportType: String,
    val status: String,
    val details: String,
    val timestamp: Timestamp?
)

class TelemetryReporter(private val context: Context) {

    private val prefs =
        context.getSharedPreferences("stvx_telemetry", Context.MODE_PRIVATE)

    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val crashlytics by lazy { FirebaseCrashlytics.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

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

    val isSignedIn: Boolean
        get() = auth.currentUser != null

    val firebaseUid: String?
        get() = auth.currentUser?.uid

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

        if (now - previousTime < 5_000L) return
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

        ensureAnonymousLogin(
            onSuccess = { uid ->
                data["firebaseUid"] = uid

                firestore.collection("bleTelemetry")
                    .add(data)
                    .addOnFailureListener { error ->
                        crashlytics.recordException(error)
                    }
            },
            onFailure = crashlytics::recordException
        )
    }

    fun reportProblem(
        title: String,
        details: String,
        scooterState: ScooterState,
        reportType: String = "Fehlerbericht",
        scooterModel: String = "",
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        crashlytics.log("Testerbericht: $title")
        crashlytics.setCustomKey("tester_id", testerId)
        crashlytics.setCustomKey("scooter_name", scooterState.deviceName)
        crashlytics.setCustomKey(
            "ble_channel",
            scooterState.lastCharacteristic
        )
        crashlytics.setCustomKey(
            "analysis_phase",
            scooterState.analysisPhase
        )
        crashlytics.recordException(
            TesterReportException("$title: ${details.take(500)}")
        )

        if (!uploadEnabled) {
            onFailure?.invoke(
                IllegalStateException("Testdaten-Upload ist ausgeschaltet.")
            )
            return
        }

        val data = hashMapOf<String, Any>(
            "testerId" to testerId,
            "timestamp" to Timestamp.now(),
            "title" to title,
            "details" to details,
            "reportType" to reportType,
            "status" to "Neu",
            "scooterModel" to scooterModel,
            "deviceName" to scooterState.deviceName,
            "deviceAddressHash" to sha256(scooterState.address),
            "lastCharacteristic" to scooterState.lastCharacteristic,
            "lastRawHex" to scooterState.lastRawHex,
            "analysisPhase" to scooterState.analysisPhase,
            "analysisPhaseNumber" to scooterState.analysisPhaseNumber,
            "packetTotal" to scooterState.packetTotal,
            "channelCount" to scooterState.channels.size,
            "activeChannelCount" to scooterState.channels.count { it.active },
            "speedKmh" to (scooterState.speedKmh ?: -1.0),
            "batteryPercent" to (scooterState.batteryPercent ?: -1),
            "voltageV" to (scooterState.voltageV ?: -1.0),
            "temperatureC" to (scooterState.temperatureC ?: -1.0),
            "phoneManufacturer" to Build.MANUFACTURER,
            "phoneModel" to Build.MODEL,
            "androidVersion" to Build.VERSION.RELEASE,
            "androidSdk" to Build.VERSION.SDK_INT,
            "appVersion" to BuildConfig.VERSION_NAME,
            "appVersionCode" to BuildConfig.VERSION_CODE
        )

        ensureAnonymousLogin(
            onSuccess = { uid ->
                data["firebaseUid"] = uid

                firestore.collection("testerReports")
                    .add(data)
                    .addOnSuccessListener {
                        onSuccess?.invoke()
                    }
                    .addOnFailureListener { error ->
                        crashlytics.recordException(error)
                        onFailure?.invoke(error)
                    }
            },
            onFailure = { error ->
                crashlytics.recordException(error)
                onFailure?.invoke(error)
            }
        )
    }

    fun observeOwnReports(
        onUpdate: (List<TesterReportItem>) -> Unit,
        onFailure: (Exception) -> Unit
    ): ListenerRegistration? {
        var registration: ListenerRegistration? = null

        ensureAnonymousLogin(
            onSuccess = { uid ->
                registration = firestore.collection("testerReports")
                    .whereEqualTo("firebaseUid", uid)
                    .limit(30)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            crashlytics.recordException(error)
                            onFailure(error)
                            return@addSnapshotListener
                        }

                        val reports = snapshot?.documents.orEmpty().map { document ->
                            TesterReportItem(
                                id = document.id,
                                title = document.getString("title")
                                    ?: "Testerbericht",
                                reportType = document.getString("reportType")
                                    ?: "Bericht",
                                status = document.getString("status")
                                    ?: "Neu",
                                details = document.getString("details")
                                    ?: "",
                                timestamp = document.getTimestamp("timestamp")
                            )
                        }

                        onUpdate(
                            reports.sortedByDescending {
                                it.timestamp?.seconds ?: 0L
                            }
                        )
                    }
            },
            onFailure = onFailure
        )

        return registration
    }

    fun ensureLogin(
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        ensureAnonymousLogin(
            onSuccess = { onSuccess?.invoke() },
            onFailure = { onFailure?.invoke(it) }
        )
    }

    private fun ensureAnonymousLogin(
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val currentUser = auth.currentUser

        if (currentUser != null) {
            onSuccess(currentUser.uid)
            return
        }

        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                val uid = result.user?.uid

                if (uid != null) {
                    onSuccess(uid)
                } else {
                    onFailure(
                        IllegalStateException(
                            "Keine anonyme Firebase-Benutzer-ID erhalten."
                        )
                    )
                }
            }
            .addOnFailureListener { error ->
                crashlytics.recordException(error)
                onFailure(error)
            }
    }

    private fun sha256(value: String): String {
        if (value.isBlank()) return ""

        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

class TesterReportException(message: String) : Exception(message)
