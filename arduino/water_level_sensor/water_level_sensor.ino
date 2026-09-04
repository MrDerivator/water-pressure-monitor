/*
 * Water Pressure Monitor — Tank Level sensor firmware (ESP8266)
 * ---------------------------------------------------------------
 * Board: NodeMCU 1.0 (ESP-12E) — ported from an old Arduino IoT Cloud sketch
 * to POST directly to our own backend, same pattern as the ESP32 pressure sensor.
 *
 * Sensor: HC-SR04 ultrasonic, mounted at the TOP of the tank, facing down at the water.
 *   TRIG -> D6 (GPIO12)
 *   ECHO -> D5 (GPIO14)
 *
 * !!! CALIBRATE THESE FOR YOUR TANK !!!
 *   FULL_DISTANCE_CM  = distance from sensor to water surface when tank is FULL (small number)
 *   EMPTY_DISTANCE_CM = distance from sensor to water surface when tank is EMPTY (large number)
 * Defaults below (30 / 300) are carried over from the old sketch — measure your own tank
 * and adjust.
 *
 * Create "secrets.h" next to this sketch (it is .gitignored):
 *   #define WIFI_SSID     "your-wifi"
 *   #define WIFI_PASSWORD "your-password"
 *   #define API_URL       "https://your-app.up.railway.app/api/water-level"
 *   #define API_KEY       "the APP_TANK_DEVICE_API_KEY value from Railway"
 */

#include <ESP8266WiFi.h>
#include <WiFiClientSecureBearSSL.h>
#include <ESP8266HTTPClient.h>
#include "secrets.h"

#define TRIG_PIN 12  // D6
#define ECHO_PIN 14  // D5
const float SOUND_VELOCITY_CM_US = 0.0343;

// ---------------- calibration & config ----------------
const float    FULL_DISTANCE_CM   = 30;    // sensor-to-water when tank is full
const float    EMPTY_DISTANCE_CM  = 300;   // sensor-to-water when tank is empty
const uint32_t SEND_INTERVAL_MS   = 2UL * 60UL * 1000UL; // every 2 minutes
const uint32_t WIFI_TIMEOUT_MS    = 20000;

float readDistanceCm() {
  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(20);
  digitalWrite(TRIG_PIN, LOW);
  // 30ms timeout ~= 5m max range, prevents pulseIn() blocking forever on a bad echo
  float duration = pulseIn(ECHO_PIN, HIGH, 30000);
  return duration * (SOUND_VELOCITY_CM_US / 2.0);
}

bool ensureWifi() {
  if (WiFi.status() == WL_CONNECTED) return true;
  Serial.printf("Connecting to WiFi '%s'...\n", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < WIFI_TIMEOUT_MS) delay(250);
  if (WiFi.status() == WL_CONNECTED) { Serial.println("WiFi connected."); return true; }
  Serial.println("WiFi connect failed.");
  return false;
}

void postReading(float levelPercent, float distanceCm) {
  std::unique_ptr<BearSSL::WiFiClientSecure> client(new BearSSL::WiFiClientSecure);
  client->setInsecure(); // skip cert validation -- fine for a hobby project

  HTTPClient http;
  http.begin(*client, API_URL);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Api-Key", API_KEY);
  http.setTimeout(10000);

  char body[96];
  snprintf(body, sizeof(body), "{\"levelPercent\": %.2f, \"distanceCm\": %.2f}", levelPercent, distanceCm);
  int code = http.POST(body);
  http.end();

  if (code == 201) Serial.printf("Sent %.1f%% (dist %.1fcm) -> 201\n", levelPercent, distanceCm);
  else Serial.printf("POST failed: HTTP %d\n", code);
}

void setup() {
  Serial.begin(115200);
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  Serial.println("\nWater Tank Level Monitor -- starting");
}

void loop() {
  float distance = readDistanceCm();

  if (distance > 0 && distance <= EMPTY_DISTANCE_CM) {
    int clamped = constrain((int)distance, (int)FULL_DISTANCE_CM, (int)EMPTY_DISTANCE_CM);
    float levelPercent = map(clamped, (int)EMPTY_DISTANCE_CM, (int)FULL_DISTANCE_CM, 0, 100);
    Serial.printf("Distance: %.1f cm -> %.0f%%\n", distance, levelPercent);
    if (ensureWifi()) postReading(levelPercent, distance);
  } else {
    Serial.printf("Invalid reading: %.1f cm (out of range, sensor echo failed?)\n", distance);
  }

  delay(SEND_INTERVAL_MS);
}
