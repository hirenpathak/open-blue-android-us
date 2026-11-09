# OpenBlue — USA support fork notes

Goal: add Hyundai USA (Bluelink, api.telematics.hyundaiusa.com) support to OpenBlue,
which today is Canada-only (mybluelink.ca / kiaconnect.ca "tods" API).
Owner's car for testing: 2022 Hyundai Ioniq 5 SEL, US, Gen5W head unit (gen 2 vehicle in US API terms).
Canada behaviour must stay untouched.

## How the Canada code is wired (mapped 2026-11-09)

- `api/Brand.kt` — enum of brand/host/client credentials; `baseUrl = https://<host>/tods/api/`.
  Login screen already picks a Brand; region selection will ride on this mechanism.
- `api/BluelinkClient.kt` (836 lines) — concrete class, Canada API:
  login `v2/login` (+ MFA otp flow), `vhcllst`/`sltvhcl` (vehicles + caps),
  status `lstvhclsts` (cached) / `rltmvhclsts` (live), commands via `command(path, vehicleId, extra)`
  after PIN exchange (`vrfypin` -> pAuth header): `drlck`/`drulck` lock/unlock,
  `evc/rfon`/`evc/rfoff` climate, `evc/setsoc` charge limits, `evc/chport*`, horn/lights,
  result polling in `awaitResult`. Token has no refresh endpoint in Canada — re-login.
- `api/Models.kt` — `VehicleInfo(id, nickname, model, year, vin, fuelKindCode, kindCode)`,
  `VehicleCaps` (gen type, seat options, odometer, feature flags), `ClimateSettings`, `CarLocation`.
- `ui/StatusModel.kt` — parses a Canada-shaped status JsonObject: `evStatus.batteryStatus`,
  `evStatus.batteryCharge`, `evStatus.drvDistance[0].rangeByFuel...`, `doorLock`, `doorOpen.*`,
  `airCtrlOn`, `airTemp.value` (hex temp table!), `seatHeaterVentInfo.*SeatHeatState`,
  `battery.batSoc`, `tirePressureLamp.*`, `lastStatusDate`.
- `MainViewModel.kt` (2088 lines) — holds `private val api = BluelinkClient(store)` and calls it
  directly. Canada-only extras (Bluelink Store, in-app public charging in `api/Charging.kt`,
  SVM surround photos, server schedules in `api/Schedules.kt`) hang off the same client/VM.
- `data/SecureStore.kt` — encrypted prefs: username/password/pin, accessToken + expiry,
  brand, deviceId, selected vehicle, caps, cached status JSON.

## Key insight: the US status JSON is a close cousin

The US legacy API (`/ac/v2/rcs/rvs/vehicleStatus`) returns a `vehicleStatus` object whose field
names largely match the Canada shape StatusModel already reads (evStatus.*, doorOpen, airCtrlOn,
airTemp, seatHeaterVentInfo, battery.batSoc, tirePressureLamp). Differences to translate:
unwrap the `vehicleStatus` wrapper, `doorLockStatus` -> `doorLock`, US date format in
`dateTime` -> `lastStatusDate`, a few EV field renames (see US_API_MAP.md when written).
So the USA client will *translate* US status JSON into the Canada shape; the UI needs no changes
for status display.

## Fork design (v1 scope)

1. `api/Region.kt` (new): region/brand selection = Hyundai Canada, Kia Canada (existing),
   Hyundai USA (new). Stored in SecureStore; picked on the login screen.
2. `api/UsaBluelinkClient.kt` (new, self-contained): US auth + core calls with the same
   method signatures the ViewModel uses for core actions:
   login, vehicles, cachedStatus, liveStatus (force refresh), lock, unlock,
   startClimate, stopClimate, awaitResult (transaction polling), setChargeLimits, locate.
   US specifics (endpoints, headers, payloads) per US_API_MAP.md.
