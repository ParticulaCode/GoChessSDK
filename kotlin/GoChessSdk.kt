/**
 * GoChess Kotlin/Android BLE SDK
 * ===============================
 *
 * A single-file BLE SDK for communicating with GoChess smart chess boards
 * from Android applications. Uses Android's native [android.bluetooth] API
 * with Kotlin coroutines for asynchronous operations.
 *
 * The GoChess board uses an nRF52832 BLE SoC with the Nordic UART Service (NUS)
 * for bidirectional communication. This SDK provides a high-level API for:
 *
 * - Scanning and connecting to GoChess boards
 * - Receiving real-time piece movement notifications (via Hall effect sensors)
 * - Querying battery level, board state, border state, and firmware version
 * - Controlling per-square RGB LEDs
 *
 * ## Dependencies
 *
 * Add to your `build.gradle.kts`:
 * ```
 * implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
 * implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
 * ```
 *
 * ## Required Permissions
 *
 * Declare in `AndroidManifest.xml`:
 * ```xml
 * <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
 * <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
 * <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
 * ```
 * The caller is responsible for requesting these permissions at runtime before
 * calling any SDK methods.
 *
 * ## Supported Boards
 *
 * - GoChess XR (Robotic) - advertises as "GoChessXR_XXXXXX"
 * - GoChess Mini          - advertises as "GoChessM_XXXXXX"
 * - GoChess Lite          - advertises as "GoChessL_XXXXXX"
 *
 * ## Protocol Notes
 *
 * The board uses two message formats over the NUS TX characteristic:
 *
 * 1. **Raw messages** (no framing):
 *    - Piece move notifications: 3-4 ASCII bytes, e.g. `"81d"`
 *    - Board state (0x03):  `[0x03][8 bytes]`
 *    - Border state (0x0C): `[0x0C][6 bytes]`
 *    - FW version:          `"Ver" + version_byte`
 *
 * 2. **Framed messages**:
 *    `[START][LEN][TYPE][DATA...][CHECKSUM][CR][LF]`
 *    - START = 0x2A ('*')
 *    - LEN   = total bytes from START through CHECKSUM (inclusive)
 *    - CHECKSUM = sum of all bytes from START through DATA
 *    - CR LF = 0x0D 0x0A
 *    Used for: battery, charging state, etc.
 */

package com.particula.gochess.sdk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ==========================================================================
// Constants
// ==========================================================================

/**
 * BLE UUIDs, command bytes, and response types for the GoChess protocol.
 */
object GoChessConstants {

    // -- Nordic UART Service (NUS) UUIDs ------------------------------------

    /** NUS Service UUID. */
    val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    /** NUS RX Characteristic UUID (App -> Board, write). */
    val NUS_RX_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    /** NUS TX Characteristic UUID (Board -> App, notify). */
    val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** Client Characteristic Configuration Descriptor UUID for enabling notifications. */
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // -- Command bytes (App -> Board) ---------------------------------------

    /** Request border/storage slot occupancy state. */
    const val CMD_GET_BORDER_STATE: Byte = 0x22

    /** Set RGB LEDs with bitmask (12-byte payload: mask1(4) mask2(4) R G B overwrite). */
    const val CMD_SET_RGB_LEDS: Byte = 0x32

    /** Enable/disable LED-on-sensor mode. 1 byte: 0x00/0x01. */
    const val CMD_LED_ON_SENSOR: Byte = 0x33

    /** Set per-square LED colours with multiple colour groups. Variable length. */
    const val CMD_LED_ON_SPECIAL: Byte = 0x34

    /** Request full 8x8 board occupancy state. */
    const val CMD_GET_BOARD_STATE: Byte = 0x35

    /** Set LED display mode. 1 byte: mode 0/1/2. */
    const val CMD_SET_LEDS_MODE: Byte = 0x36

    /** Turn on pattern LEDs (pattern 1). */
    const val CMD_PATTERN_LED_ON: Byte = 0x37

    /** Turn on pattern LEDs (pattern 2). */
    const val CMD_PATTERN2_LED_ON: Byte = 0x38

    /** Request battery percentage. */
    const val CMD_CHECK_BATTERY: Byte = 0x39

    /** Request battery voltage in millivolts. */
    const val CMD_CHECK_BATTERY_MV: Byte = 0x3A

    /** Set interrupt mask. 8 bytes payload. */
    const val CMD_INT_MASK: Byte = 0x3B

    /** Request current measurement in micro-amps. */
    const val CMD_MEASURE_CURRENT: Byte = 0x3C

    /** Request charging state. */
    const val CMD_GET_CHARGING_STATE: Byte = 0x3D

    /** Complex LED message. */
    const val CMD_COMPLEX_LED_MESSAGE: Byte = 0x3E

    /** Complex LED message parts. */
    const val CMD_COMPLEX_LED_PARTS: Byte = 0x3F

    /** Request firmware version. */
    const val CMD_GET_FW_VERSION: Byte = 0x76 // 'v'

    // -- Response message types (Board -> App) ------------------------------

    /** Framed response: battery percentage (1 byte payload). */
    const val RESP_BATTERY: Int = 0x01

    /** Framed response: battery voltage in mV (2 bytes, big-endian). */
    const val RESP_BATTERY_MV: Int = 0x02

