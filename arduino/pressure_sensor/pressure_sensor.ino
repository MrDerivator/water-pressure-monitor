/*
 * Water Pressure Monitor — ESP32 sensor firmware
 * ------------------------------------------------
 * Sensor: 1.2 MPa (174 PSI) analog transducer, DC 5V supply, 0.5–4.5 V output, G1/4 thread.
 *
 * !!! WIRING — READ THIS FIRST !!!
 * The sensor outputs up to 4.5 V. The ESP32 ADC tolerates ~3.3 V MAX.
 * Connect the sensor signal through a voltage divider:
 *
 *      sensor signal ──[ R1 = 10 kΩ ]──┬──> ESP32 GPIO34 (ADC1_CH6, input-only pin)
 *                                      │
 *                                 [ R2 = 20 kΩ ]
 *                                      │
 *                                     GND
 *
 *   Divider ratio = R2 / (R1 + R2) = 20k / 30k = 0.6667  →  4.5 V becomes 3.0 V. Safe.
 *   Sensor V+  -> 5 V (VIN/USB 5V rail)
 *   Sensor GND -> GND (shared with ESP32!)
 *
 * Calibration: PSI = (V_sensor − 0.5 V) × 174 / 4.0
 * where V_sensor = V_adc / DIVIDER_RATIO.
 *
 * Create a file "secrets.h" next to this sketch (it is .gitignored):
 *   #define WIFI_SSID     "your-wifi"
 *   #define WIFI_PASSWORD "your-password"
 *   #define API_URL       "https://your-app.up.railway.app/api/measurements"
 *   #define API_KEY       "the APP_DEVICE_API_KEY value from Railway"
 */

#include <WiFi.h>
#include <HTTPClient.h>
#include "secrets.h"

// ---------------- configuration ----------------
const uint32_t SEND_INTERVAL_MS   = 2UL * 60UL * 1000UL; // every 2 minutes (owner-confirmed)
const int      ADC_PIN            = 34;                  // input-only ADC1 pin (safe with WiFi)
const int      ADC_SAMPLES        = 20;                  // averaged per reading
const float    DIVIDER_RATIO      = 20.0 / (10.0 + 20.0);// R2/(R1+R2) — match your resistors!
const float    SENSOR_ZERO_V      = 0.5;                 // V at 0 PSI
const float    SENSOR_SPAN_V      = 4.0;                 // V across full range (0.5→4.5)
const float    SENSOR_RANGE_PSI   = 174.0;               // 1.2 MPa
const int      BUFFER_SIZE        = 30;                  // offline retry buffer (~1 h of readings)
const uint32_t WIFI_TIMEOUT_MS    = 20000;

// ---------------- offline buffer ----------------
struct Reading { float psi; uint32_t ageMs; };
Reading buffer[BUFFER_SIZE];
int bufCount = 0;

void bufferPush(float psi) {
  if (bufCount == BUFFER_SIZE) { // full: drop oldest
    for (int i = 1; i < BUFFER_SIZE; i++) buffer[i - 1] = buffer[i];
    bufCount--;
  }
  buffer[bufCount++] = { psi, millis() };
}

// ---------------- sensor ----------------
float readPressurePsi() {
  // Median-of-3 batches of averaged samples to reject spikes
  float batches[3];
  for (int b = 0; b < 3; b++) {
    uint32_t sum = 0;
    for (int i = 0; i < ADC_SAMPLES; i++) { sum += analogReadMilliVolts(ADC_PIN); delay(3); }
    float vAdc = (sum / (float)ADC_SAMPLES) / 1000.0;       // volts at the pin
    float vSensor = vAdc / DIVIDER_RATIO;                   // undo the divider
    batches[b] = (vSensor - SENSOR_ZERO_V) * SENSOR_RANGE_PSI / SENSOR_SPAN_V;
  }
  // median of 3
  float lo = min(batches[0], min(batches[1], batches[2]));
  float hi = max(batches[0], max(batches[1], batches[2]));
  float psi = batches[0] + batches[1] + batches[2] - lo - hi;
  return psi < 0 ? 0 : psi;
}

// ---------------- network ----------------
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

bool postReading(float psi, long ageSeconds) {
  HTTPClient http;
  http.begin(API_URL); // Railway serves a valid public TLS cert; ESP32 uses bundled roots
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Api-Key", API_KEY);
  http.setTimeout(10000);

  // Server stamps the time; for buffered readings we only note staleness in logs.
  char body[64];
  snprintf(body, sizeof(body), "{\"pressurePsi\": %.2f}", psi);
  int code = http.POST(body);
  http.end();

  if (code == 201) {
    Serial.printf("Sent %.2f PSI (buffered %lds ago) -> 201\n", psi, ageSeconds);
    return true;
  }
  Serial.printf("POST failed: HTTP %d\n", code);
  return false;
}

void flushBuffer() {
  while (bufCount > 0) {
    Reading r = buffer[0];
    long age = (millis() - r.ageMs) / 1000;
    if (!postReading(r.psi, age)) return; // still failing — keep buffer, retry next cycle
    for (int i = 1; i < bufCount; i++) buffer[i - 1] = buffer[i];
    bufCount--;
  }
}

// ---------------- main ----------------
void setup() {
  Serial.begin(115200);
  analogSetPinAttenuation(ADC_PIN, ADC_11db); // full ~0–3.3 V range
  Serial.println("\nWater Pressure Monitor — starting");
}

void loop() {
  float psi = readPressurePsi();
  Serial.printf("Reading: %.2f PSI\n", psi);
  bufferPush(psi);

  if (ensureWifi()) flushBuffer();

  delay(SEND_INTERVAL_MS);
}
