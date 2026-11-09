# SPEC — write ONE Kotlin file: UsaBluelinkClient.kt

You are drafting a new self-contained Kotlin file for an existing Android app
(package `io.github.alamo3.openblue.api`, Jetpack Compose app, OkHttp 4, kotlinx.serialization,
kotlinx.coroutines). Output ONLY the Kotlin code for this one file, no explanations.

## Existing types in the same package you MUST use and MUST NOT redefine

- `class ApiException(val code: String?, message: String) : Exception(message)`
- `sealed interface LoginResult { data object Success; data class NeedsOtp(val challenge: OtpChallenge) }`
- `enum class CommandResult { SUCCESS, FAILED, TIMEOUT }`
- `data class VehicleInfo(val id: String, val nickname: String, val model: String, val year: String, val vin: String, val fuelKindCode: String, val kindCode: String? = null)` — fuelKindCode is "E" (EV), "P" (PHEV), "N" (ICE).
- `data class VehicleCaps(...)` with named args: genType: String?, genHeader: String?, hvacTempType: Int?, strgWhlHeatingOption: Int?, seatOptions: List<Int?> (4 entries drv,ast,rl,rr; Canada semantics: null = unknown = allow all), photoUrl: String?, trim: String?, odometer: Double?, odometerUnit: Int? (1 = km, 3 = miles), hasSvm: Boolean, modelCode: String?, listsStore: Boolean, storeUrlEn: String?, storeUrlFr: String?
- `data class ClimateSettings(val name: String = "", val climateOn: Boolean = true, val tempC: Double = 21.0, val defrost: Boolean = false, val heating: Int = 0, val durationMin: Int = 10, val driverSeat: Int = 0, val passengerSeat: Int = 0, val rearLeftSeat: Int = 0, val rearRightSeat: Int = 0)` — seat values use codes 0=off, 3/4/5 = cool low/med/high, 6/7/8 = heat low/med/high.
- `data class CarLocation(val lat: Double, val lon: Double, val heading: Double? = null, val time: String? = null, val fetchedAt: Long = 0)`
- JSON helpers (extension functions on JsonElement?): `at(path)`, `str(path)`, `int(path)`, `dbl(path)`, `bool(path)`, `arr(path)` — dotted paths, numeric segments index arrays.
- `TempTables` object: `TempTables.forVehicle(hvacTempType: Int, model: String, fuelKindCode: String): List<TempTables.Entry>`, `TempTables.nearest(table, tempC: Double): TempTables.Entry`, Entry has `.hex: String` and `.celsius: Double`. Canada encodes cabin temp as a hex string from this table.
- `SecureStore` (package `io.github.alamo3.openblue.data`) with mutable properties: `username: String?`, `password: String?`, `pin: String?`, `accessToken: String?`, `tokenExpiresAt: Long`, `refreshToken: String?` (new, US only), `caps: VehicleCaps?`, `vehicle: VehicleInfo?`.

## Constants to declare in a companion/object in this file

- `US_CLIENT_ID` and `US_CLIENT_SECRET`: use the placeholder strings "REPLACE_WITH_PUBLIC_APP_CLIENT_ID" / "REPLACE_WITH_PUBLIC_APP_CLIENT_SECRET" (real public values from the official app will be substituted at integration time — do NOT invent values).
- Host: `https://api.telematics.hyundaiusa.com`
- Token URL: `https://api.telematics.hyundaiusa.com/v2/ac/oauth/token` (note `v2/ac/` order)
- API base: `https://api.telematics.hyundaiusa.com/ac/v2/` (note `ac/v2/` order — do not normalise)

## HTTP plumbing (mirror this style)

OkHttpClient with 30s connect / 90s read timeouts. All calls are suspending, wrapped in
`withContext(Dispatchers.IO)`. Two private helpers like:
- `request(method, fullUrl, body: JsonObject?, extraHeaders: Map<String,String>): Reply` where Reply holds the parsed body (JsonObject, EMPTY JsonObject when the response body is empty — commands often return HTTP 200 with an empty body, which is success) plus response `Headers`.
- Static headers on every request: `Content-Type: application/json`, `client_id`, `clientSecret`, `from: SPA`, `to: ISS`, `language: 0`, `brandIndicator: H`, `encryptFlag: false`, `offset`: local UTC offset in whole hours as string (compute from java.util.TimeZone default offset).
- Authenticated headers add: `username`, `accessToken`, and `blueLinkServicePin` (the stored PIN) when a PIN is stored.
- Per-vehicle headers add: `registrationId` = vehicle.id (this is the server-issued regid, NOT the VIN), `gen` = vehicle generation string, `vin`, `APPCLOUD-VIN`.
- Error envelope: any response body containing `errorCode` (arrives as int or string — normalise to string) is an error: code "502" means transient backend failure → throw ApiException("502", message + " (temporary server problem, try again)"); otherwise throw ApiException(code, errorMessage + optional systemName/functionName). PIN failures appear in command bodies as `isBlueLinkServicePinValid == false` with `remainingAttemptCount` and `invalidAttemptMessage` → throw ApiException("pin", that message + attempts left). Never retry a failed command automatically.

