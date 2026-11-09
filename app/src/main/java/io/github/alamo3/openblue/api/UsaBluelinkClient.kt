package io.github.alamo3.openblue.api

import io.github.alamo3.openblue.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Client for the Hyundai USA Bluelink API (https://api.telematics.hyundaiusa.com).
 *
 * This is a completely different backend from the Canadian "tods" API in [BluelinkClient]:
 * OAuth token login at /v2/ac/oauth/token, vehicles from enrollment/details, commands under
 * /ac/v2/rcs/... and /ac/v2/evc/..., and the account PIN sent as a plain header
 * (there is no vrfypin/pAuth exchange in the US). Behaviour mirrors the USA implementation
 * of hyundai_kia_connect_api, cross-checked against the BlueDeck Android app.
 *
 * Status responses are translated into the Canada-shaped JSON that the app's UI parser
 * (StatusModel) already understands, so no UI changes are needed to display them.
 *
 * Gemini-drafted 2026-11-09 from US_CLIENT_SPEC_FOR_GEMINI.md; reviewed and corrected by Muse
 * (auth error codes, refresh fallback rules, gen-2 seat key names, climate body shapes,
 * polling service types, removal of shadow JSON helpers that clashed with JsonUtil).
 */
class UsaBluelinkClient(private val store: SecureStore) {

    companion object {
        // Public client credentials embedded in the official MyHyundai app, as published in
        // hyundai_kia_connect_api (HyundaiBlueLinkApiUSA.API_HEADERS) and BlueDeck
        // (BluelinkConstants.CLIENT_ID / CLIENT_SECRET). Not user credentials.
        private const val US_CLIENT_ID = "m66129Bb-em93-SPAHYN-bZ91-am4540zp19920"
        private const val US_CLIENT_SECRET = "v558o935-6nne-423i-baa8"

        private const val HOST = "https://api.telematics.hyundaiusa.com"
        private const val TOKEN_URL = "$HOST/v2/ac/oauth/token"
        private const val API_BASE = "$HOST/ac/v2/"

        // "400" included: the OAuth endpoint answers bad grants/credentials with HTTP 400
        // (error/error_description body, no errorCode), which request() surfaces as code "400".
        private val HARD_AUTH_CODES = setOf("400", "401", "403", "invalid_grant", "invalid_token")
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    /** regid -> vehicleDetails object from the last enrollment fetch. */
    private val detailsCache = ConcurrentHashMap<String, JsonObject>()

    /** transactionId -> getRunningStatus service_type, set when a command is sent. */
    private val serviceTypes = ConcurrentHashMap<String, String>()

    private class Reply(val body: JsonObject, val headers: Headers)

    private fun offsetHours(): String =
        (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000).toString()

    /**
     * One HTTP call. Throws ApiException on the US error envelope ({errorCode, errorMessage,...})
     * or on a non-2xx HTTP status (code = the HTTP status as a string). A 200 with an empty
     * body is success and parses to an empty JsonObject — several US commands answer that way.
     */
    private suspend fun request(
        method: String,
        fullUrl: String,
        body: JsonObject?,
        extraHeaders: Map<String, String>,
    ): Reply = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(fullUrl)
            .header("Content-Type", "application/json")
            .header("client_id", US_CLIENT_ID)
            .header("clientSecret", US_CLIENT_SECRET)
            .header("from", "SPA")
            .header("to", "ISS")
            .header("language", "0")
            .header("brandIndicator", "H")
            .header("encryptFlag", "false")
            .header("offset", offsetHours())
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        if (method == "GET") {
            builder.get()
        } else {
            // A null body means "send no body at all" (e.g. charge start/stop), like the
            // reference clients do; an empty {} would be a different request.
            builder.post(body?.toString()?.toRequestBody(jsonMedia) ?: ByteArray(0).toRequestBody(null))
        }

        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = if (text.isBlank()) {
                JsonObject(emptyMap())
            } else {
                try {
                    Json.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    JsonObject(emptyMap())
                }
            }
            checkError(parsed)
            if (!resp.isSuccessful) {
                throw ApiException(resp.code.toString(), "Hyundai USA request failed (HTTP ${resp.code})")
            }
            Reply(parsed, resp.headers)
        }
    }

    /** The US error envelope; 502 is a transient HATA backend failure, never an auth failure. */
    private fun checkError(body: JsonObject) {
        // A refused PIN comes back in the command body, sometimes without an errorCode.
        if ((body["isBlueLinkServicePinValid"] as? JsonPrimitive)?.contentOrNull == "false") {
            val msg = body.str("invalidAttemptMessage") ?: "That PIN wasn't accepted"
            val left = body.int("remainingAttemptCount")
            throw ApiException("pin", if (left != null) "$msg ($left attempts left)" else msg)
        }
        val raw = body["errorCode"] as? JsonPrimitive ?: return
        val code = raw.contentOrNull ?: return
        if (code.isBlank() || code == "0") return
        val desc = body.str("errorMessage") ?: body.str("errorSubMessage") ?: "Request failed"
        val where = listOfNotNull(body.str("systemName"), body.str("functionName"))
            .joinToString("/").takeIf { it.isNotBlank() }
        val full = if (where != null) "$desc [$where]" else desc
        if (code == "502") throw ApiException("502", "$full (temporary server problem — try again)")
        throw ApiException(code, full)
    }

    private fun authedHeaders(): Map<String, String> {
        val h = mutableMapOf(
            "username" to (store.username.orEmpty()),
            "accessToken" to (store.accessToken.orEmpty()),
        )
        store.pin?.takeIf { it.isNotBlank() }?.let { h["blueLinkServicePin"] = it }
        return h
    }

    private fun vehicleHeaders(vehicleId: String): Map<String, String> {
        val details = detailsCache[vehicleId]
        val vin = details?.str("vin") ?: store.vehicle?.takeIf { it.id == vehicleId }?.vin.orEmpty()
        val gen = details?.str("vehicleGeneration") ?: "2"
        return authedHeaders() + mapOf(
            "registrationId" to vehicleId,
            "gen" to gen,
            "vin" to vin,
            "APPCLOUD-VIN" to vin,
        )
    }

    // ------------------------------------------------------------------ auth

    /**
     * POST /v2/ac/oauth/token with username+password. Stores the access/refresh tokens.
     * The US flow has no OTP step of its own; if Hyundai demands device verification we
     * surface the official-app workaround instead of an OTP screen.
     */
    suspend fun login(username: String, password: String): LoginResult {
        val reply = try {
            request(
                "POST", TOKEN_URL,
                buildJsonObject { put("username", username); put("password", password) },
                emptyMap(),
            )
        } catch (e: ApiException) {
            throw when (e.code) {
                "400", "401" -> ApiException("auth", "Wrong email or password for MyHyundai")
                "403" -> ApiException("auth", "This MyHyundai account is locked — unlock it in the official app, then try again")
                "429" -> ApiException("auth", "Too many sign-in attempts — wait a while and try again")
                else -> {
                    val m = e.message.orEmpty()
                    if (m.contains("otp", true) || m.contains("verification", true)) {
                        ApiException("otp", "Hyundai wants a one-time verification for this account. Sign in once in the official MyHyundai app, then try again here.")
                    } else {
                        e
                    }
                }
            }
        }
        val body = reply.body
        if (body["otpKey"] != null) {
            throw ApiException("otp", "Hyundai wants a one-time verification for this account. Sign in once in the official MyHyundai app, then try again here.")
        }
        saveTokens(body)
        store.username = username
        store.password = password
        return LoginResult.Success
    }

    private fun saveTokens(body: JsonObject) {
        val token = body.str("access_token") ?: throw ApiException("auth", "Login response had no access token")
        store.accessToken = token
        body.str("refresh_token")?.let { store.refreshToken = it }
        val expiresIn = body.int("expires_in") ?: 1800
        store.tokenExpiresAt = System.currentTimeMillis() + (expiresIn - 60) * 1000L
    }

    /**
     * Refresh at the same token endpoint. The US endpoint 400s unless ALL FOUR fields are
     * sent (username + password + grant_type + refresh_token). The refresh token rotates.
     * Hard auth failures fall back to one full login; network/5xx errors keep the session.
     */
    private suspend fun refresh() {
        val u = store.username ?: throw ApiException("auth", "Not signed in")
        val p = store.password ?: throw ApiException("auth", "Not signed in")
        val rt = store.refreshToken ?: throw ApiException("auth", "No refresh token stored")
        try {
            val reply = request(
                "POST", TOKEN_URL,
                buildJsonObject {
                    put("username", u)
                    put("password", p)
                    put("grant_type", "refresh_token")
                    put("refresh_token", rt)
                },
                emptyMap(),
            )
            saveTokens(reply.body)
        } catch (e: ApiException) {
            if (e.code in HARD_AUTH_CODES) {
                login(u, p)
            } else {
                throw e
            }
        }
    }

    private suspend fun <T> authed(block: suspend () -> T): T {
        if (store.accessToken.isNullOrBlank() || store.tokenExpiresAt <= System.currentTimeMillis()) {
            if (!store.refreshToken.isNullOrBlank()) {
                refresh()
            } else {
                val u = store.username ?: throw ApiException("auth", "Not signed in")
                val p = store.password ?: throw ApiException("auth", "Not signed in")
                login(u, p)
            }
        }
        try {
            return block()
        } catch (e: ApiException) {
            if (e.code == "401") {
                refresh()
                return block()
            }
            throw e
        }
    }

    // ------------------------------------------------------------------ vehicles

    /** GET enrollment/details/{username}: the US vehicle list (id = the server regid). */
    suspend fun vehicles(): List<VehicleInfo> = authed {
        val u = store.username ?: throw ApiException("auth", "Not signed in")
        val reply = request("GET", "${API_BASE}enrollment/details/$u", null, authedHeaders())
        if (reply.body["enrolledVehicleDetails"] == null) {
            throw ApiException("auth", "Hyundai USA didn't return any vehicles for this account")
        }
        val result = mutableListOf<VehicleInfo>()
        for (item in reply.body.arr("enrolledVehicleDetails")) {
            val details = (item as? JsonObject)?.get("vehicleDetails") as? JsonObject ?: continue
            if (details.str("enrollmentStatus") == "CANCELLED") continue
            val regid = details.str("regid") ?: continue
            detailsCache[regid] = details
            result += VehicleInfo(
                id = regid,
                nickname = details.str("nickName").orEmpty(),
                model = details.str("modelCode") ?: details.str("series").orEmpty(),
                year = details.str("modelYear").orEmpty(),
                vin = details.str("vin").orEmpty(),
                fuelKindCode = details.str("evStatus") ?: "N",
                kindCode = details.str("modelCode"),
            )
        }
        result
    }

    /** Cached vehicleDetails for [vehicleId], refetching enrollment once if unknown. */
    private suspend fun detailsFor(vehicleId: String): JsonObject {
        detailsCache[vehicleId]?.let { return it }
        vehicles()
        return detailsCache[vehicleId] ?: throw ApiException("vehicle", "That vehicle isn't on this account")
    }

    /** A VehicleInfo rebuilt from cached enrollment details (no network when cached). */
    private suspend fun infoFor(vehicleId: String): VehicleInfo {
        val d = detailsFor(vehicleId)
        return VehicleInfo(
            id = vehicleId,
            nickname = d.str("nickName").orEmpty(),
            model = d.str("modelCode") ?: d.str("series").orEmpty(),
            year = d.str("modelYear").orEmpty(),
            vin = d.str("vin").orEmpty(),
            fuelKindCode = d.str("evStatus") ?: "N",
            kindCode = d.str("modelCode"),
        )
    }

    /** Capabilities from enrollment details, in the shape the UI expects. */
    suspend fun capsFor(vehicle: VehicleInfo): VehicleCaps {
        val d = detailsFor(vehicle.id)
        val seatConfigs = d.at("seatConfigurations.seatConfigs") as? kotlinx.serialization.json.JsonArray
        val seatOptions: List<Int?> = if (seatConfigs == null) {
            listOf(null, null, null, null)
        } else {
            (1..4).map { loc ->
                val seat = seatConfigs.firstOrNull {
                    (it as? JsonObject)?.int("seatLocationID") == loc
                } as? JsonObject ?: return@map null
                val heat = seat.bool("heatingCapable") == true
                val vent = seat.bool("ventCapable") == true
                when {
                    heat && vent -> 6
                    heat -> 2
                    vent -> 3
                    else -> 0
                }
            }
        }
        return VehicleCaps(
            genType = d.str("vehicleGeneration") ?: "2",
            genHeader = null,
            hvacTempType = if (vehicle.fuelKindCode == "E") 1 else 0,
            strgWhlHeatingOption = if (d.bool("steeringWheelHeatCapable") == true) 1 else 0,
            seatOptions = seatOptions,
            photoUrl = null,
            trim = null,
            odometer = d.dbl("odometer"),
            odometerUnit = 3,
            hasSvm = false,
            modelCode = d.str("modelCode"),
            listsStore = false,
            storeUrlEn = null,
            storeUrlFr = null,
        )
    }

    // ------------------------------------------------------------------ status

    /** GET rcs/rvs/vehicleStatus (cached copy), translated to the Canada status shape. */
    suspend fun cachedStatus(vehicleId: String): JsonObject = authed { fetchStatus(vehicleId, refresh = false) }

    /** GET rcs/rvs/vehicleStatus with REFRESH: true (wakes the car — use sparingly). */
    suspend fun liveStatus(vehicleId: String): JsonObject = authed { fetchStatus(vehicleId, refresh = true) }

    private suspend fun fetchStatus(vehicleId: String, refresh: Boolean): JsonObject {
        // After a process restart the details cache is empty and the vin/gen headers would
        // go out blank; enrollment is one call and is cached for the process lifetime.
        if (!detailsCache.containsKey(vehicleId)) detailsFor(vehicleId)
        val headers = vehicleHeaders(vehicleId) + if (refresh) mapOf("REFRESH" to "true") else emptyMap()
        val reply = request("GET", "${API_BASE}rcs/rvs/vehicleStatus", null, headers)
        val vs = (reply.body["vehicleStatus"] as? JsonObject) ?: reply.body
        return translateStatus(vs, infoFor(vehicleId))
    }

    /**
     * US vehicleStatus -> the shape StatusModel parses. Most fields pass through untouched;
     * the patches are: doorLock normalised to a boolean, lastStatusDate in yyyyMMddHHmmss,
     * gen-2 seat block renamed to seatHeaterVentInfo (fl/fr -> drv/ast), tire warnings renamed
     * from tirePressureWarningLamp* to tirePressureLamp*, and airTemp converted from a
     * Fahrenheit string to the Canada hex-table encoding the UI decodes.
     */
    private fun translateStatus(vs: JsonObject, vehicle: VehicleInfo): JsonObject {
        val out = vs.toMutableMap()

        val locked = vs.bool("doorLock")
            ?: when (vs.str("doorLockStatus")?.uppercase()) {
                "TRUE", "LOCKED", "1" -> true
                "FALSE", "UNLOCKED", "0" -> false
                else -> null
            }
        if (locked != null) out["doorLock"] = JsonPrimitive(locked)

        vs.str("dateTime")?.let { dt ->
            val cleaned = dt.filter { it.isDigit() }
            if (cleaned.length >= 14) out["lastStatusDate"] = JsonPrimitive(cleaned.substring(0, 14))
        }

        if (vs["seatHeaterVentInfo"] == null) {
            (vs["seatHeaterVentState"] as? JsonObject)?.let { seats ->
                out["seatHeaterVentInfo"] = buildJsonObject {
                    seats["flSeatHeatState"]?.let { put("drvSeatHeatState", it) }
                    seats["frSeatHeatState"]?.let { put("astSeatHeatState", it) }
                    seats["rlSeatHeatState"]?.let { put("rlSeatHeatState", it) }
                    seats["rrSeatHeatState"]?.let { put("rrSeatHeatState", it) }
                }
            }
        }

        (vs["tirePressureLamp"] as? JsonObject)?.let { lamp ->
            fun warn(name: String) = lamp.bool("tirePressureWarningLamp$name") == true
            val fl = warn("FrontLeft")
            val fr = warn("FrontRight")
            val rl = warn("RearLeft")
            val rr = warn("RearRight")
            val all = warn("All") || fl || fr || rl || rr
            out["tirePressureLamp"] = buildJsonObject {
                put("tirePressureLampAll", if (all) 1 else 0)
                put("tirePressureLampFL", if (fl) 1 else 0)
                put("tirePressureLampFR", if (fr) 1 else 0)
                put("tirePressureLampRL", if (rl) 1 else 0)
                put("tirePressureLampRR", if (rr) 1 else 0)
            }
        }

        val table = TempTables.forVehicle(1, vehicle.model, vehicle.fuelKindCode)
        when (val raw = vs.str("airTemp.value")?.uppercase()) {
            null, "OFF" -> out.remove("airTemp")
            "LO" -> table.minByOrNull { it.celsiusValue ?: Double.MAX_VALUE }?.let { e ->
                out["airTemp"] = buildJsonObject { put("value", e.hex); put("unit", 0); put("hvacTempType", 1) }
            }
            "HI" -> table.maxByOrNull { it.celsiusValue ?: Double.MIN_VALUE }?.let { e ->
                out["airTemp"] = buildJsonObject { put("value", e.hex); put("unit", 0); put("hvacTempType", 1) }
            }
            else -> raw.toDoubleOrNull()?.let { f ->
                val entry = TempTables.nearest(table, (f - 32.0) * 5.0 / 9.0)
                out["airTemp"] = buildJsonObject { put("value", entry.hex); put("unit", 0); put("hvacTempType", 1) }
            }
        }

        if (vs["defrost"] == null) {
            vs.str("defrostStatus")?.let { out["defrost"] = JsonPrimitive(it.equals("true", true) || it == "1") }
        }

        return JsonObject(out)
    }

    /**
     * Location: coordinates already inside the status when present, else
     * GET rcs/rfc/findMyCar (rate-limited server-side; errors surface as ApiException).
     */
    suspend fun locate(vehicleId: String): CarLocation = authed {
        val status = fetchStatus(vehicleId, refresh = false)
        val loc = status["vehicleLocation"] as? JsonObject
        val coord = (loc?.get("coord") as? JsonObject) ?: loc
        val lat = coord?.dbl("lat")
        val lon = coord?.dbl("lon")
        if (lat != null && lon != null) {
            return@authed CarLocation(
                lat = lat,
                lon = lon,
                heading = loc?.dbl("head") ?: loc?.dbl("heading"),
                time = loc?.str("time"),
                fetchedAt = System.currentTimeMillis(),
            )
        }
        val reply = request("GET", "${API_BASE}rcs/rfc/findMyCar", null, vehicleHeaders(vehicleId))
        val c = (reply.body["coord"] as? JsonObject) ?: reply.body
        CarLocation(
            lat = c.dbl("lat") ?: throw ApiException("location", "Hyundai USA didn't return a location"),
            lon = c.dbl("lon") ?: throw ApiException("location", "Hyundai USA didn't return a location"),
            heading = reply.body.dbl("head") ?: reply.body.dbl("heading"),
            time = null,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------ commands

    /** The transaction id rides in response headers, not the body (and may be absent). */
    private fun extractTid(headers: Headers): String {
        for (name in listOf("tmsTid", "transactionId", "Xid")) {
            headers[name]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private suspend fun command(
        vehicleId: String,
        path: String,
        body: JsonObject?,
        serviceType: String = "REMOTE_POLL",
    ): String {
        val reply = request("POST", API_BASE + path, body, vehicleHeaders(vehicleId))
        val tid = extractTid(reply.headers)
        if (tid.isNotBlank()) serviceTypes[tid] = serviceType
        return tid
    }

    private suspend fun userVinBody(vehicleId: String): JsonObject = buildJsonObject {
        put("userName", store.username.orEmpty())
        put("vin", detailsFor(vehicleId).str("vin").orEmpty())
    }

    /** POST rcs/rdo/off — lock. */
    suspend fun lock(vehicleId: String): String = authed { command(vehicleId, "rcs/rdo/off", userVinBody(vehicleId)) }

    /** POST rcs/rdo/on — unlock. */
    suspend fun unlock(vehicleId: String): String = authed { command(vehicleId, "rcs/rdo/on", userVinBody(vehicleId)) }

    /**
     * Climate start: EV -> POST evc/fatc/start, ICE/PHEV -> POST rcs/rsc/start.
     * Gen-2 EVs take a minimal body (no duration, no seat block — the car rejects them);
     * gen-3 EVs add duration + seats; ICE always takes the full body with a numeric temp.
     */
    suspend fun startClimate(vehicle: VehicleInfo, s: ClimateSettings): String = authed {
        val tempF = ((s.tempC * 9.0 / 5.0) + 32.0).roundToInt().coerceIn(62, 81)
        val gen = detailsFor(vehicle.id).str("vehicleGeneration") ?: "2"
        val isEv = vehicle.fuelKindCode == "E"
        val body = when {
            isEv && gen == "2" -> buildJsonObject {
                put("airCtrl", if (s.climateOn) 1 else 0)
                putJsonObject("airTemp") { put("value", tempF.toString()); put("unit", 1) }
                put("defrost", s.defrost)
                put("heating1", s.heating)
            }
            isEv -> buildJsonObject {
                put("airCtrl", if (s.climateOn) 1 else 0)
                putJsonObject("airTemp") { put("value", tempF.toString()); put("unit", 1) }
                put("defrost", s.defrost)
                put("heating1", s.heating)
                put("igniOnDuration", s.durationMin)
                putJsonObject("seatHeaterVentInfo") {
                    put("drvSeatHeatState", s.driverSeat)
                    put("astSeatHeatState", s.passengerSeat)
                    put("rlSeatHeatState", s.rearLeftSeat)
                    put("rrSeatHeatState", s.rearRightSeat)
                }
            }
            else -> buildJsonObject {
                put("Ims", 0)
                put("airCtrl", if (s.climateOn) 1 else 0)
                putJsonObject("airTemp") { put("unit", 1); put("value", tempF) }
                put("defrost", s.defrost)
                put("heating1", s.heating)
                put("igniOnDuration", s.durationMin)
                putJsonObject("seatHeaterVentInfo") {
                    put("drvSeatHeatState", s.driverSeat)
                    put("astSeatHeatState", s.passengerSeat)
                    put("rlSeatHeatState", s.rearLeftSeat)
                    put("rrSeatHeatState", s.rearRightSeat)
                }
                put("username", store.username.orEmpty())
                put("vin", vehicle.id)
            }
        }
        command(vehicle.id, if (isEv) "evc/fatc/start" else "rcs/rsc/start", body)
    }

    /** Climate stop: EV -> POST evc/fatc/stop, ICE/PHEV -> POST rcs/rsc/stop. No body. */
    suspend fun stopClimate(vehicleId: String): String = authed {
        val isEv = detailsFor(vehicleId).str("evStatus") == "E"
        command(vehicleId, if (isEv) "evc/fatc/stop" else "rcs/rsc/stop", null)
    }

    /** POST evc/charge/start — no body. */
    suspend fun startCharge(vehicleId: String): String = authed { command(vehicleId, "evc/charge/start", null) }

    /** POST evc/charge/stop — no body. */
    suspend fun stopCharge(vehicleId: String): String = authed { command(vehicleId, "evc/charge/stop", null) }

    /** POST evc/charge/targetsoc/set (plugType 0 = DC, 1 = AC in this list only). */
    suspend fun setChargeLimits(vehicleId: String, ac: Int, dc: Int): String = authed {
        command(
            vehicleId, "evc/charge/targetsoc/set",
            buildJsonObject {
                putJsonArray("targetSOClist") {
                    add(buildJsonObject { put("plugType", 0); put("targetSOClevel", dc) })
                    add(buildJsonObject { put("plugType", 1); put("targetSOClevel", ac) })
                }
            },
        )
    }

    /** POST rcs/rhl/hnl (horn + lights) or rcs/rhl/light (lights only). */
    suspend fun hornLights(vehicleId: String, horn: Boolean): String = authed {
        command(
            vehicleId,
            if (horn) "rcs/rhl/hnl" else "rcs/rhl/light",
            userVinBody(vehicleId),
            serviceType = if (horn) "HORN_AND_LIGHTS" else "LIGHTS_ONLY",
        )
    }

    /**
     * Polls GET rmt/getRunningStatus every 2 s until the car reports SUCCESS/ERROR or the
     * timeout passes. An empty transaction id means the command was accepted with an
     * empty-body 200 and there is nothing to poll — reported as SUCCESS, and the caller's
     * status refresh shows the result.
     */
    suspend fun awaitResult(vehicleId: String, transactionId: String, timeoutMs: Long = 120_000): CommandResult {
        if (transactionId.isBlank()) return CommandResult.SUCCESS
        val serviceType = serviceTypes.remove(transactionId) ?: "REMOTE_POLL"
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            val headers = vehicleHeaders(vehicleId) + mapOf(
                "tid" to transactionId,
                "login_id" to store.username.orEmpty(),
                "service_type" to serviceType,
            )
            val status = try {
                authed { request("GET", "${API_BASE}rmt/getRunningStatus", null, headers) }
                    .body.str("status")?.uppercase()
            } catch (e: ApiException) {
                if (e.code == "pin" || e.code == "auth") return CommandResult.FAILED
                null // transient poll failures (incl. HATA 502) just keep waiting
            }
            when (status) {
                "SUCCESS" -> return CommandResult.SUCCESS
                "ERROR" -> return CommandResult.FAILED
            }
        }
        return CommandResult.TIMEOUT
    }
}