    /** Raw response: board state (8 bytes, bitfield per row). */
    const val RESP_BOARD_STATE: Int = 0x03

    /** Framed response: current in micro-amps (2 bytes, big-endian). */
    const val RESP_CURRENT: Int = 0x04

    /** Framed response: charging state (1 byte, 0/1). */
    const val RESP_CHARGING: Int = 0x07

    /** Framed response: chamber connected state (1 byte, 0/1). */
    const val RESP_CHAMBER: Int = 0x0B

    /** Raw response: border state (6 bytes). */
    const val RESP_BORDER_STATE: Int = 0x0C

    // -- Protocol constants -------------------------------------------------

    /** Start byte for framed messages. */
    const val START_BYTE: Int = 0x2A
}

// ==========================================================================
// Data Classes
// ==========================================================================

/**
 * A discovered GoChess board from BLE scanning.
 *
 * @property index Zero-based index in the scan results list.
 * @property name  BLE advertised name (e.g. "GoChessXR_A1B2C3").
 * @property address  BLE MAC address.
 * @property bleDevice  The underlying Android [BluetoothDevice] (internal use).
 */
data class GoChessDevice(
    val index: Int,
    val name: String,
    val address: String,
    internal val bleDevice: BluetoothDevice
)

/**
 * A piece-movement event from the Hall-effect sensors.
 *
 * For board squares:
 * - [row] = 1-8, [col] = 1-8, [isBorder] = false
 *
 * For border/storage slots (GoChess Robotic):
 * - [borderSide] = "r", "l", "t", or "b"
 * - [col] = position 1-10
 * - [isBorder] = true
 * - [row] = 0
 *
 * @property row Row on the board (1-8), or 0 for border events.
 * @property col Column on the board (1-8), or position (1-10) for border events.
 * @property isDown `true` if a piece was placed on the square, `false` if lifted.
 * @property isBorder `true` if this event is from a border/storage slot.
 * @property borderSide Side identifier: "r", "l", "t", "b", or "" for board squares.
 */
data class PieceEvent(
    val row: Int,
    val col: Int,
    val isDown: Boolean,
    val isBorder: Boolean,
    val borderSide: String
)

/**
 * 8x8 board occupancy state.
 *
 * Each square is `true` (piece present) or `false` (empty).
 * Indexed 1-8 for both row and column, matching chess convention:
 * - Row 1 = White's back rank, Row 8 = Black's back rank
 * - Col 1 = a-file, Col 8 = h-file
 *
 * Usage:
 * ```kotlin
 * val state = board.getBoardState()
 * if (state.isOccupied(1, 5)) {  // e1
 *     println("King is home")
 * }
 * println(state)  // pretty-print
 * ```
 *
 * @param raw 8 bytes, one per row; each bit represents a column.
 */
class BoardState(raw: ByteArray) {

    private val _raw: ByteArray

    init {
        require(raw.size == 8) { "Expected 8 bytes, got ${raw.size}" }
        _raw = raw.copyOf()
    }

    /**
     * Check if a square has a piece. [row] and [col] are 1-indexed (1-8).
     *
     * @throws IndexOutOfBoundsException if [row] or [col] is outside 1-8.
     */
    fun isOccupied(row: Int, col: Int): Boolean {
        if (row !in 1..8 || col !in 1..8) {
            throw IndexOutOfBoundsException("row and col must be 1-8, got ($row, $col)")
        }
        return (_raw[row - 1].toInt() and (1 shl (col - 1))) != 0
    }

    /**
     * Return an 8x8 matrix as `Array<BooleanArray>` (row-major, 0-indexed in the array
     * but the underlying data is 1-indexed internally).
     * `result[r][c]` corresponds to row `r+1`, col `c+1`.
     */
    fun toMatrix(): Array<BooleanArray> {
        return Array(8) { r ->
            BooleanArray(8) { c ->
                isOccupied(r + 1, c + 1)
            }
        }
    }

    /** The number of squares with pieces on the board. */
    val pieceCount: Int
        get() {
            var count = 0
            for (r in 1..8) {
                for (c in 1..8) {
                    if (isOccupied(r, c)) count++
                }
            }
            return count
        }

    /** The raw 8-byte occupancy data. */
    val rawBytes: ByteArray
        get() = _raw.copyOf()

    /**
     * Pretty-print the board with file and rank labels.
     * Occupied squares are shown as `\u25A0` and empty as `\u00B7`.
     */
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("  a b c d e f g h")
        for (row in 8 downTo 1) {
            sb.append("$row ")
            for (col in 1..8) {
                sb.append(if (isOccupied(row, col)) "\u25A0" else "\u00B7")
                if (col < 8) sb.append(' ')
            }
            if (row > 1) sb.appendLine()
        }
        return sb.toString()
    }
}

