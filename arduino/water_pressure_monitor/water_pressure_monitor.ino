/*
 * Water Pressure Monitor — ESP8266 sensor firmware
 * ------------------------------------------------
 * Sensor: 1.2 MPa (174 PSI) analog transducer, DC 5V supply, 0.5–4.5 V output, G1/4 thread.
 *
 * !!! WIRING — READ THIS FIRST !!!
 * The sensor outputs up to 4.5 V. ESP8266 dev boards (NodeMCU, Wemos D1 mini) expose
 * a single analog pin "A0" that already has an onboard divider bringing it down to the
 * chip's internal ~1.0 V ADC — but the BOARD's A0 input itself is only rated to roughly
 * 3.2–3.3 V (varies slightly by manufacturer). You still need an EXTERNAL divider in
 * front of it:
 *
 *      sensor signal ──[ R1 = 10 kΩ ]──┬──> board pin "A0"
 *                                      │
 *                                 [ R2 = 20 kΩ ]
 *                                      │
 *                                     GND
 *
 *   Divider ratio = R2 / (R1 + R2) = 20k / 30k = 0.6667  →  4.5 V becomes 3.0 V.
 *   That leaves headroom under the ~3.2 V board limit even accounting for tolerances.
 *
 *   Sensor V+  -> 5 V (VIN/USB 5V rail)
 *   Sensor GND -> GND (shared with ESP8266!)
 *
 *   >>> If this is instead a BARE ESP-12/07 module (no onboard USB, wired straight to
 *       the chip's TOUT pin, no onboard A0 divider) the real limit is 1.0 V, NOT 3.2 V.
 *       Feeding it 3 V will damage the pin. In that case use R1=100k / R2=27k instead
 *       (4.5V -> ~0.96V) and set ADC_FULL_SCALE_VOLTS to 1.0 below.
 *
 * Calibration: PSI = (V_sensor − 0.5 V) × 174 / 4.0
 * where V_sensor = V_adc / DIVIDER_RATIO.
 *
 * IMPORTANT — ADC_FULL_SCALE_VOLTS below is a starting estimate, not a guarantee.
 * The onboard divider on NodeMCU/Wemos-style boards varies by manufacturer (usually
 * 3.2–3.3 V full scale). To calibrate precisely: feed a known clean voltage (e.g. 3.0 V
 * from a bench supply, or from the 3.3V rail through a simple divider) into A0, note
 * the analogRead() value, and solve:
 *   ADC_FULL_SCALE_VOLTS = knownVoltage / (reading / 1023.0)
 *
 * ZERO-POINT CALIBRATION (fixes the "reads ~2-3 PSI at atmospheric pressure" offset):
 * Component tolerances (divider resistors, ADC_FULL_SCALE_VOLTS estimate, the sensor's
 * own manufacturing tolerance) stack up into a small systematic offset. Rather than
 * hand-tuning constants and reflashing, this firmware supports a serial-triggered
 * zero calibration:
 *   1. Leave the sensor open to atmosphere (NOT connected to a pressurized line).
 *   2. Open Serial Monitor, type "c" and press Enter.
 *   3. The board averages ~15 readings over ~3s and stores the result as a zero
 *      offset in EEPROM (flash) — it persists across reboots and re-flashing the
 *      sketch (as long as you don't erase flash).
 * This corrects the ZERO point only, not the slope/gain. If you later want tighter
 * accuracy at higher pressures too, a two-point calibration against a reference
 * gauge is the next step — not needed to fix today's offset.
 *
 * Create a file "secrets.h" next to this sketch (it is .gitignored):
 *   #define WIFI_SSID     "your-wifi"
 *   #define WIFI_PASSWORD "your-password"
 *   #define API_URL       "https://your-app.up.railway.app/api/measurements"
 *   #define API_KEY       "the APP_DEVICE_API_KEY value from Railway"
 */

#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <WiFiClientSecure.h>
#include <EEPROM.h>
#include "secrets.h"

// ---------------- configuration ----------------
const uint32_t SEND_INTERVAL_MS     = 1UL * 60UL * 1000UL; // every 1 minutes (owner-confirmed)
const int      ADC_PIN              = A0;                  // ESP8266 has exactly one analog pin
const int      ADC_SAMPLES          = 20;                  // averaged per reading
const int      ADC_RESOLUTION       = 1023;                 // ESP8266 ADC is 10-bit (0–1023)
const float    ADC_FULL_SCALE_VOLTS = 3.3;                 // TODO: calibrate for your specific board — see note above
const float    DIVIDER_RATIO        = 20.0 / (10.0 + 20.0);// R2/(R1+R2) — match your resistors!
const float    SENSOR_ZERO_V        = 0.5;                 // V at 0 PSI (datasheet nominal)
const float    SENSOR_SPAN_V        = 4.0;                 // V across full range (0.5→4.5)
const float    SENSOR_RANGE_PSI     = 174.0;                // 1.2 MPa
const int      BUFFER_SIZE          = 30;                  // offline retry buffer (~1 h of readings)
const uint32_t WIFI_TIMEOUT_MS      = 20000;

