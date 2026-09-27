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
#define YELLOW_ZONE_HEIGHT 18

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
volatile bool messageComplete = false;
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
    String value = chr->getValue();
    if (value.length() == 0) return;

    uint8_t frameType = (uint8_t)value[0];
    String payload = "";
    if (value.length() > 1) {
      payload = value.substring(1);
    }

    switch (frameType) {
      case FRAME_START:
        incomingBuffer = payload;
        messageComplete = false;
        break;
      case FRAME_CONTINUE:
        incomingBuffer += payload;
        messageComplete = false;
        break;
      case FRAME_END:
        incomingBuffer += payload;
        if (incomingBuffer.length() > MAX_MESSAGE_LEN) {
          incomingBuffer = incomingBuffer.substring(0, MAX_MESSAGE_LEN);
        }
        readyMessage = incomingBuffer;
        messageComplete = true;
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
        messageComplete = false;
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
  // Intentionally empty. EDITH's HUD uses reticles, rings and scan marks,
  // never corner brackets.
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
// Live receiver view: text ONLY. No HUD brackets are drawn while text is
// being received. The newest lines stay visible so a long sentence behaves
// like a scrolling HUD rather than a giant paragraph/page.

void drawReceivedTextAtOffset(String lines[], int totalLines, int offsetPx) {
  const int maxLines = 4;

  // The top 18 pixels of this OLED are physically yellow. Keep that zone
  // completely empty during transcription/answers. All received text lives
  // strictly in the blue section below it.
  const int textTop = YELLOW_ZONE_HEIGHT + 2;

  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);

  for (int i = 0; i < totalLines; i++) {
    int y = textTop + i * 10 - offsetPx;
    if (y >= textTop && y < SCREEN_HEIGHT) {
      display.setCursor(4, y);
      display.print(lines[i]);
    }
  }

  pushMirrored();
}

void renderReceivedText(const String &text) {
  const int maxCharsPerLine = 20;
  const int maxLines = 24;

  String lines[24];
  int totalLines = 0;
  wrapText(text, maxCharsPerLine, lines, totalLines, 24);

  if (totalLines == 0) {
    display.clearDisplay();
    pushMirrored();
    return;
  }

  // Normal live speech updates stay pinned to the newest text.
  int finalOffset = max(0, (totalLines - maxLines) * 10);
  drawReceivedTextAtOffset(lines, totalLines, finalOffset);
}

void smoothScrollReceivedText(const String &text) {
  const int maxCharsPerLine = 21;
  const int maxLines = 5;

  String lines[24];
  int totalLines = 0;
  wrapText(text, maxCharsPerLine, lines, totalLines, 24);

  if (totalLines <= maxLines) {
    renderReceivedText(text);
    return;
  }

  int maxOffset = (totalLines - maxLines) * 10;

  // Start at the top and smoothly scroll down to the newest line.
  for (int offset = 0; offset <= maxOffset; offset += 2) {
    drawReceivedTextAtOffset(lines, totalLines, offset);
    delay(18);
  }

  drawReceivedTextAtOffset(lines, totalLines, maxOffset);
}

// Compatibility wrapper for normal complete BLE messages.
void displayMirroredText(const String &text) {
  renderReceivedText(text);
}

void showIdleHUD() {
  display.clearDisplay();

  // Minimal EDITH-style reticle: no corner brackets.
  int cx = SCREEN_WIDTH / 2;
  int cy = 35;

  display.drawCircle(cx, cy, 14, SSD1306_WHITE);
  display.drawCircle(cx, cy, 5, SSD1306_WHITE);
  display.drawPixel(cx, cy, SSD1306_BLACK);

  display.drawFastHLine(cx - 31, cy, 12, SSD1306_WHITE);
  display.drawFastHLine(cx + 20, cy, 12, SSD1306_WHITE);
  display.drawFastVLine(cx, cy - 27, 10, SSD1306_WHITE);
  display.drawFastVLine(cx, cy + 18, 10, SSD1306_WHITE);

  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(4, 2);
  display.print("EDITH");
  display.setCursor(92, 2);
  display.print("READY");

  pushMirrored();
}

// ----------------------------------------------------------------------------
// BOOT ANIMATION
// ----------------------------------------------------------------------------
// Cinematic startup: blue-style edge flash -> INITIALIZING progress -> EDITH lock-on -> HUD.
// It is intentionally fast so the glasses become usable quickly.