/**
 * Border/storage slot occupancy state (36 positions surrounding the board).
 *
 * Position labels:
 * - Top row (rank 9):    a9, b9, c9, d9, e9, f9, g9, h9
 * - Bottom row (rank 0): a0, b0, c0, d0, e0, f0, g0, h0
 * - Left column (file "q"): q0, q1, q2, q3, q4, q5, q6, q7, q8, q9
 * - Right column (file "i"): i0, i1, i2, i3, i4, i5, i6, i7, i8, i9
 * - Corners: q9 = top-left, i9 = top-right, q0 = bottom-left, i0 = bottom-right
 *
 * Raw byte mapping (6 bytes after the 0x0C message-type byte):
 * - byte 0: Top border, bits 0-7 -> a9..h9
 * - byte 1: Bottom border, bits 0-7 -> a0..h0
 * - byte 2: Left column, bit N -> q(N) for N=0..7
 * - byte 3: Left extension, bit 0 -> q8, bit 1 -> q9
 * - byte 4: Right column, bit N -> i(N) for N=0..7
 * - byte 5: Right extension, bit 0 -> i8, bit 1 -> i9
 *
 * Usage:
 * ```kotlin
 * val border = board.getBorderState()
 * if (border.isOccupied("a9")) {
 *     println("Piece on top border a9")
 * }
 * println(border)
 * ```
 *
 * @param raw 6 bytes of border occupancy data.
 */
class BorderState(raw: ByteArray) {

    private val _raw: ByteArray
    private val _slots: MutableMap<String, Boolean> = mutableMapOf()

    init {
        require(raw.size == 6) { "Expected 6 bytes, got ${raw.size}" }
        _raw = raw.copyOf()
        parse()
    }

    private fun parse() {
        val raw = _raw

        // Byte 0: Top border (rank 9) - bit N -> FILES[N] + "9"
        for (i in 0 until 8) {
            _slots[FILES[i] + "9"] = ((raw[0].toInt() and 0xFF) and (1 shl i)) != 0
        }
        // Byte 1: Bottom border (rank 0) - bit N -> FILES[N] + "0"
        for (i in 0 until 8) {
            _slots[FILES[i] + "0"] = ((raw[1].toInt() and 0xFF) and (1 shl i)) != 0
        }
        // Byte 2: Left column (file "q") - bit N -> "q" + N (N=0..7)
        for (i in 0 until 8) {
            _slots["q$i"] = ((raw[2].toInt() and 0xFF) and (1 shl i)) != 0
        }
        // Byte 3: Left extension - bit 0 -> q8, bit 1 -> q9
        _slots["q8"] = ((raw[3].toInt() and 0xFF) and 0x01) != 0
        _slots["q9"] = ((raw[3].toInt() and 0xFF) and 0x02) != 0
        // Byte 4: Right column (file "i") - bit N -> "i" + N (N=0..7)
        for (i in 0 until 8) {
            _slots["i$i"] = ((raw[4].toInt() and 0xFF) and (1 shl i)) != 0
        }
        // Byte 5: Right extension - bit 0 -> i8, bit 1 -> i9
        _slots["i8"] = ((raw[5].toInt() and 0xFF) and 0x01) != 0
        _slots["i9"] = ((raw[5].toInt() and 0xFF) and 0x02) != 0
    }

    /**
     * Check if a border position is occupied.
     *
     * @param position Label like "a9", "q0", "i5", etc.
     * @return `true` if occupied, `false` otherwise (including unknown positions).
     */
    fun isOccupied(position: String): Boolean = _slots[position] ?: false

    /** All 36 position labels mapped to their occupied state. */
    val slots: Map<String, Boolean>
        get() = _slots.toMap()

    /** Count of occupied border slots. */
    val occupiedCount: Int
        get() = _slots.values.count { it }

    /** The raw 6-byte border occupancy data. */
    val rawBytes: ByteArray
        get() = _raw.copyOf()

    /**
     * ASCII art representation of the border.
     * Occupied slots are shown as `\u25A0` and empty as `\u00B7`.
     */
    override fun toString(): String {
        val sb = StringBuilder()
        fun c(pos: String): Char = if (isOccupied(pos)) '\u25A0' else '\u00B7'

        // Top row
        sb.append(c("q9"))
        sb.append(' ')
        sb.append(FILES.joinToString(" ") { c(it + "9").toString() })
        sb.append(' ')
        sb.appendLine(c("i9"))

        // Side rows 8 -> 1
        for (r in 8 downTo 1) {
            sb.append(c("q$r"))
            sb.append(' ')
            sb.append("\u00B7 ".repeat(8))
            sb.append(' ')
            sb.appendLine(c("i$r"))
        }

        // Bottom row
        sb.append(c("q0"))
        sb.append(' ')
        sb.append(FILES.joinToString(" ") { c(it + "0").toString() })
        sb.append(' ')
        sb.append(c("i0"))

        return sb.toString()
    }

    companion object {
        private val FILES = listOf("a", "b", "c", "d", "e", "f", "g", "h")
    }
}

/**
 * A group of squares sharing the same LED colour, for use with [GoChessBoard.setLedsSpecial].
 *
 * @property squares List of `(row, col)` pairs (1-indexed, 1-8).
 * @property r Red component 0-255.
 * @property g Green component 0-255.
 * @property b Blue component 0-255.
 */
data class LedGroup(
    val squares: List<Pair<Int, Int>>,
    val r: Int,
    val g: Int,
    val b: Int
)

// ==========================================================================
// Helper Functions
// ==========================================================================

/**
 * Utility functions for LED bitmask encoding and chess notation conversion.
 */
object GoChessHelpers {

