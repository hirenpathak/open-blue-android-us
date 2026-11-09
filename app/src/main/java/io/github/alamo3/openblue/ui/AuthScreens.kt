package io.github.alamo3.openblue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.Screen
import io.github.alamo3.openblue.api.Brand
import io.github.alamo3.openblue.api.VehicleInfo

/** Full-width primary action; shows a spinner while [loading]. */
@Composable
fun PrimaryButton(text: String, enabled: Boolean, loading: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .alpha(if (enabled || loading) 1f else 0.4f)
            .clip(CircleShape)
            .background(scheme.primary)
            .pressable(enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(color = scheme.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        else Text(text, style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
    }
}

@Composable
fun LoginScreen(vm: MainViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf(vm.brand) }
    val busy = vm.busy != null
    val ready = email.isNotBlank() && password.isNotEmpty() && pin.length == 4

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        Text("Open Blue", style = MaterialTheme.typography.displayMedium)
        Text(
            if (brand == Brand.HYUNDAI_USA) {
                "Sign in with your MyHyundai account (USA), the same one you use in the MyHyundai app."
            } else {
                "Sign in with your ${brand.serviceName} Canada account, the same one you use on ${brand.host}."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Brand.entries.forEach { b ->
                Row(
                    Modifier.clickable(enabled = !busy) { brand = b },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = brand == b, onClick = { brand = b }, enabled = !busy)
                    Text(b.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(4) },
            label = { Text("${brand.serviceName} PIN") },
            supportingText = { Text("The 4-digit PIN for remote commands") },
            singleLine = true, shape = MaterialTheme.shapes.medium,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        PrimaryButton("Sign in", enabled = ready && !busy, loading = busy) { vm.login(email, password, pin, brand) }
        Text(
            "Your details are stored encrypted on this phone and sent only to ${brand.host}. Open Blue is unofficial and not affiliated with ${brand.company}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )
    }
}

@Composable
fun OtpScreen(vm: MainViewModel, state: Screen.Otp) {
    var code by rememberSaveable { mutableStateOf("") }
    val busy = vm.busy != null
    val sent = state.challenge.otpKey != null
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        Text("Verify this phone", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (sent) "Enter the code ${vm.brand.serviceName} sent you." else "${vm.brand.serviceName} sends a one-time code the first time it sees a new phone. Where should it go?",
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleChip(Icons.Rounded.Email, "Email", false, { if (!busy) vm.sendOtp(bySms = false) }, scheme.primary)
            if (state.challenge.phone != null) {
                ToggleChip(Icons.Rounded.Sms, "Text message", false, { if (!busy) vm.sendOtp(bySms = true) }, scheme.primary)
            }
        }
        Text(
            listOfNotNull(state.challenge.email, state.challenge.phone).joinToString(" or "),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        if (sent) {
            OutlinedTextField(
                value = code, onValueChange = { code = it.filter(Char::isLetterOrDigit).take(10) },
                label = { Text("Code") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton("Verify", enabled = code.isNotBlank() && !busy, loading = busy) { vm.verifyOtp(code) }
            Text("Didn't get it? Tap Email or Text message again to resend.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        TextButton(onClick = { vm.cancelOtp() }) { Text("Cancel") }
    }
}

@Composable
fun VehiclePickerScreen(vm: MainViewModel, vehicles: List<VehicleInfo>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        Text("Choose a vehicle", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        vehicles.forEach { v ->
            NavRow(Icons.Rounded.DirectionsCar, v.nickname, "${v.year} ${v.model}", onClick = { if (vm.busy == null) vm.chooseVehicle(v) })
        }
    }
}
