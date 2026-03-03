# GoChess Kotlin (Android) SDK

![Platform](https://img.shields.io/badge/platform-Android-green)
![Language](https://img.shields.io/badge/language-Kotlin-purple)
![Min SDK](https://img.shields.io/badge/minSdk-21-blue)
![BLE](https://img.shields.io/badge/BLE-native%20android.bluetooth-informational)
![License](https://img.shields.io/badge/license-Proprietary-lightgrey)

A single-file BLE SDK for communicating with **GoChess** smart chess boards from
Android applications. Uses Android's native `android.bluetooth` API with Kotlin
coroutines for clean, asynchronous operations -- no external BLE library required.

---

## Features

- **BLE scanning and connection** -- discover and connect to GoChess boards over
  Bluetooth Low Energy.
- **Real-time piece tracking** -- receive callbacks when pieces are lifted or
  placed on the 8x8 board (Hall-effect sensors).
- **Board state queries** -- read the full 64-square occupancy map in a single
  call.
- **Border / storage slot support** -- 36 border positions on the GoChess XR
  (Robotic) model.
- **Per-square RGB LED control** -- light individual squares in any colour, with
  single-colour and multi-colour group APIs.
- **Battery and firmware queries** -- read battery percentage and firmware
  version.
- **Coroutine-first API** -- every I/O operation is a `suspend` function,
  integrating naturally with `viewModelScope`, `lifecycleScope`, and structured
  concurrency.
- **Zero external BLE dependencies** -- built entirely on
  `android.bluetooth.*`.

---

## Requirements

| Requirement | Detail |
|---|---|
| Android Studio | Arctic Fox (2020.3.1) or later |
| Kotlin | 1.7+ |
| Min SDK (compile) | API 21 (Android 5.0) |
| Recommended Min SDK | API 23 (Android 6.0) -- runtime permission model |
| Target SDK | API 33+ recommended |
| BLE hardware | Device must support Bluetooth Low Energy |

---

## Installation

### 1. Copy the SDK file

Copy `GoChessSdk.kt` into your project source tree, for example:

```
app/src/main/java/com/particula/gochess/sdk/GoChessSdk.kt
```

The file declares the package `com.particula.gochess.sdk`. Adjust the
package declaration if your project structure differs.

### 2. Add Gradle dependencies

In your module-level `build.gradle.kts`:

```kotlin
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

### 3. Declare permissions in AndroidManifest.xml

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

    <!-- Required for BLE on older devices -->
    <uses-permission android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />

    <uses-feature
        android:name="android.hardware.bluetooth_le"
        android:required="true" />

    <!-- ... -->
</manifest>
```

### 4. Request permissions at runtime

Permissions must be granted **before** calling any SDK method. On API 31+
(Android 12), `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` are runtime permissions.
On older versions, `ACCESS_FINE_LOCATION` is required for BLE scanning.

```kotlin
val requiredPermissions = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    add(Manifest.permission.ACCESS_FINE_LOCATION)
}.toTypedArray()

// Use ActivityResultContracts.RequestMultiplePermissions() to request them.
```

---

## Quick Start

```kotlin
import com.particula.gochess.sdk.*
import kotlinx.coroutines.*

// Inside a CoroutineScope (e.g. viewModelScope, lifecycleScope):

// 1. Scan for boards (5-second window)
val devices = GoChessBoard.scan(context, timeoutMs = 5000L)
if (devices.isEmpty()) {
    println("No boards found")
    return@launch
}

// 2. Connect to the first board found
val board = GoChessBoard(context)
board.connect(devices[0])

// 3. Register a piece-move callback
board.onPieceMove { event ->
    val square = GoChessHelpers.rcToSquareNotation(event.row, event.col)
    val action = if (event.isDown) "placed on" else "lifted from"
    println("Piece $action $square")
}

// 4. Query battery and board state
val battery = board.getBattery()
println("Battery: $battery%")

val state = board.getBoardState()
println("Pieces on board: ${state.pieceCount}")
println(state) // pretty-printed 8x8 grid

// 5. Light up e2 and e4 in green
board.setLeds(
    squares = listOf(2 to 5, 4 to 5),
    r = 0, g = 255, b = 0
)

// 6. Turn LEDs off
board.setLedsOff()

// 7. Disconnect
board.disconnect()
```

---

## Running the Example

The included `Example.kt` is a complete Jetpack Compose application that
demonstrates scanning, connecting, querying, LED control, and real-time piece
tracking.

### Setup

1. Create a new Android project in Android Studio with Jetpack Compose enabled.
2. Copy `GoChessSdk.kt` into your SDK package directory.
3. Copy `Example.kt` into your app's main source directory.
4. Add the coroutines and Compose dependencies to `build.gradle.kts`.
5. Declare the BLE permissions in `AndroidManifest.xml` (see above).
6. Set the `MainActivity` in your manifest to
   `com.particula.gochess.example.MainActivity`.
7. Build and run on a physical Android device (BLE is not available in the
   emulator).

### Example features

- Scan for nearby GoChess boards and display them in a list.
- Tap a board to connect; firmware version and battery are read automatically.
- Real-time event log shows piece lift/place events.
- Buttons for board state, border state (XR only), firmware version, and battery.
- LED dialog to set square colours by chess notation.

---

## API Reference

### GoChessBoard

The main SDK class. One instance manages one BLE connection.

```kotlin
class GoChessBoard(context: Context)
```

#### Static Methods

| Method | Description |
|---|---|
| `suspend fun scan(context: Context, timeoutMs: Long = 5000L): List<GoChessDevice>` | Scan for nearby GoChess boards. Returns all boards discovered within the timeout window. |

#### Connection

| Method | Description |
|---|---|
| `suspend fun connect(device: GoChessDevice)` | Connect to a board. Suspends until the BLE connection is established, services are discovered, and TX notifications are enabled. |
| `suspend fun disconnect()` | Disconnect and release all BLE resources. Cancels any pending command futures. |
| `val isConnected: Boolean` | `true` if currently connected to a board. |

#### Callbacks

| Method | Description |
|---|---|
| `fun onPieceMove(callback: (PieceEvent) -> Unit)` | Register a callback for piece lift/place events. Multiple callbacks can be registered. |
| `fun onRawNotification(callback: (ByteArray) -> Unit)` | Register a callback for every raw BLE notification (for debugging). |

#### Queries

| Method | Description |
|---|---|
| `suspend fun getBattery(timeoutMs: Long = 5000L): Int` | Battery percentage (0--100). |
| `suspend fun getBoardState(timeoutMs: Long = 5000L): BoardState` | Full 8x8 occupancy state. |
| `suspend fun getBorderState(timeoutMs: Long = 5000L): BorderState` | 36-position border occupancy (**XR only**). Mini/Lite boards will timeout. |
| `suspend fun getFwVersion(timeoutMs: Long = 5000L): Int` | Firmware version byte (e.g. `0x03` = Mini/Lite, `0x04` = XR). |

#### LED Control

| Method | Description |
|---|---|
| `suspend fun setLeds(squares, r, g, b, overwrite)` | Set LEDs for a list of `(row, col)` pairs to a uniform colour. See [LED Control](#led-control) below. |
| `suspend fun setLedsOff()` | Turn off all LEDs. |
| `suspend fun setLedsSpecial(groups: List<LedGroup>)` | Set multiple colour groups in a single command. Each `LedGroup` has its own list of squares and RGB colour. |
| `suspend fun setLedsByNotation(squareColors: Map<String, Triple<Int,Int,Int>>)` | Convenience method using chess notation strings (e.g. `"e4"`) mapped to `Triple(r, g, b)`. |

---

### GoChessHelpers

Utility object for bitmask encoding and notation conversion.

```kotlin
object GoChessHelpers
```

| Method | Description |
|---|---|
| `fun buildLedMasks(squares: List<Pair<Int, Int>>): Pair<Int, Int>` | Convert `(row, col)` pairs to firmware LED bitmasks. Returns `(mask_rows1to4, mask_rows5to8)`. |
| `fun encodeLedMasksToBytes(mask1: Int, mask2: Int): ByteArray` | Encode bitmasks into the 8 data bytes expected by the firmware. |
| `fun squareNotationToRC(notation: String): Pair<Int, Int>` | Convert chess notation (e.g. `"e4"`) to `(row, col)` where both are 1--8. |
| `fun rcToSquareNotation(row: Int, col: Int): String` | Convert `(row, col)` to chess notation (e.g. `"e4"`). |

---

## Data Types

### GoChessDevice

Represents a discovered board from BLE scanning.

```kotlin
data class GoChessDevice(
    val index: Int,              // Zero-based index in scan results
    val name: String,            // BLE advertised name (e.g. "GoChessXR_A1B2C3")
    val address: String,         // BLE MAC address
    internal val bleDevice: BluetoothDevice
)
```

### PieceEvent

A piece movement event from the Hall-effect sensors.

```kotlin
data class PieceEvent(
    val row: Int,          // 1-8 for board squares, 0 for border events
    val col: Int,          // 1-8 for board squares, 1-10 for border events
    val isDown: Boolean,   // true = piece placed, false = piece lifted
    val isBorder: Boolean, // true if from a border/storage slot
    val borderSide: String // "r", "l", "t", "b", or "" for board squares
)
```

**Board events**: `row` = 1--8 (rank), `col` = 1--8 (file a--h), `isBorder` = `false`.

**Border events** (XR only): `row` = 0, `col` = position 1--10,
`borderSide` = `"r"` (right), `"l"` (left), `"t"` (top), or `"b"` (bottom).

### BoardState

8x8 board occupancy. Constructed from the 8-byte raw response.

```kotlin
class BoardState(raw: ByteArray)
```

| Member | Description |
|---|---|
| `fun isOccupied(row: Int, col: Int): Boolean` | Check if a square has a piece. Row and col are 1-indexed (1--8). |
| `fun toMatrix(): Array<BooleanArray>` | 8x8 boolean matrix (0-indexed). `result[r][c]` = row `r+1`, col `c+1`. |
| `val pieceCount: Int` | Number of occupied squares. |
| `val rawBytes: ByteArray` | The raw 8-byte occupancy data. |
| `fun toString(): String` | Pretty-printed board with file/rank labels. |

### BorderState

36-position border/storage slot occupancy (XR only). Constructed from the 6-byte raw response.

```kotlin
class BorderState(raw: ByteArray)
```

| Member | Description |
|---|---|
| `fun isOccupied(position: String): Boolean` | Check a border position by label (e.g. `"a9"`, `"q0"`, `"i5"`). |
| `val slots: Map<String, Boolean>` | All 36 position labels mapped to occupied state. |
| `val occupiedCount: Int` | Count of occupied border slots. |
| `val rawBytes: ByteArray` | The raw 6-byte border data. |
| `fun toString(): String` | ASCII art representation of the border. |

### LedGroup

A group of squares sharing the same LED colour.

```kotlin
data class LedGroup(
    val squares: List<Pair<Int, Int>>,  // (row, col) pairs, 1-indexed
    val r: Int,                          // Red   0-255
    val g: Int,                          // Green 0-255
    val b: Int                           // Blue  0-255
)
```

---

## LED Control

### Single colour for multiple squares

Use `setLeds` to light a set of squares in the same colour. When `overwrite` is
`true` (the default), all other LEDs are turned off. When `false`, only the
listed squares are changed and others keep their current colour.

```kotlin
// Light e2 and e4 in green, turn everything else off
board.setLeds(
    squares = listOf(2 to 5, 4 to 5),
    r = 0, g = 255, b = 0,
    overwrite = true
)
```

### Multiple colour groups

Use `setLedsSpecial` to set different colours for different squares in a single
command. This first clears all LEDs, then applies each group.

```kotlin
board.setLedsSpecial(listOf(
    LedGroup(
        squares = listOf(4 to 5, 5 to 4),  // d5 and e4
        r = 0, g = 255, b = 0              // green
    ),
    LedGroup(
        squares = listOf(2 to 3),           // c2
        r = 255, g = 0, b = 0              // red
    )
))
```

### Chess notation convenience

Use `setLedsByNotation` to specify squares with standard chess notation and
individual colours per square.

```kotlin
board.setLedsByNotation(mapOf(
    "e2" to Triple(0, 255, 0),   // green
    "e4" to Triple(0, 255, 0),   // green
    "d7" to Triple(255, 0, 0),   // red
    "d5" to Triple(255, 0, 0)    // red
))
```

### Turn off all LEDs

```kotlin
board.setLedsOff()
```

---

## Border State (XR Only)

The GoChess XR (Robotic) board has 36 border/storage slots surrounding the 8x8
playing area. These are only available on the XR model -- Mini and Lite boards
do not have border sensors and will timeout if `getBorderState()` is called.

### Position diagram

```
  q9  a9 b9 c9 d9 e9 f9 g9 h9  i9
  q8  .  .  .  .  .  .  .  .   i8
  q7  .  .  .  .  .  .  .  .   i7
  q6  .  .  .  .  .  .  .  .   i6
  q5  .  .  .  .  .  .  .  .   i5
  q4  .  .  .  .  .  .  .  .   i4
  q3  .  .  .  .  .  .  .  .   i3
  q2  .  .  .  .  .  .  .  .   i2
  q1  .  .  .  .  .  .  .  .   i1
  q0  a0 b0 c0 d0 e0 f0 g0 h0  i0
```

- **Top row (rank 9)**: `a9` through `h9`
- **Bottom row (rank 0)**: `a0` through `h0`
- **Left column (file q)**: `q0` through `q9`
- **Right column (file i)**: `i0` through `i9`
- **Corners**: `q9` (top-left), `i9` (top-right), `q0` (bottom-left), `i0` (bottom-right)

### Usage

```kotlin
val border = board.getBorderState()

if (border.isOccupied("a9")) {
    println("Piece on top border slot a9")
}

println("Occupied border slots: ${border.occupiedCount}")
println(border) // ASCII art rendering
```

### Border piece events

When a piece is placed or removed from a border slot, the `PieceEvent` has
`isBorder = true`, `row = 0`, and `borderSide` set to one of `"r"`, `"l"`,
`"t"`, or `"b"`. The `col` field indicates the 1-indexed position (1--10) along
that side.

---

## Supported Boards

| Board | BLE Advertised Name | FW Version | Border Slots | Notes |
|---|---|---|---|---|
| GoChess XR (Robotic) | `GoChessXR_XXXXXX` | `0x04` | Yes (36 slots) | Full feature set including border state |
| GoChess Mini | `GoChessM_XXXXXX` | `0x03` | No | 8x8 board only |
| GoChess Lite | `GoChessL_XXXXXX` | `0x03` | No | 8x8 board only |

The SDK automatically filters BLE scan results to devices whose name starts with
`"GoChess"`.

---

## Android-Specific Notes

### Runtime permissions (API 23+)

All BLE permissions must be requested and granted at runtime before calling any
SDK method. The SDK itself does **not** request permissions -- this is the
caller's responsibility.

```kotlin
// API 31+ (Android 12):
//   BLUETOOTH_SCAN, BLUETOOTH_CONNECT

// API 23-30:
//   ACCESS_FINE_LOCATION (required for BLE scanning)

// API 21-22:
//   Permissions are granted at install time (declared in manifest only)
```

### API 33+ (Android 13) BLE changes

Android 13 introduced new `writeCharacteristic` and `writeDescriptor` method
signatures. The SDK handles both the legacy and new APIs transparently:

- On API 33+, the SDK uses `gatt.writeCharacteristic(characteristic, data, writeType)`.
- On older APIs, the SDK uses the deprecated `characteristic.value = data` pattern.

No action is required from the caller.

### BLE threading

All BLE GATT callbacks are delivered on a binder thread. The SDK uses
`suspendCancellableCoroutine` and `CompletableDeferred` to bridge BLE callbacks
into coroutines. Write operations are serialized with a `Mutex` to prevent
overlapping BLE writes.

Callers should launch SDK calls from a coroutine scope tied to the appropriate
lifecycle (e.g. `viewModelScope`):

```kotlin
viewModelScope.launch {
    val board = GoChessBoard(applicationContext)
    board.connect(device)
    // ...
}
```

### Context usage

The `GoChessBoard` constructor and `scan` method accept an Android `Context`.
Use `applicationContext` rather than an Activity context to avoid memory leaks:

```kotlin
val board = GoChessBoard(applicationContext)
val devices = GoChessBoard.scan(applicationContext)
```

---

## Protocol Overview

The GoChess board uses an **nRF52832** BLE SoC communicating through the
**Nordic UART Service (NUS)**:

| UUID | Role |
|---|---|
| `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | NUS Service |
| `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | NUS RX (App -> Board, write) |
| `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | NUS TX (Board -> App, notify) |

### Message formats

The board uses two message formats over the NUS TX characteristic:

**1. Raw messages** (no framing):

| Message | Format | Example |
|---|---|---|
| Piece move | 3--4 ASCII bytes | `'8' '1' 'd'` = row 8, col 1, placed |
| Board state | `[0x03][8 bytes]` | One byte per row, bits = columns |
| Border state | `[0x0C][6 bytes]` | 6 bytes encoding 36 border slots |
| FW version | `"Ver" + byte` | `0x56 0x65 0x72 0x04` |

**2. Framed messages**:

```
[START=0x2A][LEN][TYPE][DATA...][CHECKSUM][0x0D][0x0A]
```

- `LEN` = total bytes from START through CHECKSUM (inclusive).
- `CHECKSUM` = sum of all bytes from START through DATA.
- Used for: battery percentage, battery voltage, charging state, current
  measurement.

### Command bytes (App -> Board)

| Byte | Constant | Description |
|---|---|---|
| `0x22` | `CMD_GET_BORDER_STATE` | Request border occupancy |
| `0x32` | `CMD_SET_RGB_LEDS` | Set LEDs with bitmask (12-byte payload) |
| `0x34` | `CMD_LED_ON_SPECIAL` | Set per-square LED colours (variable length) |
| `0x35` | `CMD_GET_BOARD_STATE` | Request 8x8 board occupancy |
| `0x39` | `CMD_CHECK_BATTERY` | Request battery percentage |
| `0x76` | `CMD_GET_FW_VERSION` | Request firmware version |

---

## Troubleshooting

### No boards found during scan

- Ensure the board is powered on and not already connected to another device.
- BLE connections are exclusive -- if the board is connected to a phone or
  tablet, it will not appear in scans from another device.
- Verify Bluetooth is enabled on the Android device.
- Confirm that all required permissions have been granted (check Logcat for
  `MissingPermission` warnings).
- Move the Android device closer to the board. BLE range is typically 1--10
  meters.

### Connection drops or fails

- BLE on Android can be unreliable on some devices. If `connect()` throws an
  exception, wait a few seconds and retry.
- Ensure you are not calling `connect()` from the main thread without a
  coroutine scope (it is a `suspend` function).
- Check Logcat with tag `GoChessSdk` for detailed connection state logs.

### getBorderState() times out

- Border state is only supported on the GoChess XR (Robotic) model. Mini and
  Lite boards do not have border sensors and will not respond to this command.
- Check `GoChessDevice.name` -- XR boards advertise as `GoChessXR_XXXXXX`.

### LEDs not lighting up

- Verify the `(row, col)` values are 1-indexed (1--8), not 0-indexed.
- Check that RGB values are in the 0--255 range.
- If using `setLeds` with `overwrite = false`, previous LED state is preserved.
  Use `overwrite = true` or call `setLedsOff()` first if you see unexpected
  colours.

### Permission errors on API 31+

Starting with Android 12 (API 31), `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` are
separate runtime permissions. If you only request `ACCESS_FINE_LOCATION`, BLE
calls will throw `SecurityException` on API 31+ devices.

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    // Request BLUETOOTH_SCAN and BLUETOOTH_CONNECT
}
```

### Coroutine cancellation

All SDK suspend functions respect coroutine cancellation. If you cancel the
coroutine scope (e.g. when a ViewModel is cleared), in-flight BLE operations
will be cancelled cleanly. Always use structured concurrency to avoid leaked
connections.

---

## File Structure

```
sdk/kotlin/
  GoChessSdk.kt    -- Complete SDK (single file, ~1350 lines)
  Example.kt       -- Jetpack Compose example activity
  README.md        -- This file
```

---

## License

Copyright Particula Ltd. All rights reserved.
