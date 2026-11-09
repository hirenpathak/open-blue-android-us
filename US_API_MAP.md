# Hyundai USA Bluelink — API Map for an OpenBlue Fork

Research-only mapping, extracted 2026-11-09 from two open-source reference implementations.
No real user credentials are in this file. The public app client_id / client_secret that ship
inside the official MyHyundai app are **not copied verbatim** — they are referenced by source
location (see §2.3); take the values from those files when building.

| Reference | Local clone (research) | Key files used |
|---|---|---|
| Hyundai-Kia-Connect / hyundai_kia_connect_api (Python, Home Assistant library) | `~/workspace/openblue-us/research/hyundai_kia_connect_api/` | `hyundai_kia_connect_api/HyundaiBlueLinkApiUSA.py` (1,555 lines, the single source of truth for USA), `hyundai_kia_connect_api/const.py`, `hyundai_kia_connect_api/Token.py`, tests `tests/test_us_climate_command.py`, `tests/test_us_lock_command.py`, `tests/test_us_charge_command.py`, `tests/test_us_hazard_horn_command.py`, fixtures in `tests/fixtures/` |
| tracer99 / BlueDeck (Android, Kotlin) | `~/workspace/openblue-us/research/BlueDeck/` | `app/src/main/java/com/bluedeck/data/api/BluelinkApiService.kt` (Retrofit interface, USA Hyundai), `…/api/BluelinkConstants.kt` (`ApiClient`, `Region` enum), `…/data/repository/VehicleRepository.kt` (4,196 lines; US-Hyundai paths are the `else` fall-through branches), `…/data/models/Models.kt` (all request/status DTOs) |

Target vehicle for field paths in §6: **2022 Hyundai Ioniq 5 EV (US)** — legacy (non-CCS2)
Bluelink generation. In the USA vehicle-list fixture (`tests/fixtures/vehicle_lists/usa_hyundai.json`)
every vehicle carries `"vehicleGeneration": "2"`; a 2025 Santa Fe Hybrid enrollment capture
carries `"3"`. Treat 2022 Ioniq 5 as **gen 2** unless its enrollment says otherwise (§5).

> Scope note: `HyundaiBlueLinkApiUSA` subclasses `ApiImpl` directly — it is the *legacy*
> telematics API. It is **not** the CCS2/CCI (`ApiImplType1` / GSPA) stack used for newer
> EU/AU cars. Do not port EU code for US support.

---

## 1. Hosts and base URLs

| Purpose | Value |
|---|---|
| Host | `api.telematics.hyundaiusa.com` |
| Login / OAuth base (`LOGIN_API` in Python) | `https://api.telematics.hyundaiusa.com/v2/ac/` — note: **no `ac` segment order swap**; token path is `v2/ac/oauth/token` |
| API base (`API_URL` in Python) | `https://api.telematics.hyundaiusa.com/ac/v2/` — all vehicle calls below are relative to this |
| BlueDeck Retrofit base | `BluelinkConstants.BASE_URL_US_HYUNDAI = "https://api.telematics.hyundaiusa.com/"`, and its interface mixes both prefixes literally: token methods are `@POST("v2/ac/oauth/token")`, vehicle methods are `@GET/@POST("ac/v2/…")`. Mirror this exactly in Kotlin — do **not** normalise to one prefix. |
| TLS quirk (Python only) | Hyundai USA requires legacy ciphers: the Python client builds an SSL context with `CIPHERS = "DEFAULT@SECLEVEL=1"` (`create_cipher_context()` / `cipherAdapter`). On Android/OkHttp this has not been needed in BlueDeck, but if TLS handshakes fail on old head-unit-era endpoints, suspect cipher/SECLEVEL before auth. |

Contrast with OpenBlue (Canada): base `https://mybluelink.ca/tods/api/`, login `v2/login` +
MFA/OTP, PIN→`vrfypin`→`pAuth`, commands `drlck` / `evc/rfon`. **None of those exist on the US
host.** The US fork needs a new client class, a region selector at login, and separate DTOs.

---

## 2. Headers

### 2.1 Static headers sent on every US call (Python `API_HEADERS` in `HyundaiBlueLinkApiUSA.__init__`)

```text
content-type: application/json;charset=UTF-8
accept: application/json, text/plain, */*
accept-encoding: gzip, deflate, br
accept-language: en-US,en;q=0.9
user-agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 … Chrome/75.0.3770.142 Safari/537.36
host: api.telematics.hyundaiusa.com
origin: https://api.telematics.hyundaiusa.com
referer: https://api.telematics.hyundaiusa.com/login
from: SPA
to: ISS
language: 0
offset: <local UTC offset in whole hours, e.g. "-5" for EST, computed at client init>
sec-fetch-dest: empty · sec-fetch-mode: cors · sec-fetch-site: same-origin
refresh: false
encryptFlag: false
brandIndicator: H            (Hyundai; G = Genesis on the same host/stack)
client_id:     <see §2.3>
clientSecret:  <see §2.3>    (yes — sent as a header, in the clear, by design of this API)
```

BlueDeck equivalence: its OkHttp interceptor in `BluelinkConstants.kt` (`ApiClient`) adds
`Accept`, `User-Agent: okhttp/4.12.0`, `client_id`, `clientSecret`, `appId` (an app UUID const
in the same file), `deviceType: Android`, `from: SPA`, `language: 0`, `offset: -5`, `to: ISS`,
`encryptFlag: false` globally; each Retrofit method then re-declares most headers explicitly
with `User-Agent: okhttp/3.12.0` and per-call `offset` of `-5` (reads) or `-4` (commands) —
an inconsistency inherited from capturing different official-app flows. The Python set above
is self-consistent and is the safer one to port; BlueDeck proves the server tolerates
`okhttp/*` user agents and either offset.

### 2.2 Authenticated / per-vehicle headers (Python `_get_authenticated_headers` / `_get_vehicle_headers`)

On top of §2.1, after login:

```text
username: <account email>                       (all authenticated calls)
accessToken: <access_token>                     (all authenticated calls — header name is
                                                 "accessToken" in Python, "access_token"
                                                 in BlueDeck's Retrofit annotations; both observed working)
blueLinkServicePin: <4-digit Bluelink PIN>      (all authenticated calls in Python)
```

