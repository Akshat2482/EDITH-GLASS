/*
  ============================================================================
  EDITH GLASSES — ESP32-S3 Firmware
  ============================================================================
  Hardware:
    - ESP32-S3 dev board
    - 0.96" 128x64 SSD1306 I2C OLED (yellow top ~16px, blue bottom ~48px)
      I2C address : 0x3C
      SDA         : GPIO 8
      SCL         : GPIO 9

  Function:
    - Boots with a futuristic "EDITH" HUD animation.
    - Advertises as a BLE peripheral named "EDITH-GLASSES".
    - Receives chunked UTF-8 text messages from the companion Android app.
    - Renders text inside a HUD frame, word-wrapped, then MIRRORS the whole
      framebuffer horizontally before pushing it to the physical display,
      because the OLED is viewed through a reflective optical combiner.

  Libraries required (Arduino Library Manager):
    - Adafruit GFX Library
    - Adafruit SSD1306
    - ESP32 BLE Arduino (bundled with the ESP32 board package)

  Board package: esp32 by Espressif Systems (select an ESP32-S3 board, e.g.
  "ESP32S3 Dev Module").
  ============================================================================
*/

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ----------------------------------------------------------------------------
// DISPLAY CONFIG
// ----------------------------------------------------------------------------
#define SCREEN_WIDTH   128
#define SCREEN_HEIGHT  64
#define OLED_ADDR      0x3C
#define OLED_SDA       8
#define OLED_SCL       9
#define OLED_RESET     -1   // no dedicated reset pin

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

// Rough split between the physical yellow (top) and blue (bottom) regions
// of the two-color OLED panel. Used only to keep primary UI elements inside
// the zone that makes visual sense (status bar in yellow, body in blue).
#define YELLOW_ZONE_HEIGHT 16

// ----------------------------------------------------------------------------
// BLE CONFIG  —  see BLE_PROTOCOL.md for full protocol documentation
// ----------------------------------------------------------------------------
#define DEVICE_NAME         "EDITH-GLASSES"
#define SERVICE_UUID         "0000ED01-0000-1000-8000-00805F9B34FB"
#define TEXT_CHAR_UUID        "0000ED02-0000-1000-8000-00805F9B34FB"

// Framing byte written as the FIRST byte of every BLE packet on the text
// characteristic. The remaining bytes of that packet are UTF-8 payload.
#define FRAME_START     0x01   // begins a new message (clears buffer)
#define FRAME_CONTINUE  0x02   // appends payload to buffer
#define FRAME_END       0x03   // appends payload, then message is complete
#define FRAME_APPEND    0x04   // appends a live speech chunk and renders it immediately

#define MAX_MESSAGE_LEN 512    // safety cap on assembled message length

BLEServer         *pServer          = nullptr;
BLECharacteristic *pTextCharacteristic = nullptr;
bool deviceConnected    = false;
bool oldDeviceConnected = false;

String incomingBuffer = "";     // accumulates a message across chunks
volatile bool messageReady = false;
String readyMessage = "";

// ----------------------------------------------------------------------------
// FRAMEBUFFER MIRROR
// ----------------------------------------------------------------------------
// The Adafruit_SSD1306 buffer is laid out in 8 "pages" (rows of 8 pixels),
// each page holding SCREEN_WIDTH bytes, one byte per column (8 vertical
// pixels packed per byte). To flip the image horizontally we only need to
// reverse the column order within each page — the bit order inside each
// byte (vertical direction) must stay untouched.
void flipBufferHorizontal() {
  uint8_t *buf = display.getBuffer();
  const int pages = SCREEN_HEIGHT / 8;
  for (int page = 0; page < pages; page++) {
    int rowStart = page * SCREEN_WIDTH;
    for (int x = 0; x < SCREEN_WIDTH / 2; x++) {
      uint8_t tmp = buf[rowStart + x];
      buf[rowStart + x] = buf[rowStart + (SCREEN_WIDTH - 1 - x)];
      buf[rowStart + (SCREEN_WIDTH - 1 - x)] = tmp;
    }
  }
}

