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
    private val topics = arrayOf("esp32/startup", "esp32/led/status")

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
            .setContentTitle("MQTT Service Running")
            .setContentText("Maintaining connection to ESP32")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Using system icon
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
                // Automatic reconnect is handled by the callback now
                connectionTimeout = 10
                keepAliveInterval = 60
            }

            mqttClient.setCallback(object : MqttCallbackExtended {
                private var shouldReconnect = true

                override fun connectComplete(reconnect: Boolean, serverURI: String) {
                    Log.d("MQTT_SERVICE", "Connected to $serverURI")
                    subscribeToTopics()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.e("MQTT_SERVICE", "Connection lost: ${cause?.message}")
                    if (shouldReconnect) {
                        Thread {
                            try {
                                Thread.sleep(5000) // Wait 5 seconds before reconnecting
                                mqttClient.connect(options)
                            } catch (e: Exception) {
                                Log.e("MQTT_SERVICE", "Reconnect failed", e)
                            }
                        }.start()
                    }
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    Log.d("MQTT_SERVICE", "Message arrived on $topic: ${String(message.payload)}")
                    if (topic == "esp32/startup") {
                        showNotification("ESP32 Alert", String(message.payload))
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    Log.d("MQTT_SERVICE", "Message delivered")
                }
            })

            mqttClient.connect(options)
            startForeground(NOTIFICATION_ID, getForegroundNotification())
        } catch (e: Exception) {
            Log.e("MQTT_SERVICE", "Error starting MQTT client", e)
        }
    }

    private fun subscribeToTopics() {
        try {
            topics.forEach { topic ->
                mqttClient.subscribe(topic, 1)
                Log.d("MQTT_SERVICE", "Subscribed to $topic")
            }
        } catch (e: Exception) {
            Log.e("MQTT_SERVICE", "Subscription error", e)
        }
    }

    private fun showNotification(title: String, message: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Using system icon
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::mqttClient.isInitialized && mqttClient.isConnected) {
                mqttClient.disconnect()
                Log.d("MQTT_SERVICE", "Disconnected from broker")
            }
        } catch (e: Exception) {
            Log.e("MQTT_SERVICE", "Disconnection error", e)
        }
    }
}