package io.github.alamo3.openblue

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.alamo3.openblue.api.ApiException
import io.github.alamo3.openblue.api.BluelinkClient
import io.github.alamo3.openblue.api.ChargeWindow
import io.github.alamo3.openblue.api.ClimateFormat
import io.github.alamo3.openblue.api.ClimateSchedule
import io.github.alamo3.openblue.api.ClimateSettings
import io.github.alamo3.openblue.api.CommandResult
import io.github.alamo3.openblue.api.LoginResult
import io.github.alamo3.openblue.api.OtpChallenge
import io.github.alamo3.openblue.api.OtpRequiredException
import io.github.alamo3.openblue.api.ServerPreset
import io.github.alamo3.openblue.api.SvmCapture
import io.github.alamo3.openblue.api.ActiveSession
import io.github.alamo3.openblue.api.ChargeHistory
import io.github.alamo3.openblue.api.ChargeOffer
import io.github.alamo3.openblue.api.Charger
import io.github.alamo3.openblue.api.SessionStatus
import io.github.alamo3.openblue.api.ChargePlan
import io.github.alamo3.openblue.api.ChargeService
import io.github.alamo3.openblue.api.Station
import io.github.alamo3.openblue.data.Gallery
import io.github.alamo3.openblue.data.SvmArchive
import io.github.alamo3.openblue.data.SvmShot
import io.github.alamo3.openblue.api.TempTables
import io.github.alamo3.openblue.api.VehicleCaps
import io.github.alamo3.openblue.api.VehicleInfo
import io.github.alamo3.openblue.data.SecureStore
import io.github.alamo3.openblue.debug.DebugState
import io.github.alamo3.openblue.sound.EngineSound
import io.github.alamo3.openblue.sound.EngineSoundService
import io.github.alamo3.openblue.sound.SoundController
import io.github.alamo3.openblue.sound.SoundReadout
import io.github.alamo3.openblue.work.AlertKind
import io.github.alamo3.openblue.work.CommandWorker
import io.github.alamo3.openblue.work.StatusWatchWorker
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.alamo3.openblue.api.forCar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException

sealed interface Screen {
    data object Login : Screen
    data class Otp(val challenge: OtpChallenge) : Screen
    data class PickVehicle(val vehicles: List<VehicleInfo>) : Screen
    data object Main : Screen
}

/** A remote command in flight, shown as a progress strip until the car answers. */
data class CommandProgress(val action: Action, val stage: Stage, val note: String? = null) {
    enum class Stage { SENDING, WAITING, DONE, FAILED }

    enum class Action(val doing: String, val done: String) {
        LOCK("Locking", "Locked"),
        UNLOCK("Unlocking", "Unlocked"),
        CLIMATE_START("Starting climate", "Climate is on"),
        CLIMATE_STOP("Stopping climate", "Climate is off"),
        WAKE("Asking the car for its status", "Status updated"),
        CAMERA("Asking the car for photos", "New photos are in"),
        HORN("Sounding the horn and flashing the lights", "Horn and lights done"),
        LIGHTS("Flashing the lights", "Lights flashed"),
        PORT_OPEN("Opening the charge port", "Charge port open"),
        PORT_CLOSE("Closing the charge port", "Charge port closed"),
        PRECONDITION_ON("Turning on battery preconditioning", "Battery preconditioning on"),
        PRECONDITION_OFF("Turning off battery preconditioning", "Battery preconditioning off"),
        CHARGE_LIMITS("Saving charge limits", "Charge limits saved"),
        LOCATE("Asking the car where it is", "Found the car"),
        SCHEDULE_CLIMATE("Saving the climate schedule", "Climate schedule saved"),
        SCHEDULE_CHARGE("Saving the charging schedule", "Charging schedule saved"),
    }
}

/**
 * [online] = false keeps the ViewModel from making network calls on its own (used by the
 * screenshot tests, which render screens from stored demo data).
 */
