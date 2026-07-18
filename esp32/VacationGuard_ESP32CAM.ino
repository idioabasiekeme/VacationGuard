/*
 * VacationGuard - ESP32-CAM home unit (hardware alternative)
 * ----------------------------------------------------------
 * Hardware: AI-Thinker ESP32-CAM + HC-SR501 PIR sensor on GPIO 13,
 *           optional active buzzer (siren) on GPIO 12.
 *
 * On PIR motion: captures a JPEG and publishes it over MQTT along with
 * an alert message. Listens for SIREN / SIREN_OFF / SNAPSHOT commands
 * from the VacationGuard owner app (same Home ID).
 *
 * Libraries: PubSubClient (Nick O'Leary), esp32-camera (bundled with
 * the esp32 Arduino core, board "AI Thinker ESP32-CAM").
 */
#include <WiFi.h>
#include <PubSubClient.h>
#include "esp_camera.h"

// ---------- CONFIG ----------
const char* WIFI_SSID = "YOUR_WIFI_NAME";
const char* WIFI_PASS = "YOUR_WIFI_PASSWORD";
const char* HOME_ID   = "demo123";              // same as in the app
const char* BROKER    = "broker.hivemq.com";
const int   PIR_PIN   = 13;
const int   SIREN_PIN = 12;
const unsigned long ALERT_COOLDOWN_MS = 15000;
// ----------------------------

// AI-Thinker ESP32-CAM pin map
#define PWDN_GPIO_NUM  32
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM   0
#define SIOD_GPIO_NUM  26
#define SIOC_GPIO_NUM  27
#define Y9_GPIO_NUM    35
#define Y8_GPIO_NUM    34
#define Y7_GPIO_NUM    39
#define Y6_GPIO_NUM    36
#define Y5_GPIO_NUM    21
#define Y4_GPIO_NUM    19
#define Y3_GPIO_NUM    18
#define Y2_GPIO_NUM     5
#define VSYNC_GPIO_NUM 25
#define HREF_GPIO_NUM  23
#define PCLK_GPIO_NUM  22

WiFiClient wifi;
PubSubClient mqtt(wifi);
unsigned long lastAlert = 0;
unsigned long lastBeat = 0;
String tStatus, tAlert, tSnapshot, tCmd;

void publishSnapshot() {
  camera_fb_t* fb = esp_camera_fb_get();
  if (!fb) return;
  mqtt.publish(tSnapshot.c_str(), fb->buf, fb->len);
  esp_camera_fb_return(fb);
}

void onCommand(char* topic, byte* payload, unsigned int len) {
  String cmd;
  for (unsigned int i = 0; i < len; i++) cmd += (char)payload[i];
  if (cmd == "SIREN")      digitalWrite(SIREN_PIN, HIGH);
  else if (cmd == "SIREN_OFF") digitalWrite(SIREN_PIN, LOW);
  else if (cmd == "SNAPSHOT")  publishSnapshot();
}

void connectMqtt() {
  while (!mqtt.connected()) {
    String cid = "vg-esp32-" + String((uint32_t)ESP.getEfuseMac(), HEX);
    if (mqtt.connect(cid.c_str(), tStatus.c_str(), 1, true, "OFFLINE")) {
      mqtt.subscribe(tCmd.c_str());
      mqtt.publish(tStatus.c_str(), "ONLINE esp32", true);
    } else {
      delay(2000);
    }
  }
}

void setup() {
  pinMode(PIR_PIN, INPUT);
  pinMode(SIREN_PIN, OUTPUT);
  digitalWrite(SIREN_PIN, LOW);

  camera_config_t c = {};
  c.ledc_channel = LEDC_CHANNEL_0;
  c.ledc_timer   = LEDC_TIMER_0;
  c.pin_d0 = Y2_GPIO_NUM;  c.pin_d1 = Y3_GPIO_NUM;
  c.pin_d2 = Y4_GPIO_NUM;  c.pin_d3 = Y5_GPIO_NUM;
  c.pin_d4 = Y6_GPIO_NUM;  c.pin_d5 = Y7_GPIO_NUM;
  c.pin_d6 = Y8_GPIO_NUM;  c.pin_d7 = Y9_GPIO_NUM;
  c.pin_xclk = XCLK_GPIO_NUM; c.pin_pclk = PCLK_GPIO_NUM;
  c.pin_vsync = VSYNC_GPIO_NUM; c.pin_href = HREF_GPIO_NUM;
  c.pin_sccb_sda = SIOD_GPIO_NUM; c.pin_sccb_scl = SIOC_GPIO_NUM;
  c.pin_pwdn = PWDN_GPIO_NUM; c.pin_reset = RESET_GPIO_NUM;
  c.xclk_freq_hz = 20000000;
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size   = FRAMESIZE_VGA;   // 640x480 keeps MQTT payload small
  c.jpeg_quality = 15;
  c.fb_count     = 1;
  esp_camera_init(&c);

  WiFi.begin(WIFI_SSID, WIFI_PASS);
  while (WiFi.status() != WL_CONNECTED) delay(300);

  String base = "vacationguard/" + String(HOME_ID) + "/";
  tStatus = base + "status"; tAlert = base + "alert";
  tSnapshot = base + "snapshot"; tCmd = base + "cmd";

  mqtt.setServer(BROKER, 1883);
  mqtt.setBufferSize(60000);        // room for VGA JPEG frames
  mqtt.setCallback(onCommand);
  connectMqtt();
}

void loop() {
  if (!mqtt.connected()) connectMqtt();
  mqtt.loop();

  unsigned long now = millis();
  if (now - lastBeat > 20000) {
    lastBeat = now;
    mqtt.publish(tStatus.c_str(), "ONLINE esp32", true);
  }
  if (digitalRead(PIR_PIN) == HIGH && now - lastAlert > ALERT_COOLDOWN_MS) {
    lastAlert = now;
    mqtt.publish(tAlert.c_str(), "MOTION DETECTED (PIR, ESP32-CAM)");
    publishSnapshot();
  }
}