    /**
     * Convert a list of `(row, col)` pairs (1-indexed) to firmware LED bitmasks.
     *
     * @param squares List of (row, col) pairs where row and col are 1-8.
     * @return A [Pair] of `(mask_rows1to4, mask_rows5to8)`.
     * @throws IllegalArgumentException if any square is outside range 1-8.
     */
    fun buildLedMasks(squares: List<Pair<Int, Int>>): Pair<Int, Int> {
        var mask1 = 0 // rows 1-4 (LED indices 0-31)
        var mask2 = 0 // rows 5-8 (LED indices 32-63)
        for ((row, col) in squares) {
            require(row in 1..8 && col in 1..8) {
                "Square ($row, $col) out of range 1-8."
            }
            if (row <= 4) {
                mask1 = mask1 or (1 shl ((row - 1) * 8 + (col - 1)))
            } else {
                mask2 = mask2 or (1 shl ((row - 5) * 8 + (col - 1)))
            }
        }
        return Pair(mask1, mask2)
    }

    /**
     * Encode LED masks into the 8 data bytes expected by the 0x32 command.
     *
     * The firmware decodes the bytes as follows:
     * ```
     * tmp  = LE32(data[1..4])  ->  g_mask_led2 = reverse_num(tmp)   // rows 5-8
     * tmp2 = LE32(data[5..8])  ->  g_mask_led  = reverse_num(tmp2)  // rows 1-4
     * ```
     *
     * For the 0x32 path to produce correct results, we send the raw masks
     * directly (no reversal) as little-endian 32-bit values.
     *
     * @param mask1 Bitmask for rows 1-4.
     * @param mask2 Bitmask for rows 5-8.
     * @return 8 bytes: LE32(mask1) + LE32(mask2).
     */
    fun encodeLedMasksToBytes(mask1: Int, mask2: Int): ByteArray {
        val result = ByteArray(8)
        // mask1 as LE32
        result[0] = (mask1 and 0xFF).toByte()
        result[1] = ((mask1 shr 8) and 0xFF).toByte()
        result[2] = ((mask1 shr 16) and 0xFF).toByte()
        result[3] = ((mask1 shr 24) and 0xFF).toByte()
        // mask2 as LE32
        result[4] = (mask2 and 0xFF).toByte()
        result[5] = ((mask2 shr 8) and 0xFF).toByte()
        result[6] = ((mask2 shr 16) and 0xFF).toByte()
        result[7] = ((mask2 shr 24) and 0xFF).toByte()
        return result
    }

    /**
     * Convert chess notation like "e4" to a `(row, col)` pair (1-indexed).
     *
     * @param notation Two-character string like "e4", "a1", "h8".
     * @return `Pair(row, col)` with row and col in 1-8.
     * @throws IllegalArgumentException if the notation is invalid.
     */
    fun squareNotationToRC(notation: String): Pair<Int, Int> {
        require(notation.length == 2) { "Invalid notation: $notation" }
        val fileChar = notation[0].lowercaseChar()
        val rankChar = notation[1]
        require(fileChar in 'a'..'h') { "Invalid file: $fileChar" }
        require(rankChar in '1'..'8') { "Invalid rank: $rankChar" }
        val col = fileChar - 'a' + 1
        val row = rankChar - '0'
        return Pair(row, col)
    }

    /**
     * Convert a `(row, col)` pair (1-indexed) to chess notation like "e4".
     *
     * @param row Row 1-8.
     * @param col Column 1-8.
     * @return Two-character chess notation string.
     */
    fun rcToSquareNotation(row: Int, col: Int): String {
        return "${('a' + col - 1)}$row"
    }
}

// ==========================================================================
// Main SDK Class
// ==========================================================================

/**
 * High-level interface to a GoChess smart chess board.
 *
 * Usage:
 * ```kotlin
 * val board = GoChessBoard(context)
 * val devices = GoChessBoard.scan(context)
 * board.connect(devices[0])
 * board.onPieceMove { event -> println(event) }
 * val battery = board.getBattery()
 * board.disconnect()
 * ```
 *
 * @param context Android [Context] used for BLE operations.
 */
@SuppressLint("MissingPermission")
class GoChessBoard(private val context: Context) {

    companion object {
        private const val TAG = "GoChessSdk"

        /**
         * Scan for nearby GoChess boards via BLE.
         *
         * The caller must have already obtained `BLUETOOTH_SCAN` and
         * `ACCESS_FINE_LOCATION` permissions before calling this method.
         *
         * @param context Android [Context].
         * @param timeoutMs How long to scan in milliseconds (default 5000).
         * @return A list of [GoChessDevice] objects found during the scan.
         */
        @SuppressLint("MissingPermission")
        suspend fun scan(context: Context, timeoutMs: Long = 5000L): List<GoChessDevice> {
            val bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter: BluetoothAdapter = bluetoothManager.adapter
                ?: throw IllegalStateException("Bluetooth is not available on this device.")
            val scanner = adapter.bluetoothLeScanner
                ?: throw IllegalStateException(
                    "BLE scanner not available. Is Bluetooth enabled?"
                )

            val devices = mutableListOf<GoChessDevice>()
            val seenAddresses = mutableSetOf<String>()

            val scanFilters = listOf(
                ScanFilter.Builder().setDeviceName(null).build()
            )
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    val name = result.scanRecord?.deviceName ?: device.name ?: return
                    if (name.startsWith("GoChess") && seenAddresses.add(device.address)) {
                        devices.add(
                            GoChessDevice(
                                index = devices.size,
                                name = name,
                                address = device.address,
                                bleDevice = device
                            )
                        )
                        Log.d(TAG, "Found GoChess device: $name (${device.address})")
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "BLE scan failed with error code: $errorCode")
                }
            }

