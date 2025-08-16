package ir.iotlab.eaj.smartdrugbox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import ir.iotlab.eaj.smartdrugbox.ui.theme.SmartDrugBoxTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()
        MqttForegroundService.startService(this)

        setContent {
            SmartDrugBoxTheme {
                DrugBoxApp(
                    onSettingsClick = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                )
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "pill_reminder_channel",
                "Pill Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Channel for pill reminder notifications"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrugBoxApp(onSettingsClick: () -> Unit) {
    val context = LocalContext.current
    val pillBoxes = remember {
        List(4) { index ->
            mutableStateOf(
                PillBox(
                    id = index,
                    name = "Pill ${index + 1}",
                    amount = index + 1,
                    color = when(index) {
                        0 -> Color(0xFF90CAF9)
                        1 -> Color(0xFFF48FB1)
                        2 -> Color(0xFFA5D6A7)
                        else -> Color(0xFFFFF59D)
                    },
                    period = 60, // Default 60 seconds
                    lastTaken = LocalDateTime.now(),
                    ledPin = when(index) {
                        0 -> 19
                        1 -> 21
                        2 -> 22
                        else -> 23
                    },
                    isActive = false,
                    lastActivation = "Never",
                    nextActivation = "Not scheduled"
                )
            )
        }
    }

    val onPeriodChange = { index: Int, newPeriod: Int ->
        pillBoxes[index].value = pillBoxes[index].value.copy(period = newPeriod)
        sendPeriodCommand(context, pillBoxes[index].value.ledPin, newPeriod)
    }

    val onReset = { index: Int ->
        pillBoxes[index].value = pillBoxes[index].value.copy(
            lastTaken = LocalDateTime.now(),
            isActive = false
        )
        sendResetCommand(context, pillBoxes[index].value.ledPin)
    }

    LaunchedEffect(Unit) {
        while (true) {
            pillBoxes.forEach { pillBoxState ->
                val pillBox = pillBoxState.value
                val nextDoseTime = pillBox.lastTaken.plusSeconds(pillBox.period.toLong())
                if (LocalDateTime.now().isAfter(nextDoseTime)) {
                    showPillNotification(context, pillBox)
                    sendLedCommand(context, pillBox.ledPin, true)

                    pillBoxState.value = pillBox.copy(
                        lastTaken = LocalDateTime.now(),
                        isActive = true,
                        lastActivation = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    )

                    delay(1000) // Keep LED on for 1 second
                    sendLedCommand(context, pillBox.ledPin, false)
                    pillBoxState.value = pillBox.copy(isActive = false)
                }
            }
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Smart Drug Box") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        PillBoxScreen(
            modifier = Modifier.padding(padding),
            pillBoxes = pillBoxes.map { it.value },
            onEdit = { index, newPillBox ->
                pillBoxes[index].value = newPillBox
            },
            onReset = onReset,
            onPeriodChange = onPeriodChange
        )
    }
}

private fun showPillNotification(context: Context, pillBox: PillBox) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val notification = NotificationCompat.Builder(context, "pill_reminder_channel")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Time to take ${pillBox.name}!")
        .setContentText("Dose: ${pillBox.amount} pill(s)")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()

    notificationManager.notify(pillBox.id, notification)
}

private fun sendLedCommand(context: Context, pin: Int, state: Boolean) {
    val intent = Intent(context, MqttForegroundService::class.java).apply {
        putExtra("command", "led")
        putExtra("pin", pin)
        putExtra("state", state)
    }
    context.startService(intent)
}

private fun sendPeriodCommand(context: Context, pin: Int, period: Int) {
    val intent = Intent(context, MqttForegroundService::class.java).apply {
        putExtra("command", "period")
        putExtra("pin", pin)
        putExtra("period", period)
    }
    context.startService(intent)
}

private fun sendResetCommand(context: Context, pin: Int) {
    val intent = Intent(context, MqttForegroundService::class.java).apply {
        putExtra("command", "reset")
        putExtra("pin", pin)
    }
    context.startService(intent)
}

@Composable
fun PillBoxScreen(
    modifier: Modifier = Modifier,
    pillBoxes: List<PillBox>,
    onEdit: (Int, PillBox) -> Unit,
    onReset: (Int) -> Unit,
    onPeriodChange: (Int, Int) -> Unit
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        pillBoxes.forEachIndexed { index, pillBox ->
            var showDialog by remember { mutableStateOf(false) }

            PillCard(
                pillBox = pillBox,
                onEdit = { showDialog = true },
                onReset = { onReset(index) },
                onPeriodChange = { newPeriod -> onPeriodChange(index, newPeriod) }
            )

            if (showDialog) {
                EditPillDialog(
                    initial = pillBox,
                    onConfirm = { onEdit(index, it) },
                    onDismiss = { showDialog = false }
                )
            }
        }
    }
}