## Auth

- `suspend fun login(username: String, password: String): LoginResult`
  POST token URL, JSON body `{"username": ..., "password": ...}`.
  Success body: `access_token`, `refresh_token`, `expires_in` (string or int, ~1800). Store accessToken, refreshToken, tokenExpiresAt = now + (expiresIn - 60)s. Return LoginResult.Success.
  If the error body indicates a device-verification/OTP demand (keys `otpKey` present, or errorMessage mentioning verification): throw ApiException("otp", "Hyundai wants a one-time verification for this account. Sign in once in the official MyHyundai app, then try again here.") — do NOT return NeedsOtp.
  HTTP 401 → ApiException("auth", "Wrong email or password"); 403 → account locked message; 429 → too many attempts message.
- Token refresh (private `suspend fun refresh()`): POST the SAME token URL with ALL FOUR fields `{"username", "password", "grant_type": "refresh_token", "refresh_token"}` — sending only grant_type+refresh_token returns 400 "password required". Persist the rotated refresh_token (fall back to old if absent). Uses store.username/store.password. On hard failure (invalid_grant/invalid_token/401/403) fall back to one full `login()` with stored credentials; on network/5xx keep the session and rethrow.
- Private `suspend fun <T> authed(block: suspend () -> T): T`: if no token or token expired (tokenExpiresAt <= now) refresh (or login if no refresh token) first; run block; if block throws ApiException with code indicating auth expiry (401) refresh once and retry block once. 502 must NOT trigger re-login.

## Vehicles

- `suspend fun vehicles(): List<VehicleInfo>`
  GET `{API base}enrollment/details/{username}` with authenticated headers.
  Response root: `enrolledVehicleDetails: [ { "vehicleDetails": {...}, "packageDetails": [...] } ]`. Missing root → ApiException("auth", ...).
  Per vehicleDetails: `regid` → VehicleInfo.id, `nickName` → nickname, `modelCode` or `series` → model, `modelYear` → year, `vin`, `evStatus` ("E"/"P"/"N") → fuelKindCode, `modelCode` → kindCode.
  Skip entries whose `enrollmentStatus == "CANCELLED"`.
  Keep an in-memory cache (map id → details JsonObject, plus generation from `vehicleGeneration`, default "2") for later calls; expose private `suspend fun vehicleDetails(vehicleId: String): JsonObject` that returns the cached details, refetching enrollment once if missing.
- `suspend fun capsFor(vehicle: VehicleInfo): VehicleCaps`
  From the enrollment details: genType = vehicleGeneration (e.g. "2"), genHeader = null, hvacTempType = 1 if EV else 0, strgWhlHeatingOption = 1 if `steeringWheelHeatCapable == true` else 0, seatOptions: from `seatConfigurations.seatConfigs` (seatLocationID 1..4 in order drv,ast,rl,rr): 6 if heatingCapable && ventCapable, 2 if heatingCapable only, 3 if ventCapable only, 0 if neither, null if the config block is absent; odometer from details `odometer` (Double), odometerUnit = 3, hasSvm = false, modelCode = modelCode, trim = null, listsStore = false.

## Status — fetch US, return Canada-shaped JSON

- `suspend fun cachedStatus(vehicleId: String): JsonObject` = fetch(refresh=false)
- `suspend fun liveStatus(vehicleId: String): JsonObject` = fetch(refresh=true)
  GET `{API base}rcs/rvs/vehicleStatus` with per-vehicle headers; when refresh=true add header `REFRESH: "true"`.
  Response root has `vehicleStatus` object. Translate it (private fun `translateStatus(vs: JsonObject, vehicle: VehicleInfo): JsonObject`) into the shape the app's UI parser already reads:
  - Start from a deep copy of the vehicleStatus object.
  - `doorLock`: if a boolean `doorLock` exists keep it; else derive from string `doorLockStatus` ("true"/"LOCKED"/"1" → true; "false"/"UNLOCKED"/"0" → false) and put it as boolean `doorLock`.
  - `lastStatusDate`: from `dateTime`: if it contains "T" or "-", strip all `-`, `T`, `:`, `Z` and spaces → "yyyyMMddHHmmss"; if already 14 digits keep as-is.
  - Seats: build `seatHeaterVentInfo` object with keys `drvSeatHeatState`, `astSeatHeatState`, `rlSeatHeatState`, `rrSeatHeatState`: if `seatHeaterVentInfo` already present keep it; else map from `seatHeaterVentState`: fl→drv, fr→ast, rl→rl, rr→rr.
  - Tires: build `tirePressureLamp` object: `tirePressureLampAll` = 1 if any of `tirePressureLamp.tirePressureWarningLampFrontLeft/FrontRight/RearLeft/RearRight/All` is true else 0 (if the source block is missing entirely, omit). Note: source uses "Warning" in the names; the target key names drop "Warning" except keep target exactly `tirePressureLampAll`, `tirePressureLampFL`, `tirePressureLampFR`, `tirePressureLampRL`, `tirePressureLampRR` as ints 0/1.
  - Temp: `airTemp`: US value is a Fahrenheit string like "72" (or "LO"/"HI"/"OFF"). If numeric: convert °F→°C, find nearest TempTables entry using table = TempTables.forVehicle(1, vehicle.model, "E"), replace airTemp with `{"value": entry.hex, "unit": 0, "hvacTempType": 1}`. If "LO"/"HI": use the min/max entry of that table the same way. If "OFF" or missing: remove airTemp.
  - Defrost: if boolean `defrost` missing but string `defrostStatus` present, put boolean `defrost` parsed from it.
  - Everything else (evStatus incl. drvDistance, reservChargeInfos.targetSOClist, remainTime2, battery, doorOpen, windowOpen, hoodOpen, trunkOpen, airCtrlOn, steerWheelHeat, sideBackWindowHeat, vehicleLocation) passes through unchanged.