// ---------------- zero-offset calibration (EEPROM) ----------------
const int     EEPROM_SIZE        = 16;
const int     EEPROM_ADDR_MAGIC  = 0;   // 1 byte marker so we know EEPROM was ever written
const int     EEPROM_ADDR_OFFSET = 4;   // float, 4 bytes
const uint8_t EEPROM_MAGIC       = 0xA5;
const int     CAL_SAMPLES        = 15;  // readings averaged during calibration
const uint32_t CAL_SAMPLE_DELAY_MS = 200;

float zeroOffsetPsi = 0.0; // subtracted from every raw reading; 0 until calibrated

void loadCalibration() {
  uint8_t magic;
  EEPROM.get(EEPROM_ADDR_MAGIC, magic);
  if (magic == EEPROM_MAGIC) {
    EEPROM.get(EEPROM_ADDR_OFFSET, zeroOffsetPsi);
    Serial.printf("Loaded zero-offset calibration: %.2f PSI\n", zeroOffsetPsi);
  } else {
    zeroOffsetPsi = 0.0;
    Serial.println("No calibration stored yet. At atmospheric pressure, send 'c' to zero-calibrate.");
  }
}

void saveCalibration(float offset) {
  zeroOffsetPsi = offset;
  EEPROM.put(EEPROM_ADDR_MAGIC, EEPROM_MAGIC);
  EEPROM.put(EEPROM_ADDR_OFFSET, offset);
  EEPROM.commit();
  Serial.printf("Saved zero-offset calibration: %.2f PSI (persists across reboots)\n", offset);
}

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
// Raw reading BEFORE zero-offset correction — used both for normal readings and
// for computing a new calibration offset (so calibration measures the same thing
// it's meant to cancel out).
float readRawPsi() {
  // Median-of-3 batches of averaged samples to reject spikes
  float batches[3];
  for (int b = 0; b < 3; b++) {
    uint32_t sum = 0;
    for (int i = 0; i < ADC_SAMPLES; i++) { sum += analogRead(ADC_PIN); delay(3); }
    float raw     = sum / (float)ADC_SAMPLES;                     // 0–1023 counts
    float vAdc    = (raw / ADC_RESOLUTION) * ADC_FULL_SCALE_VOLTS; // volts at the board's A0 pin
    float vSensor = vAdc / DIVIDER_RATIO;                          // undo the external divider
    batches[b] = (vSensor - SENSOR_ZERO_V) * SENSOR_RANGE_PSI / SENSOR_SPAN_V;
  }
  // median of 3
  float lo = min(batches[0], min(batches[1], batches[2]));
  float hi = max(batches[0], max(batches[1], batches[2]));
  return batches[0] + batches[1] + batches[2] - lo - hi;
}

float readPressurePsi() {
  float psi = readRawPsi() - zeroOffsetPsi;
  return psi < 0 ? 0 : psi;
}

void runCalibration() {
  Serial.println("Calibrating zero point — make sure the sensor is at atmospheric pressure");
  Serial.println("(NOT connected to a pressurized line) for the next few seconds...");
  float sum = 0;
  for (int i = 0; i < CAL_SAMPLES; i++) {
    float r = readRawPsi();
    Serial.printf("  sample %d/%d: %.2f PSI (raw)\n", i + 1, CAL_SAMPLES, r);
    sum += r;
    delay(CAL_SAMPLE_DELAY_MS);
  }
  saveCalibration(sum / CAL_SAMPLES);
}

void checkSerialCommands() {
  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    if (cmd == "c" || cmd == "cal" || cmd == "calibrate") {
      runCalibration();
    }
  }
}

// Same total wait as delay(), but keeps checking for a 'c' calibration command
// every 50ms instead of blocking, so calibration doesn't have to wait for the
// full SEND_INTERVAL_MS to elapse.
void smartDelay(uint32_t ms) {
  uint32_t start = millis();
  while (millis() - start < ms) {
    checkSerialCommands();
    delay(50);
  }
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
  WiFiClientSecure client;
  client.setInsecure(); // preskače provjeru certifikata — u redu za hobi projekt

  HTTPClient http;
  http.begin(client, API_URL);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Api-Key", API_KEY);
  http.setTimeout(10000);

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
  Serial.println("\nWater Pressure Monitor — starting");
  EEPROM.begin(EEPROM_SIZE);
  loadCalibration();
  Serial.println("(At any time: type 'c' + Enter here to zero-calibrate at atmospheric pressure.)");
}

void loop() {
  checkSerialCommands();

  float psi = readPressurePsi();
  Serial.printf("Reading: %.2f PSI\n", psi);
  bufferPush(psi);

  if (ensureWifi()) flushBuffer();

  smartDelay(SEND_INTERVAL_MS);
}
