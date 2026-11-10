package io.github.alamo3.openblue.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.api.ClimateSettings
import io.github.alamo3.openblue.api.Heating
import io.github.alamo3.openblue.api.SeatLevel

private val DURATIONS = listOf(10, 15, 20, 30)
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClimateScreen(vm: MainViewModel, onBack: () -> Unit) {
    val e = vm.editor
    val app = AppTheme.colors
    val scheme = MaterialTheme.colorScheme
    val st = vm.status?.let { CarStatus.from(it, vm.tempTable) }
    val running = st?.climateOn == true
    val temps = vm.tempTable.filterNot { it.isLimit }.mapNotNull { it.celsiusValue }.distinct().sorted()
    // Hyundai USA accounts think in Fahrenheit: the temp table carries both scales, so
    // render the Fahrenheit label of the entry nearest the Celsius working value.
    val usF = vm.isUs
    fun disp(c: Double): String = if (usF) {
        vm.tempTable.filterNot { it.isLimit }
            .minByOrNull { kotlin.math.abs((it.celsiusValue ?: 0.0) - c) }?.fahrenheit
            ?: ClimateSettings.fmt(c * 9.0 / 5.0 + 32.0)
    } else ClimateSettings.fmt(c)
    val unit = if (usF) "°F" else "°C"
    var saving by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshWeather() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Climate", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (running) "Climate is on" + (st?.setTempC?.let { " at ${disp(it)} $unit" } ?: "")
                else "Climate is off",
                style = MaterialTheme.typography.bodyLarge,
                color = if (running) (if (e.tempC >= 20) app.heat else app.cool) else scheme.onSurfaceVariant,
            )

            // Temperature
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StepButton(Icons.Rounded.KeyboardArrowLeft, "Cooler") {
                    vm.updateEditor { s -> s.copy(tempC = temps.lastOrNull { it < s.tempC } ?: s.tempC) }
                }
                AnimatedContent(
                    targetState = e.tempC,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically(tween(220)) { h -> if (up) h / 2 else -h / 2 } + fadeIn(tween(220))) togetherWith
                            (slideOutVertically(tween(180)) { h -> if (up) -h / 2 else h / 2 } + fadeOut(tween(180)))
                    },
                    label = "temp",
                    modifier = Modifier.weight(1f),
                ) { t ->
                    Text(
                        "${disp(t)}°",
                        style = MaterialTheme.typography.displayLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                StepButton(Icons.Rounded.KeyboardArrowRight, "Warmer") {
                    vm.updateEditor { s -> s.copy(tempC = temps.firstOrNull { it > s.tempC } ?: s.tempC) }
                }
            }

            // Power
            Spacer(Modifier.height(16.dp))
            PowerButton(
                running = running,
                enabled = vm.canAct,
                onClick = { if (running) vm.stopClimate() else vm.startClimate() },
            )
            Text(
                if (running) "Stop" else "Start for ${e.durationMin} min",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            // Seats
            Spacer(Modifier.height(24.dp))
            val wheelHeated = if (vm.caps.splitHeating) {
                e.heating == Heating.STEERING.code || e.heating == Heating.STEERING_REAR_MIRRORS.code
            } else {
                e.heating != 0
            }
            CabinView(
                seats = e.seats.map { SeatLevel.of(it) },
                wheelHeated = wheelHeated,
                defrost = e.defrost,
                onSeat = { vm.cycleSeat(it) },
                modifier = Modifier.width(250.dp),
            )
            val seatWarning = vm.seatWarning()
            Text(
                seatWarning?.substringBefore(". ")?.let { "$it, check the seats" }
                    ?: if (vm.caps.remoteSeats) "Tap a seat to change it" else "Bluelink can't heat or cool the seats on this car remotely",
                style = MaterialTheme.typography.bodySmall,
                color = if (seatWarning != null) app.alert else scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            vm.weather?.let { w ->
                if (seatWarning == null) Text(
                    "${ClimateSettings.fmt(Math.round(if (usF) w.tempC * 9.0 / 5.0 + 32.0 else w.tempC).toDouble())}° outside" + (if (vm.weatherNearPhone) " near you" else "") +
                        w.summary.takeIf { it.isNotBlank() }?.let { ", ${it.lowercase()}" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            // Extras
            Spacer(Modifier.height(20.dp))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToggleChip(Icons.Rounded.Waves, "Front defrost", e.defrost, { vm.updateEditor { it.copy(defrost = !it.defrost) } }, app.heat)
                if (vm.caps.splitHeating) {
                    val wheel = e.heating == Heating.STEERING.code || e.heating == Heating.STEERING_REAR_MIRRORS.code
                    val rear = e.heating == Heating.REAR_MIRRORS.code || e.heating == Heating.STEERING_REAR_MIRRORS.code
                    ToggleChip(Icons.Rounded.LocalFireDepartment, "Heated wheel", wheel, { vm.updateEditor { it.copy(heating = splitCode(!wheel, rear)) } }, app.heat)
                    ToggleChip(Icons.Rounded.LocalFireDepartment, "Rear window & mirrors", rear, { vm.updateEditor { it.copy(heating = splitCode(wheel, !rear)) } }, app.heat)
                } else {
                    val on = e.heating != 0
                    // One switch on this car: steering wheel, side mirrors and rear defroster together.
                    ToggleChip(
                        Icons.Rounded.LocalFireDepartment, "Heated wheel & rear defrost", on,
                        { vm.updateEditor { it.copy(heating = if (on) 0 else Heating.ALL.code) } }, app.heat,
                    )
                }
            }

            // Duration
            Text(
                "Run for",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (DURATIONS + e.durationMin).distinct().sorted().forEach { d ->
                    OptionPill("$d min", e.durationMin == d, { vm.updateEditor { it.copy(durationMin = d) } }, Modifier.weight(1f))
                }
            }

            // Presets
            Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Presets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { saving = true }) { Text("Save current") }
            }
            val presets = vm.serverPresets.map { Triple(it.settings, true, it.isDefault) } +
                vm.localPresets.map { Triple(it, false, false) }
            if (presets.isEmpty()) {
                Text(
                    "Save these settings to start them in one tap. Presets from mybluelink.ca show up here too.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    presets.forEach { (p, fromServer, isDefault) ->
                        PresetCard(
                            settings = p,
                            caption = when {
                                isDefault -> "Bluelink default"
                                fromServer -> "From Bluelink"
                                else -> "On this phone"
                            },
                            enabled = vm.canAct,
                            onStart = { vm.startClimate(p) },
                            onEdit = { vm.editPreset(p) },
                            onDelete = if (fromServer) null else ({ vm.deleteLocalPreset(p) }),
                        )
                    }
                }
            }
        }
    }

    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save preset") },
            text = {
                Column {
                    Text(e.summary(), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveLocalPreset(name); saving = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }
}

private fun splitCode(wheel: Boolean, rear: Boolean) = when {
    wheel && rear -> Heating.STEERING_REAR_MIRRORS.code
    wheel -> Heating.STEERING.code
    rear -> Heating.REAR_MIRRORS.code
    else -> Heating.OFF.code
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, modifier = Modifier.size(32.dp)) }
}

