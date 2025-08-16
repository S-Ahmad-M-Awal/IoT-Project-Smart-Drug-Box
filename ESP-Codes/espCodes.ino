#include <WiFi.h>
#include <PubSubClient.h>
#include <WiFiManager.h>
#include <LittleFS.h>
#include <ArduinoJson.h>

// Pins
#define LED_PIN 19
#define CONFIG_BUTTON 0

// MQTT Configuration
char mqtt_server[40] = "87.248.152.126";
char mqtt_port[6] = "1883";
char mqtt_user[32] = "AJ_IoT";
char mqtt_pass[32] = "hgsde32993004";

// Topics
const char* topic_led_command = "esp32/led/command";  // Receive LED commands
const char* topic_led_status = "esp32/led/status";    // Publish LED status
const char* topic_notify = "esp32/notify";            // Publish notifications
const char* topic_period = "esp32/period";            // Receive period commands
const char* topic_reset = "esp32/reset";              // Receive reset commands

WiFiClient espClient;
PubSubClient client(espClient);
WiFiManager wifiManager;
bool shouldSaveConfig = false;

unsigned long ledOnTime = 0;
bool ledActive = false;
unsigned long period = 60000; // Default 60 seconds in milliseconds
unsigned long lastPeriodCheck = 0;
bool firstBoot = true;

void saveConfigCallback() {
  Serial.println("Should save config");
  shouldSaveConfig = true;
}

void loadConfig() {
  if (LittleFS.begin()) {
    if (LittleFS.exists("/config.json")) {
      File configFile = LittleFS.open("/config.json", "r");
      if (configFile) {
        DynamicJsonDocument json(1024);
        deserializeJson(json, configFile);
        strcpy(mqtt_server, json["mqtt_server"]);
        strcpy(mqtt_port, json["mqtt_port"]);
        strcpy(mqtt_user, json["mqtt_user"]);
        strcpy(mqtt_pass, json["mqtt_pass"]);
        period = json["period"] | 60000; // Default to 60 seconds if not set
        configFile.close();
      }
    }
  }
}

void saveConfig() {
  DynamicJsonDocument json(1024);
  json["mqtt_server"] = mqtt_server;
  json["mqtt_port"] = mqtt_port;
  json["mqtt_user"] = mqtt_user;
  json["mqtt_pass"] = mqtt_pass;
  json["period"] = period;

  File configFile = LittleFS.open("/config.json", "w");
  serializeJson(json, configFile);
  configFile.close();
}

void sendNotification(const char* message) {
  if (client.connected()) {
    client.publish(topic_notify, message);
    Serial.print("Notification: ");
    Serial.println(message);
  }
}

void setLed(bool state) {
  digitalWrite(LED_PIN, state);
  ledActive = state;
  
  // Publish status update
  client.publish(topic_led_status, state ? "ON" : "OFF");
  
  if (state) {
    ledOnTime = millis();
    sendNotification("LED activated");
  } else {
    sendNotification("LED deactivated");
  }
}

void setup_wifi() {
  wifiManager.setSaveConfigCallback(saveConfigCallback);

  WiFiManagerParameter custom_mqtt_server("server", "MQTT server", mqtt_server, 40);
  WiFiManagerParameter custom_mqtt_port("port", "MQTT port", mqtt_port, 6);
  WiFiManagerParameter custom_mqtt_user("user", "MQTT user", mqtt_user, 32);
  WiFiManagerParameter custom_mqtt_pass("pass", "MQTT password", mqtt_pass, 32);

  wifiManager.addParameter(&custom_mqtt_server);
  wifiManager.addParameter(&custom_mqtt_port);
  wifiManager.addParameter(&custom_mqtt_user);
  wifiManager.addParameter(&custom_mqtt_pass);

  pinMode(CONFIG_BUTTON, INPUT_PULLUP);
  if (digitalRead(CONFIG_BUTTON) == LOW) {
    wifiManager.startConfigPortal("ESP32_Config");
    sendNotification("Entered configuration mode");
  } else {
    wifiManager.autoConnect("ESP32_Config");
  }

  strcpy(mqtt_server, custom_mqtt_server.getValue());
  strcpy(mqtt_port, custom_mqtt_port.getValue());
  strcpy(mqtt_user, custom_mqtt_user.getValue());
  strcpy(mqtt_pass, custom_mqtt_pass.getValue());

  if (shouldSaveConfig) {
    saveConfig();
    sendNotification("Configuration saved");
  }
}

void callback(char* topic, byte* payload, unsigned int length) {
  // Convert payload to string
  char message[length + 1];
  for (int i = 0; i < length; i++) {
    message[i] = (char)payload[i];
  }
  message[length] = '\0';

  Serial.print("Message arrived [");
  Serial.print(topic);
  Serial.print("] ");
  Serial.println(message);

  if (strcmp(topic, topic_led_command) == 0) {
    if (strcmp(message, "ON") == 0) {
      setLed(true);
    } else if (strcmp(message, "OFF") == 0) {
      setLed(false);
    }
  } 
  else if (strcmp(topic, topic_period) == 0) {
    period = atoi(message) * 1000; // Convert seconds to milliseconds
    saveConfig();
    char notification[50];
    snprintf(notification, sizeof(notification), "Period set to %d seconds", period/1000);
    sendNotification(notification);
  }
  else if (strcmp(topic, topic_reset) == 0) {
    setLed(false);
    lastPeriodCheck = millis();
    sendNotification("Timer reset");
  }
}

void reconnect() {
  while (!client.connected()) {
    Serial.print("Attempting MQTT connection...");
    String clientId = "ESP32Client-" + String(random(0xffff), HEX);
    
    if (client.connect(clientId.c_str(), mqtt_user, mqtt_pass)) {
      Serial.println("connected");
      
      // Subscribe to topics
      client.subscribe(topic_led_command);
      client.subscribe(topic_period);
      client.subscribe(topic_reset);
      
      sendNotification("Reconnected to MQTT");
    } else {
      Serial.print("failed, rc=");
      Serial.print(client.state());
      Serial.println(" try again in 5 seconds");
      delay(5000);
    }
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);
  
  // Initialize filesystem
  if (!LittleFS.begin(true)) {
    Serial.println("LittleFS Mount Failed, formatting...");
    LittleFS.format();
    LittleFS.begin();
  }

  loadConfig();
  setup_wifi();
  
  // MQTT setup
  client.setServer(mqtt_server, atoi(mqtt_port));
  client.setCallback(callback);

  // Send startup notification after 5 seconds
  sendNotification("ESP32 booted successfully");
}

void loop() {
  if (!client.connected()) {
    reconnect();
  }
  client.loop();

  // Handle periodic LED activation
  if (millis() - lastPeriodCheck >= period) {
    setLed(true);
    lastPeriodCheck = millis();
  }

  // Auto turn off LED after 1 second
  if (ledActive && millis() - ledOnTime >= 1000) {
    setLed(false);
  }
}