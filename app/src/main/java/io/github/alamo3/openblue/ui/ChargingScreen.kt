package io.github.alamo3.openblue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import kotlin.math.roundToInt

/**
 * Charging: battery and charge state, the charge port door, AC and DC charge limits, and battery
 * preconditioning for faster DC fast charging.
 */
@Composable
fun ChargingScreen(vm: MainViewModel, onBack: () -> Unit, open: (Route) -> Unit = {}) {
    val st = vm.status?.let { CarStatus.from(it, vm.tempTable) }
    val app = AppTheme.colors
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Charging", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 120.dp),
        ) {
            vm.session?.let { SessionCard(vm, it) }

            // Battery
            Row(verticalAlignment = Alignment.Bottom) {
                Text(st?.battery?.let { "$it%" } ?: "–", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.width(12.dp))
                Text(
                    st?.range?.let { "$it ${st.rangeUnit}" } ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            BatteryBar(st?.battery, st?.charging == true, Modifier.fillMaxWidth())
            if (st != null) {
                Text(
                    chargingLine(st),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (st.charging) app.charge else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // Charge port — hidden for Hyundai USA (no such control on the US API yet).
            if (!vm.isUs) {
            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 20.dp))
            SectionTitle("Charge port")
            val portOpen = st?.chargePortOpen
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (portOpen) { true -> "Open"; false -> "Closed"; null -> "State unknown" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (st?.pluggedIn == true) "A cable is plugged in" else "Rear right, behind the wheel",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (portOpen == null) {
                    // The car doesn't say which way it is, so offer both.
                    ActionPill("Close", enabled = vm.canAct, primary = false) { vm.chargePort(open = false) }
                    Spacer(Modifier.width(8.dp))
                    ActionPill("Open", enabled = vm.canAct) { vm.chargePort(open = true) }
                } else {
                    ActionPill(if (portOpen) "Close" else "Open", enabled = vm.canAct) { vm.chargePort(open = !portOpen) }
                }
            }
            }

            // Charge limits
            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 16.dp))
            SectionTitle("Charge limits")
            Text(
                "Charging stops at these levels. A lower everyday AC limit is easier on the battery; " +
                    "set it to 100% before a long trip.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            val savedAc = st?.chargeLimitAc ?: 80
            val savedDc = st?.chargeLimitDc ?: 80
            var ac by remember { mutableIntStateOf(savedAc) }
            var dc by remember { mutableIntStateOf(savedDc) }
            // Follow the car when its limits change (after saving, or set in the car).
            LaunchedEffect(savedAc, savedDc) {
                ac = savedAc
                dc = savedDc
            }
            LimitSlider("AC (home and public Level 2)", ac) { ac = it }
            LimitSlider("DC fast charging", dc) { dc = it }
            val changed = ac != savedAc || dc != savedDc
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (changed) {
                    ActionPill("Undo", enabled = true, primary = false) { ac = savedAc; dc = savedDc }
                    Spacer(Modifier.width(8.dp))
                }
                ActionPill("Save limits", enabled = changed && vm.canAct) { vm.setChargeLimits(ac, dc) }
            }

            // Preconditioning — hidden for Hyundai USA (not on the US API yet).
            if (!vm.isUs) {
            HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 20.dp))
            SectionTitle("Battery preconditioning")
            val pre = st?.preconditioning
            SettingSwitch(
                "Precondition battery",
                "Brings the battery to the right temperature so DC fast charging is quicker, " +
                    "especially in the cold. Uses some energy; best started about 30 minutes before you plug in." +
                    if (pre == null) " The car doesn't report whether it's on." else "",
                pre == true,
            ) { on -> if (vm.canAct) vm.batteryPreconditioning(on) }
            }

            // Hyundai's charging service (read-only for now); Kia has no in-app charging.
            if (vm.brand.hasInAppCharging) {
                HorizontalDivider(color = app.hairline, modifier = Modifier.padding(top = 8.dp))
                SectionTitle("Public charging")
                PublicCharging(vm, open)
            }
        }
    }
}