3. `MainViewModel`: branch core calls to the USA client when region == USA;
   hide Canada-only features (Store, in-app charging, SVM, schedules) in USA mode for v1.
4. Out of v1 scope: US charge start/stop nuances beyond basic, trips, US schedules,
   digital key (impossible for third-party apps), SVM (SEL trim has no surround cameras anyway).

## Hard constraints

- Credentials/PIN are entered only in the app on the owner's phone. Never in chat, code, or logs.
  US client must reuse SecureStore (encrypted) and the existing Redact helpers for debug output.
- US API rate limits are strict (~30 requests/day total reported). v1 must default to cached
  status, no background polling, one force-refresh per explicit user refresh.
- Build: no JDK/Android SDK on the Muse VM. Build via GitHub Actions on the fork
  (upstream CI already builds an APK per push) or Android Studio on the owner's Windows PC.
- This is an unofficial client: Hyundai can change the API; keep the official app installed.

## Implementation status (2026-11-09, branch `us-support`)

DONE (code written, NOT yet compiled or run — no Android SDK on the dev VM):
- `US_API_MAP.md` — full US endpoint/header/payload map from hyundai_kia_connect_api (USA)
  and BlueDeck, incl. the 4-field refresh rule, gen-2 climate minimal body, polling, errors.
- `api/UsaBluelinkClient.kt` (~620 lines) — US auth (login/refresh), enrollment vehicles,
  capsFor, cached/live status with translation to the Canada status shape, locate,
  lock/unlock, climate (gen-2/gen-3/ICE bodies), charge start/stop, charge limits,
  horn/lights, getRunningStatus polling. PIN rides as the blueLinkServicePin header.
  Drafted by Gemini from `US_CLIENT_SPEC_FOR_GEMINI.md` per the house token rule;
  verdict: NEEDED FIXES — Muse rewrote it during integration (shadow JSON helpers clashing
  with JsonUtil, wrong gen-2 seat keys, invented polling service types, HTTP-code mapping,
  refresh fallback too eager, enrollment refetched on every status call).
- `api/Brand.kt` — HYUNDAI_USA entry (login screen lists it automatically; hasStore /
  hasInAppCharging false, so Store + public-charging UI stay hidden; caps.hasSvm false
  hides surround view).
- `data/SecureStore.kt` — refreshToken property (US refresh token, rotates).
- `MainViewModel.kt` — isUs branches: login, vehicles, loadVehicle (caps+status),
  refresh/wakeCar, awaitCommand (foreground poll, no CommandWorker), lock/unlock,
  start/stop climate, locate, horn/lights, setChargeLimits; serverPresets skipped;
  StatusWatchWorker never scheduled in US mode (rate-limit protection).
  Charge-port + battery-preconditioning commands show "not available for Hyundai USA yet".
- `widget/WidgetAction.kt` — widget refresh/lock/climate route to the US client and poll
  inline instead of via CommandWorker.
- Schedules screen is still reachable in US mode and will error on load (v1 cosmetic gap).

NEXT (needs the owner):
1. Push branch to the owner's GitHub (fork of alamo3/open-blue-android) — upstream CI
   (.github/workflows/build.yml) builds a debug-signed APK on push to main; either merge
   us-support into main there or extend the workflow trigger to this branch.
   Alternative: build with Android Studio on the owner's Windows PC.
2. First compile will likely surface small Kotlin errors — fix from CI logs.
3. Then run the test plan below on the real car, watching US rate limits.

## Test plan (owner's car, when a build exists)

1. Login with US account -> vehicle list shows the Ioniq 5, correct VIN/trim.
2. Cached status: battery %, range in miles, lock state, odometer match the official app.
3. One explicit refresh (live status) — count API calls, stay well under daily limits.
4. Lock, then unlock (one command each), verify in official app + awaitResult outcome.
5. Climate start with seats/defrost, then stop.
6. Charge limits read/set (AC/DC) if the US API exposes them for gen 2.
Each step logged pass/fail here before moving to the next.