Per-vehicle calls add:

```text
registrationId: <vehicle.id>     — this is enrollment's "regid", NOT the VIN (see §4)
gen: <vehicle.generation as string, e.g. "2">
vin: <VIN>
APPCLOUD-VIN: <VIN>              — added explicitly for lock/unlock and horn/lights in Python;
                                   BlueDeck sends it on every vehicle call
```

Command-status polling adds (§7): `tid`, `login_id`, `service_type`.
Forced status refresh adds (§4.3): `REFRESH: true`.
EV trip details adds: `userId: <username>`.

### 2.3 Client id / secret — source locations only

Both repos embed the same public values extracted from the official app:

- Python: `client_id` and `clientSecret` entries in the `API_HEADERS` dict,
  `HyundaiBlueLinkApiUSA.__init__`, file `hyundai_kia_connect_api/HyundaiBlueLinkApiUSA.py`.
- Kotlin: `BluelinkConstants.CLIENT_ID` / `BluelinkConstants.CLIENT_SECRET`
  (comment in source: “Public client credentials from the Bluelink app (via bluelinky)”),
  file `BlueDeck/app/src/main/java/com/bluedeck/data/api/BluelinkConstants.kt`. The same file
  also holds `APP_ID`, used by BlueDeck's interceptor.
- Canada contrast (for the OpenBlue port): BlueDeck's `CA_CLIENT_ID` / `CA_CLIENT_SECRET` in
  the same constants file are a *different pair* for the TODS hosts — do not reuse across regions.

### 2.4 PIN handling — the biggest Canada→US difference

- **Canada (existing OpenBlue):** PIN is exchanged server-side: `POST vrfypin {pin}` →
  `pAuth` token, and every command carries `pAuth` (BlueDeck: `runCanadaPinCommand` /
  `getCanadaPinAuth`, with its own generated Canada `deviceId`).
- **USA:** there is **no PIN-verification endpoint**. The raw PIN is simply sent as the
  `blueLinkServicePin` header (Python: on every authenticated request via
  `_get_authenticated_headers`; BlueDeck: as the `bluelinkservicepin` header parameter on
  command methods only, default `""`). Lock in BlueDeck is called with
  `getServicePin(required = false)`, unlock/climate/charge with `required = true`.
  In Python's `Token`, the PIN is stored on the token object (`Token.pin`) and re-attached
  to every header set. Fork implication: delete the `vrfypin` step for the US region;
  prompt/store the PIN once and attach the header.
- PIN failure surfaces in the command *response body*, not the HTTP status — BlueDeck's
  `CommandResponse` DTO models it: `isBlueLinkServicePinValid`, `remainingAttemptCount`,
  `invalidAttemptMessage` (e.g. contains “Incorrect PIN”), `escalationWindow`, `errorCode`,
  `errorMessage`, `status`. Surface `remainingAttemptCount` in the UI; repeated bad PINs
  escalate/lock — do not retry commands blindly.

### 2.5 registrationId and device-id generation

- `registrationId` is **not generated by the client at all** for US Hyundai. It is the
  server-issued `regid` field from enrollment (§4): Python sets
  `headers["registrationId"] = vehicle.id` where `Vehicle.id = entry["regid"]`;
  BlueDeck threads a `registrationId: String` parameter (from its `Vehicle.regId`,
  `@SerializedName("regid")`) through every call, defaulting to `""`.
- There is **no US device-registration call** in either repo for Hyundai USA.
  Generated/persisted device IDs exist in BlueDeck only for the *other* regions:
  Canada (`PreferencesManager.getOrCreateCanadaDeviceId()`), EU (server-registered via
  CCSP `notifications/register`, stored deviceId) and AU (`getOrCreateAuDeviceId()`),
  and for **Kia USA** (`getOrCreateKiaUsDeviceId()`, a separate `KiaUsApiService` stack —
  Kia US is a different host, `api.owners.kia.com/apigw/v1/`, and is out of scope for a
  Hyundai-US fork). Fork implication: do not carry OpenBlue's Canada device-id logic into
  the US client.

---

## 3. Auth flow (login + refresh)

### 3.1 Login — `POST {LOGIN_API}oauth/token`

Full URL: `POST https://api.telematics.hyundaiusa.com/v2/ac/oauth/token`

Request body (JSON, Python `login()`; BlueDeck `LoginRequest` adds two extra fields the
server accepts: `"deviceKey": ""`, `"cvt": "m"`):

```json
{ "username": "<email>", "password": "<password>" }
```

Response body:

```json
{ "access_token": "…", "refresh_token": "…", "expires_in": "1799", "token_type": "…", "username": "…" }
```

- `expires_in` ≈ **1800 s (30 min)**. Python: `valid_until = now + expires_in`.
  BlueDeck: `TokenResponse.expiresIn: String = "1799"`, session considered valid until
  `expiresAt − 60 s` (`ensureValidAccessToken()` refreshes early inside a Mutex).
- Failure detection (Python `_check_response_for_errors` + login guard): if the JSON has
  `errorCode`, raise with `errorMessage` (+ `systemName`/`functionName` suffix); if
  `access_token` is absent, login failed. BlueDeck maps HTTP codes at login:
  401 → invalid username/password; 403 → account locked (use official app to unlock);
  429 → too many attempts, wait.
- **No MFA/OTP step exists in the Python US-Hyundai flow.** BlueDeck US-Hyundai login
  explicitly detects an OTP/device-verification demand in the error body (`otpKey` /
  `otp`+`verify`) and *bails out* with: login requires device verification that BlueDeck
  does not support for US Hyundai — sign in once in the official MyHyundai app, then retry.
  (Its full OTP machinery — `OtpChallenge`, `completeOtpLogin()` — is wired for Canada and
  Kia US, not Hyundai US.) Fork implication: implement the bail-out message, not an OTP UI,
  for v1.

### 3.2 Refresh — same endpoint, four fields or it 400s

`POST https://api.telematics.hyundaiusa.com/v2/ac/oauth/token` with **all four** fields:

```json
{ "username": "…", "password": "…", "grant_type": "refresh_token", "refresh_token": "…" }
```