void bootAnimation() {
  int cx = SCREEN_WIDTH / 2;
  int cy = 36;

  // 1. EDITH powers up with blue-style perimeter flashes.
  display.clearDisplay();
  pushMirrored();
  delay(100);

  for (int flash = 0; flash < 2; flash++) {
    display.clearDisplay();

    // Perimeter edge flash.
    display.drawFastHLine(0, 0, SCREEN_WIDTH, SSD1306_WHITE);
    display.drawFastHLine(0, SCREEN_HEIGHT - 1, SCREEN_WIDTH, SSD1306_WHITE);
    display.drawFastVLine(0, 0, SCREEN_HEIGHT, SSD1306_WHITE);
    display.drawFastVLine(SCREEN_WIDTH - 1, 0, SCREEN_HEIGHT, SSD1306_WHITE);

    pushMirrored();
    delay(45);

    display.clearDisplay();
    pushMirrored();
    delay(35);
  }

  // 2. INITIALIZING... with a progress bar in the blue display area.
  const int barX = 12;
  const int barY = 48;
  const int barW = 104;
  const int barH = 7;

  for (int progress = 0; progress <= 100; progress += 4) {
    display.clearDisplay();

    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(34, 21);
    display.print("INITIALIZING...");

    display.drawRect(barX, barY, barW, barH, SSD1306_WHITE);
    int fillW = ((barW - 2) * progress) / 100;
    if (fillW > 0) {
      display.fillRect(barX + 1, barY + 1, fillW, barH - 2, SSD1306_WHITE);
    }

    // Small moving scan marker above the bar.
    int markerX = barX + ((barW - 1) * progress) / 100;
    display.drawFastVLine(markerX, 31, 10, SSD1306_WHITE);

    pushMirrored();
    delay(18);
  }

  // 3. EDITH identity appears behind a scanning line.
  for (int phase = 0; phase < 14; phase++) {
    display.clearDisplay();

    display.setTextSize(2);
    display.setTextColor(SSD1306_WHITE);
    int16_t x1, y1;
    uint16_t w, h;
    display.getTextBounds("EDITH", 0, 0, &x1, &y1, &w, &h);
    display.setCursor((SCREEN_WIDTH - w) / 2, 20);
    display.print("EDITH");

    int scanY = 10 + phase * 3;
    display.drawFastHLine(10, scanY, 108, SSD1306_WHITE);
    display.drawFastHLine(25, scanY + 1, 78, SSD1306_WHITE);

    pushMirrored();
    delay(25);
  }

  // 4. Reticle acquires a target: expanding rings + four tracking ticks.
  for (int r = 28; r >= 8; r -= 2) {
    display.clearDisplay();

    display.drawCircle(cx, cy, r, SSD1306_WHITE);
    if (r > 12) display.drawCircle(cx, cy, r - 5, SSD1306_WHITE);

    display.drawFastHLine(cx - r - 9, cy, 7, SSD1306_WHITE);
    display.drawFastHLine(cx + r + 2, cy, 7, SSD1306_WHITE);
    display.drawFastVLine(cx, cy - r - 9, 7, SSD1306_WHITE);
    display.drawFastVLine(cx, cy + r + 2, 7, SSD1306_WHITE);

    pushMirrored();
    delay(35);
  }

  // 5. Target lock flash.
  display.clearDisplay();
  display.fillCircle(cx, cy, 7, SSD1306_WHITE);
  display.drawCircle(cx, cy, 18, SSD1306_WHITE);
  pushMirrored();
  delay(55);

  display.clearDisplay();
  display.drawCircle(cx, cy, 18, SSD1306_WHITE);
  display.drawCircle(cx, cy, 4, SSD1306_WHITE);
  pushMirrored();
  delay(70);

  // 6. HUD settles into the live reticle.
  for (int pulse = 0; pulse < 4; pulse++) {
    display.clearDisplay();

    int r = 9 + pulse * 2;
    display.drawCircle(cx, cy, r, SSD1306_WHITE);
    display.drawFastHLine(cx - 25 - pulse, cy, 10, SSD1306_WHITE);
    display.drawFastHLine(cx + 16 + pulse, cy, 10, SSD1306_WHITE);
    display.drawFastVLine(cx, cy - 22 - pulse, 8, SSD1306_WHITE);
    display.drawFastVLine(cx, cy + 15 + pulse, 8, SSD1306_WHITE);

    pushMirrored();
    delay(40);
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
    bool complete = messageComplete;
    String msg = readyMessage;

    if (complete) {
      smoothScrollReceivedText(msg);
    } else {
      renderReceivedText(msg);
    }

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