@Composable
fun PillCard(
    pillBox: PillBox,
    onEdit: () -> Unit,
    onReset: () -> Unit,
    onPeriodChange: (Int) -> Unit
) {
    var showPeriodDialog by remember { mutableStateOf(false) }
    var newPeriod by remember { mutableStateOf(pillBox.period.toString()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                color = if (pillBox.isActive) Color.Green else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            ),
        elevation = CardDefaults.cardElevation(4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .background(pillBox.color.copy(alpha = 0.1f))
                .padding(16.dp)
        ) {
            // LED Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LED ${pillBox.ledPin}: ", style = MaterialTheme.typography.titleMedium)
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(if (pillBox.isActive) Color.Green else Color.Red)
                        .border(1.dp, Color.Black)
                )
            }

            // Period Control
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Period: ${pillBox.period} sec", modifier = Modifier.weight(1f))
                Button(
                    onClick = { showPeriodDialog = true },
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Set")
                }
            }

            // Activation Times
            Text("Last: ${pillBox.lastActivation}")
            Text("Next: ${pillBox.nextActivation}")

            // Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onEdit,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) {
                    Text("Edit")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onReset,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text("RESET")
                }
            }
        }
    }

    if (showPeriodDialog) {
        AlertDialog(
            onDismissRequest = { showPeriodDialog = false },
            title = { Text("Set Period (seconds)") },
            text = {
                OutlinedTextField(
                    value = newPeriod,
                    onValueChange = { newPeriod = it },
                    label = { Text("Period in seconds") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    newPeriod.toIntOrNull()?.let {
                        onPeriodChange(it)
                        showPeriodDialog = false
                    }
                }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                Button(onClick = { showPeriodDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun EditPillDialog(
    initial: PillBox,
    onConfirm: (PillBox) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(TextFieldValue(initial.name)) }
    var amount by remember { mutableStateOf(TextFieldValue(initial.amount.toString())) }
    var color by remember { mutableStateOf(initial.color) }

    val colors = listOf(
        Color(0xFF90CAF9), Color(0xFFF48FB1), Color(0xFFA5D6A7),
        Color(0xFFFFF59D), Color(0xFFCE93D8), Color(0xFFFFAB91)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    initial.copy(
                        name = name.text,
                        amount = amount.text.toIntOrNull() ?: initial.amount,
                        color = color
                    )
                )
            }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Edit Drug Box") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") }
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") }
                )

                Text("Select Color:")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    colors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(c, shape = RoundedCornerShape(4.dp))
                                .clickable { color = c }
                                .border(
                                    2.dp,
                                    if (c == color) Color.Black else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                        )
                    }
                }
            }
        }
    )
}

data class PillBox(
    val id: Int,
    val name: String,
    val amount: Int,
    val color: Color,
    val period: Int,
    val lastTaken: LocalDateTime,
    val ledPin: Int,
    val isActive: Boolean,
    val lastActivation: String,
    val nextActivation: String
)