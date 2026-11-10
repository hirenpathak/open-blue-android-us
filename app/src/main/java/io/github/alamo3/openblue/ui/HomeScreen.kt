package io.github.alamo3.openblue.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.ClimateSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, open: (Route) -> Unit, onUnlock: () -> Unit) {
    val st = vm.status?.let { CarStatus.from(it, vm.tempTable) }
    val app = AppTheme.colors
    val v = vm.vehicle
    val caps = vm.caps
    var hornDialog by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshWeather() }

    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 120.dp),
        ) {
            // Name and freshness
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(v?.nickname ?: "Your car", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Updated ${relativeTime(st?.updatedAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PlaceAndWeather(vm, onClick = { open(Route.Location) })
                }
                Avatar(caps.photoUrl, v?.nickname.orEmpty(), onClick = { open(Route.Settings) })
            }

            // Battery and range
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(st?.battery?.let { "$it%" } ?: "–", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.width(12.dp))
                Text(
                    st?.range?.let { "$it ${st.rangeUnit}" } ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                if (st?.charging == true) {
                    Icon(Icons.Rounded.Bolt, contentDescription = "Charging", tint = app.charge, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            BatteryBar(st?.battery, st?.charging == true, Modifier.fillMaxWidth())
            if (st != null && (st.charging || st.pluggedIn)) {
                Text(
                    chargingLine(st),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (st.charging) app.charge else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // The car, in 3D: doors, frunk and tailgate follow the status; airflow shows climate,
            // and the charging cable shows when it's plugged in.
            Car3D(st.toPose(vm.editor.tempC), Modifier.fillMaxWidth().height(240.dp), body = vm.carBody, paint = vm.carPaint)

            // One-tap controls, three to a row.
            val isEv = v?.fuelKindCode.equals("E", ignoreCase = true)
            val controls = buildList<@Composable () -> Unit> {
                add {
                    val locked = st?.locked
                    RoundControl(
                        icon = if (locked == false) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                        label = when (locked) { true -> "Locked"; false -> "Unlocked"; null -> "Lock" },
                        active = locked == false,
                        tint = app.alert,
                        enabled = vm.canAct,
                        onClick = { if (locked == true) onUnlock() else vm.lock() },
                    )
                }
                add {
                    // Tap opens the climate screen; press and hold starts or stops with the last settings.
                    val climateOn = st?.climateOn == true
                    RoundControl(
                        icon = Icons.Rounded.Air,
                        label = if (climateOn) (st?.setTempC?.let { c ->
                            if (vm.isUs) "${ClimateSettings.fmt(c * 9.0 / 5.0 + 32.0)}° on" else "${ClimateSettings.fmt(c)}° on"
                        } ?: "On") else "Climate",
                        active = climateOn,
                        tint = if ((st?.setTempC ?: vm.editor.tempC) >= 20) app.heat else app.cool,
                        onClick = { open(Route.Climate) },
                        onLongClick = { if (vm.canAct) { if (climateOn) vm.stopClimate() else vm.startClimate() } },
                    )
                }
                if (isEv && !vm.isUs) add {
                    val portOpen = st?.chargePortOpen == true
                    RoundControl(
                        icon = Icons.Rounded.EvStation,
                        label = if (portOpen) "Port open" else "Charge port",
                        active = portOpen,
                        tint = app.charge,
                        enabled = vm.canAct,
                        onClick = { vm.chargePort(open = !portOpen) },
                    )
                }
                add {
                    RoundControl(icon = Icons.Rounded.Campaign, label = "Horn & lights", enabled = vm.canAct, onClick = { hornDialog = true })
                }
                if (caps.hasSvm) add {
                    RoundControl(icon = Icons.Rounded.PhotoCamera, label = "Camera", onClick = { open(Route.Camera) })
                }
                add {
                    RoundControl(icon = Icons.Rounded.Sync, label = "Wake car", enabled = vm.canAct, onClick = { vm.wakeCar() })
                }
            }
            controls.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    row.forEach { c -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { c() } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = app.hairline)
            Spacer(Modifier.height(8.dp))

            if (isEv) {
                NavRow(
                    Icons.Rounded.BatteryChargingFull, "Charging",
                    st?.let { s -> listOfNotNull(chargingLine(s), limitsLine(s)).joinToString(" · ") },
                    onClick = { open(Route.Charging) },
                    iconTint = if (st?.charging == true) app.charge else MaterialTheme.colorScheme.onSurface,
                )
            }
            NavRow(
                Icons.Rounded.Place, "Location",
                vm.location?.let { l -> listOfNotNull(vm.place, "located ${relativeTime(java.util.Date(l.fetchedAt))}").joinToString(" · ").replaceFirstChar { it.uppercase() } }
                    ?: "Find your car on a map",
                onClick = { open(Route.Location) },
            )
            NavRow(
                Icons.Rounded.GraphicEq, "Engine sound",
                if (vm.soundOn) "On · ${vm.engineSound.label}" else "A virtual engine that follows the car",
                onClick = { open(Route.Sound) },
                iconTint = if (vm.soundOn) AppTheme.colors.heat else MaterialTheme.colorScheme.onSurface,
            )
            NavRow(Icons.Rounded.DirectionsCar, "Vehicle", st?.let { vehicleLine(it) }, onClick = { open(Route.Vehicle) },
                iconTint = if (st != null && needsAttention(st)) app.alert else MaterialTheme.colorScheme.onSurface)
            NavRow(Icons.Rounded.Schedule, "Schedules", if (isEv) "Climate starts and a charging window" else "Climate starts", onClick = { open(Route.Schedules) })
            if (vm.brand.hasStore) {
                NavRow(Icons.Rounded.Storefront, "Bluelink Store", "Features on demand from Hyundai", onClick = { open(Route.Store) })
            }
        }
    }

    if (hornDialog) {
        AlertDialog(
            onDismissRequest = { hornDialog = false },
            title = { Text("Horn and lights") },
            text = { Text("Flash the hazard lights to find the car, or sound the horn too.") },
            confirmButton = {
                TextButton(onClick = { hornDialog = false; vm.hornLights(horn = true) }) { Text("Horn and lights") }
            },
            dismissButton = {
                TextButton(onClick = { hornDialog = false; vm.hornLights(horn = false) }) { Text("Lights only") }
            },
        )
    }
}

fun limitsLine(st: CarStatus): String? =
    if (st.chargeLimitAc == null && st.chargeLimitDc == null) null
    else "AC ${st.chargeLimitAc ?: "–"}% · DC ${st.chargeLimitDc ?: "–"}%"

fun chargingLine(st: CarStatus): String = when {
    st.charging && st.chargeMinutesLeft != null -> "Charging, full in ${formatMinutes(st.chargeMinutesLeft)}"
    st.charging -> "Charging"
    st.pluggedIn -> "Plugged in, not charging"
    else -> "Not plugged in"
}

fun needsAttention(st: CarStatus) =
    st.locked == false || st.openDoors.isNotEmpty() || st.openWindows.isNotEmpty() || st.trunkOpen || st.frunkOpen || st.tireWarning

fun vehicleLine(st: CarStatus): String {
    val issues = buildList {
        if (st.openDoors.isNotEmpty()) add(if (st.openDoors.size == 1) "a door is open" else "${st.openDoors.size} doors open")
        if (st.openWindows.isNotEmpty()) add("windows open")
        if (st.trunkOpen) add("trunk open")
        if (st.frunkOpen) add("frunk open")
        if (st.tireWarning) add("check tire pressure")
    }
    return if (issues.isEmpty()) "Everything closed" + (st.battery12v?.let { ", 12V at $it%" } ?: "")
    else issues.joinToString(", ").replaceFirstChar { it.uppercase() }
}

/** Where the car is and the weather there: "100 Queen St W, Toronto · 8° Cloudy". */
@Composable
private fun PlaceAndWeather(vm: MainViewModel, onClick: () -> Unit) {
    val w = vm.weather
    val place = vm.place ?: if (vm.location != null) "Car located" else null
    if (w == null && place == null) return
    val weather = w?.let { listOf("${ClimateSettings.fmt(Math.round(it.tempC).toDouble())}°", it.summary).filter { s -> s.isNotBlank() }.joinToString(" ") }
    Row(
        Modifier.padding(top = 4.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (place != null) Icons.Rounded.Place else Icons.Rounded.Thermostat, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            listOfNotNull(place, weather?.let { if (vm.weatherNearPhone && place == null) "$it near you" else it }).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
