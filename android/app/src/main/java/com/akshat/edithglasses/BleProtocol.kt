package com.akshat.edithglasses

import java.util.UUID

/**
 * Shared BLE protocol constants. Must match the ESP32 firmware exactly —
 * see /BLE_PROTOCOL.md at the project root for the full write-up.
 */
object BleProtocol {
    const val DEVICE_NAME = "EDITH-GLASSES"

    val SERVICE_UUID: UUID = UUID.fromString("0000ed01-0000-1000-8000-00805f9b34fb")
    val TEXT_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ed02-0000-1000-8000-00805f9b34fb")
    val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val FRAME_START: Byte = 0x01
    const val FRAME_CONTINUE: Byte = 0x02
    const val FRAME_END: Byte = 0x03
    const val FRAME_APPEND: Byte = 0x04

    /** MTU we ask the peripheral for; real usable size may be negotiated lower. */
    const val REQUESTED_MTU = 517

    /** ATT header overhead subtracted from the negotiated MTU. */
    const val ATT_HEADER_OVERHEAD = 3

    /** One byte of every packet is the frame-type marker. */
    const val FRAME_HEADER_SIZE = 1
}
