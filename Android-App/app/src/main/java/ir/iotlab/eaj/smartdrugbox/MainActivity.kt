package ir.iotlab.eaj.smartdrugbox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Notification constants
    private val CHANNEL_ID = "app_channel"
    private val NOTIFICATION_ID = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create notification channel
        createNotificationChannel()

        // Start foreground service for MQTT
        startMqttService()

        // Check notification permission (Android 13+)
        checkNotificationPermission()

        setContent {
            AppUI()
        }
    }

    @Composable
    fun AppUI() {
        var serviceStatus by remember { mutableStateOf("Starting...") }
        var lastMessage by remember { mutableStateOf("No messages yet") }

        LaunchedEffect(Unit) {
            launch(Dispatchers.IO) {
                // Simulate service startup
                kotlinx.coroutines.delay(2000)
                serviceStatus = "Service running"
                lastMessage = "Connected to MQTT broker"
            }
        }

        MaterialTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("MQTT Service Status: $serviceStatus")
                Spacer(modifier = Modifier.height(20.dp))
                Text("Last Message: $lastMessage")
                Spacer(modifier = Modifier.height(40.dp))
                Button(onClick = { testNotification() }) {
                    Text("Test Notification")
                }
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (!notificationManager.areNotificationsEnabled()) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            }
        }
    }

    private fun startMqttService() {
        try {
            val serviceIntent = Intent(this, MqttForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d("MainActivity", "Started MQTT service")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start service", e)
            showToast("Failed to start service")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "App Notifications"
            val description = "Channel for MQTT notifications"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                this.description = description
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun testNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Use Android's built-in icon if you haven't created ic_notification
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info) // Default system icon
                .setContentTitle("Test Notification")
                .setContentText("MQTT service is working correctly")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
            showToast("Test notification sent")
        } catch (e: Exception) {
            Log.e("MainActivity", "Notification failed", e)
            showToast("Failed to show notification")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Service continues running in background
    }
}