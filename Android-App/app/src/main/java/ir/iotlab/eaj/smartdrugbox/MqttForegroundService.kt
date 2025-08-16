package ir.iotlab.eaj.smartdrugbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.eclipse.paho.client.mqttv3.*

class MqttForegroundService : Service() {
    private lateinit var mqttClient: MqttClient
    private val brokerUrl = "tcp://87.248.152.126:1883"
    private val clientId = "AndroidClient-${System.currentTimeMillis()}"
    private val username = "AJ_IoT"
    private val passwordss = "hgsde32993004"

    companion object {
        private const val NOTIFICATION_ID = 123
        private const val CHANNEL_ID = "mqtt_service_channel"
        fun startService(context: Context) {
            val intent = Intent(context, MqttForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.getStringExtra("command")) {
                "led" -> {
                    val pin = it.getIntExtra("pin", -1)
                    val state = it.getBooleanExtra("state", false)
                    if (pin != -1) {
                        val message = "{\"pin\":$pin,\"state\":${if (state) "ON" else "OFF"}}"
                        publishMessage("esp32/led/command", message)
                    }
                }
                "period" -> {
                    val pin = it.getIntExtra("pin", -1)
                    val period = it.getIntExtra("period", 60)
                    if (pin != -1) {
                        val message = "{\"pin\":$pin,\"period\":$period}"
                        publishMessage("esp32/period", message)
                    }
                }
                "reset" -> {
                    val pin = it.getIntExtra("pin", -1)
                    if (pin != -1) {
                        publishMessage("esp32/reset", pin.toString())
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun publishMessage(topic: String, payload: String) {
        try {
            if (::mqttClient.isInitialized && mqttClient.isConnected) {
                val mqttMessage = MqttMessage(payload.toByteArray())
                mqttClient.publish(topic, mqttMessage)
                Log.d("MQTT", "Published to $topic: $payload")
            }
        } catch (e: Exception) {
            Log.e("MQTT", "Publish error", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startMqttClient()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MQTT Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps MQTT connection alive"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun getForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pill Reminder Service")
            .setContentText("Monitoring pill schedules")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startMqttClient() {
        try {
            mqttClient = MqttClient(brokerUrl, clientId, null)
            val options = MqttConnectOptions().apply {
                userName = username
                password = passwordss.toCharArray()
                isCleanSession = false
                connectionTimeout = 10
                keepAliveInterval = 60
            }

            mqttClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String) {
                    Log.d("MQTT", "Connected to $serverURI")
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.e("MQTT", "Connection lost: ${cause?.message}")
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    Log.d("MQTT", "Message arrived: ${String(message.payload)}")
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    Log.d("MQTT", "Message delivered")
                }
            })

            mqttClient.connect(options)
            startForeground(NOTIFICATION_ID, getForegroundNotification())
        } catch (e: Exception) {
            Log.e("MQTT", "Error starting MQTT client", e)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::mqttClient.isInitialized && mqttClient.isConnected) {
                mqttClient.disconnect()
            }
        } catch (e: Exception) {
            Log.e("MQTT", "Disconnection error", e)
        }
    }
}