Critical pitfall, documented identically in both repos (Python `refresh_access_token()`
docstring; BlueDeck `RefreshTokenRequest` comment: “US Hyundai: refresh requires username +
password + grant_type + refresh_token together”): the endpoint validates username/password
presence *before* looking at `grant_type`; sending only `grant_type` + `refresh_token`
returns **400 “password required”**. On success the `refresh_token` **rotates** — always
persist the returned one (fall back to the old one if the field is absent). Consequences:

- A US client **must store the account password** (Android Keystore / EncryptedSharedPreferences)
  to refresh silently; a refresh-token-only design (EU style) cannot work here.
- Fallback chain used by both: refresh fails (non-hard error) → one full password `login()`;
  hard failures (invalid_grant / invalid_token / revoked / 401 / 403 / “account locked” /
  bad credentials) → clear session and force re-login. BlueDeck deliberately does **not**
  clear tokens on network errors or 5xx (`isHardAuthRefreshFailure()`).

---

## 4. Vehicles and status

### 4.1 Enrollment / vehicle list — `GET {API_URL}enrollment/details/{username}`

- Headers: authenticated set (§2.2) only — no vehicle headers yet.
  BlueDeck adds `payloadGenerated: 20200226171938` and `includeNonConnectedVehicles: Y`.
- Response root: `enrolledVehicleDetails: [ { "vehicleDetails": { … }, "packageDetails": […] } ]`.
  Missing `enrolledVehicleDetails` ⇒ `AuthenticationError` in Python.
- Per-vehicle fields (`vehicleDetails`) the fork must read:

| JSON field | Use |
|---|---|
| `regid` | `Vehicle.id` → the `registrationId` header everywhere (§2.5) |
| `vin` | VIN headers / command bodies |
| `nickName` | display name |
| `modelCode`, `series`, `modelYear`, `color` | display / model |
| `enrollmentDate` | registration date |
| `enrollmentStatus` | vehicle disabled iff `== "CANCELLED"` |
| `evStatus` | `"E"` → EV, `"P"` → PHEV, `"N"` → ICE (drives climate/charge endpoint choice, §5) |
| `vehicleGeneration` | string → int, **default `"2"` when absent** (Python `get_vehicles`) → the `gen` header and the gen-2/gen-3 climate split (§5) |
| `odometer` | enrollment odometer (status odometer may be newer; Python takes max of the two) |
| `seatConfigurations.seatConfigs[]` | `seatLocationID` 1–4, `heatingCapable`, `ventCapable`, `supportedLevels` (e.g. `"2,6,7,8,3,4,5"` — codes in §6.6). BlueDeck extracts/caches this from the raw enrollment JSON because its Gson `Vehicle` model otherwise drops it. |
| capability flags | e.g. `fatcAvailable`, `steeringWheelHeatCapable`, `rearWindowHeatCapable`, `sideMirrorHeatCapable` — gate UI controls |
| `brandIndicator` | `"H"` Hyundai / `"G"` Genesis (same US stack) |

- Caching guidance (Python): enrollment is cached for the client instance's lifetime
  (capabilities/generation rarely change); only the vehicle-list call force-refreshes it.

### 4.2 Cached status — `GET {API_URL}rcs/rvs/vehicleStatus`

Headers: per-vehicle set (§2.2), `REFRESH` header absent/`false`.
Response root: `{ "hataTID": "…", "vehicleStatus": { … } }` — parse `vehicleStatus` (§6).
Python normalises `vehicleStatus.dateTime` by stripping `- T : Z`
(`"2025-02-19T15:48:19Z"` → `"20250219154819"`); the 2024 Ioniq 5 fixture already stores it
compact (`"20240901120000"`). Handle both.

Python's full cached-state update (`update_vehicle_with_cached_state`) is actually
**3 calls**, in order:

1. `GET enrollment/details/{username}` (cached, §4.1) → `vehicleDetails`
2. `GET rcs/rvs/vehicleStatus` (cached) → `vehicleStatus`
3. `GET ts/alerts/maintenance/evTripDetails` (EV only; extra header `userId: <username>`)
   → trip/energy detail under `evTripDetails.tripdetails[]`
   (`startdate`, `totalused`, `drivetrain`, `climate`, `accessories`, `batterycare`, `regen`,
   `odometer.value`, `mileagetime.value`, `duration.value`, `avgspeed.value`, `maxspeed.value`)
4. Location (§4.4) is merged into `vehicleStatus.vehicleLocation` **only if the odometer
   moved** since the previous update (and the location throttle in §4.4 allows it).

### 4.3 Forced (live) status — same URL, `REFRESH: true`

`GET {API_URL}rcs/rvs/vehicleStatus` with header `REFRESH: "true"`
(Python `_get_vehicle_status(refresh=True)`; BlueDeck passes `refresh = forceRefresh.toString()`
as the `REFRESH` header). This wakes the car — it is the expensive call for rate limits
and 12 V drain (§8). Python's `force_refresh_vehicle_state` then always attempts the
location call, falling back to the cached `vehicleLocation` from the status body on failure.

### 4.4 Location — two sources

**(a) Inside status:** `vehicleStatus.vehicleLocation.coord.lat` / `.lon`, timestamp
`vehicleStatus.vehicleLocation.time`. In the Santa Fe capture the same block appears with
`coord: { alt, lon, type, lat }` and no time. Check this first — it's free.

**(b) Dedicated call:** `GET {API_URL}rcs/rfc/findMyCar`, per-vehicle headers.
Success response is the location object at the **root**: `{ "coord": { "lat", "lon", "alt", "type" }, "speed": { "value", "unit" }, "head"/"heading": … }`
(BlueDeck `VehicleLocation` / `Coordinate` / `Speed` DTOs).
Python throttles it hard: only when the odometer moved **and** the last location fetch was
> 1 h ago (`last_loc_timestamp` initialised to 3 h ago so the first update may fetch).
Error bodies are common and must be non-fatal (§7): feature-off (`errorCode 502`,
`errorSubCode "GEN"`, system `BLODS`) and HATA failure (`errorCode 504`, `errorSubCode "HT_504"`).

### 4.5 Surround View (SVM) — present in the Python US client, optional for the fork