            scanner.startScan(scanFilters, scanSettings, scanCallback)
            delay(timeoutMs)
            scanner.stopScan(scanCallback)

            Log.d(TAG, "Scan complete. Found ${devices.size} device(s).")
            return devices
        }
    }

    // -- Private state -------------------------------------------------------

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var _connected: Boolean = false

    private val pieceCallbacks = mutableListOf<(PieceEvent) -> Unit>()
    private val rawCallbacks = mutableListOf<(ByteArray) -> Unit>()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Any>>()
    private val writeMutex = Mutex()

    // Continuations for the connection state machine
    private var connectContinuation: Continuation<Unit>? = null
    private var disconnectContinuation: Continuation<Unit>? = null
    private var descriptorWriteContinuation: Continuation<Unit>? = null
    private var characteristicWriteContinuation: Continuation<Unit>? = null

    /** `true` if currently connected to a GoChess board. */
    val isConnected: Boolean
        get() = _connected

    // -- Connection ----------------------------------------------------------

    /**
     * Connect to a GoChess board and start listening for notifications.
     *
     * This suspends until the connection is established, services are discovered,
     * and TX notifications are enabled.
     *
     * @param device One of the devices returned by [scan].
     * @throws Exception if the connection fails or times out.
     */
    suspend fun connect(device: GoChessDevice) {
        suspendCancellableCoroutine { cont ->
            connectContinuation = cont
            cont.invokeOnCancellation {
                connectContinuation = null
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
            bluetoothGatt = device.bleDevice.connectGatt(context, false, gattCallback)
        }
        _connected = true
        Log.i(TAG, "Connected to ${device.name} (${device.address})")
    }

    /**
     * Disconnect from the board and release all BLE resources.
     *
     * Any pending command futures are cancelled.
     */
    suspend fun disconnect() {
        val gatt = bluetoothGatt ?: return
        if (_connected) {
            try {
                suspendCancellableCoroutine { cont ->
                    disconnectContinuation = cont
                    cont.invokeOnCancellation {
                        disconnectContinuation = null
                    }
                    gatt.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during disconnect: ${e.message}")
            }
        }
        gatt.close()
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
        _connected = false

        // Cancel any pending futures
        for ((key, deferred) in pending) {
            deferred.cancel()
        }
        pending.clear()
        Log.i(TAG, "Disconnected")
    }

    // -- Callbacks -----------------------------------------------------------

    /**
     * Register a callback invoked whenever a piece is lifted or placed.
     *
     * The callback receives a [PieceEvent]. Multiple callbacks can be registered.
     *
     * @param callback Function to call on each piece movement event.
     */
    fun onPieceMove(callback: (PieceEvent) -> Unit) {
        pieceCallbacks.add(callback)
    }

    /**
     * Register a callback for every raw BLE notification (for debugging).
     *
     * The callback receives the raw byte array from the TX characteristic.
     * Multiple callbacks can be registered.
     *
     * @param callback Function to call on each raw notification.
     */
    fun onRawNotification(callback: (ByteArray) -> Unit) {
        rawCallbacks.add(callback)
    }

    // -- Commands (App -> Board) ---------------------------------------------

    /**
     * Request the battery percentage.
     *
     * @param timeoutMs Timeout in milliseconds (default 5000).
     * @return Battery level 0-100 (%).
     * @throws kotlinx.coroutines.TimeoutCancellationException if no response within timeout.
     */
    suspend fun getBattery(timeoutMs: Long = 5000L): Int {
        val deferred = expect("battery")
        write(byteArrayOf(GoChessConstants.CMD_CHECK_BATTERY))
        return withTimeout(timeoutMs) {
            deferred.await() as Int
        }
    }

    /**
     * Request the full 8x8 board occupancy.
     *
     * @param timeoutMs Timeout in milliseconds (default 5000).
     * @return A [BoardState] object.
     * @throws kotlinx.coroutines.TimeoutCancellationException if no response within timeout.
     */
    suspend fun getBoardState(timeoutMs: Long = 5000L): BoardState {
        val deferred = expect("board_state")
        write(byteArrayOf(GoChessConstants.CMD_GET_BOARD_STATE))
        return withTimeout(timeoutMs) {
            deferred.await() as BoardState
        }
    }

    /**
     * Request border/storage slot occupancy (36 positions around the board).
     *
     * **GoChess XR (Robotic) only** -- Mini and Lite boards do not have
     * border slots and will not respond to this command (will timeout).
     *
     * @param timeoutMs Timeout in milliseconds (default 5000).
     * @return A [BorderState] object with the 36 border positions.
     * @throws kotlinx.coroutines.TimeoutCancellationException if no response within timeout.
     */
    suspend fun getBorderState(timeoutMs: Long = 5000L): BorderState {
        val deferred = expect("border_state")
        write(byteArrayOf(GoChessConstants.CMD_GET_BORDER_STATE))
        return withTimeout(timeoutMs) {
            deferred.await() as BorderState
        }
    }

    /**
     * Request the firmware version byte.
     *
     * @param timeoutMs Timeout in milliseconds (default 5000).
     * @return Version number (e.g. 0x04 for GoChess Robotic, 0x03 for Mini/Lite).
     * @throws kotlinx.coroutines.TimeoutCancellationException if no response within timeout.
     */
    suspend fun getFwVersion(timeoutMs: Long = 5000L): Int {
        val deferred = expect("fw_version")
        write(byteArrayOf(GoChessConstants.CMD_GET_FW_VERSION))
        return withTimeout(timeoutMs) {
            deferred.await() as Int
        }
    }

    /**
     * Turn on LEDs for the given squares with a uniform colour (command 0x32).
     *
     * @param squares List of `(row, col)` pairs (1-indexed, 1-8).
     *                An empty list with [overwrite]=`true` turns all LEDs off.
     * @param r Red   0-255.
     * @param g Green 0-255.
     * @param b Blue  0-255.
     * @param overwrite If `true`, squares *not* in the list are turned off.
     *                  If `false`, only the listed squares are changed;
     *                  others keep their current colour.
     */
    suspend fun setLeds(
        squares: List<Pair<Int, Int>>,
        r: Int = 0,
        g: Int = 0,
        b: Int = 0,
        overwrite: Boolean = true
    ) {
        val (maskLed, maskLed2) = GoChessHelpers.buildLedMasks(squares)
        val maskBytes = GoChessHelpers.encodeLedMasksToBytes(maskLed, maskLed2)

        val data = ByteArray(13)
        data[0] = GoChessConstants.CMD_SET_RGB_LEDS
        System.arraycopy(maskBytes, 0, data, 1, 8)
        // The firmware stores data[9]->g_green, data[10]->g_red, but the main
        // loop passes them swapped to setLedsRGB_I2C (g_red as r-param,
        // g_green as g-param) and the physical LEDs are GRB-wired.
        // Net result: data[9] drives physical RED, data[10] drives physical GREEN.
        data[9] = (r and 0xFF).toByte()
        data[10] = (g and 0xFF).toByte()
        data[11] = (b and 0xFF).toByte()
        data[12] = if (overwrite) 0x01 else 0x00

        write(data)
    }

    /**
     * Turn off all board LEDs.
     */
    suspend fun setLedsOff() {
        setLeds(emptyList(), overwrite = true)
    }

    /**
     * Set per-square LED colours with multiple colour groups (command 0x34).
     *
     * This command first turns off all LEDs, then applies each group.
     *
     * @param groups A list of [LedGroup] objects, each containing squares and an RGB colour.
     *
     * Example:
     * ```kotlin
     * board.setLedsSpecial(listOf(
     *     LedGroup(squares = listOf(4 to 5, 5 to 4), r = 0, g = 255, b = 0),
     *     LedGroup(squares = listOf(2 to 3), r = 255, g = 0, b = 0),
     * ))
     * ```
     */
    suspend fun setLedsSpecial(groups: List<LedGroup>) {
        val buffer = mutableListOf<Byte>()
        buffer.add(GoChessConstants.CMD_LED_ON_SPECIAL)

        for (group in groups) {
            buffer.add(group.squares.size.toByte())
            for ((row, col) in group.squares) {
                require(row in 1..8 && col in 1..8) {
                    "Square ($row, $col) out of range 1-8."
                }
                buffer.add(((row shl 4) or col).toByte()) // upper nibble = row, lower = col
            }
            // Same GRB colour swap as 0x32 -- first byte drives physical RED,
            // second byte drives physical GREEN (see setLeds for details).
            buffer.add((group.r and 0xFF).toByte())
            buffer.add((group.g and 0xFF).toByte())
            buffer.add((group.b and 0xFF).toByte())
        }

        write(buffer.toByteArray())
    }

    /**
     * Convenience: set LEDs using chess notation.
     *
     * Uses [setLedsSpecial] under the hood so each square can have its own colour.
     *
     * @param squareColors Mapping of chess notation to `Triple(r, g, b)`.
     *                     Example: `mapOf("e2" to Triple(0, 255, 0), "e4" to Triple(0, 255, 0))`
     */
    suspend fun setLedsByNotation(squareColors: Map<String, Triple<Int, Int, Int>>) {
        // Group squares by colour
        val colourGroups = mutableMapOf<Triple<Int, Int, Int>, MutableList<Pair<Int, Int>>>()
        for ((notation, rgb) in squareColors) {
            val rc = GoChessHelpers.squareNotationToRC(notation)
            colourGroups.getOrPut(rgb) { mutableListOf() }.add(rc)
        }

        val groups = colourGroups.map { (rgb, sqs) ->
            LedGroup(squares = sqs, r = rgb.first, g = rgb.second, b = rgb.third)
        }
        setLedsSpecial(groups)
    }

    // -- Internal: send / receive --------------------------------------------

    private fun ensureConnected() {
        check(_connected && bluetoothGatt != null) {
            "Not connected to a GoChess board."
        }
    }

    /**
     * Write raw bytes to the NUS RX characteristic.
     *
     * Uses a [Mutex] to serialize BLE writes to avoid overlapping operations.
     */
    private suspend fun write(data: ByteArray) {
        ensureConnected()
        writeMutex.withLock {
            suspendCancellableCoroutine { cont ->
                characteristicWriteContinuation = cont
                cont.invokeOnCancellation {
                    characteristicWriteContinuation = null
                }

                val rx = rxCharacteristic
                    ?: throw IllegalStateException("RX characteristic not found.")
                val gatt = bluetoothGatt
                    ?: throw IllegalStateException("BluetoothGatt not available.")

                Log.d(TAG, "TX -> ${data.joinToString("") { "%02X".format(it) }}")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33+: use the new writeCharacteristic method
                    val result = gatt.writeCharacteristic(
                        rx,
                        data,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )
                    if (result != BluetoothGatt.GATT_SUCCESS) {
                        characteristicWriteContinuation = null
                        cont.resumeWithException(
                            RuntimeException("writeCharacteristic failed with status $result")
                        )
                    }
                } else {
                    // Legacy API: set value then write
                    @Suppress("DEPRECATION")
                    rx.value = data
                    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    if (!gatt.writeCharacteristic(rx)) {
                        characteristicWriteContinuation = null
                        cont.resumeWithException(
                            RuntimeException("writeCharacteristic returned false")
                        )
                    }
                }
            }
        }
    }

    /**
     * Create a [CompletableDeferred] that will be resolved when a matching response arrives.
     */
    private fun expect(key: String): CompletableDeferred<Any> {
        val deferred = CompletableDeferred<Any>()
        pending[key] = deferred
        return deferred
    }

    /**
     * Resolve a pending [CompletableDeferred] by key.
     */
    private fun resolve(key: String, value: Any) {
        val deferred = pending.remove(key)
        deferred?.complete(value)
    }

    // -- GATT Callback -------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected, discovering services...")
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected (status=$status)")
                    _connected = false
                    val dCont = disconnectContinuation
                    disconnectContinuation = null
                    dCont?.resume(Unit)

                    // If we were still connecting, fail the connect continuation
                    val cCont = connectContinuation
                    connectContinuation = null
                    cCont?.resumeWithException(
                        RuntimeException(
                            "Connection lost during setup (status=$status)"
                        )
                    )
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val cCont = connectContinuation
                connectContinuation = null
                cCont?.resumeWithException(
                    RuntimeException("Service discovery failed with status $status")
                )
                return
            }

            Log.d(TAG, "Services discovered")

            val nusService = gatt.getService(GoChessConstants.NUS_SERVICE_UUID)
            if (nusService == null) {
                val cCont = connectContinuation
                connectContinuation = null
                cCont?.resumeWithException(
                    RuntimeException("NUS service not found on device")
                )
                return
            }

            rxCharacteristic = nusService.getCharacteristic(GoChessConstants.NUS_RX_CHAR_UUID)
            txCharacteristic = nusService.getCharacteristic(GoChessConstants.NUS_TX_CHAR_UUID)

            if (rxCharacteristic == null || txCharacteristic == null) {
                val cCont = connectContinuation
                connectContinuation = null
                cCont?.resumeWithException(
                    RuntimeException("NUS RX/TX characteristics not found")
                )
                return
            }

            // Enable notifications on the TX characteristic
            val txChar = txCharacteristic!!
            if (!gatt.setCharacteristicNotification(txChar, true)) {
                val cCont = connectContinuation
                connectContinuation = null
                cCont?.resumeWithException(
                    RuntimeException("setCharacteristicNotification failed")
                )
                return
            }

            // Write to the CCCD to enable notifications
            val descriptor = txChar.getDescriptor(GoChessConstants.CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor == null) {
                val cCont = connectContinuation
                connectContinuation = null
                cCont?.resumeWithException(
                    RuntimeException("CCCD descriptor not found on TX characteristic")
                )
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
                if (result != BluetoothGatt.GATT_SUCCESS) {
                    val cCont = connectContinuation
                    connectContinuation = null
                    cCont?.resumeWithException(
                        RuntimeException("writeDescriptor failed with status $result")
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                if (!gatt.writeDescriptor(descriptor)) {
                    val cCont = connectContinuation
                    connectContinuation = null
                    cCont?.resumeWithException(
                        RuntimeException("writeDescriptor returned false")
                    )
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == GoChessConstants.CLIENT_CHARACTERISTIC_CONFIG) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "TX notifications enabled")
                    // Connection setup complete -- resume the connect continuation
                    val cCont = connectContinuation
                    connectContinuation = null
                    cCont?.resume(Unit)
                } else {
                    val cCont = connectContinuation
                    connectContinuation = null
                    cCont?.resumeWithException(
                        RuntimeException(
                            "Enabling TX notifications failed with status $status"
                        )
                    )
                }
            }

            // Resume any pending descriptor write continuation
            val dCont = descriptorWriteContinuation
            descriptorWriteContinuation = null
            dCont?.resume(Unit)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Legacy callback (pre-API 33)
            if (characteristic.uuid == GoChessConstants.NUS_TX_CHAR_UUID) {
                val data = characteristic.value ?: return
                onNotify(data)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // API 33+ callback
            if (characteristic.uuid == GoChessConstants.NUS_TX_CHAR_UUID) {
                onNotify(value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val cont = characteristicWriteContinuation
            characteristicWriteContinuation = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cont?.resume(Unit)
            } else {
                cont?.resumeWithException(
                    RuntimeException("Characteristic write failed with status $status")
                )
            }
        }
    }

    // -- Notification Handler ------------------------------------------------

    /**
     * Dispatch incoming BLE notifications from the TX characteristic.
     *
     * Matches the Python SDK's `_on_notify` dispatch logic exactly.
     */
    private fun onNotify(data: ByteArray) {
        if (data.isEmpty()) return

        Log.d(TAG, "RX <- ${data.joinToString("") { "%02X".format(it) }}")

        // Invoke raw callbacks
        for (cb in rawCallbacks) {
            try {
                cb(data.copyOf())
            } catch (e: Exception) {
                Log.e(TAG, "Error in raw callback", e)
            }
        }

        val first = data[0].toInt() and 0xFF

        when {
            // --- Framed message: [*][len][type][payload...][checksum][\r\n] ---
            first == GoChessConstants.START_BYTE && data.size >= 5 -> {
                parseFramed(data)
            }

            // --- Raw: Board state [0x03][8 bytes] ---
            first == GoChessConstants.RESP_BOARD_STATE && data.size >= 9 -> {
                resolve("board_state", BoardState(data.copyOfRange(1, 9)))
            }

            // --- Raw: Border state [0x0C][6 bytes] ---
            first == GoChessConstants.RESP_BORDER_STATE && data.size >= 7 -> {
                resolve("border_state", BorderState(data.copyOfRange(1, 7)))
            }

            // --- Raw: FW version "Ver" + byte ---
            first == 0x56 && data.size >= 4
                && (data[1].toInt() and 0xFF) == 0x65   // 'e'
                && (data[2].toInt() and 0xFF) == 0x72 -> // 'r'
            {
                resolve("fw_version", data[3].toInt() and 0xFF)
            }

            // --- Raw: Piece move on board ('1'-'8' first byte) ---
            first in 0x31..0x38 && data.size >= 3 -> {
                emitPieceMove(data)
            }

            // --- Raw: Piece move on border ('r','l','t','b') ---
            first.toChar() in "rltb" && data.size >= 3 -> {
                emitBorderMove(data)
            }

            else -> {
                Log.d(TAG, "Unknown notification: ${data.joinToString("") { "%02X".format(it) }}")
            }
        }
    }

    /**
     * Parse a framed message and resolve the matching pending deferred.
     */
    private fun parseFramed(data: ByteArray) {
        val msgType = data[2].toInt() and 0xFF

        when {
            msgType == GoChessConstants.RESP_BATTERY && data.size >= 4 -> {
                resolve("battery", data[3].toInt() and 0xFF)
            }

            msgType == GoChessConstants.RESP_BATTERY_MV && data.size >= 5 -> {
                val mv = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
                resolve("battery_mv", mv)
            }

            msgType == GoChessConstants.RESP_CHARGING && data.size >= 4 -> {
                resolve("charging", (data[3].toInt() and 0xFF) != 0)
            }

            msgType == GoChessConstants.RESP_CURRENT && data.size >= 5 -> {
                val ua = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
                resolve("current", ua)
            }

            msgType == GoChessConstants.RESP_CHAMBER && data.size >= 4 -> {
                resolve("chamber", (data[3].toInt() and 0xFF) != 0)
            }

            else -> {
                Log.d(TAG, "Unknown framed type 0x${"%02X".format(msgType)}")
            }
        }
    }

    /**
     * Emit a [PieceEvent] for a board-square move.
     *
     * Data format: 3 ASCII bytes, e.g. `'8' '1' 'd'`
     * - byte 0: row ('1'-'8') -> 1-8
     * - byte 1: col ('1'-'8') -> 1-8
     * - byte 2: direction ('d' = down/placed, 'u' = up/lifted)
     */
    private fun emitPieceMove(data: ByteArray) {
        val row = (data[0].toInt() and 0xFF) - 0x30  // ASCII '1'-'8' -> 1-8
        val col = (data[1].toInt() and 0xFF) - 0x30
        val isDown = (data[2].toInt() and 0xFF) == 'd'.code

        val event = PieceEvent(
            row = row,
            col = col,
            isDown = isDown,
            isBorder = false,
            borderSide = ""
        )
        dispatchPiece(event)
    }

    /**
     * Emit a [PieceEvent] for a border/storage slot move.
     *
     * Data format:
     * - 3 bytes for positions 1-9: side, position('1'-'9'), direction('d'/'u')
     * - 4 bytes for position 10:   side, '1', '0', direction
     */
    private fun emitBorderMove(data: ByteArray) {
        val side = (data[0].toInt() and 0xFF).toChar().toString()

        val position: Int
        val isDown: Boolean

        // Position 10 is a 4-byte message: side '1' '0' direction
        if (data.size >= 4
            && (data[1].toInt() and 0xFF) == 0x31   // '1'
            && (data[2].toInt() and 0xFF) == 0x30    // '0'
        ) {
            position = 10
            isDown = (data[3].toInt() and 0xFF) == 'd'.code
        } else {
            position = (data[1].toInt() and 0xFF) - 0x30
            isDown = (data[2].toInt() and 0xFF) == 'd'.code
        }

        val event = PieceEvent(
            row = 0,
            col = position,
            isDown = isDown,
            isBorder = true,
            borderSide = side
        )
        dispatchPiece(event)
    }

    /**
     * Dispatch a [PieceEvent] to all registered piece-move callbacks.
     */
    private fun dispatchPiece(event: PieceEvent) {
        for (cb in pieceCallbacks) {
            try {
                cb(event)
            } catch (e: Exception) {
                Log.e(TAG, "Error in piece-move callback", e)
            }
        }
    }
}
