package io.github.alamo3.openblue.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.alamo3.openblue.api.Brand
import io.github.alamo3.openblue.api.CarLocation
import io.github.alamo3.openblue.api.ClimateFormat
import io.github.alamo3.openblue.api.ClimateSettings
import io.github.alamo3.openblue.api.VehicleCaps
import io.github.alamo3.openblue.api.VehicleInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Everything is kept in EncryptedSharedPreferences (AES-256, key in the Android
 * Keystore). Nothing leaves the phone except the requests to mybluelink.ca.
 */
class SecureStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(encryptedPrefs(context))

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val KEY_DEVICE = "deviceId"

        private fun encryptedPrefs(context: Context): SharedPreferences {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                "openblue_secure",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    private fun read(k: String): String? = prefs.getString(k, null)
    private fun write(k: String, v: String?) = prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()

    /**
     * Stable per-install device ID. Bluelink asks for an emailed/SMS code the
     * first time it sees a device ID, so this must never change between logins.
     */
    val deviceId: String
        get() = read(KEY_DEVICE) ?: UUID.randomUUID().toString().also { write(KEY_DEVICE, it) }

    /** Which brand's service the account belongs to; chosen on the login screen. */
    var brand: Brand
        get() = Brand.entries.firstOrNull { it.name == read("brand") } ?: Brand.HYUNDAI
        set(v) = write("brand", v.name)
    /** The climate payload key that worked after Bluelink refused the other one (6452); Automatic only. */
    var learnedClimateKey: String?
        get() = read("learnedClimateKey")
        set(v) = write("learnedClimateKey", v)
    var username: String?
        get() = read("username")
        set(v) = write("username", v)
    var password: String?
        get() = read("password")
        set(v) = write("password", v)
    var pin: String?
        get() = read("pin")
        set(v) = write("pin", v)
    var accessToken: String?
        get() = read("accessToken")
        set(v) = write("accessToken", v)
    /** OAuth refresh token (USA client only; Canada re-logins instead). Rotates on every refresh. */
    var refreshToken: String?
        get() = read("refreshToken")
        set(v) = write("refreshToken", v)
    var tokenExpiresAt: Long
        get() = prefs.getLong("tokenExpiresAt", 0L)
        set(v) = prefs.edit().putLong("tokenExpiresAt", v).apply()
    var caps: VehicleCaps?
        get() = read("caps")?.let { runCatching { json.decodeFromString<VehicleCaps>(it) }.getOrNull() }
        set(v) = write("caps", v?.let { json.encodeToString(it) })

    /** Which JSON key climate settings go under; AUTO picks from the vehicle's genType. */
    var climateFormat: ClimateFormat
        get() = read("climateFormat")?.let { runCatching { ClimateFormat.valueOf(it) }.getOrNull() } ?: ClimateFormat.AUTO
        set(v) = write("climateFormat", v.name)

    /** Shows the floating developer panel. */
    var developerMode: Boolean
        get() = prefs.getBoolean("developerMode", false)
        set(v) = prefs.edit().putBoolean("developerMode", v).apply()

    /** Trip planning route option (22 Recommended, 21 Fastest, 23 Economical). */
    var routeStyle: Int
        get() = prefs.getInt("routeStyle", if (prefs.getBoolean("avoidTolls", false)) 23 else 22)
        set(v) = prefs.edit().putInt("routeStyle", v).apply()

    /** Ask for fingerprint/screen lock before unlocking the car. */
    var confirmUnlock: Boolean
        get() = prefs.getBoolean("confirmUnlock", true)
        set(v) = prefs.edit().putBoolean("confirmUnlock", v).apply()

    var vehicle: VehicleInfo?
        get() = read("vehicle")?.let { runCatching { json.decodeFromString<VehicleInfo>(it) }.getOrNull() }
        set(v) = write("vehicle", v?.let { json.encodeToString(it) })

    var localPresets: List<ClimateSettings>
        get() = read("localPresets")?.let { runCatching { json.decodeFromString<List<ClimateSettings>>(it) }.getOrNull() } ?: emptyList()
        set(v) = write("localPresets", json.encodeToString(v))

    /** The presets Bluelink had last time they were loaded, so the widget can offer them without a request. */
    var serverPresetsCache: List<ClimateSettings>
        get() = read("serverPresetsCache")?.let { runCatching { json.decodeFromString<List<ClimateSettings>>(it) }.getOrNull() } ?: emptyList()
        set(v) = write("serverPresetsCache", json.encodeToString(v))

    var lastClimate: ClimateSettings?
        get() = read("lastClimate")?.let { runCatching { json.decodeFromString<ClimateSettings>(it) }.getOrNull() }
        set(v) = write("lastClimate", v?.let { json.encodeToString(it) })

    /** The car's last known location (kept encrypted like everything else here). */
    var lastLocation: CarLocation?
        get() = read("lastLocation")?.let { runCatching { json.decodeFromString<CarLocation>(it) }.getOrNull() }
        set(v) = write("lastLocation", v?.let { json.encodeToString(it) })

    /** A paid charge started from this phone, while it may still be running. */
    var activeSession: io.github.alamo3.openblue.api.ActiveSession?
        get() = read("activeSession")?.let { runCatching { json.decodeFromString<io.github.alamo3.openblue.api.ActiveSession>(it) }.getOrNull() }
        set(v) = write("activeSession", v?.let { json.encodeToString(it) })

    /** The card the user last charged with. */
    var payCardId: String?
        get() = read("payCardId")
        set(v) = write("payCardId", v)

    var cachedStatus: String?
        get() = read("cachedStatus")
        set(v) = write("cachedStatus", v)

    /** The last weather the app fetched (for the home-screen widget and the seat check). */
    /** The user's own World Labs API key, for generating 3D worlds (experimental). */
    var worldLabsKey: String?
        get() = read("worldLabsKey")
        set(v) = write("worldLabsKey", v?.trim()?.takeIf { it.isNotEmpty() })

    var lastWeather: io.github.alamo3.openblue.api.Weather?
        get() = read("lastWeather")?.let { runCatching { json.decodeFromString<io.github.alamo3.openblue.api.Weather>(it) }.getOrNull() }
        set(v) = write("lastWeather", v?.let { json.encodeToString(it) })

    /** A command sent from the home-screen widget and not answered yet: "ACTION|millis". */
    var widgetPending: String?
        get() = read("widgetPending")
        set(v) = write("widgetPending", v)

    /** Notify when a command finishes after you've left the app. */
    var notifyCommands: Boolean
        get() = prefs.getBoolean("notifyCommands", true)
        set(v) = prefs.edit().putBoolean("notifyCommands", v).apply()

    /** Engine sound: which engine, how loud, and whether revs hold while cruising. */
    var engineSound: String?
        get() = read("engineSound")
        set(v) = write("engineSound", v)
    var soundVolume: Float
        get() = prefs.getFloat("soundVolume", 0.8f)
        set(v) = prefs.edit().putFloat("soundVolume", v).apply()
    /** How far ahead the engine sound predicts, to hide speaker delay (ms). */
    var soundLookaheadMs: Int
        get() = prefs.getInt("soundLookaheadMs", 0)
        set(v) = prefs.edit().putInt("soundLookaheadMs", v).apply()
    /** The phone mount learned for the engine sound (tilt and which way is forward). */
    var soundMount: String?
        get() = read("soundMount")
        set(v) = write("soundMount", v)
    /** Start the engine sound when the car's Bluetooth connects, stop when it disconnects. */
    /** Where the phone sits: 1 on the charging pad top to the front, -1 top to the back, 0 elsewhere. */
    var soundPlacement: Int
        get() = prefs.getInt("soundPlacement", 0)
        set(v) = prefs.edit().putInt("soundPlacement", v).apply()
    var autoSound: Boolean
        get() = prefs.getBoolean("autoSound", false)
        set(v) = prefs.edit().putBoolean("autoSound", v).apply()
    var soundCarAddress: String?
        get() = read("soundCarAddress")
        set(v) = write("soundCarAddress", v)
    var soundCarName: String?
        get() = read("soundCarName")
        set(v) = write("soundCarName", v)
    var gearshifts: Boolean
        get() = prefs.getBoolean("gearshifts", false)
        set(v) = prefs.edit().putBoolean("gearshifts", v).apply()
    var holdRevs: Boolean
        get() = prefs.getBoolean("holdRevs", false)
        set(v) = prefs.edit().putBoolean("holdRevs", v).apply()

    var askedNotifications: Boolean
        get() = prefs.getBoolean("askedNotifications", false)
        set(v) = prefs.edit().putBoolean("askedNotifications", v).apply()

    /** Check the car's last reported status in the background and alert on changes. */
    var watchEnabled: Boolean
        get() = prefs.getBoolean("watchEnabled", false)
        set(v) = prefs.edit().putBoolean("watchEnabled", v).apply()

    /** Minutes between background checks (Android's minimum is 15). */
    var watchMinutes: Int
        get() = prefs.getInt("watchMinutes", 30)
        set(v) = prefs.edit().putInt("watchMinutes", v).apply()

    fun alertOn(alert: String): Boolean = prefs.getBoolean("alert.$alert", true)
    fun setAlertOn(alert: String, on: Boolean) = prefs.edit().putBoolean("alert.$alert", on).apply()

    /** Background-check memory, so each alert fires once per occurrence. */
    var watchMemory: io.github.alamo3.openblue.work.WatchMemory
        get() = read("watchMemory")?.let { runCatching { json.decodeFromString<io.github.alamo3.openblue.work.WatchMemory>(it) }.getOrNull() }
            ?: io.github.alamo3.openblue.work.WatchMemory()
        set(v) = write("watchMemory", json.encodeToString(v))

    /** Signs out but keeps the device ID (so the next login won't need a code) and local presets. */
    fun signOut() {
        val keep = mapOf(KEY_DEVICE to read(KEY_DEVICE), "localPresets" to read("localPresets"), "worldLabsKey" to read("worldLabsKey"))
        prefs.edit().clear().apply()
        keep.forEach { (k, v) -> write(k, v) }
    }

}