- `suspend fun locate(vehicleId: String): CarLocation`
  First try the translated status's `vehicleLocation.coord.lat/lon` via cachedStatus; if absent, GET `{API base}rcs/rfc/findMyCar` (per-vehicle headers) and read root `coord.lat`, `coord.lon`, `head` or `heading`, returning CarLocation(lat, lon, heading, time=null, fetchedAt=now). Location errors from that call (e.g. feature off) must surface as ApiException with the server message.

## Commands

All commands POST with per-vehicle headers, return the transaction id taken from response HEADERS — first present of `tmsTid`, `transactionId`, `Xid` — or "" when absent. Empty-body 200 = success. Each command remembers its polling service_type in a private map (tid → serviceType).

- `lock(vehicleId)`: POST `rcs/rdo/off`, JSON body `{"userName": username, "vin": vin}`, service REMOTE_POLL.
- `unlock(vehicleId)`: POST `rcs/rdo/on`, same body.
- `startClimate(vehicle: VehicleInfo, s: ClimateSettings): String`:
  EV (fuelKindCode "E") → POST `evc/fatc/start`; ICE/PHEV → POST `rcs/rsc/start`.
  Temp: convert s.tempC → °F, round to nearest int, clamp 62..81, send as STRING value with unit 1.
  Gen 2 (generation == "2") EV body — ONLY these keys: `{"airCtrl": 1 or 0 from s.climateOn, "airTemp": {"value": "<F>", "unit": 1}, "defrost": s.defrost, "heating1": s.heating}`.
  Gen 3 EV body adds: `"igniOnDuration": s.durationMin` and `"seatHeaterVentInfo": {"drvSeatHeatState": s.driverSeat, "astSeatHeatState": s.passengerSeat, "rlSeatHeatState": s.rearLeftSeat, "rrSeatHeatState": s.rearRightSeat}`.
  ICE body: full shape always: `{"Ims": 0, "airCtrl", "airTemp": {"unit": 1, "value": <F as NUMBER>}, "defrost", "heating1", "igniOnDuration", "seatHeaterVentInfo": {...}, "username": username, "vin": vehicle.id}`.
- `stopClimate(vehicleId)`: EV → POST `evc/fatc/stop`, ICE/PHEV → `rcs/rsc/stop`, no body. (Determine EV-ness from cached enrollment details evStatus.)
- `startCharge(vehicleId)`: POST `evc/charge/start`, NO body.
- `stopCharge(vehicleId)`: POST `evc/charge/stop`, NO body.
- `setChargeLimits(vehicleId, ac: Int, dc: Int): String`: POST `evc/charge/targetsoc/set`, body `{"targetSOClist": [{"plugType": 0, "targetSOClevel": dc}, {"plugType": 1, "targetSOClevel": ac}]}` (plugType 0 = DC, 1 = AC here).
- `hornLights(vehicleId, horn: Boolean)`: POST `rcs/rhl/hnl` if horn else `rcs/rhl/light`, body `{"userName", "vin"}`, service_type HORN_AND_LIGHTS or LIGHTS_ONLY respectively.
- `suspend fun awaitResult(vehicleId: String, transactionId: String, timeoutMs: Long = 120_000): CommandResult`:
  If transactionId is empty → return SUCCESS (US commands with empty-body 200 have no pollable id; the caller refreshes status). Otherwise poll GET `{API base}rmt/getRunningStatus` with per-vehicle headers plus `tid`, `login_id` = username, `service_type` = remembered type or "REMOTE_POLL". Every 2 seconds (delay(2000)) until deadline: body `status` == "SUCCESS" → SUCCESS; == "ERROR" → FAILED; anything else or empty body → keep waiting. On deadline → TIMEOUT. Pop the remembered service type on first poll.

## Style requirements

- Kotlin idioms matching the codebase: buildJsonObject/put/putJsonObject, JsonPrimitive handling via the provided helpers, no Gson, no Retrofit, no new dependencies.
- Every public function has a short KDoc saying which US endpoint it wraps.
- No logging of credentials, tokens, PIN or VIN (no Log calls with those values at all).
- File should be roughly 550–700 lines. Output ONLY the file content.