// Always call this instead of display.display() directly, anywhere in the
// firmware. It guarantees nothing unflipped is ever shown.
void pushMirrored() {
  flipBufferHorizontal();
  display.display();
}

// ----------------------------------------------------------------------------
// BLE CALLBACKS
// ----------------------------------------------------------------------------
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *srv) override {
    deviceConnected = true;
  }
  void onDisconnect(BLEServer *srv) override {
    deviceConnected = false;
  }
};

class TextCharCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *chr) override {
    std::string value = chr->getValue();
    if (value.length() == 0) return;

    uint8_t frameType = (uint8_t)value[0];
    String payload = "";
    if (value.length() > 1) {
      payload = String(value.c_str() + 1, value.length() - 1);
    }

    switch (frameType) {
      case FRAME_START:
        incomingBuffer = payload;
        break;
      case FRAME_CONTINUE:
        incomingBuffer += payload;
        break;
      case FRAME_END:
        incomingBuffer += payload;
        if (incomingBuffer.length() > MAX_MESSAGE_LEN) {
          incomingBuffer = incomingBuffer.substring(0, MAX_MESSAGE_LEN);
        }
        readyMessage = incomingBuffer;
        messageReady = true;
        incomingBuffer = "";
        break;

      case FRAME_APPEND:
        // Live speech update: add only the newly recognized suffix and
        // render immediately. There is deliberately no status bar or
        // pagination here — only the HUD brackets and the growing text.
        incomingBuffer += payload;
        if (incomingBuffer.length() > MAX_MESSAGE_LEN) {
          incomingBuffer = incomingBuffer.substring(0, MAX_MESSAGE_LEN);
        }
        readyMessage = incomingBuffer;
        messageReady = true;
        break;
      default:
        // Unknown frame type - ignore, but reset buffer defensively.
        incomingBuffer = "";
        break;
    }
  }
};

void setupBLE() {
  BLEDevice::init(DEVICE_NAME);

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  pTextCharacteristic = pService->createCharacteristic(
      TEXT_CHAR_UUID,
      BLECharacteristic::PROPERTY_WRITE |
      BLECharacteristic::PROPERTY_WRITE_NR |
      BLECharacteristic::PROPERTY_NOTIFY
  );
  pTextCharacteristic->addDescriptor(new BLE2902());
  pTextCharacteristic->setCallbacks(new TextCharCallbacks());

  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);
  pAdvertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();
}

// ----------------------------------------------------------------------------
// HUD PRIMITIVES
// ----------------------------------------------------------------------------
void drawCornerBrackets(int inset = 2, int len = 8) {
  // Top-left
  display.drawLine(inset, inset, inset + len, inset, SSD1306_WHITE);
  display.drawLine(inset, inset, inset, inset + len, SSD1306_WHITE);
  // Top-right
  display.drawLine(SCREEN_WIDTH - 1 - inset, inset, SCREEN_WIDTH - 1 - inset - len, inset, SSD1306_WHITE);
  display.drawLine(SCREEN_WIDTH - 1 - inset, inset, SCREEN_WIDTH - 1 - inset, inset + len, SSD1306_WHITE);
  // Bottom-left
  display.drawLine(inset, SCREEN_HEIGHT - 1 - inset, inset + len, SCREEN_HEIGHT - 1 - inset, SSD1306_WHITE);
  display.drawLine(inset, SCREEN_HEIGHT - 1 - inset, inset, SCREEN_HEIGHT - 1 - inset - len, SSD1306_WHITE);
  // Bottom-right
  display.drawLine(SCREEN_WIDTH - 1 - inset, SCREEN_HEIGHT - 1 - inset, SCREEN_WIDTH - 1 - inset - len, SCREEN_HEIGHT - 1 - inset, SSD1306_WHITE);
  display.drawLine(SCREEN_WIDTH - 1 - inset, SCREEN_HEIGHT - 1 - inset, SCREEN_WIDTH - 1 - inset, SCREEN_HEIGHT - 1 - inset - len, SSD1306_WHITE);
}

