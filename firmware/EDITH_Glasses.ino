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
// Displays arbitrary text inside the HUD frame, word-wrapped and paginated.
// Handles long text by paging: each page is shown for `msPerPage`
// milliseconds, then advances automatically. Call is blocking for the total
// duration of all pages (kept short/interruptible via BLE poll inside loop).
void displayMirroredText(const String &text) {
  const int maxCharsPerLine = 21;   // ~128px / 6px per glyph at size 1
  const int maxLinesPerPage = 4;    // lines available below the status bar
  const int maxTotalLines   = 40;   // hard cap on wrapped lines

  String lines[maxTotalLines];
  int totalLines = 0;
  wrapText(text, maxCharsPerLine, lines, totalLines, maxTotalLines);
  if (totalLines == 0) {
    lines[0] = "";
    totalLines = 1;
  }

  int totalPages = (totalLines + maxLinesPerPage - 1) / maxLinesPerPage;
  if (totalPages < 1) totalPages = 1;

  for (int page = 0; page < totalPages; page++) {
    display.clearDisplay();
    drawCornerBrackets();

    char statusLabel[16];
    if (totalPages > 1) {
      snprintf(statusLabel, sizeof(statusLabel), "%d/%d", page + 1, totalPages);
    } else {
      snprintf(statusLabel, sizeof(statusLabel), "RX");
    }
    drawStatusBar(statusLabel);

    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    int y = 16;
    int firstLine = page * maxLinesPerPage;
    int linesOnPage = min(maxLinesPerPage, totalLines - firstLine);
    for (int i = 0; i < linesOnPage; i++) {
      display.setCursor(4, y);
      display.print(lines[firstLine + i]);
      y += 12;
    }

    pushMirrored();

    if (totalPages > 1 && page < totalPages - 1) {
      // Give the BLE stack a chance to process while paging, and allow a
      // new incoming message to interrupt paging early.
      unsigned long pageStart = millis();
      while (millis() - pageStart < 1400) {
        if (messageReady) return; // new message takes priority
        delay(20);
      }
    }
  }
}

void showIdleHUD() {
  display.clearDisplay();
  drawCornerBrackets();
  drawStatusBar("ONLINE");

  // Center crosshair, kept small so it doesn't dominate the frame.
  int cx = SCREEN_WIDTH / 2;
  int cy = 16 + (SCREEN_HEIGHT - 16) / 2;
  display.drawCircle(cx, cy, 14, SSD1306_WHITE);
  display.drawCircle(cx, cy, 2, SSD1306_WHITE);
  display.drawFastHLine(cx - 20, cy, 10, SSD1306_WHITE);
  display.drawFastHLine(cx + 10, cy, 10, SSD1306_WHITE);
  display.drawFastVLine(cx, cy - 20, 10, SSD1306_WHITE);
  display.drawFastVLine(cx, cy + 10, 10, SSD1306_WHITE);

  display.setTextSize(1);
  display.setCursor(4, SCREEN_HEIGHT - 10);
  display.print("STANDBY");

  pushMirrored();
}

// ----------------------------------------------------------------------------
// INITIALIZING ANIMATION
// ----------------------------------------------------------------------------
// A short animated loading sequence used during boot: a rotating spinner
// ring plus a filling progress bar. Every coordinate below is fixed and
// chosen so the whole animation stays inside the 128x64 panel with margin
// to spare — the spinner ring (center 64,33 / radius 9) sits clear of the
// corner brackets above it and the progress bar below it, and the bar
// itself (x:14..114, y:50..58) is well inside the 0..127 / 0..63 bounds.
void initializingAnimation() {
  const int titleY   = 14;             // "INITIALIZING" text row
  const int spinCx    = SCREEN_WIDTH / 2;  // 64
  const int spinCy    = 33;                // clear of title (ends ~y22) and bar (starts y50)
  const int spinRadius = 9;                // ring extends y:24..42, x:55..73 — safely inside frame
  const int barX = 14;
  const int barY = 50;
  const int barW = 100;                // 14 + 100 = 114, inside 128px width
  const int barH = 8;                  // 50 + 8 = 58, inside 64px height
  const int totalSteps = 24;

  for (int step = 0; step < totalSteps; step++) {
    display.clearDisplay();
    drawCornerBrackets();

    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(28, titleY);
    display.print("INITIALIZING");

    // Rotating spinner: 8 points evenly spaced on a ring. The point at the
    // current rotation phase is drawn as a small filled dot (the "leading"
    // point of motion); the rest are single pixels, which reads as a ring
    // spinning around the center at a steady rate.
    int activeDot = step % 8;
    for (int i = 0; i < 8; i++) {
      float angle = i * (2.0 * PI / 8.0);
      int dx = spinCx + (int)round(cos(angle) * spinRadius);
      int dy = spinCy + (int)round(sin(angle) * spinRadius);
      if (i == activeDot) {
        display.fillCircle(dx, dy, 2, SSD1306_WHITE);
      } else {
        display.drawPixel(dx, dy, SSD1306_WHITE);
      }
    }

    // Progress bar frame + fill, advancing one step at a time.
    display.drawRect(barX, barY, barW, barH, SSD1306_WHITE);
    int fillW = map(step, 0, totalSteps - 1, 0, barW - 4);
    if (fillW > 0) {
      display.fillRect(barX + 2, barY + 2, fillW, barH - 4, SSD1306_WHITE);
    }

    pushMirrored();
    delay(45);
  }
}