class MainViewModel(
    app: Application,
    private val store: SecureStore,
    private val online: Boolean,
    private val archive: SvmArchive? = null,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, SecureStore(app), online = true, archive = SvmArchive(java.io.File(app.filesDir, "svm")))

    private val api = BluelinkClient(store)
    /** Hyundai USA client (different backend); used instead of [api] when the brand is HYUNDAI_USA. */
    private val usa = io.github.alamo3.openblue.api.UsaBluelinkClient(store)
    val isUs: Boolean get() = store.brand == io.github.alamo3.openblue.api.Brand.HYUNDAI_USA

    /** Cached status from whichever backend the account belongs to. */
    private suspend fun fetchCached(id: String) = if (isUs) usa.cachedStatus(id) else api.cachedStatus(id)

    private val prettyJson = Json { prettyPrint = true }

    var screen by mutableStateOf<Screen>(Screen.Login)
        private set

    /** Non-null while a foreground request (sign-in, loading) runs. Blocks other actions. */
    var busy by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<CommandProgress?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    var vehicle by mutableStateOf(store.vehicle)
        private set
    var caps by mutableStateOf(store.caps ?: VehicleCaps())
        private set
    /** The last status Bluelink reported. Screens read [status], which developer mode can override. */
    private var realStatus by mutableStateOf(store.cachedStatus?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() })

    var developerMode by mutableStateOf(store.developerMode)
        private set
    var debug by mutableStateOf(DebugState())
        private set

    /** What the UI shows: Bluelink's status, or the simulated car when developer mode says so. */
    val status: JsonObject?
        get() = if (developerMode && debug.simulate) debug.toStatusJson(tempTable) else realStatus
    var serverPresets by mutableStateOf<List<ServerPreset>>(emptyList())
        private set
    var localPresets by mutableStateOf(store.localPresets)
        private set
    var editor by mutableStateOf(store.lastClimate ?: ClimateSettings())
        private set
    var climateFormat by mutableStateOf(store.climateFormat)
        private set
    var confirmUnlock by mutableStateOf(store.confirmUnlock)
        private set
    /** Surround-view photos, newest first: everything archived on this phone plus Bluelink's latest. */
    var svm by mutableStateOf<List<SvmShot>>(emptyList())
        private set
    var svmLoaded by mutableStateOf(false)
        private set
    var diagnostics by mutableStateOf<String?>(null)


    val username: String? get() = store.username

    /** True when no command or blocking request is running. */
    val canAct: Boolean
        get() = busy == null && progress.let {
            it == null || it.stage == CommandProgress.Stage.DONE || it.stage == CommandProgress.Stage.FAILED
        }

    // Credentials typed on the login screen, held until login fully succeeds.
    private var pendingUser: String? = null
    private var pendingPass: String? = null
    private var pendingPin: String? = null
    private var clearProgressJob: Job? = null

    init {
        val v = store.vehicle
        if (store.username != null && store.password != null && v != null) {
            screen = Screen.Main
        }
        if (online && screen == Screen.Main && v != null) {
            runCatching {
                io.github.alamo3.openblue.work.Notifier.createChannels(getApplication())
                if (!isUs) StatusWatchWorker.sync(getApplication(), store)
            }
            refreshing = true
            launchSafely(onDone = { refreshing = false }) {
                loadVehicle(v)
                serverPresets = if (isUs) emptyList() else api.serverPresets(v.id, tempTable)
            }
        }
    }

    val tempTable: List<TempTables.Entry>
        get() {
            val v = vehicle
            val type = caps.hvacTempType ?: if (v?.fuelKindCode.equals("E", true)) 1 else 0
            return TempTables.forVehicle(type, v?.model.orEmpty(), v?.fuelKindCode.orEmpty())
        }

    /** The JSON key the next climate start will use, for display. */
    val climateKey: String get() = api.climateKey()

    // ---------------------------------------------------------------- plumbing

    private fun describe(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "Bluelink returned an error"
        is IOException -> "Couldn't reach Bluelink. Check your connection and try again."
        else -> e.message ?: e.toString()
    }

    /** Runs [block], routing errors to [onError] and device re-verification to the code screen. */
    private fun launchSafely(
        onDone: () -> Unit = {},
        onError: (Throwable) -> Unit = { message = describe(it) },
        block: suspend () -> Unit,
    ): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: OtpRequiredException) {
            pendingUser = store.username
            pendingPass = store.password
            pendingPin = store.pin
            screen = Screen.Otp(e.challenge)
            message = "Bluelink wants to verify this phone again"
            progress = null
        } catch (e: Exception) {
            onError(e)
        } finally {
            onDone()
        }
    }

    private fun task(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        launchSafely(onDone = { busy = null }, block = block)
    }

    // ------------------------------------------------------------------ auth

    /** The brand the account belongs to (Hyundai unless chosen otherwise at sign-in). */
    val brand: io.github.alamo3.openblue.api.Brand get() = store.brand

    fun login(user: String, pass: String, pin: String, brand: io.github.alamo3.openblue.api.Brand) = task("Signing in") {
        store.brand = brand
        pendingUser = user.trim()
        pendingPass = pass
        pendingPin = pin.trim()
        when (val r = if (brand == io.github.alamo3.openblue.api.Brand.HYUNDAI_USA) usa.login(user.trim(), pass) else api.login(user.trim(), pass)) {
            is LoginResult.NeedsOtp -> screen = Screen.Otp(r.challenge)
            LoginResult.Success -> finishLogin()
        }
    }

    fun sendOtp(bySms: Boolean) {
        val s = screen as? Screen.Otp ?: return
        task("Sending code") {
            screen = Screen.Otp(api.sendOtp(s.challenge, bySms))
            message = if (bySms) "Code sent by text message" else "Code sent to ${s.challenge.email}"
        }
    }

    fun verifyOtp(code: String) {
        val s = screen as? Screen.Otp ?: return
        val user = pendingUser ?: return
        task("Checking code") {
            api.verifyOtp(user, code, s.challenge)
            finishLogin()
        }
    }

    fun cancelOtp() {
        screen = if (store.vehicle != null && store.username != null) Screen.Main else Screen.Login
    }

    private suspend fun finishLogin() {
        pendingUser?.let { store.username = it }
        pendingPass?.let { store.password = it }
        pendingPin?.takeIf { it.isNotEmpty() }?.let { store.pin = it }
        pendingPass = null
        if (store.vehicle != null) {
            // Re-verification while already set up: just go back.
            screen = Screen.Main
            return
        }
        val list = if (isUs) usa.vehicles() else api.vehicles()
        when {
            list.isEmpty() -> message = "This Bluelink account has no vehicles"
            list.size == 1 -> chooseVehicleNow(list.first())
            else -> screen = Screen.PickVehicle(list)
        }
    }

    fun changeVehicle() = task("Loading vehicles") {
        screen = Screen.PickVehicle(if (isUs) usa.vehicles() else api.vehicles())
    }

    fun chooseVehicle(v: VehicleInfo) = task("Loading ${v.nickname}") { chooseVehicleNow(v) }

    private suspend fun chooseVehicleNow(v: VehicleInfo) {
        if (store.vehicle?.id != v.id) {
            store.learnedClimateKey = null
            store.lastLocation = null
            location = null
            chargeService = null
            stations = emptyList()
            chargeHistory = null
        }
        store.vehicle = v
        vehicle = v
        svm = emptyList()
        svmLoaded = false
        try {
            loadVehicle(v)
        } catch (e: ApiException) {
            message = describe(e)
        }
        screen = Screen.Main
        try {
            serverPresets = if (isUs) emptyList() else api.serverPresets(v.id, tempTable)
        } catch (e: ApiException) {
            // Presets are optional; the Climate tab offers a reload.
        }
    }

    /** sltvhcl: capabilities + status in one call; falls back to the plain status call. */
    private suspend fun loadVehicle(v: VehicleInfo) {
        if (isUs) {
            // USA: capabilities come from enrollment details; status from vehicleStatus.
            val c = usa.capsFor(v)
            store.caps = c
            caps = c
            try {
                applyStatus(usa.cachedStatus(v.id))
            } catch (e: Exception) {
                message = "${describe(e)} [${usa.lastDiag ?: "no diag"}]"
                return
            }
            // Diagnostic (v2): if the status arrived but carries no battery reading, say so
            // on screen with the server's reply shape instead of silently showing dashes.
            val evNow = realStatus?.get("evStatus") as? JsonObject
            if (evNow?.get("batteryStatus") == null) {
                message = "Status arrived without battery data. [${usa.lastDiag ?: "no diag"}]"
            }
            return
        }
        val selected = api.selectVehicle(v.id)
        val c = api.capsFrom(selected)
        store.caps = c
        caps = c
        val s = selected["status"] as? JsonObject ?: api.cachedStatus(v.id)
        applyStatus(s)
    }

    fun signOut() {
        if (online) StatusWatchWorker.stop(getApplication())
        store.signOut()
        notifyCommands = store.notifyCommands
        watchEnabled = store.watchEnabled
        watchMinutes = store.watchMinutes
        alertsOn = AlertKind.entries.associateWith { store.alertOn(it.key) }
        vehicle = null
        realStatus = null
        caps = VehicleCaps()
        serverPresets = emptyList()
        svm = emptyList()
        svmLoaded = false
        location = null
        chargeService = null
        stations = emptyList()
        chargeHistory = null
        progress = null
        screen = Screen.Login
    }

    /** Replaces the car photo on Bluelink (the picture shown top right), like the official app. */
    fun changeCarPhoto(uri: Uri) {
        val v = vehicle ?: return
        task("Uploading photo") {
            val b64 = withContext(Dispatchers.IO) { encodePhoto(uri) }
            val url = api.uploadVehiclePhoto(v.id, b64)
            if (url != null) {
                val c = caps.copy(photoUrl = url)
                store.caps = c
                caps = c
            }
            message = "Car photo updated"
        }
    }

    /** Downscale to ~800 px wide and compress to under 100 KB, as the official app does. */
    private fun encodePhoto(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = if (bounds.outWidth >= 800) bounds.outWidth / 800 else 1
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Couldn't read that photo")
        var quality = 90
        var bytes: ByteArray
        do {
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 10
        } while (bytes.size > 100 * 1024 && quality > 20)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
    }

    fun updatePin(pin: String) {
        store.pin = pin.trim()
        message = "PIN updated"
    }

    // ---------------------------------------------------------------- status

    private fun applyStatus(s: JsonObject) {
        realStatus = s
        store.cachedStatus = s.toString()
        updateWidgets()
    }

    private fun updateWidgets() {
        if (!online) return
        val ctx = getApplication<Application>()
        viewModelScope.launch { runCatching { io.github.alamo3.openblue.widget.CarWidget.updateAll(ctx) } }
    }

    /** Pull-to-refresh: reloads what Bluelink has stored. Doesn't wake the car. */
    fun refresh() {
        val v = vehicle ?: return
        if (refreshing) return
        refreshing = true
        launchSafely(onDone = { refreshing = false }) {
            applyStatus(fetchCached(v.id))
        }
        refreshWeather()
    }

    /** Asks the car itself for fresh status. Slower and wakes the car's modem. */
    fun wakeCar() {
        val v = vehicle ?: return
        if (!canAct) return
        if (developerMode && debug.dryRun) {
            dryRun(CommandProgress.Action.WAKE) { it }
            return
        }
        val action = CommandProgress.Action.WAKE
        showProgress(CommandProgress(action, CommandProgress.Stage.WAITING, "This can take up to a minute."))
        launchSafely(onError = { finish(action, false, describe(it)) }) {
            applyStatus(if (isUs) usa.liveStatus(v.id) else api.liveStatus(v.id))
            finish(action, true)
        }
    }

    // -------------------------------------------------------------- commands

    private fun showProgress(p: CommandProgress) {
        clearProgressJob?.cancel()
        progress = p
    }

    private fun finish(action: CommandProgress.Action, ok: Boolean, note: String? = null) {
        showProgress(CommandProgress(action, if (ok) CommandProgress.Stage.DONE else CommandProgress.Stage.FAILED, note))
        clearProgressJob = viewModelScope.launch {
            delay(if (ok) 3_500 else 10_000)
            progress = null
        }
    }

    fun dismissProgress() {
        val stage = progress?.stage
        if (stage == CommandProgress.Stage.DONE || stage == CommandProgress.Stage.FAILED) progress = null
    }

    private fun command(
        action: CommandProgress.Action,
        simulate: (DebugState) -> DebugState = { it },
        send: suspend (VehicleInfo) -> String,
    ) {
        val v = vehicle ?: return
        if (!canAct) return
        if (developerMode && debug.dryRun) {
            dryRun(action, simulate)
            return
        }
        showProgress(CommandProgress(action, CommandProgress.Stage.SENDING))
        launchSafely(onError = { finish(action, false, describe(it)) }) {
            val tx = send(v)
            showProgress(CommandProgress(action, CommandProgress.Stage.WAITING))
            when (awaitCommand(v, tx, action)) {
                CommandResult.SUCCESS -> finish(action, true)
                CommandResult.FAILED -> finish(action, false, "The car didn't accept the command." + if (isUs) usa.lastDiag?.let { "\n[$it]" } ?: "" else "")
                CommandResult.TIMEOUT -> finish(action, false, "No answer from the car yet. It may still go through, so check again in a minute.")
            }
        }
    }

    /**
     * Waits for the car's answer. The waiting itself runs as background work, so it carries on
     * (and ends in a notification) if the app is closed; the app just follows along while open.
     * Leaves the latest status in [status] either way.
     */
    private suspend fun awaitCommand(v: VehicleInfo, tx: String, action: CommandProgress.Action): CommandResult {
        if (isUs) {
            // USA v1: poll in the foreground (no CommandWorker path yet); an empty tx means
            // the command was accepted with an empty-body 200 and returns SUCCESS at once.
            val r = usa.awaitResult(v.id, tx)
            runCatching { applyStatus(usa.cachedStatus(v.id)) }
            return r
        }
        if (!online) {
            val r = api.awaitResult(v.id, tx)
            runCatching { applyStatus(api.cachedStatus(v.id)) }
            return r
        }
        val ctx = getApplication<Application>()
        CommandWorker.watching += tx
        try {
            val id = CommandWorker.enqueue(ctx, v.id, tx, action, v.nickname.ifBlank { "Your car" })
            val info = WorkManager.getInstance(ctx).getWorkInfoByIdFlow(id).first { it?.state?.isFinished == true }
            store.cachedStatus?.let { raw -> runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() }?.let { realStatus = it }
            return info?.outputData?.getString(CommandWorker.KEY_RESULT)
                ?.let { runCatching { CommandResult.valueOf(it) }.getOrNull() }
                ?: CommandResult.TIMEOUT
        } finally {
            CommandWorker.watching -= tx
        }
    }

    fun lock() = command(CommandProgress.Action.LOCK, { it.copy(locked = true) }) { if (isUs) usa.lock(it.id) else api.lock(it.id) }
    fun unlock() = command(CommandProgress.Action.UNLOCK, { it.copy(locked = false) }) { if (isUs) usa.unlock(it.id) else api.unlock(it.id) }
    fun stopClimate() = command(CommandProgress.Action.CLIMATE_STOP, {
        it.copy(climateOn = false, defrost = false, wheelHeat = false, seats = listOf(2, 2, 2, 2))
    }) { if (isUs) usa.stopClimate(it.id) else api.stopClimate(it.id) }

    /** Climate settings waiting for the user to confirm seats that don't suit the weather. */
    var pendingClimate by mutableStateOf<ClimateSettings?>(null)
        private set

    /** Why [settings]' seats don't suit the weather right now, or null. */
    fun seatWarning(settings: ClimateSettings = editor): String? =
        io.github.alamo3.openblue.api.SeatWeather.warning(settings.seats, weather?.takeIf { fresh(it.fetchedAt, 3 * 60) })

    /**
     * Starts climate, unless the seats don't suit the weather (heated on a warm day, cooled on a
     * cold one): then it asks first ([pendingClimate]); [confirmed] skips that.
     */
    fun startClimate(settings: ClimateSettings = editor, confirmed: Boolean = false) {
        if (!confirmed && seatWarning(settings) != null) {
            pendingClimate = settings
            return
        }
        pendingClimate = null
        val fixed = settings.forCar(caps)
        command(CommandProgress.Action.CLIMATE_START, {
            it.copy(
                climateOn = fixed.climateOn, tempC = fixed.tempC, defrost = fixed.defrost,
                wheelHeat = fixed.heating != 0, seats = fixed.seats.map { s -> if (s == 0) 2 else s },
            )
        }) { if (isUs) usa.startClimate(it, fixed) else api.startClimate(it, fixed) }
    }

    // ------------------------------------------------- public charging (read-only)

    var chargeService by mutableStateOf<ChargeService?>(null)
        private set
    var chargeServiceError by mutableStateOf<String?>(null)
        private set
    /** What was sent and received the last time the service was asked, for troubleshooting. */
    val chargeServiceDetails: String? get() = api.lastExchange

    fun loadChargeService(force: Boolean = false) {
        val v = vehicle ?: return
        if (!online || (chargeService != null && !force)) return
        chargeServiceError = null
        launchSafely(onError = { chargeServiceError = describe(it) }) {
            // The service identifies the car by its vehicleKindCode from the vehicle list, as the
            // official app does; sltvhcl may not carry it, so fetch the list if we don't have it.
            var code = v.kindCode
            if (code == null) {
                api.vehicles().firstOrNull { it.id == v.id }?.let { fresh ->
                    store.vehicle = fresh
                    vehicle = fresh
                    code = fresh.kindCode
                }
            }
            if (code == null) code = caps.modelCode
            val r = api.iacServiceInfo(v.id, code, v.year)
            var service = ChargeService.from(r)
            if (service.plans.isEmpty()) {
                // Plans may only come from the subscription call.
                runCatching { api.iacSubscriptions(v.id, code, v.year) }.getOrNull()?.let { list ->
                    service = service.copy(plans = list.map { ChargePlan.from(it) })
                }
            }
            chargeService = service
        }
    }

    /** Stations found around [stationsCenter], nearest first. */
    var stations by mutableStateOf<List<Station>>(emptyList())
        private set
    var stationsLoading by mutableStateOf(false)
        private set
    var stationsError by mutableStateOf<String?>(null)
        private set
    var stationsCenter by mutableStateOf<Pair<Double, Double>?>(null)
        private set
    /** Bumped on every search, so the map reframes around the results. */
    var stationsSearch by mutableStateOf(0)
        private set

    /** Finds stations of every speed (the screen filters AC/DC itself; the server's speed filter isn't reliable). */
    fun searchStations(lat: Double, lon: Double, speeds: String? = null, radiusKm: Int = 15) {
        val v = vehicle ?: return
        if (!online) return
        stationsLoading = true
        stationsError = null
        stationsCenter = lat to lon
        launchSafely(onDone = { stationsLoading = false }, onError = { stationsError = describe(it) }) {
            stations = api.iacStations(v.id, lat, lon, radiusKm, speeds)
                .mapNotNull { Station.from(it) }
                .sortedBy { it.distanceKm ?: Double.MAX_VALUE }
            stationsSearch++
        }
    }

    /** Full details for one station (chargers and connectors), merged into the list. */
    fun loadStation(id: String) {
        val v = vehicle ?: return
        if (!online) return
        launchSafely(onError = { }) {
            val detail = api.iacStation(v.id, id)?.let { Station.from(it) } ?: return@launchSafely
            stations = stations.map { if (it.id == id) detail.copy(distanceKm = it.distanceKm ?: detail.distanceKm) else it }
        }
    }

    /**
     * Developer mode and screenshots: made-up stations around the car (or downtown Toronto),
     * a plan, a card and a month of history, to see the charging screens without the service.
     */
    fun showDemoCharging() {
        val (lat, lon) = location?.let { it.lat to it.lon } ?: (43.6534 to -79.3841)
        chargeService = ChargeService(
            inAppCharging = true, remoteStart = true, stationSearch = true, plugAndCharge = true, routePlanning = true,
            cardRegistered = true,
            cards = listOf(io.github.alamo3.openblue.api.PayCard("Visa", "Visa", "************4242")),
            plans = listOf(ChargePlan("Demo charging plan", "ACTIVE", "20260101", "20271231")),
        )
        fun st(i: Int, name: String, net: String, dLat: Double, dLon: Double, kw: Double, speed: String, free: Int, total: Int, down: Int = 0) = Station(
            id = "demo$i", name = name, address = "${100 + i * 7} Example Street", network = net,
            lat = lat + dLat, lon = lon + dLon, distanceKm = kotlin.math.sqrt(dLat * dLat + dLon * dLon) * 111,
            maxKw = kw, speed = io.github.alamo3.openblue.api.ChargeSpeed.of(speed), available = free, total = total, outOfService = down,
            price = if (speed == "slow") "\$2.00 per hour" else "\$0.45 per kWh", phone = null,
            chargers = (1..total).map { n ->
                io.github.alamo3.openblue.api.Charger(
                    "c$n", "Stall $n",
                    listOf(io.github.alamo3.openblue.api.Connector(listOf(if (speed == "slow") "J1772" else "CCS1"), kw, if (n <= free) "AVAILABLE" else if (n > total - down) "OUTOFORDER" else "CHARGING", null)),
                    null,
                )
            },
        )
        stations = listOf(
            st(1, "Market Square", "ChargePoint", 0.004, 0.003, 7.2, "slow", 2, 4),
            st(2, "Harbour Hub", "Electrify Canada", -0.012, 0.018, 350.0, "ultra", 3, 6),
            st(3, "Union Plaza", "FLO", 0.015, -0.01, 100.0, "rapid", 0, 2),
            st(4, "Library Garage", "ChargePoint", -0.02, -0.022, 7.2, "slow", 0, 2, down = 2),
            st(5, "Highway Services", "Petro-Canada", 0.03, 0.035, 200.0, "ultra", 1, 4),
        ).sortedBy { it.distanceKm }
        stationsCenter = lat to lon
        stationsSearch++
        chargeHistory = ChargeHistory(
            orders = listOf(
                io.github.alamo3.openblue.api.ChargeOrder("1", "20261024183000", "20261024190500", "Harbour Hub", "107 Example Street, Toronto", 41.6, 18.72, "CAD", "4242", null, "COMPLETE"),
                io.github.alamo3.openblue.api.ChargeOrder("2", "20261012120000", "20261012124000", "Highway Services", "135 Example Street, Toronto", 52.3, 23.54, "CAD", "4242", null, "COMPLETE"),
            ),
            kwh = 93.9, total = 42.26,
        )
    }

    // ------------------------------------------------- paid charging sessions

    var offer by mutableStateOf<ChargeOffer?>(null)
        private set
    var offerFor by mutableStateOf<String?>(null)
        private set
    var offerLoading by mutableStateOf(false)
        private set
    var offerError by mutableStateOf<String?>(null)
        private set
    val savedCardId: String? get() = store.payCardId

    /** Station ID the charge is addressed to (Tesla uses the charger's own station ID). */
    private fun poiFor(station: Station, charger: Charger) =
        if (station.isTesla) charger.stationId ?: station.id else station.id

    /** Price and payment details for one charger. */
    fun loadOffer(station: Station, charger: Charger) {
        val v = vehicle ?: return
        val chargerId = charger.id ?: return
        offer = null
        offerError = null
        offerFor = chargerId
        if (!online || dryRunning) {
            offer = demoOffer(charger)
            return
        }
        offerLoading = true
        launchSafely(onDone = { offerLoading = false }, onError = { offerError = describe(it) }) {
            val o = ChargeOffer.from(api.iacOffer(v.id, poiFor(station, charger), chargerId))
            if (offerFor == chargerId) offer = o
        }
    }

    fun clearOffer() {
        offer = null
        offerFor = null
        offerError = null
    }

    var session by mutableStateOf(store.activeSession)
        private set
    var sessionStatus by mutableStateOf<SessionStatus?>(null)
        private set
    var sessionBusy by mutableStateOf(false)
        private set

    /** Starts a paid charge at [charger]. The screen confirms the price and identity first. */
    fun startCharge(station: Station, charger: Charger, cardId: String?, onStarted: () -> Unit) {
        val v = vehicle ?: return
        val o = offer ?: return
        val paymentId = o.paymentId ?: run { message = "The charging service didn't give a payment ID"; return }
        if (sessionBusy) return
        val poi = poiFor(station, charger)
        sessionBusy = true
        launchSafely(onDone = { sessionBusy = false }) {
            val (code, err) = if (dryRunning) { delay(1_200); 0 to null } else api.iacStartCharge(v.id, paymentId, poi, cardId)
            if (code == 0 || code == null && err == null) {
                cardId?.let { store.payCardId = it }
                val s = ActiveSession(paymentId, poi, station.name, charger.reference, System.currentTimeMillis(), o.remoteStop)
                store.activeSession = s
                session = s
                sessionStatus = null
                clearOffer()
                message = "Charging requested at ${station.name}"
                onStarted()
            } else {
                message = "The charger didn't start" + (err?.let { " ($it)" } ?: "")
            }
        }
    }

    /** Fetches live numbers for the running session. */
    fun refreshSession(refresh: Boolean = false) {
        val v = vehicle ?: return
        val s = session ?: return
        if (!online || dryRunning) {
            sessionStatus = demoSessionStatus(s)
            return
        }
        launchSafely(onError = { }) {
            sessionStatus = SessionStatus.from(api.iacSessionStatus(v.id, v.vin, s.paymentId, refresh))
        }
    }

    fun stopCharge() {
        val v = vehicle ?: return
        val s = session ?: return
        if (sessionBusy) return
        sessionBusy = true
        launchSafely(onDone = { sessionBusy = false }) {
            val (code, err) = if (dryRunning) { delay(1_000); 0 to null } else api.iacStopCharge(v.id, s.paymentId, s.poiId)
            if (code == 0 || code == null && err == null) {
                message = "Charging stopped"
                refreshSession(true)
            } else {
                message = "The charger didn't stop" + (err?.let { " ($it)" } ?: "") + ". Unplug or use the charger's screen."
            }
        }
    }

    /** Developer mode's dry run, which also covers paid charging. */
    private val dryRunning get() = developerMode && debug.dryRun

    private fun demoOffer(charger: Charger) = ChargeOffer(
        paymentId = "demo-payment", productId = "demo", productName = "Pay as you go",
        price = null, description = "Demo prices; nothing is charged in a dry run.",
        priceLines = if ((charger.maxKw ?: 0.0) >= 25) listOf("\$0.45 per kWh", "\$0.40 per minute idle after 10 minutes") else listOf("\$2.00 per hour"),
        remoteStart = true, remoteStop = true,
        cards = listOf(io.github.alamo3.openblue.api.PayCardRef("demo-card", io.github.alamo3.openblue.api.PayCard("Visa", "Visa", "************4242"))),
        resultCode = 0, message = null,
    )

    private fun demoSessionStatus(s: ActiveSession): SessionStatus {
        val minutes = ((System.currentTimeMillis() - s.startedAt) / 60_000.0).coerceAtLeast(1.0)
        val kwh = minutes * 2.3
        return SessionStatus(kwh = kwh, powerKw = 138.0, cost = kwh * 0.45, currency = "CAD", battery = (42 + minutes).toInt().coerceAtMost(80), charging = true, minutesLeft = 24)
    }

    /** Screenshots: a session in progress. */
    fun showDemoSession() {
        val s = ActiveSession("demo-payment", "demo2", "Harbour Hub", "Stall 3", System.currentTimeMillis() - 12 * 60_000, true)
        session = s
        sessionStatus = demoSessionStatus(s)
    }

    /** Stops showing the session (it's finished, or was started elsewhere). */
    fun dismissSession() {
        store.activeSession = null
        session = null
        sessionStatus = null
    }

    // ------------------------------------------------- trip planning (iac/rp)

    /** A place to drive to, from the phone's geocoder. */
    data class Place(val name: String, val detail: String?, val lat: Double, val lon: Double)

    /** Route option for trip planning: 22 Recommended, 21 Fastest, 23 Economical (no tolls). Remembered. */
    var routeStyle by mutableStateOf(store.routeStyle)
        private set

    fun changeRouteStyle(code: Int) {
        store.routeStyle = code
        routeStyle = code
    }

    var placeResults by mutableStateOf<List<Place>>(emptyList())
        private set
    var placeSearching by mutableStateOf(false)
        private set
    var tripDestination by mutableStateOf<Place?>(null)
    var trip by mutableStateOf<io.github.alamo3.openblue.api.TripPlan?>(null)
        private set
    var tripLoading by mutableStateOf(false)
        private set
    var tripError by mutableStateOf<String?>(null)
        private set

    /** Looks up places by name with Android's geocoder, biased toward the car. */
    fun searchPlaces(query: String) {
        if (query.isBlank()) return
        val context = getApplication<Application>()
        placeSearching = true
        viewModelScope.launch {
            placeResults = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val g = android.location.Geocoder(context, java.util.Locale.getDefault())
                    val near = location
                    val found = if (near != null) {
                        // About 300 km around the car first, then anywhere.
                        g.getFromLocationName(query, 6, near.lat - 3, near.lon - 4, near.lat + 3, near.lon + 4)?.takeIf { it.isNotEmpty() }
                            ?: g.getFromLocationName(query, 6)
                    } else {
                        g.getFromLocationName(query, 6)
                    }
                    found.orEmpty().map { a ->
                        val street = listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ").ifBlank { null }
                        Place(
                            name = a.featureName?.takeIf { it != a.subThoroughfare } ?: street ?: a.locality ?: query,
                            detail = listOfNotNull(street, a.locality, a.adminArea).distinct().joinToString(", ").ifBlank { null },
                            lat = a.latitude,
                            lon = a.longitude,
                        )
                    }
                }.getOrDefault(emptyList())
            }
            placeSearching = false
            if (placeResults.isEmpty()) message = "No places found for \"$query\""
        }
    }

    fun clearPlaces() {
        placeResults = emptyList()
    }

    /** Plans a trip from the car to [tripDestination] with Hyundai's EV route planner. */
    fun planTrip(chargerSoc: Int, destinationSoc: Int, options: List<Int> = listOf(22)) {
        val v = vehicle ?: return
        val dest = tripDestination ?: return
        val from = location ?: run {
            tripError = "Locate the car first"
            locate()
            return
        }
        tripError = null
        trip = null
        if (!online || dryRunning) {
            demoOptions = options.ifEmpty { listOf(22) }
            showDemoTrip()
            return
        }
        tripLoading = true
        launchSafely(onDone = { tripLoading = false }, onError = { tripError = describe(it) }) {
            val plan = io.github.alamo3.openblue.api.TripPlan.from(
                api.iacRoute(v.id, v.vin, from.lat to from.lon, dest.lat to dest.lon, chargerSoc, destinationSoc, chargeService?.vendorNum ?: 0, options.ifEmpty { listOf(22) }),
            )
            trip = plan
            tripError = when {
                plan.status == 1 -> "The route planner rejected the request (status 1)"
                plan.status == 2 -> "The route planner had an error (status 2)"
                plan.status == 3 -> "No EV route found to there"
                plan.routes.isEmpty() -> "The route planner answered but sent no route"
                else -> null
            }
        }
    }

    /** One route option code tried on its own (developer scan). */
    data class OptionResult(val code: Int, val status: Int?, val route: io.github.alamo3.openblue.api.TripRoute?, val error: String?)

    var scanResults by mutableStateOf<List<OptionResult>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set

    /**
     * Developer: asks the planner for each code in [codes] separately (one bad code can fail a
     * whole request), then shows every route that came back so they can be compared.
     */
    fun scanRouteOptions(chargerSoc: Int, destinationSoc: Int, codes: List<Int>) {
        val v = vehicle ?: return
        val dest = tripDestination ?: return
        val from = location ?: run { tripError = "Locate the car first"; locate(); return }
        if (scanning || codes.isEmpty()) return
        tripError = null
        trip = null
        scanResults = emptyList()
        scanning = true
        viewModelScope.launch {
            val results = mutableListOf<OptionResult>()
            for (code in codes) {
                val r = try {
                    if (!online || dryRunning) {
                        delay(150)
                        demoOptions = listOf(code)
                        showDemoTrip()
                        OptionResult(code, 0, trip?.routes?.firstOrNull(), null)
                    } else {
                        val plan = io.github.alamo3.openblue.api.TripPlan.from(
                            api.iacRoute(v.id, v.vin, from.lat to from.lon, dest.lat to dest.lon, chargerSoc, destinationSoc, chargeService?.vendorNum ?: 0, listOf(code)),
                        )
                        OptionResult(code, plan.status, plan.routes.firstOrNull()?.copy(option = code), null)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    OptionResult(code, null, null, describe(e))
                }
                results += r
                scanResults = results.toList()
            }
            trip = io.github.alamo3.openblue.api.TripPlan(0, results.mapNotNull { it.route }, null)
            if (trip?.routes.isNullOrEmpty()) tripError = "None of the codes returned a route"
            scanning = false
        }
    }

    /** Screenshots and dry run: a made-up trip with two charging stops. */
    fun showDemoTrip() {
        val (lat, lon) = location?.let { it.lat to it.lon } ?: (43.6534 to -79.3841)
        if (tripDestination == null) tripDestination = Place("Demo destination", "About 450 km away", lat + 0.8, lon - 4.5)
        val dest = tripDestination!!
        val pts = (0..40).map { i -> val f = i / 40.0; (lat + (dest.lat - lat) * f + 0.15 * kotlin.math.sin(f * 3)) to (lon + (dest.lon - lon) * f) }
        fun stop(i: Int, name: String, net: String, a: Int, b: Int, km: Int) = io.github.alamo3.openblue.api.TripStop(
            name, net, "demo$i", pts[i].first, pts[i].second, a, b, 22 * 60L, km * 40, km * 1000, 350,
        )
        trip = io.github.alamo3.openblue.api.TripPlan(
            status = 0,
            reachedDestination = true,
            routes = demoOptions.mapIndexed { n, opt ->
                io.github.alamo3.openblue.api.TripRoute(
                    option = opt,
                    distanceM = 452_000 + n * 23_000, seconds = 5 * 3600 + 20 * 60 + n * 900, fee = 31 - n * 2,
                    stops = listOf(stop(14, "Highway Services", "Electrify Canada", 18, 75, 160), stop(29, "Lakeside Plaza", "Petro-Canada", 21, 70, 325)),
                    line = listOf(0 to pts.take(15), 1 to pts.subList(14, 21), 0 to pts.drop(20)),
                    arriveSoc = 34, unreachable = 0,
                )
            },
        )
    }

    /** Route option codes the demo trip answers (dry run uses what the screen asked for). */
    private var demoOptions = listOf(22)

    var chargeHistory by mutableStateOf<ChargeHistory?>(null)
        private set
    var chargeHistoryMonth by mutableStateOf(java.time.YearMonth.now())
        private set
    var chargeHistoryLoading by mutableStateOf(false)
        private set
    var chargeHistoryError by mutableStateOf<String?>(null)
        private set

    fun loadChargeHistory(month: java.time.YearMonth = chargeHistoryMonth) {
        val v = vehicle ?: return
        chargeHistoryMonth = month
        if (!online) return
        val profile = store.username ?: return
        chargeHistoryLoading = true
        chargeHistoryError = null
        val f = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
        launchSafely(onDone = { chargeHistoryLoading = false }, onError = { chargeHistoryError = describe(it) }) {
            val r = api.iacOrders(v.id, profile, month.atDay(1).format(f), month.atEndOfMonth().format(f))
            if (chargeHistoryMonth == month) chargeHistory = ChargeHistory.from(r)
        }
    }

    // ------------------------------------------------------- Bluelink Store

    /** The store page to open: where, and the token it takes as its cookie. */
    data class StorePage(val url: String?, val token: String, val expiresIn: String?, val otherHosts: List<String>)

    var storePage by mutableStateOf<StorePage?>(null)
        private set
    var storeLoading by mutableStateOf(false)
        private set
    var storeError by mutableStateOf<String?>(null)
        private set

    /**
     * Gets a store token for the car and works out the store's address: the one in the car's
     * details (English or French to match the phone), else the token's reroute address, else
     * Hyundai Canada's store (commerce.hyundai.com/ca/…, where Bluelink tokens sign in). The
     * official app hides the store unless the car lists "Fod"; this asks anyway.
     */
    fun openStore() {
        val v = vehicle ?: return
        storeError = null
        if (!online) {
            if (storePage == null) storeError = "The demo has no Bluelink account, so there's no store to open."
            return
        }
        storeLoading = true
        launchSafely(onDone = { storeLoading = false }, onError = { storePage = null; storeError = describe(it) }) {
            val access = api.storeAccess(v.id)
            val french = java.util.Locale.getDefault().language == "fr"
            fun pick(en: String?, fr: String?) = if (french) fr ?: en else en ?: fr
            val listed = pick(caps.storeUrlEn, caps.storeUrlFr)
                ?: runCatching { api.storeUrls(v.id) }.getOrNull()?.let { (en, fr) -> pick(en, fr) }
            // Hyundai Canada's store, which answers for cars it doesn't list it for too.
            val canada = "https://commerce.hyundai.com/ca/${if (french) "fr" else "en"}/commerce/fod"
            val url = listed ?: access.rerouteUrl ?: canada
            storePage = StorePage(url, access.token, access.expiresIn, listOfNotNull(access.rerouteUrl).filter { it != url })
        }
    }

    /** Opens the store at an address typed in by hand, with the token already fetched. */
    fun openStoreAt(url: String) {
        val p = storePage ?: return
        val u = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        storePage = p.copy(url = u)
    }

    /** Demo and screenshots: the store token came back without an address. */
    fun showDemoStoreWithoutAddress() {
        storeError = null
        storePage = StorePage(null, "demo", null, emptyList())
    }

    fun closeStore() {
        storePage = null
        storeError = null
        storeCheck = null
    }

    /** Developer mode: what Bluelink says about this car that decides what the store offers. */
    var storeCheck by mutableStateOf<List<Pair<String, String>>?>(null)
        private set

    fun loadStoreCheck() {
        val v = vehicle ?: return
        if (!online) return
        launchSafely {
            val raw = api.selectVehicle(v.id)
            val entry = runCatching { api.vehicleListEntry(v.id) }.getOrNull()
            val car = raw["vehicle"] as? JsonObject
            fun f(o: JsonObject?, k: String) = o?.get(k)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.ifBlank { null }
            val featureNames = (raw["featuresModel"] as? JsonObject)?.get("features")?.let { it as? kotlinx.serialization.json.JsonArray }
                ?.flatMap { cat -> ((cat as? JsonObject)?.get("features") as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { f(it as? JsonObject, "featureName") } }
                .orEmpty()
            val keywords = Regex("fod|store|theme|ota|update|profile|ccnc|digital|commerce", RegexOption.IGNORE_CASE)
            // Any field in the vehicle details or list entry whose name mentions the store.
            val storeFields = listOfNotNull(car, entry).flatMap { o ->
                o.entries.filter { (k, _) -> keywords.containsMatchIn(k) }
                    .map { (k, value) -> k to ((value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: value.toString()) }
            }.distinct()
            val rows = mutableListOf<Pair<String, String>>()
            rows += "Car" to listOfNotNull(f(car, "modelName"), f(car, "modelYear"), f(car, "trim")).joinToString(" · ")
            rows += "Model code" to listOfNotNull(f(car, "modelCode"), f(car, "vehicleKindCode")).joinToString(" / ")
            rows += "Generation" to (f(car, "genType") ?: "?") + (caps.genHeader?.takeIf { it.isNotEmpty() }?.let { " (header $it)" } ?: "")
            rows += "Bluelink subscription" to "${f(car, "subscriptionStatus") ?: "?"}, ends ${f(car, "subscriptionEndDate") ?: "?"}"
            rows += "Enrollment status" to (f(car, "enrollmentStatus") ?: "?")
            rows += "Lists the store (Fod)" to if ("Fod" in featureNames) "yes" else "no"
            rows += "Store address (English)" to (f(car, "fodWebManualUrlEn") ?: f(entry, "fodWebManualUrlEn") ?: "none")
            rows += "Store address (French)" to (f(car, "fodWebManualUrlFr") ?: f(entry, "fodWebManualUrlFr") ?: "none")
            val page = storePage
            rows += "Opened" to (page?.url ?: "nothing yet")
            rows += "Store token" to (page?.let { "handed out, lasts ${it.expiresIn ?: "?"} s" } ?: "not asked for yet")
            val related = featureNames.filter { keywords.containsMatchIn(it) }
            rows += "Related features" to (related.joinToString(", ").ifEmpty { "none" })
            if (storeFields.isNotEmpty()) rows += "Related fields" to storeFields.joinToString("\n") { (k, value) -> "$k = ${value.take(120)}" }
            rows += "All features" to featureNames.joinToString(", ").ifEmpty { "none listed" }
            storeCheck = rows
        }
    }

    // ------------------------------------------------------- developer: endpoint probe

    var probeResults by mutableStateOf<List<BluelinkClient.ProbeResult>>(emptyList())
        private set
    var probing by mutableStateOf(false)
        private set

    /**
     * Posts a send-to-car style body to each path in turn and records what comes back.
     * The destination is the car's own location (or the CN Tower), so a hit is harmless.
     */
    fun probeEndpoints(paths: List<String>, authorise: Boolean) {
        val v = vehicle ?: return
        if (!online || probing || paths.isEmpty()) return
        val (lat, lon) = location?.let { it.lat to it.lon } ?: (43.6426 to -79.3871)
        val body = kotlinx.serialization.json.buildJsonObject {
            put("vin", kotlinx.serialization.json.JsonPrimitive(v.vin))
            put("userName", kotlinx.serialization.json.JsonPrimitive(store.username.orEmpty()))
            store.pin?.let { put("pin", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("actionType", kotlinx.serialization.json.JsonPrimitive("SEND"))
            put("poiInfoList", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive("Open Blue test"))
                    put("addr", kotlinx.serialization.json.JsonPrimitive(""))
                    put("src", kotlinx.serialization.json.JsonPrimitive("HERE"))
                    put("waypointID", kotlinx.serialization.json.JsonPrimitive(0))
                    put("lang", kotlinx.serialization.json.JsonPrimitive(1))
                    put("coord", kotlinx.serialization.json.buildJsonObject {
                        put("lat", kotlinx.serialization.json.JsonPrimitive(lat))
                        put("lon", kotlinx.serialization.json.JsonPrimitive(lon))
                        put("alt", kotlinx.serialization.json.JsonPrimitive(0))
                        put("type", kotlinx.serialization.json.JsonPrimitive(0))
                    })
                })
            })
        }
        probing = true
        probeResults = emptyList()
        viewModelScope.launch {
            val out = mutableListOf<BluelinkClient.ProbeResult>()
            for (p in paths) {
                out += try {
                    api.probe(p, v.id, body, authorise)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BluelinkClient.ProbeResult(p, -1, null, null, describe(e), "")
                }
                probeResults = out.toList()
                delay(400)
            }
            probing = false
        }
    }

    /** The car's last known location; [locate] asks the car for a fresh one. */
    var location by mutableStateOf(store.lastLocation)
        private set

    fun dismissPendingClimate() { pendingClimate = null }

    // ------------------------------------------------------------- weather and place

    /** The weather where the car was last seen (or, failing that, where the phone is). */
    var weather by mutableStateOf(store.lastWeather)
        private set
    /** Street and town of the car's last known location, when the phone can look it up. */
    var place by mutableStateOf<String?>(null)
        private set
    /** True when [weather] is for the phone's location, not the car's. */
    var weatherNearPhone by mutableStateOf(false)
        private set
    private val weatherClient by lazy { io.github.alamo3.openblue.api.WeatherClient() }
    private var weatherJob: Job? = null

    init {
        // (Here, after the properties it uses: initialisers run in order.)
        if (online && screen == Screen.Main) refreshWeather()
    }

    private fun fresh(at: Long, minutes: Int) = System.currentTimeMillis() - at < minutes * 60_000L

    /** Refreshes the weather (at most every 15 minutes unless [force]) and the place name. */
    fun refreshWeather(force: Boolean = false) {
        if (!online || weatherJob?.isActive == true) return
        val w = weather
        val car = location
        weatherJob = viewModelScope.launch {
            if (car != null) place = withContext(Dispatchers.IO) { placeName(car.lat, car.lon) }
            if (!force && w != null && fresh(w.fetchedAt, 15)) return@launch
            val (at, nearPhone) = when {
                car != null -> (car.lat to car.lon) to false
                else -> (withContext(Dispatchers.IO) { phoneLocation() } ?: return@launch) to true
            }
            runCatching { withContext(Dispatchers.IO) { weatherClient.current(at.first, at.second) } }
                .onSuccess { weather = it; weatherNearPhone = nearPhone; store.lastWeather = it; updateWidgets() }
                .onFailure { android.util.Log.w("OpenBlue", "Weather unavailable", it) }
        }
    }

    private fun placeName(lat: Double, lon: Double): String? {
        val ctx = getApplication<Application>()
        if (!android.location.Geocoder.isPresent()) return null
        return runCatching {
            @Suppress("DEPRECATION")
            android.location.Geocoder(ctx, java.util.Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
                listOfNotNull(
                    listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ").takeIf { it.isNotBlank() },
                    a.locality,
                ).joinToString(", ").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** The phone's last known position, only if location is already allowed (never asks). */
    @android.annotation.SuppressLint("MissingPermission")
    private fun phoneLocation(): Pair<Double, Double>? {
        val ctx = getApplication<Application>()
        val ok = listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION).any {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!ok) return null
        val lm = ctx.getSystemService(android.location.LocationManager::class.java) ?: return null
        return runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

    /** Screenshots: a made-up weather reading. */
    fun showDemoWeather(tempC: Double, code: Int = 2) {
        weather = io.github.alamo3.openblue.api.Weather(tempC, tempC - 2, code, true, System.currentTimeMillis())
        place = "100 Queen St W, Toronto"
    }

    fun locate() {
        val v = vehicle ?: return
        if (!canAct || !online) return
        val action = CommandProgress.Action.LOCATE
        if (developerMode && debug.dryRun) {
            dryRun(action) { it }
            if (location == null) location = io.github.alamo3.openblue.api.CarLocation(43.6534, -79.3841, 90.0, fetchedAt = System.currentTimeMillis())
            return
        }
        showProgress(CommandProgress(action, CommandProgress.Stage.WAITING, "This can take up to a minute."))
        launchSafely(onError = { finish(action, false, describe(it)) }) {
            val loc = if (isUs) usa.locate(v.id) else api.locate(v.id, isEv = v.fuelKindCode.equals("E", ignoreCase = true))
            store.lastLocation = loc
            location = loc
            finish(action, true)
            refreshWeather(force = true)
        }
    }

    fun hornLights(horn: Boolean) = command(if (horn) CommandProgress.Action.HORN else CommandProgress.Action.LIGHTS) {
        if (isUs) usa.hornLights(it.id, horn) else api.hornLights(it.id, horn)
    }

    fun chargePort(open: Boolean) = command(
        if (open) CommandProgress.Action.PORT_OPEN else CommandProgress.Action.PORT_CLOSE,
        { it.copy(chargePortOpen = open) },
    ) { if (isUs) throw ApiException(null, "Charge-port control isn't available for Hyundai USA yet") else api.chargePort(it.id, open) }

    fun batteryPreconditioning(on: Boolean) = command(
        if (on) CommandProgress.Action.PRECONDITION_ON else CommandProgress.Action.PRECONDITION_OFF,
        { it.copy(preconditioning = on) },
    ) { if (isUs) throw ApiException(null, "Battery preconditioning isn't available for Hyundai USA yet") else api.batteryPreconditioning(it.id, on) }

    /** Saves AC and DC charge limits (percent, steps of 10). */
    fun setChargeLimits(ac: Int, dc: Int) {
        val v = vehicle ?: return
        if (!canAct) return
        val action = CommandProgress.Action.CHARGE_LIMITS
        if (developerMode && debug.dryRun) {
            dryRun(action) { it.copy(limitAc = ac, limitDc = dc) }
            return
        }
        showProgress(CommandProgress(action, CommandProgress.Stage.SENDING))
        launchSafely(onError = { finish(action, false, describe(it)) }) {
            val tx: String? = if (isUs) usa.setChargeLimits(v.id, ac, dc) else api.setChargeLimits(v.id, ac, dc)
            if (tx != null) {
                showProgress(CommandProgress(action, CommandProgress.Stage.WAITING))
                when (awaitCommand(v, tx, action)) {
                    CommandResult.SUCCESS -> finish(action, true)
                    CommandResult.FAILED -> finish(action, false, "The car didn't accept the new limits.")
                    CommandResult.TIMEOUT -> finish(action, false, "No answer from the car yet. It may still go through, so check again in a minute.")
                }
            } else {
                finish(action, true, "The car picks them up next time it connects.")
                try {
                    applyStatus(fetchCached(v.id))
                } catch (e: ApiException) {
                    // The limits are saved; the status will catch up on the next refresh.
                }
            }
        }
    }

    // ------------------------------------------------------------ schedules

    /** Schedules kept on Bluelink's servers; null until loaded. */
    var climateSchedule by mutableStateOf<ClimateSchedule?>(null)
        private set
    var chargeSchedule by mutableStateOf<ChargeWindow?>(null)
        private set
    var schedulesLoading by mutableStateOf(false)
        private set
    /** Why the schedules couldn't be read, per kind (the car may not support one of them). */
    var climateScheduleError by mutableStateOf<String?>(null)
        private set
    var chargeScheduleError by mutableStateOf<String?>(null)
        private set

    val hasChargeSchedule: Boolean get() = vehicle?.fuelKindCode.equals("E", ignoreCase = true)

    fun loadSchedules() {
        val v = vehicle ?: return
        if (!online || schedulesLoading) return
        schedulesLoading = true
        viewModelScope.launch {
            try {
                try {
                    climateSchedule = api.climateSchedule(v.id, tempTable)
                    climateScheduleError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    climateScheduleError = describe(e)
                }
                if (hasChargeSchedule) {
                    try {
                        chargeSchedule = api.chargeSchedule(v.id) ?: ChargeWindow()
                        chargeScheduleError = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        chargeScheduleError = describe(e)
                    }
                }
            } finally {
                schedulesLoading = false
            }
        }
    }

    fun saveClimateSchedule(schedule: ClimateSchedule) = saveSchedule(CommandProgress.Action.SCHEDULE_CLIMATE) { v ->
        val tx = api.saveClimateSchedule(v.id, schedule, tempTable)
        climateSchedule = schedule
        tx
    }

    fun saveChargeSchedule(window: ChargeWindow) = saveSchedule(CommandProgress.Action.SCHEDULE_CHARGE) { v ->
        val tx = api.saveChargeSchedule(v.id, window)
        chargeSchedule = window
        tx
    }

    private fun saveSchedule(action: CommandProgress.Action, send: suspend (VehicleInfo) -> String?) {
        val v = vehicle ?: return
        if (!canAct) return
        showProgress(CommandProgress(action, CommandProgress.Stage.SENDING))
        launchSafely(onError = { finish(action, false, describe(it)) }) {
            val tx = send(v)
            if (tx != null) {
                showProgress(CommandProgress(action, CommandProgress.Stage.WAITING))
                when (awaitCommand(v, tx, action)) {
                    CommandResult.SUCCESS -> finish(action, true)
                    CommandResult.FAILED -> finish(action, false, "The car didn't accept the schedule.")
                    CommandResult.TIMEOUT -> finish(action, false, "No answer from the car yet. It may still go through; reopen this screen in a minute to check.")
                }
            } else {
                finish(action, true)
            }
            loadSchedules()
        }
    }

    // ------------------------------------------------------------ developer mode

    /** Plays a command's progress locally and applies its effect to the simulated car. */
    private fun dryRun(action: CommandProgress.Action, effect: (DebugState) -> DebugState) {
        showProgress(CommandProgress(action, CommandProgress.Stage.SENDING, "Dry run: the car isn't contacted."))
        viewModelScope.launch {
            delay(900)
            showProgress(CommandProgress(action, CommandProgress.Stage.WAITING, "Dry run: the car isn't contacted."))
            delay(1_800)
            debug = effect(debug)
            finish(action, true, "Dry run")
        }
    }

    fun changeDeveloperMode(on: Boolean) {
        store.developerMode = on
        developerMode = on
        if (!on) debug = DebugState()
    }

    fun updateDebug(block: (DebugState) -> DebugState) {
        debug = block(debug)
    }

    /** Shows the progress strip in a given state, to check how it looks. */
    fun previewProgress(stage: CommandProgress.Stage) {
        val action = CommandProgress.Action.CLIMATE_START
        when (stage) {
            CommandProgress.Stage.DONE -> finish(action, true)
            CommandProgress.Stage.FAILED -> finish(action, false, "Preview of a failure message.")
            else -> showProgress(CommandProgress(action, stage))
        }
    }

    fun clearProgressPreview() {
        clearProgressJob?.cancel()
        progress = null
    }

    /** Map heating1 onto what this car accepts: split cars use 2/3/4, others a single on (1)/off (0). */
    fun updateEditor(block: (ClimateSettings) -> ClimateSettings) {
        editor = block(editor)
        store.lastClimate = editor
    }

    fun cycleSeat(index: Int) = updateEditor { e -> e.withSeat(index, caps.nextSeatLevel(index, e.seats[index]).code) }

    // --------------------------------------------------------- surround view

    fun loadSurroundView(force: Boolean = false) {
        val v = vehicle ?: return
        if (svmLoaded && !force) return
        // Archived photos show straight away, even offline.
        if (svm.isEmpty() && archive != null) {
            viewModelScope.launch { svm = withContext(Dispatchers.IO) { archive.list(v.id) } }
        }
        if (!online) {
            svmLoaded = true
            return
        }
        if (force) refreshing = true
        launchSafely(onDone = { svmLoaded = true; refreshing = false }) {
            keepSurroundView(v.id, api.lastSurroundView(v.id))
        }
    }

    /** Archives [latest] and shows everything we have. */
    private suspend fun keepSurroundView(vehicleId: String, latest: List<SvmCapture>) {
        svm = if (archive != null) {
            withContext(Dispatchers.IO) {
                archive.add(vehicleId, latest)
                archive.list(vehicleId)
            }
        } else {
            latest.map { SvmShot.of(it) }
        }
    }

    /** Asks the car for new photos, then polls until a capture newer than the last one shows up. */
    fun captureSurroundView() {
        val v = vehicle ?: return
        if (!canAct) return
        if (developerMode && debug.dryRun) {
            dryRun(CommandProgress.Action.CAMERA) { it }
            return
        }
        val action = CommandProgress.Action.CAMERA
        showProgress(CommandProgress(action, CommandProgress.Stage.SENDING))
        launchSafely(onError = { finish(action, false, describe(it)) }) {
            val before = svm.firstOrNull()?.utcTime
            api.requestSurroundView(v.id)
            showProgress(CommandProgress(action, CommandProgress.Stage.WAITING, "This usually takes about a minute."))
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline) {
                delay(10_000)
                val latest = try {
                    api.lastSurroundView(v.id)
                } catch (e: ApiException) {
                    continue
                }
                if (latest.isNotEmpty() && latest.first().utcTime != before) {
                    keepSurroundView(v.id, latest)
                    svmLoaded = true
                    finish(action, true)
                    return@launchSafely
                }
            }
            finish(action, false, "The car hasn't sent photos yet. Pull down on this screen in a minute to check again.")
        }
    }

    /** Removes a set of photos from this phone's archive (Bluelink keeps its own latest copy). */
    fun deleteSurroundView(shot: SvmShot) {
        val v = vehicle ?: return
        val time = shot.utcTime ?: return
        viewModelScope.launch {
            svm = withContext(Dispatchers.IO) {
                archive?.remove(v.id, time)
                archive?.list(v.id) ?: svm.filter { it !== shot }
            }
        }
    }

    /** Saves the photos (all views, or just [only]) to the phone's gallery. */
    fun exportSurroundView(shot: SvmShot, only: String? = null) {
        if (!Gallery.supported) {
            message = "Saving to the gallery needs Android 10 or newer"
            return
        }
        val context = getApplication<Application>()
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val views = shot.views().filter { only == null || it.first == only }
                    Gallery.save(context, shot.takenAt, views)
                }.getOrDefault(0)
            }
            message = when (saved) {
                0 -> "Couldn't save to the gallery"
                1 -> "Saved to Pictures/Open Blue"
                else -> "Saved $saved photos to Pictures/Open Blue"
            }
        }
    }

    // --------------------------------------------------------------- presets

    fun loadPresets() {
        val v = vehicle ?: return
        task("Loading presets") { serverPresets = if (isUs) emptyList() else api.serverPresets(v.id, tempTable) }
    }

    fun saveLocalPreset(name: String) {
        val p = editor.copy(name = name.trim().ifEmpty { "Preset ${localPresets.size + 1}" })
        localPresets = localPresets.filterNot { it.name == p.name } + p
        store.localPresets = localPresets
        message = "Saved \"${p.name}\""
    }

    fun deleteLocalPreset(p: ClimateSettings) {
        localPresets = localPresets - p
        store.localPresets = localPresets
        message = "Deleted \"${p.name}\""
    }

    fun editPreset(p: ClimateSettings) {
        updateEditor { p }
        message = "Loaded \"${p.name}\" into the controls above"
    }

    // -------------------------------------------------------------- settings

    fun setFormat(f: ClimateFormat) {
        store.climateFormat = f
        climateFormat = f
    }

    fun changeConfirmUnlock(on: Boolean) {
        store.confirmUnlock = on
        confirmUnlock = on
    }

    // ------------------------------------------------------- 3D surroundings

    class SurroundResult(val glb: ByteArray, val vehicles: List<io.github.alamo3.openblue.svm3d.Vehicle>)
    data class SurroundState(
        val stage: String? = null,
        val progress: Float = 0f,
        val error: String? = null,
        val needsDownload: Boolean = false,
        val downloadBytes: Long = 0,
        val scene: SurroundResult? = null,
    )

    var surround by mutableStateOf(SurroundState())
        private set
    private var surroundShot: SvmShot? = null
    private var surroundJob: Job? = null
    private val modelStore by lazy { io.github.alamo3.openblue.svm3d.ModelStore(java.io.File(getApplication<Application>().filesDir, "models")) }
    // v7: + a full panorama (pano.jpg) for generating a 3D world.
    private fun surroundCache(shot: SvmShot) = java.io.File(getApplication<Application>().filesDir, "svm3d/v7_${shot.utcTime ?: "latest"}")

    /** Picks which set of photos the 3D view is for. */
    fun openSurround3d(shot: SvmShot) {
        if (surroundShot !== shot) {
            surroundJob?.cancel()
            surroundShot = shot
            surround = SurroundState()
        }
    }

    /** Loads the saved 3D scene, or builds it (asking to download the models first if needed). */
    fun prepareSurround3d() {
        val shot = surroundShot ?: return
        if (surround.scene != null || surroundJob?.isActive == true) return
        if (!online) return
        surroundJob = viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { runCatching { loadSurround(surroundCache(shot)) }.getOrNull() }
            if (cached != null) {
                surround = SurroundState(scene = cached)
                return@launch
            }
            if (!modelStore.ready) {
                surround = SurroundState(needsDownload = true, downloadBytes = modelStore.missingBytes)
                return@launch
            }
            buildSurround(shot)
        }
    }

    fun downloadSurroundModels() {
        val shot = surroundShot ?: return
        surroundJob?.cancel()
        surroundJob = viewModelScope.launch {
            surround = SurroundState(stage = "Downloading the models", progress = 0f)
            try {
                withContext(Dispatchers.IO) {
                    modelStore.download { f -> viewModelScope.launch { surround = surround.copy(progress = f * 0.99f) } }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                surround = SurroundState(needsDownload = true, downloadBytes = modelStore.missingBytes, error = "Download failed: ${e.message}")
                message = "Couldn't download the 3D models. Check your connection and try again."
                return@launch
            }
            buildSurround(shot)
        }
    }

    private suspend fun buildSurround(shot: SvmShot) {
        surround = SurroundState(stage = "Reading the photos", progress = 0f)
        try {
            val result = withContext(Dispatchers.Default) {
                val cams = mapOf(
                    "Front" to io.github.alamo3.openblue.svm3d.SvmCalibration.FRONT,
                    "Rear" to io.github.alamo3.openblue.svm3d.SvmCalibration.REAR,
                    "Left" to io.github.alamo3.openblue.svm3d.SvmCalibration.LEFT,
                    "Right" to io.github.alamo3.openblue.svm3d.SvmCalibration.RIGHT,
                )
                val frames = shot.views().mapNotNull { (name, bmp) ->
                    val cam = cams[name] ?: return@mapNotNull null
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    for (i in px.indices) px[i] = px[i] and 0xFFFFFF
                    io.github.alamo3.openblue.svm3d.Fisheye(cam, bmp.width, bmp.height, px)
                }
                if (frames.size < 4) error("These photos don't have all four camera views.")
                if (frames.any { it.width != io.github.alamo3.openblue.svm3d.SvmCalibration.FRAME_WIDTH || it.height != io.github.alamo3.openblue.svm3d.SvmCalibration.FRAME_HEIGHT }) {
                    error("These photos are a different size from the ones the cameras were calibrated with.")
                }
                val depth = io.github.alamo3.openblue.svm3d.OnnxDepth(modelStore.file(io.github.alamo3.openblue.svm3d.ModelStore.DEPTH))
                val detector = runCatching { io.github.alamo3.openblue.svm3d.OnnxVehicles(modelStore.file(io.github.alamo3.openblue.svm3d.ModelStore.DETECTOR)) }.getOrNull()
                val scene = try {
                    io.github.alamo3.openblue.svm3d.SurroundBuilder.run(frames, depth, detector) { stage, f ->
                        viewModelScope.launch { surround = surround.copy(stage = stage, progress = f * 0.8f) }
                    }
                } finally {
                    depth.close(); detector?.close()
                }
                // Paint in the floor and the far surroundings no camera saw (one model at a time, to
                // stay within memory).
                var ground = scene.ground
                var backdrop: io.github.alamo3.openblue.svm3d.Backdrop? = null
                val lama = if (modelStore.has(io.github.alamo3.openblue.svm3d.ModelStore.INPAINT)) {
                    runCatching { io.github.alamo3.openblue.svm3d.OnnxInpaint(modelStore.file(io.github.alamo3.openblue.svm3d.ModelStore.INPAINT)) }
                        .onFailure { android.util.Log.w("OpenBlue", "Inpainting model unavailable", it) }.getOrNull()
                } else null
                try {
                    if (lama != null) {
                        viewModelScope.launch { surround = surround.copy(stage = "Painting in the floor the cameras couldn't see", progress = 0.8f) }
                        runCatching {
                            io.github.alamo3.openblue.svm3d.FloorFill.fill(scene, lama) { f ->
                                viewModelScope.launch { surround = surround.copy(progress = 0.8f + 0.1f * f) }
                            }
                        }.onSuccess { ground = it }.onFailure { android.util.Log.w("OpenBlue", "Floor fill failed; keeping the plain fill", it) }
                    }
                    viewModelScope.launch { surround = surround.copy(stage = "Stitching the far surroundings", progress = 0.9f) }
                    backdrop = runCatching {
                        io.github.alamo3.openblue.svm3d.Backdrop.build(frames, scene.backdropBlocked, lama) { f ->
                            viewModelScope.launch { surround = surround.copy(progress = 0.9f + 0.08f * f) }
                        }
                    }.onFailure { android.util.Log.w("OpenBlue", "Backdrop failed", it) }.getOrNull()
                } finally {
                    lama?.close()
                }
                val n = scene.groundSize
                val argb = IntArray(n * n) { ground[it] or (0xFF shl 24) }
                val bmp = Bitmap.createBitmap(argb, n, n, Bitmap.Config.ARGB_8888)
                val jpeg = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
                val backdropJpeg = backdrop?.let { bd ->
                    val bb = Bitmap.createBitmap(IntArray(bd.rgb.size) { bd.rgb[it] or (0xFF shl 24) }, bd.width, bd.height, Bitmap.Config.ARGB_8888)
                    java.io.ByteArrayOutputStream().also { bb.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                }
                val glb = io.github.alamo3.openblue.svm3d.SceneGlb.write(scene, jpeg, backdropImage = backdropJpeg)
                val res = SurroundResult(glb, scene.vehicles)
                runCatching { saveSurround(surroundCache(shot), res) }
                // The whole surroundings as one 360° picture, for World Labs (only sent if asked).
                backdrop?.let { bd ->
                    runCatching {
                        val pano = io.github.alamo3.openblue.svm3d.Panorama.compose(frames, bd, ground, scene.groundSize, scene.groundExtent)
                        val pb = Bitmap.createBitmap(IntArray(pano.size) { pano[it] or (0xFF shl 24) }, io.github.alamo3.openblue.svm3d.Panorama.WIDTH, io.github.alamo3.openblue.svm3d.Panorama.HEIGHT, Bitmap.Config.ARGB_8888)
                        java.io.FileOutputStream(panoFile(shot)).use { pb.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    }.onFailure { android.util.Log.w("OpenBlue", "Panorama failed", it) }
                }
                res
            }
            surround = SurroundState(scene = result)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.e("OpenBlue", "3D build failed", e)
            surround = SurroundState(stage = "Couldn't build the 3D view", error = e.message ?: e.javaClass.simpleName)
        } catch (e: OutOfMemoryError) {
            surround = SurroundState(stage = "Couldn't build the 3D view", error = "The phone ran out of memory.")
        }
    }

    private fun saveSurround(base: java.io.File, r: SurroundResult) {
        base.parentFile?.mkdirs()
        base.parentFile?.listFiles { f -> !f.name.startsWith("v7_") }?.forEach { it.delete() }  // older formats
        java.io.File(base.path + ".glb").writeBytes(r.glb)
        java.io.File(base.path + ".txt").writeText(r.vehicles.joinToString("\n") { "${it.x} ${it.y} ${it.heading} ${it.length} ${it.height} ${it.kind}" })
    }

    private fun loadSurround(base: java.io.File): SurroundResult? {
        val g = java.io.File(base.path + ".glb"); val t = java.io.File(base.path + ".txt")
        if (!g.exists() || !t.exists()) return null
        val vs = t.readLines().filter { it.isNotBlank() }.map { l ->
            val p = l.split(" ")
            io.github.alamo3.openblue.svm3d.Vehicle(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5])
        }
        return SurroundResult(g.readBytes(), vs)
    }

    // ------------------------------------------------------- 3D world from World Labs (experiment)

    private fun panoFile(shot: SvmShot) = java.io.File(surroundCache(shot).path + ".pano.jpg")
    private fun worldKey(shot: SvmShot) = surroundCache(shot).name

    var worldLabsKeySet by mutableStateOf(online && store.worldLabsKey != null)
        private set

    fun changeWorldLabsKey(key: String?) {
        if (!online) return
        store.worldLabsKey = key
        worldLabsKeySet = store.worldLabsKey != null
    }

    /** The generation for the photos in the 3D view, if one was asked for. */
    var worldJob by mutableStateOf<io.github.alamo3.openblue.work.WorldJob?>(null)
        private set

    /** True when the 3D view's photos have a panorama to send. */
    val canMakeWorld: Boolean get() = surroundShot?.let { online && panoFile(it).exists() } == true

    fun refreshWorldJob() {
        val shot = surroundShot ?: return
        if (!online) return
        worldJob = io.github.alamo3.openblue.work.WorldJobs.get(getApplication(), worldKey(shot))
    }

    /** Sends the panorama to World Labs (billed to the user's key) and follows it in the background. */
    fun makeWorld() {
        val shot = surroundShot ?: return
        if (!online || store.worldLabsKey == null) return
        val f = panoFile(shot)
        if (!f.exists()) { message = "Open the 3D view once more so it can make the panorama."; return }
        val key = worldKey(shot)
        val job = io.github.alamo3.openblue.work.WorldJob("sending")
        io.github.alamo3.openblue.work.WorldJobs.put(getApplication(), key, job)
        worldJob = job
        io.github.alamo3.openblue.work.WorldWorker.enqueue(getApplication(), key, f.path)
    }

    /** Screenshots: a world being generated, or ready. */
    fun showDemoWorld(job: io.github.alamo3.openblue.work.WorldJob?, keySet: Boolean = true) {
        worldJob = job
        worldLabsKeySet = keySet
    }

    /** Fills the 3D screen's states for screenshots: [stage] null = the download offer. */
    fun showDemoSurround(stage: String?) {
        surround = if (stage == null) SurroundState(needsDownload = true, downloadBytes = io.github.alamo3.openblue.svm3d.ModelStore.TOTAL_BYTES) else SurroundState(stage = stage, progress = 0.42f)
    }

    // ------------------------------------------------------------- the car's look

    /** Settings' choice of 3D model (null = from the car's model name). */
    var carBodyOverride by mutableStateOf(if (online) io.github.alamo3.openblue.ui.CarBody.override(app) else null)
        private set
    val carBody: io.github.alamo3.openblue.ui.CarBody
        get() = carBodyOverride ?: io.github.alamo3.openblue.ui.CarBody.forModel(vehicle?.model)
    var carPaint by mutableStateOf(if (online) io.github.alamo3.openblue.ui.CarPaint.current(app) else io.github.alamo3.openblue.ui.CarPaint.WHITE)
        private set

    fun changeCarBody(body: io.github.alamo3.openblue.ui.CarBody?) {
        carBodyOverride = body
        if (online) io.github.alamo3.openblue.ui.CarBody.setOverride(getApplication(), body)
        updateWidgets()
    }

    fun changeCarPaint(paint: io.github.alamo3.openblue.ui.CarPaint) {
        carPaint = paint
        if (online) io.github.alamo3.openblue.ui.CarPaint.set(getApplication(), paint)
    }

    /** Screenshots: show another body and colour. */
    fun showDemoLook(body: io.github.alamo3.openblue.ui.CarBody?, paint: io.github.alamo3.openblue.ui.CarPaint) {
        carBodyOverride = body
        carPaint = paint
    }

    // ------------------------------------------------------------- app updates

    data class UpdateUi(
        val checking: Boolean = false,
        val release: io.github.alamo3.openblue.update.Release? = null,
        val upToDate: Boolean = false,
        /** 0..1 while downloading. */
        val downloading: Float? = null,
        val error: String? = null,
    )

    var update by mutableStateOf(UpdateUi())
        private set
    // (Screenshot tests run without a real Context, hence the online check.)
    var autoUpdate by mutableStateOf(!online || io.github.alamo3.openblue.update.UpdateState.autoUpdate(app))
        private set

    init {
        if (online) {
            if (autoUpdate) io.github.alamo3.openblue.update.UpdateWorker.schedule(app)
            checkForUpdate(quiet = true)
        }
    }

    fun changeAutoUpdate(on: Boolean) {
        val ctx = getApplication<Application>()
        autoUpdate = on
        io.github.alamo3.openblue.update.UpdateState.setAutoUpdate(ctx, on)
        if (on) io.github.alamo3.openblue.update.UpdateWorker.schedule(ctx) else io.github.alamo3.openblue.update.UpdateWorker.cancel(ctx)
    }

    /** Looks for a newer build; [quiet] only mentions it if there is one. */
    fun checkForUpdate(quiet: Boolean = false) {
        if (!online || update.checking || update.downloading != null) return
        update = update.copy(checking = true, error = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { io.github.alamo3.openblue.update.Updater.check() } }
                .onSuccess { r ->
                    update = UpdateUi(release = r.takeIf { it.isNewer }, upToDate = !r.isNewer)
                    if (quiet && r.isNewer) message = "Open Blue ${r.versionName} is available. Settings has it."
                }
                .onFailure { update = UpdateUi(error = if (quiet) null else "Couldn't check: ${it.message}") }
        }
    }

    /** Downloads and installs the newer build now; the app closes while Android replaces it. */
    fun installUpdate() {
        val r = update.release ?: return
        if (update.downloading != null) return
        val ctx = getApplication<Application>()
        update = update.copy(downloading = 0f, error = null)
        viewModelScope.launch {
            runCatching {
                val apk = withContext(Dispatchers.IO) {
                    io.github.alamo3.openblue.update.Updater.download(ctx, r) { f -> viewModelScope.launch { update = update.copy(downloading = f) } }
                }
                io.github.alamo3.openblue.update.Updater.install(ctx, apk, r)
            }.onFailure { update = update.copy(downloading = null, error = "Couldn't update: ${it.message}") }
                .onSuccess { update = update.copy(downloading = null) }
        }
    }

    /** Screenshots: an update waiting. */
    fun showDemoUpdate() {
        update = UpdateUi(release = io.github.alamo3.openblue.update.Release(999, "0.1.999", message = "Home-screen widget: battery, lock, climate, refresh"))
    }

    // ----------------------------------------------------------- engine sound

    private val soundCtl: SoundController? get() = EngineSoundService.current
    var engineSound by mutableStateOf(store.engineSound?.let { n -> EngineSound.entries.firstOrNull { it.name == n } } ?: EngineSound.STARSHIP)
        private set
    var soundVolume by mutableStateOf(store.soundVolume)
        private set
    var holdRevs by mutableStateOf(store.holdRevs)
        private set
    var gearshifts by mutableStateOf(store.gearshifts)
        private set
    var soundLookaheadMs by mutableStateOf(store.soundLookaheadMs)
        private set
    var soundOn by mutableStateOf(online && EngineSoundService.current != null)
        private set
    /** Live gauge values while the sound runs. */
    var soundReadout by mutableStateOf<SoundReadout?>(null)
        private set
    /** Result of the last delay measurement, for the screen. */
    var soundDelayNote by mutableStateOf<String?>(null)
        private set
    var measuringDelay by mutableStateOf(false)
        private set
    private var soundPoll: Job? = null

    init {
        if (soundOn) pollSound()
    }

    /** Starts the engine sound (as a foreground service, so it keeps going under Android Auto). */
    fun startSound() {
        if (soundOn || !online) return
        EngineSoundService.start(getApplication())
        soundOn = true
        pollSound()
    }

    private fun pollSound() {
        soundPoll?.cancel()
        soundPoll = viewModelScope.launch {
            var missing = 0
            while (true) {
                val c = soundCtl
                soundReadout = c?.readout
                // Stopped from the notification: reflect it here.
                missing = if (c == null) missing + 1 else 0
                if (missing > 40) {
                    soundOn = false
                    soundReadout = null
                    break
                }
                delay(50)
            }
        }
    }

    fun stopSound() {
        soundPoll?.cancel()
        soundPoll = null
        if (online && soundOn) EngineSoundService.stop(getApplication())
        soundOn = false
        soundReadout = null
    }

    /** Revving in place: 0..1 while the slider is held, null when let go. */
    fun revEngine(throttle: Float?) {
        soundCtl?.revThrottle = throttle
    }

    fun changeEngineSound(s: EngineSound) {
        engineSound = s
        store.engineSound = s.name
        soundCtl?.sound = s
    }

    fun changeSoundVolume(v: Float) {
        soundVolume = v
        store.soundVolume = v
        soundCtl?.volume = v
    }

    fun changeHoldRevs(on: Boolean) {
        holdRevs = on
        store.holdRevs = on
        soundCtl?.holdRevs = on
    }

    fun changeGearshifts(on: Boolean) {
        gearshifts = on
        store.gearshifts = on
        soundCtl?.gearshifts = on
    }

    var soundPlacement by mutableStateOf(store.soundPlacement)
        private set

    fun changeSoundPlacement(p: Int) {
        soundPlacement = p
        store.soundPlacement = p
        soundCtl?.padSign = p.takeIf { it != 0 }
    }

    var autoSound by mutableStateOf(store.autoSound)
        private set
    var soundCarName by mutableStateOf(store.soundCarName)
        private set

    fun changeAutoSound(on: Boolean) {
        autoSound = on
        store.autoSound = on
    }

    /** The car whose Bluetooth starts and stops the sound. */
    fun setSoundCar(address: String, name: String) {
        store.soundCarAddress = address
        store.soundCarName = name
        soundCarName = name
        changeAutoSound(true)
    }

    fun forgetSoundCar() {
        store.soundCarAddress = null
        store.soundCarName = null
        soundCarName = null
        changeAutoSound(false)
    }

    fun soundMessage(text: String) {
        message = text
    }

    fun changeSoundLookahead(ms: Int) {
        soundLookaheadMs = ms
        store.soundLookaheadMs = ms
        soundCtl?.lookaheadMs = ms.toFloat()
    }

    /** Plays clicks through the current speakers and times them with the microphone. */
    fun measureSoundDelay() {
        val c = soundCtl ?: return
        if (measuringDelay) return
        measuringDelay = true
        soundDelayNote = null
        viewModelScope.launch {
            val ms = withContext(Dispatchers.IO) { runCatching { c.measureDelayMs() }.getOrNull() }
            measuringDelay = false
            soundDelayNote = if (ms == null) {
                "Couldn't hear the clicks. Turn the volume up and try again, with the phone near a speaker."
            } else {
                // Predicting much past ~250 ms does more harm than good.
                val use = (ms - 15).coerceIn(0, 250)
                changeSoundLookahead((use / 10) * 10)
                "Measured ${ms} ms from the phone to your ears. Look-ahead set to ${soundLookaheadMs} ms."
            }
        }
    }

    /** Fills the sound screen with a mid-pull reading, for screenshots. */
    fun showDemoSound() {
        soundOn = true
        soundReadout = SoundReadout(4650f, -1, 92f, 2.6f, 0.68f, 1f, gpsFix = true, hasSensors = true)
        soundDelayNote = "Measured 182 ms from the phone to your ears. Look-ahead set to 160 ms."
        setSoundCar("00:11:22:33:44:55", "IONIQ 5")
        soundLookaheadMs = 160
    }

    override fun onCleared() {
        // The sound keeps running in its service; only stop watching it.
        soundPoll?.cancel()
        super.onCleared()
    }

    // ---------------------------------------------------------- notifications

    var notifyCommands by mutableStateOf(store.notifyCommands)
        private set
    var watchEnabled by mutableStateOf(store.watchEnabled)
        private set
    var watchMinutes by mutableStateOf(store.watchMinutes)
        private set
    var alertsOn by mutableStateOf(AlertKind.entries.associateWith { store.alertOn(it.key) })
        private set

    /** True the first time it's asked (per sign-in), so the app requests notification permission once. */
    fun takeNotificationPrompt(): Boolean {
        if (!online || store.askedNotifications) return false
        store.askedNotifications = true
        return true
    }

    fun changeNotifyCommands(on: Boolean) {
        store.notifyCommands = on
        notifyCommands = on
    }

    fun changeWatch(on: Boolean) {
        store.watchEnabled = on
        watchEnabled = on
        // Start fresh, so a state that was already there doesn't count as a change.
        if (on) store.watchMemory = io.github.alamo3.openblue.work.WatchMemory()
        if (online && !isUs) StatusWatchWorker.sync(getApplication(), store, replace = true)
    }

    fun changeWatchMinutes(minutes: Int) {
        store.watchMinutes = minutes
        watchMinutes = minutes
        if (online && !isUs) StatusWatchWorker.sync(getApplication(), store, replace = true)
    }

    fun changeAlert(kind: AlertKind, on: Boolean) {
        store.setAlertOn(kind.key, on)
        alertsOn = alertsOn + (kind to on)
    }

    fun loadDiagnostics() {
        val v = vehicle ?: return
        task("Fetching vehicle data") {
            val raw = api.selectVehicle(v.id)
            // What the climate start depends on: the vehicle list entry (vehicleGeneration), the
            // key chosen from it, and the last command sent with its answer.
            val extra = buildJsonObject {
                put("climateKey", api.climateKey())
                put("climateFormatSetting", store.climateFormat.name)
                put("genTypeHeader", caps.genHeader?.ifEmpty { "(absent)" } ?: "(not fetched)")
                store.learnedClimateKey?.let { put("learnedClimateKey", it) }
                runCatching { api.vehicleListEntry(v.id) }.getOrNull()?.let { put("vhcllst", it) }
                api.lastCommand?.let { put("lastCommand", it) }
            }
            // Shown for copying and sharing, so nothing that identifies the car or its owner.
            val shared = JsonObject(raw + ("openBlueDebug" to extra))
            diagnostics = prettyJson.encodeToString(JsonElement.serializer(), io.github.alamo3.openblue.api.Redact.json(shared))
        }
    }
}