void drawStatusBar(const char *label) {
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(4, 2);
  display.print("EDITH");
  int16_t x1, y1; uint16_t w, h;
  display.getTextBounds(label, 0, 0, &x1, &y1, &w, &h);
  display.setCursor(SCREEN_WIDTH - 4 - w, 2);
  display.print(label);
  display.drawFastHLine(0, 11, SCREEN_WIDTH, SSD1306_WHITE);
}

// Word-wrap `text` into lines that fit `maxWidth` pixels at text size 1
// (6px per character including spacing).
void wrapText(const String &text, int maxCharsPerLine, String outLines[], int &lineCount, int maxLines) {
  lineCount = 0;
  int start = 0;
  int len = text.length();

  while (start < len && lineCount < maxLines) {
    int end = start + maxCharsPerLine;
    if (end >= len) {
      outLines[lineCount++] = text.substring(start);
      break;
    }
    // try to break at the last space before `end`
    int breakAt = -1;
    for (int i = end; i > start; i--) {
      if (text.charAt(i) == ' ') { breakAt = i; break; }
    }
    if (breakAt == -1) breakAt = end; // hard break, no spaces found
    outLines[lineCount++] = text.substring(start, breakAt);
    start = breakAt;
    while (start < len && text.charAt(start) == ' ') start++;
  }
}

// ----------------------------------------------------------------------------
// PUBLIC RENDERING API
// ----------------------------------------------------------------------------
// Live receiver view: only the corner brackets and the recognized text are
// drawn. The newest lines stay visible so a long sentence behaves like a
// scrolling HUD rather than a giant paragraph/page.

void renderReceivedText(const String &text) {
  const int maxCharsPerLine = 21;
  const int maxLines = 5;

  String lines[24];
  int totalLines = 0;
  wrapText(text, maxCharsPerLine, lines, totalLines, 24);

  display.clearDisplay();
  drawCornerBrackets(2, 8);

  if (totalLines == 0) {
    pushMirrored();
    return;
  }

  int firstLine = max(0, totalLines - maxLines);
  int visible = min(maxLines, totalLines);

  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);

  for (int i = 0; i < visible; i++) {
    display.setCursor(4, 13 + i * 10);
    display.print(lines[firstLine + i]);
  }

  pushMirrored();
}

// Compatibility wrapper for normal complete BLE messages.
void displayMirroredText(const String &text) {
  renderReceivedText(text);
}

void showIdleHUD() {
  display.clearDisplay();
  drawCornerBrackets(2, 8);

  int cx = SCREEN_WIDTH / 2;
  int cy = 34;

  display.drawCircle(cx, cy, 11, SSD1306_WHITE);
  display.drawCircle(cx, cy, 2, SSD1306_WHITE);
  display.drawFastHLine(cx - 23, cy, 10, SSD1306_WHITE);
  display.drawFastHLine(cx + 14, cy, 10, SSD1306_WHITE);
  display.drawFastVLine(cx, cy - 19, 8, SSD1306_WHITE);
  display.drawFastVLine(cx, cy + 11, 8, SSD1306_WHITE);

  pushMirrored();
}

// ----------------------------------------------------------------------------
// BOOT ANIMATION
// ----------------------------------------------------------------------------
// Compact cinematic startup: scanner sweep -> EDITH lock-on -> HUD brackets.
// It is intentionally fast so the glasses become usable quickly.