@Composable
private fun PublicCharging(vm: MainViewModel, open: (Route) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { vm.loadChargeService() }
    val service = vm.chargeService
    val error = vm.chargeServiceError
    when {
        service != null -> {
            val plan = service.plans.firstOrNull()
            DetailRow(
                "Plan",
                plan?.let { p -> listOfNotNull(p.name, p.end?.let { "until ${shortDate(it)}" }).joinToString(", ") }
                    ?: if (service.inAppCharging) "Pay as you go" else "Not set up",
            )
            DetailRow(
                "Payment",
                when {
                    service.cards.isNotEmpty() -> service.cards.joinToString { it.label }
                    service.cardRegistered -> "Card on file"
                    else -> "No card yet"
                },
            )
            if (!service.inAppCharging) {
                Text(
                    "Hyundai's in-app charging isn't turned on for this account. Set it up in the official app " +
                        "(payment card and plan); it then shows up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        error != null -> {
            var details by remember { mutableStateOf(false) }
            Text(
                "Couldn't load Hyundai's charging service: $error. Tap for details.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.clickable { details = true },
            )
            if (details) {
                val text = vm.chargeServiceDetails ?: "No request was recorded."
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { details = false },
                    title = { Text("Charging service details") },
                    text = {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }) { Text("Copy") }
                    },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { details = false }) { Text("Close") } },
                )
            }
        }
        else -> Text("Loading…", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
    NavRow(Icons.Rounded.Map, "Find chargers", "Nearby stations with live availability and prices", onClick = { open(Route.Chargers) })
    NavRow(Icons.Rounded.Route, "Plan a trip", "Route with charging stops, from Hyundai's EV route planner", onClick = { open(Route.Trip) })
    NavRow(Icons.Rounded.ReceiptLong, "Charging history", "Sessions paid through the app, with receipts", onClick = { open(Route.ChargeHistory) })
}

/** yyyyMMdd (or yyyy-MM-dd…) to "Mar 4, 2027"; anything else as is. */
private fun shortDate(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    if (digits.length < 8) return raw
    return runCatching {
        java.time.LocalDate.parse(digits.take(8), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.getDefault()))
    }.getOrDefault(raw)
}

@Composable
private fun LimitSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.EvStation, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("$value%", style = MaterialTheme.typography.titleMedium)
    }
    // 50 to 100 in steps of 10, as the car allows.
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(((it / 10f).roundToInt() * 10).coerceIn(50, 100)) },
        valueRange = 50f..100f,
        steps = 4,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = AppTheme.colors.charge,
            inactiveTrackColor = AppTheme.colors.hairline,
            activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
            inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        ),
    )
}

@Composable
private fun ActionPill(label: String, enabled: Boolean, primary: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (primary) scheme.primary else scheme.surfaceContainerHigh
    val fg = if (primary) scheme.onPrimary else scheme.onSurface
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .pressable(enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) fg else fg.copy(alpha = 0.6f))
    }
}

/** A paid charge started from the app: live energy, power and cost, and a stop button. */
@Composable
private fun SessionCard(vm: MainViewModel, session: io.github.alamo3.openblue.api.ActiveSession) {
    val app = AppTheme.colors
    val scheme = MaterialTheme.colorScheme
    var confirmStop by remember { mutableStateOf(false) }
    // Poll while the screen is open.
    LaunchedEffect(session.paymentId) {
        vm.refreshSession(refresh = true)
        while (true) {
            kotlinx.coroutines.delay(20_000)
            vm.refreshSession()
        }
    }
    val st = vm.sessionStatus
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
            .clip(MaterialTheme.shapes.large)
            .background(app.charge.copy(alpha = 0.12f))
            .padding(16.dp),
    ) {
        Text("Charging at ${session.stationName}", style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(session.charger, "started ${relativeTime(java.util.Date(session.startedAt))}").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SessionFigure("Energy", st?.kwh?.let { "%.1f kWh".format(it) } ?: "–", Modifier.weight(1f))
            SessionFigure("Power", st?.powerKw?.let { "${it.roundToInt()} kW" } ?: "–", Modifier.weight(1f))
            SessionFigure("Cost", st?.cost?.let { "$%.2f".format(it) } ?: "–", Modifier.weight(1f))
        }
        st?.minutesLeft?.let {
            Text("About ${formatMinutes(it)} to the limit", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
            ActionPill("Done", enabled = true, primary = false) { vm.dismissSession() }
            if (session.stopAllowed) {
                Spacer(Modifier.width(8.dp))
                ActionPill(if (vm.sessionBusy) "Stopping…" else "Stop charging", enabled = !vm.sessionBusy) { confirmStop = true }
            }
        }
        if (!session.stopAllowed) {
            Text(
                "This charger can't be stopped from the app; stop it on the charger or by unlocking the car.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    if (confirmStop) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop charging?") },
            text = { Text("The session at ${session.stationName} ends and you're billed for what's been used.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmStop = false; vm.stopCharge() }) { Text("Stop", color = app.alert) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmStop = false }) { Text("Keep charging") } },
        )
    }
}

@Composable
private fun SessionFigure(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}