- `GET {API_URL}svm/getSVMDetails` → `svmDetails[0].svmDetail`: `svmImage` (base64 JPEG
  composite), `imageSize`, `validAngleofView[]`, `doorOpen`, `trunkOpen`,
  `gpsDetail: { time, coord{lat,lon}, head, speed{value,unit} }`.
- `POST {API_URL}svm/findMyCarSVM`, body `{ "vin", "username", "gen", "blueLinkServicePin" }`,
  requires an explicit safety acknowledgement in the Python API
  (`acknowledged_warning=True`). Poll `getSVMDetails` every 15 s after a 15 s initial wait,
  up to 120 s, until the capture timestamp changes. `502` + `errorSubCode "HT_533"` =
  a previous capture is still pending (`DuplicateRequestError`).
- 2022 Ioniq 5 **SEL has no surround-view cameras** (Limited feature) — expect this to be
  unavailable on the target car; hide/disable rather than error.

---

## 5. Gen 2 vs gen 3 — what actually differs (US legacy stack)

| Aspect | Gen 2 (2022 Ioniq 5; USA fixture default) | Gen 3 (e.g. 2025 Santa Fe Hybrid enrollment) |
|---|---|---|
| `gen` header | `"2"` | `"3"` (BlueDeck *defaults* every call to `"3"` — a fork must pass the enrollment value instead; Python always sends `str(vehicle.generation)`) |
| EV climate start body | **Minimal** — only `airCtrl`, `airTemp`, `defrost`, `heating1`. Python comment: “Older vehicles do not support seat heater vent info or duration”, so `igniOnDuration` and `seatHeaterVentInfo` are **omitted** | Adds `igniOnDuration` and full `seatHeaterVentInfo` (§5.2) |
| ICE climate start body | Always includes duration + seats + `username`/`vin` (see §5.2) — the gen split in Python is applied only on the EV branch | Same |
| Status seat block name | `vehicleStatus.seatHeaterVentState` (`fl/fr/rl/rrSeatHeatState`) in the Ioniq 5 fixture | `vehicleStatus.seatHeaterVentInfo` (`drv/ast/rl/rrSeatHeatState`) in the Santa Fe capture — **parse both names** (BlueDeck's status DTO only declares `seatHeaterVentInfo`; Python reads `seatHeaterVentState` for status and writes `seatHeaterVentInfo` in commands) |
| Everything else | Same hosts, auth, lock/charge endpoints, status root | Same |

There is no separate US “gen 3 host” and no CCS2 control-token flow on this stack —
that is EU/AU. Keep the fork on `HyundaiBlueLinkApiUSA` semantics for both gens.

---

## 6. Parsing `vehicleStatus` — exact field paths (2022 Ioniq 5 EV)

Notation: paths are relative to the response root unless they start with `vehicleStatus.`.
Primary source: Python `_update_vehicle_properties()` plus fixture
`tests/fixtures/us_hyundai_ioniq_5_2024_cached.json` (US Ioniq 5, cached; values in brackets
are that fixture's). Cross-check capture: `tests/fixtures/updates/responses/usa_hyundai_vehicle_status_1040.json`.

### 6.1 EV battery, charging, range

| UI value | JSON path | Notes |
|---|---|---|
| EV battery % | `vehicleStatus.evStatus.batteryStatus` | int % — fixture `[85]`. Not `battery.batSoc` (that's 12 V) |
| Charging active | `vehicleStatus.evStatus.batteryCharge` | bool — `[false]` |
| Plug state / plug type | `vehicleStatus.evStatus.batteryPlugin` | int code, BlueDeck/Bluelinky mapping: `0` unplugged, `1` **DC fast**, `2` portable AC, `3` station AC (L2), `4` AC plugged but paused/scheduled (gen-3 observation). Note this is the *current plug* code — different namespace from `targetSOClist.plugType` (§6.2) |
| Charging power (instantaneous) | `vehicleStatus.evStatus.realTimePower` | kW. Python prefers this; the sibling `evStatus.batteryStndChrgPower` `[0.0]` is the *standard/AC* rate and stays at the AC value during a DC charge — do not use it as “current power”. BlueDeck accepts alternates `chargingPower` / `chargePower` / `chargePwr` / `realTimePower` |
| EV range | `vehicleStatus.evStatus.drvDistance[0].rangeByFuel.evModeRange.value` + `.unit` | `[240, unit 3]` |
| Total range | `vehicleStatus.evStatus.drvDistance[0].rangeByFuel.totalAvailableRange.value` + `.unit` | `[240, unit 3]` |
| Charge time remaining (current type) | `vehicleStatus.evStatus.remainTime2.atc.value` | minutes — `[0]` |
| … fast (DC) estimate | `vehicleStatus.evStatus.remainTime2.etc1.value` | minutes |
| … portable estimate | `vehicleStatus.evStatus.remainTime2.etc2.value` | minutes |
| … station (AC) estimate | `vehicleStatus.evStatus.remainTime2.etc3.value` | minutes. Captures may include `.unit: 1` alongside `value`. Legacy shape also exists: `evStatus.remainChargeTime[]` (`remainChargeType` + `remainTime{value,timeUnit}`) in BlueDeck's DTO |
| Status timestamp | `vehicleStatus.dateTime` | see §4.2 normalisation |
| V2L / V2X (where present) | `lastVehicleInfo.vehicleStatusRpt.vehicleStatus.evStatus.v2xStatus` / `.v2lStatus` | Only checked by Python on that alternate root — not in the Ioniq 5 cached fixture; treat as optional |

**Distance-unit trap:** Python's `DISTANCE_UNITS` maps unit `1 → km`, `2 → miles`, **`3 → miles`**.
BlueDeck's range getters treat `1 → miles`, `2 → km-converted`. The repos disagree on unit 1;
unit 3 (the actual Ioniq 5 fixture value) is miles in both. Hard-code: `3 = miles`, and log
raw unit/value during US testing before trusting 1/2.

### 6.2 Charge limits (targets)

| UI value | JSON path | Notes |
|---|---|---|
| DC target % | `vehicleStatus.evStatus.reservChargeInfos.targetSOClist[]` entry with `plugType == 0` → `targetSOClevel` | `[80]`. Python takes the **last** matching entry |
| AC target % | same list, entry with `plugType == 1` → `targetSOClevel` | `[100]` (fixture is at 100; typical user value 80–90) |
| (schedule blocks, same parent) | `evStatus.reservChargeInfos.reservChargeInfo` / `reserveChargeInfo2` / `ect` / `offpeakPowerInfo` | Modelled in BlueDeck DTOs (`ReservChargeInfos`, `ReservationChargeInfo*`, `ChargeScheduleWindow`); not needed for a limits-only v1 |

⚠ Namespace collision to document in code: `targetSOClist.plugType` (`0 = DC`, `1 = AC`)
is **not** `evStatus.batteryPlugin` (`0 = unplugged`, `1 = DC`, `2/3 = AC`). BlueDeck's
`EVStatus.acChargeTarget` accepts plugTypes 1/2/3 and `dcChargeTarget` accepts 0, based on a
US Ioniq 9 capture; Python's last-match 0/1 rule is the conservative one to port.

### 6.3 Doors, locks, openings, windows

| UI value | JSON path | Fixture |
|---|---|---|
| Locked (bool) | `vehicleStatus.doorLock` | `[true]`. Captures also carry `vehicleStatus.doorLockStatus` as a *string* (`"true"`); BlueDeck accepts `doorLock == true` OR `doorLockStatus in {"LOCKED","true","1"}` |
| Door open ×4 | `vehicleStatus.doorOpen.frontLeft` / `.frontRight` / `.backLeft` / `.backRight` | int `0/1`, all `[0]` |
| Hood | `vehicleStatus.hoodOpen` | bool `[false]` |
| Trunk | `vehicleStatus.trunkOpen` | bool `[false]` |
| Windows ×4 | `vehicleStatus.windowOpen.frontLeft` / `.frontRight` / `.backLeft` / `.backRight` | int `0/1`. Python notes the US client reports `supports.window_control = False` — display only, no US window command in either repo |
| Charge-port door (BlueDeck only) | `vehicleStatus.evStatus.chargePortDoorOpenStatus` — actually `evStatus.chargePortDoorOpen` (int, `-1` = unknown default in DTO) | Not in Python parser |

### 6.4 Climate / comfort state

| UI value | JSON path | Notes |
|---|---|---|
| Climate running | `vehicleStatus.airCtrlOn` | bool `[false]` |
| Engine/ignition on | `vehicleStatus.engine` (bool); captures also have `vehicleStatus.ignitionStatus` (string `"false"`) and `acc` | Prefer `engine` (Python); BlueDeck checks both |
| Set cabin temp | `vehicleStatus.airTemp.value` (+ `.unit`, `1` = °F) | string: `"72"`, or `"LO"` / `"HI"` / `"OFF"` — Python clamps LO→62, HI→81 (its `temperature_range` is 62–81 °F in 1° steps), ignores `OFF` |
| Defrost | `vehicleStatus.defrost` | bool. (Santa Fe capture uses `defrostStatus: "false"` string instead — parse both) |
| Steering-wheel heat | `vehicleStatus.steerWheelHeat` | int `0` = off (seat-code scale for on levels) |
| Rear-window heat | `vehicleStatus.sideBackWindowHeat` | int |
| Mirror heat | `vehicleStatus.sideMirrorHeat` | int (present in fixture, not in BlueDeck status DTO) |
| Seats ×4 | `vehicleStatus.seatHeaterVentState.flSeatHeatState` / `.frSeatHeatState` / `.rlSeatHeatState` / `.rrSeatHeatState` (gen-2 name) **or** `vehicleStatus.seatHeaterVentInfo.drvSeatHeatState` / `.astSeatHeatState` / `.rlSeatHeatState` / `.rrSeatHeatState` (gen-3/capture name) | codes in §6.6 |

### 6.5 Odometer, 12 V, tires, misc warnings

| UI value | JSON path | Notes |
|---|---|---|
| Odometer | `vehicleStatus.odometer` **or** enrollment `vehicleDetails.odometer` | Python: `max()` of the two non-null values, unit = miles. Fixture pair: status-side details odometer `[12500]`; Santa Fe capture has `vehicleStatus.odometer: 239` directly |
| 12 V battery % | `vehicleStatus.battery.batSoc` | int — fixture `[92]`, capture `[93]`. Also `battery.batState`, `battery.powerAutoCutMode`, `battery.batSignalReferenceValue.batWarning` (warning threshold, `[65]`) |
| Tire pressure warnings ×4 + all | `vehicleStatus.tirePressureLamp.tirePressureWarningLampFrontLeft` / `…FrontRight` / `…RearLeft` / `…RearRight` / `…All` | bools, all `[false]`. **Naming trap:** the Ioniq 5 fixture uses `Warning` (`tirePressureWarningLamp…`); CCS2/other regions use different spellings — parse exactly these for US legacy |
| Tire pressures ×4 | `vehicleStatus.tirePressure.tirePressureFrontLeft` / `…FrontRight` / `…RearLeft` / `…RearRight` (+ per-corner `datetime…` stamps) | `[36, 36, 37, 36]` in fixture. Python treats the unit as PSI whenever the `tirePressure` block exists |
| Washer fluid warning | `vehicleStatus.washerFluidStatus` | bool |
| Brake-fluid warning | `vehicleStatus.breakOilStatus` | bool (spelling is `break`, as in the API) |
| Smart-key battery warning | `vehicleStatus.smartKeyBatteryWarning` | bool |
| Fuel (ICE/PHEV only) | `vehicleStatus.fuelLevel`, `vehicleStatus.lowFuelLight`, `vehicleStatus.dte.value` + `.unit` | EV fixture has `fuelLevel: 0` — ignore on BEV (BlueDeck explicitly suppresses placeholder fuel telemetry for known BEV models) |

### 6.6 Seat state codes (shared read + write scale, Python `const.SEAT_STATUS`)

```text
0 = Off · 1 = On (generic) · 2 = Off (alternate, seen in captures)
3 = Low Cool · 4 = Medium Cool · 5 = High Cool
6 = Low Heat · 7 = Medium Heat · 8 = High Heat
```

The same codes are written in climate commands (`drvSeatHeatState` etc.) and appear in
enrollment `supportedLevels`. In the Santa Fe status capture all four seats read `2` (Off).

---

## 7. Commands — endpoints, bodies, responses, polling

All commands: `POST` to `{API_URL}<path>` with the per-vehicle headers (§2.2).
All return the transaction id the same way (Python `_get_transaction_id`):
**response headers**, first present of `tmsTid`, `transactionId`, `Xid` — not the body.
**Success is often HTTP 200 with an empty body** (Python `_safe_parse_json` exists solely
for this): never call a strict JSON parser on a command response before checking for an
empty body; if a body *is* present, run the error check (§8) on it.

BlueDeck divergence to know: its US command methods return `Response<ResponseBody>` and it
validates via `validateRawCommandResponse()` + the `CommandResponse` DTO (§2.4); it does
**not** implement transaction polling for US Hyundai (see §7.6).

### 7.1 Lock / unlock

| Action | Endpoint | Body |
|---|---|---|
| Lock | `POST ac/v2/rcs/rdo/off` | JSON (Python): `{"userName": "<username>", "vin": "<VIN>"}` |
| Unlock | `POST ac/v2/rcs/rdo/on` | same |

Extra header (Python, both): `APPCLOUD-VIN: <VIN>`.
BlueDeck sends these two as **form-url-encoded fields** `userName` + `vin` instead of a JSON
body (`@FormUrlEncoded @POST("ac/v2/rcs/rdo/off") lockDoors(…, @Field("userName"), @Field("vin"))`).
Both shapes are in production code; the captured official-app behaviour behind BlueDeck used
the form shape — if one is rejected in testing, try the other before debugging headers.

### 7.2 Climate start / stop (EV vs ICE split)

| Vehicle | Start | Stop |
|---|---|---|
| EV | `POST ac/v2/evc/fatc/start` | `POST ac/v2/evc/fatc/stop` |
| ICE/PHEV | `POST ac/v2/rcs/rsc/start` | `POST ac/v2/rcs/rsc/stop` |

Stop: no body. Start bodies (Python `start_climate`, defaults: temp 70 °F, duration 5 min,
climate on, defrost false, heating 0, all seats 0):

EV, gen 3 (and the full shape BlueDeck's `RemoteStartRequest` models):

```json
{
  "airCtrl": 1,
  "airTemp": { "value": "70", "unit": 1 },
  "defrost": false,
  "heating1": 0,
  "igniOnDuration": 5,
  "seatHeaterVentInfo": {
    "drvSeatHeatState": 0, "astSeatHeatState": 0,
    "rlSeatHeatState": 0,  "rrSeatHeatState": 0
  }
}
```

EV, **gen 2 (target car): omit the last two keys entirely** (§5):

```json
{ "airCtrl": 1, "airTemp": { "value": "70", "unit": 1 }, "defrost": false, "heating1": 0 }
```

- `airTemp.value` is a **string** on the EV branch in Python; `unit: 1` = °F.
  BlueDeck's `AirTempRequest` is `{ "hvacTempType": 0, "unit": 1, "value": "70" }` and its
  `RemoteStartRequest` always includes `Ims: 0`, `igniOnDuration` (default 10),
  `seatHeaterVentInfo`, `username`, `vin` — i.e. BlueDeck always sends the full ICE-shaped
  body even to the EV endpoint. Prefer Python's gen-gated minimal body for a gen-2 car.
- ICE body (Python): `{ "Ims": 0, "airCtrl", "airTemp": {"unit": 1, "value": <number>},
  "defrost", "heating1", "igniOnDuration", "seatHeaterVentInfo": {…},
  "username": "<username>", "vin": <vehicle.id — note: regid, not VIN, in this one field> }`.
- `heating1`: 0 = no accessory heat, 1 = heated steering wheel + rear defrost bundle
  (this is how Python's `ClimateRequestOptions.heating` is used).
- Seat values use the §6.6 codes.
- BlueDeck also declares legacy alternates `POST ac/v2/rcs/rfc/start|stop` (`startClimate`/
  `stopClimate`) — not used by the Python client; treat as dead ends unless testing proves otherwise.

### 7.3 Charge start / stop (EV only)

| Action | Endpoint (Python) | Body |
|---|---|---|
| Start | `POST ac/v2/evc/charge/start` | none (headers only) |
| Stop | `POST ac/v2/evc/charge/stop` | none |

BlueDeck declares the same two paths but attaches a body
`EVChargeRequest { "userName", "vin", "action": "start"|"stop" }`
(comment in source: “Hyundai USA charge start/stop uses this body shape”). Python sends no
body and its dedicated tests (`tests/test_us_charge_command.py`) pin that. Port Python's
no-body form first; keep BlueDeck's body as the fallback experiment. Both return the
transaction id from headers; empty-body 200 = success (this exact case is why
`_safe_parse_json` exists — charge commands on newer vehicles returned empty 200s that
crashed strict parsers).

### 7.4 Set charge limits

`POST ac/v2/evc/charge/targetsoc/set`, body (Python `set_charge_limits`):

```json
{ "targetSOClist": [
    { "plugType": 0, "targetSOClevel": "<DC percent, int>" },
    { "plugType": 1, "targetSOClevel": "<AC percent, int>" }
] }
```

(`plugType` namespace = §6.2: 0 DC, 1 AC.) BlueDeck equivalents, in the order it tries them:
`POST ac/v2/evc/charge/targetsoc/set` with `SpaChargeTargetRequest { targetSOClist }`
(same body, no username/vin), and `PUT ac/v2/evc/soc` with
`ChargeTargetRequest { username, vin, targetSOClist }`; read-back via the status field
(§6.2) — its `GET api/v2/spa/vehicles/{id}/charge/target` is a CCS-style path, not part of
the Python legacy-US flow. Schedule setting (not limits) in BlueDeck:
`POST ac/v2/evc/charge/reserv/set` with an opaque JSON body.

### 7.5 Horn and lights

| Action | Endpoint | Body | Polling service_type (§7.6) |
|---|---|---|---|
| Lights only | `POST ac/v2/rcs/rhl/light` | `{"userName", "vin"}` + header `APPCLOUD-VIN` | `LIGHTS_ONLY` |
| Horn + lights | `POST ac/v2/rcs/rhl/hnl` | same | `HORN_AND_LIGHTS` |

(BlueDeck declares form-encoded variants under `rcs/rfc/horn` and `rcs/rfc/light` —
again, Python's `rhl/*` paths are the tested ones; see §7.2 note on alternates.)

### 7.6 Command-status polling — `GET {API_URL}rmt/getRunningStatus`

Full URL: `GET https://api.telematics.hyundaiusa.com/ac/v2/rmt/getRunningStatus`
(Python `check_action_status`). This is **not** under `rcs/`.

Headers: per-vehicle set, plus:

```text
tid: <transaction id from the command's response headers>
login_id: <username>
service_type: REMOTE_POLL        (default)
              | LIGHTS_ONLY      (for §7.5 light)
              | HORN_AND_LIGHTS  (for §7.5 horn+lights)
```

The Python client remembers the service_type per transaction id at command time
(`_action_service_types` map, popped on first poll).

Response: JSON with `status`: `"SUCCESS"` → done; `"ERROR"` → failed; anything else / empty
body → pending/unknown. Synchronous mode in Python polls every **2 s** up to the caller's
timeout (default **120 s**), then reports TIMEOUT; single-shot mode returns
SUCCESS / FAILED / PENDING / UNKNOWN once. BlueDeck does not poll this endpoint for US
Hyundai — it treats an accepted (2xx, valid-PIN) command response as success and lets the
next status refresh show the result (its README troubleshooting notes status “may trail
the accepted command”). For the fork, implement Python-style polling: it is the only way
to distinguish a silent car-side failure from success.

---

## 8. Errors, rate limits, and login pitfalls (repo-sourced only)

### 8.1 Error envelope (all US calls)

Any JSON body may be an error instead of data:

```json
{ "errorCode": 502, "errorSubCode": "GEN", "errorMessage": "…",
  "errorSubMessage": "…", "systemName": "HATA", "functionName": "remoteVehicleStatus", "serviceName": "…" }
```

- `errorCode` arrives as **int or string** — normalise to string before comparing (Python does).
- Rule from `_check_response_for_errors`: `errorCode == "502"` ⇒ transient server failure
  (`ServiceTemporaryUnavailable`) — **never treat 502 as an auth failure and never log the
  user out for it**. All other codes ⇒ generic API error carrying
  `errorMessage` + `[systemName/functionName]`.
- “HATA” is Hyundai America's telematics backend name in these bodies. Documented captures:
  - `remoteVehicleStatus` failing server-side: HTTP/body 502, system `HATA`
    (the reason for the 502 rule above).
  - findMyCar failing: `errorCode 504`, `errorSubCode "HT_504"`, system `HATA`,
    function `findMyCar`, message “We're sorry, but we could not complete your request…”
    (fixture `us_hyundai_findmycar_hata_error_937.json`).
  - findMyCar with the location feature off in the subscription: `errorCode 502`,
    `errorSubCode "GEN"`, system `BLODS`, “Feature Status is OFF” (fixture
    `us_hyundai_findmycar_feature_off.json`) — surface as “feature not in subscription”,
    not as a crash.

### 8.2 Rate limits — what the repos actually encode

Neither repo publishes a numeric US quota in its docs. What is encoded in code — design to it:

| Call | Limiting behaviour in reference code |
|---|---|
| `findMyCar` (§4.4) | Body `errorCode 502` + `errorSubCode "HT_534"` = **location rate limit exceeded** (checked explicitly in Python; log-and-return-None, never retry immediately). Client-side gate on top: odometer-moved **and** > 1 h since last fetch |
| `findMyCarSVM` (§4.5) | `errorSubCode "HT_533"` = previous request still pending → duplicate-request error; do not re-fire |
| Forced status (`REFRESH: true`) | No client-side quota in code, but it wakes the car; Python's architecture separates cached reads (cheap, default) from forced reads (explicit) and BlueDeck does the same (`forceRefresh` flag). Fork rule: default every screen/widget refresh to **cached**, forced only on explicit pull-to-refresh / after a command poll completes |
| Login | HTTP 429 = too many attempts (BlueDeck login mapping). Back off; do not loop logins — refresh (§3.2) exists precisely to avoid re-login every 30 min |
| General | Commands return transaction ids precisely so clients poll §7.6 instead of spamming status; poll at 2 s × ≤120 s and stop |

### 8.3 Login pitfalls checklist

1. **Refresh needs the password** (§3.2) — the #1 US-specific trap. Storing only tokens
   guarantees a broken refresh and a 400.
2. **OTP/device verification can appear on US Hyundai login** and is *unsupported* by
   BlueDeck's US flow — recovery is “sign in once in the official MyHyundai app, then
   retry” (§3.1). Do not promise an in-app OTP flow for Hyundai US in v1.
   (Canada and Kia US *do* have OTP flows in these repos — different stacks.)
3. **403 at login = account locked**, unlock via the official app (BlueDeck mapping);
   401 = bad credentials. Neither should be retried automatically.
4. **502 ≠ logged out** (§8.1). BlueDeck's refresh path also refuses to clear the session
   on 5xx/network errors — copy that behaviour or users get bounced to login on every
   Hyundai outage.
5. **Empty-body 200s on commands** (§7) — strict JSON parsing will report false failures
   for lock/charge/climate on success.
6. **Account/vehicle identity comes from enrollment, not from login.** Login returns only
   tokens; `regid`, `vehicleGeneration`, `evStatus` and seat configs all arrive via §4.1.
   A client that tries commands before fetching enrollment has no valid `registrationId`
   or `gen`.
7. **Header-name drift:** Python sends `accessToken`, BlueDeck sends `access_token`;
   Python's static `client_id`/`clientSecret` headers go on *every* call (BlueDeck: via
   interceptor + per-method params). Keep one consistent set per client build and log
   (redacted) headers in debug builds — both repos do extensive header/body debug logging
   for this API because small header changes are the usual breakage.
8. **Timestamps/offsets:** the static header set includes a local-UTC-offset `offset`
   header computed at init (Python) — a stale offset across DST changes is a known
   class of weird failure; recompute per session, not per install.

---

## 9. Kotlin port blueprint (from BlueDeck's structure)

BlueDeck already solved the “many regions, one app” shape OpenBlue needs — mirror it:

```text
data/api/
  BluelinkConstants.kt   → object BluelinkConstants { BASE_URL_US_HYUNDAI, API_HOST,
                           CLIENT_ID / CLIENT_SECRET / APP_ID (values per §2.3) }
                           + class ApiClient(baseUrl) — Retrofit.Builder + OkHttp
                             interceptor adding the §2.1 global headers
                           + enum Region { US_HYUNDAI(baseUrl, "USA — Hyundai"), … }
                             ← OpenBlue fork: add US_HYUNDAI alongside its Canada entries
  BluelinkApiService.kt  → Retrofit interface for US Hyundai (the file to copy/adapt):
                             getToken / refreshToken      @POST("v2/ac/oauth/token")
                             getVehicles                  @GET("ac/v2/enrollment/details/{username}")
                             getVehicleStatus             @GET("ac/v2/rcs/rvs/vehicleStatus")
                             getVehicleLocation           @GET("ac/v2/rcs/rfc/findMyCar")
                             lockDoors / unlockDoors      @FormUrlEncoded @POST("ac/v2/rcs/rdo/off|on")
                             startEvClimate / stopEvClimate @POST("ac/v2/evc/fatc/start|stop")
                             startEngine / stopEngine     @POST("ac/v2/rcs/rsc/start|stop")   (ICE)
                             startCharging / stopCharging @POST("ac/v2/evc/charge/start|stop")
                             setSpaChargeTargets          @POST("ac/v2/evc/charge/targetsoc/set")
                             setChargeTargets             @PUT("ac/v2/evc/soc")              (alternate)
                             hornAndLights / flashLightsOnly @FormUrlEncoded @POST("ac/v2/rcs/rfc/horn|light")  (see §7.5 divergence)
                           ⚠ BlueDeck has NO getRunningStatus method — add one per §7.6.
  CanadaApiService.kt    → the existing TODS client (in the fork: OpenBlue's current client
                           plays this role; keep it untouched behind the region switch)
data/models/Models.kt    → DTOs to copy/adapt: LoginRequest, RefreshTokenRequest (with the
                           §3.2 warning as a comment), TokenResponse, Vehicle /
                           VehicleDetail / VehicleListResponse (enrollment), VehicleStatus /
                           VehicleStatusData / EVStatus / ReservChargeInfos / ChargeTarget,
                           VehicleLocation / Coordinate, LockUnlockRequest,
                           RemoteStartRequest / AirTempRequest / SeatInfo, EVChargeRequest,
                           SpaChargeTargetRequest / ChargeTargetRequest, CommandResponse
data/repository/
  VehicleRepository.kt   → the orchestrator pattern to follow:
                             login() dispatches on region (… else -> US Hyundai token call)
                             ensureValidAccessToken() — Mutex-guarded, 60 s early refresh,
                               region-dispatched refresh, hard-vs-soft failure split
                             refreshUsHyundaiAccessToken() / refreshPasswordBasedSession()
                             getVehicles() — parses enrollment JSON with Gson into
                               VehicleListResponse, back-fills seatConfigurations from
                               the raw enrollment entry, retries once after refresh on 401/403
                             getVehicleStatus(vin, forceRefresh, registrationId, generation,
                               brandIndicator) — threads regid/gen/brand into headers
                             lockDoors()/unlockDoors()/startEngine()/… — one method per
                               command, region-dispatched, US path at the bottom
                             getServicePin() — PIN from secure prefs; required-ness varies
                               per command (§2.4)
  PreferencesManager.kt  → session storage (tokens, expiry, username, PIN, region);
                           SecureCredentialsManager → Keystore-backed username/password/PIN
                           (mandatory for US refresh, §3.2)
```

Suggested fork order: (1) Region enum + login/refresh (§3) → (2) enrollment (§4.1) →
(3) cached status + §6 parser → (4) lock/unlock + polling (§7.1/§7.6) →
(5) climate with the gen-2 body (§7.2) → (6) charge start/stop + limits (§7.3/§7.4) →
(7) location (§4.4) → (8) forced refresh, horn/lights, SVM (hide on SEL).

---

## 10. Quick endpoint index (US Hyundai, legacy)

Base A = `https://api.telematics.hyundaiusa.com/v2/ac/` · Base B = `https://api.telematics.hyundaiusa.com/ac/v2/`

| # | Method & URL | Purpose | Body / key headers |
|---|---|---|---|
| 1 | POST A`oauth/token` | Login | `{username, password}` |
| 2 | POST A`oauth/token` | Refresh | `{username, password, grant_type:"refresh_token", refresh_token}` |
| 3 | GET B`enrollment/details/{username}` | Vehicle list + capabilities | auth headers |
| 4 | GET B`rcs/rvs/vehicleStatus` | Status (cached; `REFRESH:true` = forced) | vehicle headers |
| 5 | GET B`rcs/rfc/findMyCar` | Location (throttled, §4.4) | vehicle headers |
| 6 | GET B`ts/alerts/maintenance/evTripDetails` | EV trip details | vehicle headers + `userId` |
| 7 | POST B`rcs/rdo/off` / `…/on` | Lock / unlock | `{userName, vin}` (JSON or form) + `APPCLOUD-VIN` |
| 8 | POST B`evc/fatc/start` / `…/stop` | EV climate start/stop | §7.2 body (gen-gated) / none |
| 9 | POST B`rcs/rsc/start` / `…/stop` | ICE climate start/stop | §7.2 body / none |
| 10 | POST B`evc/charge/start` / `…/stop` | Charge start/stop | none (Python) |
| 11 | POST B`evc/charge/targetsoc/set` | Set charge limits | `{targetSOClist:[{plugType,targetSOClevel}…]}` |
| 12 | POST B`rcs/rhl/light` / `…/hnl` | Lights / horn+lights | `{userName, vin}` + `APPCLOUD-VIN` |
| 13 | GET B`rmt/getRunningStatus` | Command status polling | headers `tid`, `login_id`, `service_type` |
| 14 | GET B`svm/getSVMDetails` | Latest surround-view capture | vehicle headers |
| 15 | POST B`svm/findMyCarSVM` | Request surround-view capture | `{vin, username, gen, blueLinkServicePin}` |

*Researched from the two repos named in the header. Endpoints are an undocumented,
reverse-engineered API — Hyundai can change them at any time (both repos' disclaimers say
the same). Verify each call against a debug-logging build before shipping.*