// ----------------------------------------------------------------------------
// BOOT ANIMATION
// ----------------------------------------------------------------------------
void bootAnimation() {
  // 1. Black screen
  display.clearDisplay();
  pushMirrored();
  delay(200);

  // 2. Scanning line sweep
  for (int x = 0; x < SCREEN_WIDTH; x += 4) {
    display.clearDisplay();
    display.drawFastVLine(x, 0, SCREEN_HEIGHT, SSD1306_WHITE);
    pushMirrored();
    delay(12);
  }
  display.clearDisplay();
  pushMirrored();

  // 3. "EDITH" logo
  display.clearDisplay();
  display.setTextSize(2);
  display.setTextColor(SSD1306_WHITE);
  int16_t x1, y1; uint16_t w, h;
  display.getTextBounds("EDITH", 0, 0, &x1, &y1, &w, &h);
  display.setCursor((SCREEN_WIDTH - w) / 2, 20);
  display.print("EDITH");
  pushMirrored();
  delay(700);

  // 4. INITIALIZING — animated spinner + progress bar (see initializingAnimation()
  // above; it self-contains all bounds so nothing here can draw off-screen).
  initializingAnimation();

  // 5 & 6. Expanding targeting reticle + crosshair
  int cx = SCREEN_WIDTH / 2;
  int cy = SCREEN_HEIGHT / 2;
  for (int r = 2; r <= 22; r += 3) {
    display.clearDisplay();
    display.drawCircle(cx, cy, r, SSD1306_WHITE);
    if (r >= 10) {
      display.drawFastHLine(cx - r - 6, cy, 8, SSD1306_WHITE);
      display.drawFastHLine(cx + r - 2, cy, 8, SSD1306_WHITE);
      display.drawFastVLine(cx, cy - r - 6, 8, SSD1306_WHITE);
      display.drawFastVLine(cx, cy + r - 2, 8, SSD1306_WHITE);
    }
    pushMirrored();
    delay(60);
  }
  delay(200);

  // 7. Target lock flash (brief, non-excessive)
  display.fillCircle(cx, cy, 3, SSD1306_WHITE);
  pushMirrored();
  delay(150);
  display.clearDisplay();
  pushMirrored();
  delay(100);

  // 8. System checks
  const char *checks[] = {"OPTICS", "AUDIO", "NETWORK", "CORE"};
  for (int i = 0; i < 4; i++) {
    display.clearDisplay();
    drawCornerBrackets();
    display.setTextSize(1);
    for (int j = 0; j <= i; j++) {
      display.setCursor(10, 16 + j * 10);
      display.print(checks[j]);
      display.setCursor(100, 16 + j * 10);
      display.print("OK");
    }
    pushMirrored();
    delay(220);
  }
  delay(300);

  // 9. Data sync sweep
  for (int i = 0; i < 3; i++) {
    for (int x = 0; x < SCREEN_WIDTH; x += 8) {
      display.clearDisplay();
      drawCornerBrackets();
      display.setTextSize(1);
      for (int j = 0; j < 4; j++) {
        display.setCursor(10, 16 + j * 10);
        display.print(checks[j]);
        display.setCursor(100, 16 + j * 10);
        display.print("OK");
      }
      display.drawFastVLine(x, 12, SCREEN_HEIGHT - 12, SSD1306_WHITE);
      pushMirrored();
      delay(15);
    }
  }

  // 10. SYSTEM ONLINE
  display.clearDisplay();
  drawCornerBrackets();
  display.setTextSize(1);
  display.getTextBounds("SYSTEM ONLINE", 0, 0, &x1, &y1, &w, &h);
  display.setCursor((SCREEN_WIDTH - w) / 2, 28);
  display.print("SYSTEM ONLINE");
  pushMirrored();
  delay(700);

  // 11. Final HUD
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
    displayMirroredText(msg);
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