void bootAnimation() {
  display.clearDisplay();
  pushMirrored();
  delay(120);

  // 1. Fast vertical scanner with expanding side brackets.
  for (int x = -8; x <= SCREEN_WIDTH + 8; x += 4) {
    display.clearDisplay();

    int left = max(2, x / 3);
    int right = min(SCREEN_WIDTH - 3, SCREEN_WIDTH - 1 - x / 3);
    display.drawFastVLine(x, 8, SCREEN_HEIGHT - 16, SSD1306_WHITE);

    if (x > 8 && x < SCREEN_WIDTH - 8) {
      display.drawFastHLine(8, 8, 18, SSD1306_WHITE);
      display.drawFastHLine(SCREEN_WIDTH - 26, 8, 18, SSD1306_WHITE);
      display.drawFastVLine(left, 8, 7, SSD1306_WHITE);
      display.drawFastVLine(right, 8, 7, SSD1306_WHITE);
    }

    pushMirrored();
    delay(10);
  }

  // 2. EDITH logo materializes with a horizontal scanline.
  for (int phase = 0; phase < 10; phase++) {
    display.clearDisplay();

    display.setTextSize(2);
    display.setTextColor(SSD1306_WHITE);
    int16_t x1, y1;
    uint16_t w, h;
    display.getTextBounds("EDITH", 0, 0, &x1, &y1, &w, &h);
    display.setCursor((SCREEN_WIDTH - w) / 2, 20);
    display.print("EDITH");

    int scanY = 14 + phase * 4;
    display.drawFastHLine(18, scanY, 92, SSD1306_WHITE);

    pushMirrored();
    delay(35);
  }

  // 3. Target lock: ring expands, then collapses into the HUD center.
  int cx = SCREEN_WIDTH / 2;
  int cy = 34;

  for (int r = 22; r >= 7; r -= 3) {
    display.clearDisplay();
    drawCornerBrackets(2, 8);
    display.drawCircle(cx, cy, r, SSD1306_WHITE);
    display.drawFastHLine(cx - r - 8, cy, 6, SSD1306_WHITE);
    display.drawFastHLine(cx + r + 2, cy, 6, SSD1306_WHITE);
    display.drawFastVLine(cx, cy - r - 8, 6, SSD1306_WHITE);
    display.drawFastVLine(cx, cy + r + 2, 6, SSD1306_WHITE);
    pushMirrored();
    delay(45);
  }

  // 4. Brief lock flash.
  display.clearDisplay();
  drawCornerBrackets(2, 8);
  display.fillCircle(cx, cy, 3, SSD1306_WHITE);
  pushMirrored();
  delay(90);

  display.clearDisplay();
  drawCornerBrackets(2, 8);
  display.drawCircle(cx, cy, 11, SSD1306_WHITE);
  display.drawCircle(cx, cy, 2, SSD1306_WHITE);
  pushMirrored();
  delay(160);

  // 5. Final HUD pulse.
  for (int pulse = 0; pulse < 3; pulse++) {
    display.clearDisplay();
    drawCornerBrackets(2, 8);
    int len = 8 + pulse * 3;
    display.drawFastHLine(cx - len, cy, len - 2, SSD1306_WHITE);
    display.drawFastHLine(cx + 2, cy, len - 2, SSD1306_WHITE);
    display.drawFastVLine(cx, cy - len, len - 2, SSD1306_WHITE);
    display.drawFastVLine(cx, cy + 2, len - 2, SSD1306_WHITE);
    pushMirrored();
    delay(45);
  }

  showIdleHUD();
}

// ----------------------------------------------------------------------------
// SETUP / LOOP
// ----------------------------------------------------------------------------
unsigned long lastIdleRefresh = 0;

void setup() {
  Serial.begin(115200);

  Wire.begin(OLED_SDA, OLED_SCL);

  if (!display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR)) {
    Serial.println(F("SSD1306 allocation failed"));
    for (;;) delay(1000); // halt — nothing useful can be shown
  }

  display.setRotation(0);
  display.cp437(true);

  bootAnimation();
  setupBLE();

  Serial.println("EDITH glasses ready. Advertising as " DEVICE_NAME);
}

void loop() {
  // Handle a completed incoming message (non-blocking flag set from the
  // BLE write callback, consumed here on the main loop).
  if (messageReady) {
    messageReady = false;
    String msg = readyMessage;
    renderReceivedText(msg);
    lastIdleRefresh = millis();
  }

  // Return to idle HUD after a period of no new messages, and handle
  // BLE reconnect/advertise housekeeping.
  if (!deviceConnected && oldDeviceConnected) {
    delay(300); // give the BLE stack time to settle
    pServer->startAdvertising();
    oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
  }

  if (millis() - lastIdleRefresh > 15000) {
    showIdleHUD();
    lastIdleRefresh = millis();
  }

  delay(20); // keep loop responsive, avoid busy-spinning
}
