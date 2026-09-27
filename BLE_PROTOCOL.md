# EDITH Glasses — BLE Protocol

## Advertising
- Device name: `EDITH-GLASSES`
- Role: ESP32-S3 is the BLE **peripheral/server**; the Android phone is the **central/client**.

## GATT layout

| Element | UUID |
|---|---|
| Service | `0000ED01-0000-1000-8000-00805F9B34FB` |
| Text characteristic | `0000ED02-0000-1000-8000-00805F9B34FB` |

Text characteristic properties: `WRITE`, `WRITE_NO_RESPONSE`, `NOTIFY` (notify/CCCD descriptor `0x2902` is present but not required for basic operation — it's there so a future firmware version can push status back to the phone).

## Framing

BLE writes are limited by the negotiated MTU (typically 20 bytes without negotiation, up to ~500 with MTU negotiation). Longer transcripts are therefore split into multiple packets on the Android side and reassembled on the ESP32.

Every packet written to the text characteristic has this shape:

```
[frame_type: 1 byte][utf8 payload: 0..N bytes]
```

| frame_type | Value | Meaning |
|---|---|---|
| START | `0x01` | First packet of a message. ESP32 clears its assembly buffer and stores the payload. |
| CONTINUE | `0x02` | Middle packet(s). Payload is appended to the buffer. |
| END | `0x03` | Final packet. Payload is appended, then the full message is considered complete and is displayed. |

A single-packet message is simply `START` immediately followed by an `END` packet (with the second packet's payload possibly empty), OR the Android app may send one `START` packet whose payload holds the whole string followed by one empty `END` packet — the reference app implementation always sends at least a START and an END, splitting the middle into as many CONTINUE packets as needed.

### Chunk size

The Android app requests an MTU of 517 bytes via `requestMtu()`. After negotiation it computes the usable payload size as `negotiatedMtu - 3 (ATT header) - 1 (frame byte)` and splits the UTF-8-encoded string on that boundary, being careful not to split a multi-byte UTF-8 character across two chunks.

### Message limits

The ESP32 firmware caps an assembled message at 512 characters (`MAX_MESSAGE_LEN`) as a safety bound; excess characters are truncated.

## Example: sending "What's the weather today?"

With a large negotiated MTU this fits in one chunk, so the app sends:

1. `0x01` + `"What's the weather today?"` (START, full text)
2. `0x03` + `""` (END, empty payload — signals completion)

The ESP32 accumulates the buffer on packet 1, and on packet 2 (END) displays the complete string, word-wrapped and mirrored.

## Example: a long message split into 3 chunks

1. `0x01` + `"This is a long message that will not"` (START)
2. `0x02` + `" fit into a single BLE packet so it"` (CONTINUE)
3. `0x03` + `" needs to be split into pieces."` (END)

The ESP32 concatenates all three payloads in order and displays the joined string once the END packet arrives.