@Composable
private fun PowerButton(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val app = AppTheme.colors
    val bg by animateColorAsState(if (running) app.alert.copy(alpha = 0.14f) else scheme.primary, tween(300), label = "powerBg")
    val fg by animateColorAsState(if (running) app.alert else scheme.onPrimary, tween(300), label = "powerFg")
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(bg)
            .pressable(enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PowerSettingsNew, contentDescription = if (running) "Stop climate" else "Start climate", tint = fg, modifier = Modifier.size(38.dp))
    }
}

/** Top-down cabin: a car body outline with the four seats where they sit (left-hand drive). */
@Composable
private fun PresetCard(
    settings: ClimateSettings,
    caption: String,
    enabled: Boolean,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(220.dp)
            .clip(MaterialTheme.shapes.large)
            .background(scheme.surfaceContainerHigh)
            .border(1.dp, AppTheme.colors.hairline, MaterialTheme.shapes.large)
            .padding(16.dp),
    ) {
        Text(settings.name, style = MaterialTheme.typography.titleMedium)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(settings.summary(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, minLines = 3, maxLines = 3)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(CircleShape).background(scheme.primary).pressable(enabled, onClick = onStart).padding(horizontal = 18.dp, vertical = 9.dp),
            ) { Text("Start", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Load into controls") }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete") }
        }
    }
}
