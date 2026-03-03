# GoChess Swift SDK

[![Platform](https://img.shields.io/badge/platform-iOS%2015%2B%20%7C%20macOS%2012%2B-blue.svg)](https://developer.apple.com/swift/)
[![Swift](https://img.shields.io/badge/Swift-5.5%2B-orange.svg)](https://swift.org)
[![Dependencies](https://img.shields.io/badge/dependencies-none-brightgreen.svg)](#)
[![License](https://img.shields.io/badge/license-Proprietary-lightgrey.svg)](#)

A single-file BLE SDK for communicating with GoChess smart chess boards from iOS and macOS applications using CoreBluetooth. The SDK provides a modern async/await API for scanning, connecting, receiving real-time piece movements, querying board state, and controlling per-square RGB LEDs.

---

## Features

- **Scan and connect** to GoChess boards over BLE (Nordic UART Service)
- **Real-time piece tracking** via Hall-effect sensor notifications (board squares and border slots)
- **Board state queries** -- battery level, 8x8 occupancy, firmware version, charging state, current draw
- **Border/storage slot state** -- 36-position occupancy for GoChess XR (Robotic) models
- **Per-square RGB LED control** -- uniform color, multi-color groups, and chess-notation convenience methods
- **Zero dependencies** -- uses only CoreBluetooth from the Apple SDK
- **Single file** -- drop `GoChessSdk.swift` into any Xcode project
- **Swift concurrency** -- fully async/await with structured error handling

---

## Requirements

| Requirement | Minimum |
|---|---|
| Xcode | 14.0+ |
| iOS | 15.0+ |
| macOS | 12.0+ |
| Swift | 5.5+ |
| Device | Physical device with Bluetooth (the iOS Simulator does not support BLE) |

---

## Installation

The SDK is distributed as a single Swift source file with no package manager configuration required.

### 1. Add the SDK file

Copy `GoChessSdk.swift` into your Xcode project. When prompted, check the target(s) you want to include it in.

Alternatively, drag the file into the Xcode Project Navigator.

### 2. Configure Info.plist

Add the Bluetooth usage description to your `Info.plist`:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to connect to your GoChess board.</string>
```

If you are using an `Info.plist` generator (Xcode 15+), you can add this entry under **Privacy - Bluetooth Always Usage Description** in the target's Info tab.

### 3. (macOS only) Enable Bluetooth entitlement

For macOS targets, ensure the **App Sandbox > Bluetooth** entitlement is enabled in your `.entitlements` file or under **Signing & Capabilities**.

---

## Quick Start

```swift
import Foundation

let board = GoChessBoard()

// Scan for nearby boards (5-second window)
let devices = try await board.scan(timeout: 5.0)
guard let device = devices.first else {
    print("No boards found")
    return
}

// Connect
try await board.connect(device)

// Register a callback for piece movements
board.onPieceMove { event in
    if event.isBorder {
        print("Border \(event.borderSide)\(event.col): \(event.isDown ? "down" : "up")")
    } else {
        let square = GoChessHelpers.rcToSquareNotation(row: event.row, col: event.col)
        print("\(square): \(event.isDown ? "placed" : "lifted")")
    }
}

// Query battery
let battery = try await board.getBattery()
print("Battery: \(battery)%")

// Light up e2 and e4 in green
try board.setLeds([(2, 5), (4, 5)], r: 0, g: 255, b: 0)

// Disconnect when done
await board.disconnect()
```

---

## Running the Example

The repository includes `Example.swift`, a complete SwiftUI application that demonstrates scanning, connecting, querying, LED control, and real-time event logging.

### Setup

1. Create a new Xcode project (App template, SwiftUI interface).
2. Remove the generated `ContentView.swift` and app entry point file.
3. Add both `GoChessSdk.swift` and `Example.swift` to the project.
4. Add `NSBluetoothAlwaysUsageDescription` to Info.plist (see [Installation](#2-configure-infoplist)).
5. Select a physical device as the run destination (BLE is not available in the Simulator).
6. Build and run.

The example app provides a scan screen, a connected board dashboard with command buttons, interactive LED controls, and a scrolling event log.

---

## API Reference

### GoChessBoard

The main SDK class. Inherits from `NSObject` and conforms to `CBCentralManagerDelegate` and `CBPeripheralDelegate`.

#### Connection

```swift
// Create an instance
let board = GoChessBoard()

// Scan for boards (default 5-second window)
func scan(timeout: TimeInterval = 5.0) async throws -> [GoChessDevice]

// Connect to a discovered board
func connect(_ device: GoChessDevice) async throws

// Disconnect
func disconnect() async

// Connection state (read-only)
var isConnected: Bool { get }
```

#### Callbacks

```swift
// Receive piece movement events (board squares and border slots)
func onPieceMove(_ callback: @escaping (PieceEvent) -> Void)

// Receive raw BLE notification data (for debugging)
func onRawNotification(_ callback: @escaping (Data) -> Void)
```

Multiple callbacks can be registered. They are invoked in the order they were added.

#### Query Commands

All query commands are async and accept an optional `timeout` parameter (default: 5.0 seconds). They throw `GoChessError.timeout` if the board does not respond within the specified duration.

```swift
// Battery percentage (0-100)
func getBattery(timeout: TimeInterval = 5.0) async throws -> Int

// Battery voltage in millivolts
func getBatteryMv(timeout: TimeInterval = 5.0) async throws -> Int

// Full 8x8 board occupancy
func getBoardState(timeout: TimeInterval = 5.0) async throws -> BoardState

// Border/storage slot occupancy (GoChess XR only)
func getBorderState(timeout: TimeInterval = 5.0) async throws -> BorderState

// Firmware version byte
func getFwVersion(timeout: TimeInterval = 5.0) async throws -> Int

// Charging state
func getChargingState(timeout: TimeInterval = 5.0) async throws -> Bool

// Current draw in microamps
func measureCurrent(timeout: TimeInterval = 5.0) async throws -> Int
```

#### LED Control

```swift
// Set LEDs with a uniform color (command 0x32)
func setLeds(
    _ squares: [(Int, Int)],    // (row, col) pairs, 1-indexed
    r: UInt8 = 0,               // red 0-255
    g: UInt8 = 0,               // green 0-255
    b: UInt8 = 0,               // blue 0-255
    overwrite: Bool = true      // true = turn off unlisted squares
) throws

// Turn off all LEDs
func setLedsOff() throws

// Set LEDs with multiple color groups (command 0x34)
func setLedsSpecial(_ groups: [LedGroup]) throws

// Set LEDs using chess notation with per-square colors
func setLedsByNotation(_ squareColors: [String: (UInt8, UInt8, UInt8)]) throws
```

---

## Error Handling

All errors thrown by the SDK are instances of `GoChessError`, which conforms to both `Error` and `LocalizedError`.

```swift
enum GoChessError: Error, LocalizedError {
    case notConnected                   // No board is connected
    case timeout(String)                // Command timed out waiting for response
    case bluetoothOff                   // Bluetooth is powered off
    case bluetoothUnauthorized          // App not authorized for Bluetooth
    case deviceNotFound                 // No GoChess device found during scan
    case serviceNotFound                // NUS service not found on peripheral
    case characteristicNotFound         // Required NUS characteristic not found
    case invalidSquare(Int, Int)        // Square coordinate out of 1-8 range
    case invalidNotation(String)        // Invalid chess notation string
    case invalidData(String)            // Unexpected data format
}
```

### Example

```swift
do {
    let battery = try await board.getBattery()
    print("Battery: \(battery)%")
} catch let error as GoChessError {
    switch error {
    case .notConnected:
        print("Board is not connected")
    case .timeout(let key):
        print("Timed out waiting for: \(key)")
    default:
        print("Error: \(error.localizedDescription)")
    }
} catch {
    print("Unexpected error: \(error)")
}
```

---

## Data Types

### GoChessDevice

Represents a discovered GoChess board from BLE scanning.

```swift
struct GoChessDevice {
    let index: Int          // Index in scan results
    let name: String        // Advertised name (e.g. "GoChessXR_ABC123")
    let identifier: UUID    // CoreBluetooth peripheral UUID
}
```

### PieceEvent

A piece movement event from the Hall-effect sensors.

```swift
struct PieceEvent {
    let row: Int            // Row 1-8 (board) or 0 (border)
    let col: Int            // Column 1-8 (board) or position 1-10 (border)
    let isDown: Bool        // true = piece placed, false = piece lifted
    let isBorder: Bool      // true = border/storage slot event
    let borderSide: String  // "r", "l", "t", "b" for border; "" for board
}
```

### BoardState

Represents the 8x8 board occupancy. Conforms to `CustomStringConvertible` for pretty-printing.

```swift
class BoardState: CustomStringConvertible {
    let rawBytes: Data                                  // 8 raw bytes

    init(raw: Data) throws                              // Initialize from 8 bytes
    func isOccupied(row: Int, col: Int) throws -> Bool  // Check a square (1-8)
    func toMatrix() -> [[Bool]]                         // 8x8 Bool matrix (0-indexed)
    var pieceCount: Int                                  // Count of occupied squares
    var description: String                              // Pretty-printed board
}
```

**Coordinate system:** Row 1 = White's back rank, Row 8 = Black's back rank. Column 1 = a-file, Column 8 = h-file.

```swift
let state = try await board.getBoardState()
print("Pieces on board: \(state.pieceCount)")

if try state.isOccupied(row: 1, col: 5) {  // e1
    print("King is home")
}

print(state)
// Output:
//   a b c d e f g h
// 8 . . . . . . . .
// 7 . . . . . . . .
// ...
// 1 # # # # # # # #
```

### BorderState

Represents the 36 border/storage slot positions surrounding the board on GoChess XR models. Conforms to `CustomStringConvertible`.

```swift
class BorderState: CustomStringConvertible {
    let rawBytes: Data                          // 6 raw bytes
    private(set) var slots: [String: Bool]      // All 36 positions

    init(raw: Data) throws                      // Initialize from 6 bytes
    func isOccupied(_ position: String) -> Bool // Check by label (e.g. "a9")
    var occupiedCount: Int                      // Count of occupied slots
    var description: String                     // Pretty-printed border
}
```

### LedGroup

A group of squares that share a single LED color, used with `setLedsSpecial`.

```swift
struct LedGroup {
    let squares: [(row: Int, col: Int)]  // 1-indexed (1-8)
    let r: UInt8                         // Red 0-255
    let g: UInt8                         // Green 0-255
    let b: UInt8                         // Blue 0-255
}
```

---

## LED Control

### Uniform Color (setLeds)

Light up one or more squares with the same color. The `overwrite` parameter controls whether squares not in the list are turned off.

```swift
// Light e2 and e4 in red
try board.setLeds([(2, 5), (4, 5)], r: 255, g: 0, b: 0)

// Add d4 in blue without turning off existing LEDs
try board.setLeds([(4, 4)], r: 0, g: 0, b: 255, overwrite: false)

// Turn off all LEDs
try board.setLedsOff()
```

### Multi-Color Groups (setLedsSpecial)

Set different colors for different groups of squares in a single command. All existing LEDs are turned off first.

```swift
let groups = [
    LedGroup(squares: [(2, 5), (4, 5)], r: 0, g: 255, b: 0),    // e2, e4 in green
    LedGroup(squares: [(1, 4), (1, 5)], r: 255, g: 0, b: 0),    // d1, e1 in red
    LedGroup(squares: [(7, 4), (7, 5)], r: 0, g: 0, b: 255),    // d7, e7 in blue
]
try board.setLedsSpecial(groups)
```

### Chess Notation (setLedsByNotation)

A convenience method that accepts chess notation strings and per-square colors. Squares sharing the same color are grouped automatically.

```swift
try board.setLedsByNotation([
    "e2": (0, 255, 0),     // green
    "e4": (0, 255, 0),     // green
    "d1": (255, 0, 0),     // red
    "e1": (255, 0, 0),     // red
    "a7": (0, 0, 255),     // blue
])
```

### Coordinate System

Squares use 1-indexed (row, col) tuples matching chess convention:

```
        col 1   col 2   col 3   col 4   col 5   col 6   col 7   col 8
         (a)     (b)     (c)     (d)     (e)     (f)     (g)     (h)

row 8    a8      b8      c8      d8      e8      f8      g8      h8
row 7    a7      b7      c7      d7      e7      f7      g7      h7
row 6    a6      b6      c6      d6      e6      f6      g6      h6
row 5    a5      b5      c5      d5      e5      f5      g5      h5
row 4    a4      b4      c4      d4      e4      f4      g4      h4
row 3    a3      b3      c3      d3      e3      f3      g3      h3
row 2    a2      b2      c2      d2      e2      f2      g2      h2
row 1    a1      b1      c1      d1      e1      f1      g1      h1
```

Use `GoChessHelpers.squareNotationToRC(_:)` and `GoChessHelpers.rcToSquareNotation(row:col:)` to convert between notation and coordinates.

---

## Border State (GoChess XR Only)

The GoChess XR (Robotic) model has 36 storage slots surrounding the 8x8 board. These are used for captured pieces and the robotic arm's piece storage.

### Position Diagram

```
          a   b   c   d   e   f   g   h
    q9  [ a9  b9  c9  d9  e9  f9  g9  h9 ]  i9      <- Top border
    q8  [                                  ]  i8
    q7  [                                  ]  i7
    q6  [                                  ]  i6
    q5  [         8 x 8  Board            ]  i5      <- Left / Right borders
    q4  [                                  ]  i4
    q3  [                                  ]  i3
    q2  [                                  ]  i2
    q1  [                                  ]  i1
    q0  [ a0  b0  c0  d0  e0  f0  g0  h0 ]  i0      <- Bottom border
```

- **Top border (rank 9):** a9, b9, c9, d9, e9, f9, g9, h9
- **Bottom border (rank 0):** a0, b0, c0, d0, e0, f0, g0, h0
- **Left column (file q):** q0, q1, q2, q3, q4, q5, q6, q7, q8, q9
- **Right column (file i):** i0, i1, i2, i3, i4, i5, i6, i7, i8, i9

### Usage

```swift
let border = try await board.getBorderState()
print("Occupied slots: \(border.occupiedCount)")

if border.isOccupied("a9") {
    print("Piece on top-left border slot")
}

// Pretty-print the border layout
print(border)
```

> **Note:** `getBorderState()` is only supported on GoChess XR (Robotic) boards. Calling it on Mini or Lite models will result in a timeout error, since those boards do not have border sensors.

---

## Supported Boards

| Board | Advertised Name | FW Version | Border Slots | Robotic Arm |
|---|---|---|---|---|
| GoChess XR (Robotic) | `GoChessXR_XXXXXX` | 0x04 | Yes (36 positions) | Yes |
| GoChess Mini | `GoChessM_XXXXXX` | 0x03 | No | No |
| GoChess Lite | `GoChessL_XXXXXX` | 0x03 | No | No |

All boards support 8x8 piece detection, battery queries, LED control, and firmware version queries. Border state and border piece events are exclusive to the GoChess XR.

---

## Platform Notes

### iOS

- **Physical device required.** The iOS Simulator does not support CoreBluetooth. You must run on a real iPhone or iPad.
- **Background BLE.** To receive BLE notifications while the app is in the background, add `bluetooth-central` to the `UIBackgroundModes` array in Info.plist:
  ```xml
  <key>UIBackgroundModes</key>
  <array>
      <string>bluetooth-central</string>
  </array>
  ```
- **Permissions.** iOS will prompt the user for Bluetooth access on first use. The `NSBluetoothAlwaysUsageDescription` string is displayed in the permission dialog.

### macOS

- **App Sandbox.** If your macOS app uses the App Sandbox, enable the Bluetooth entitlement in **Signing & Capabilities**.
- **No Simulator support.** As with iOS, BLE requires a physical Mac with Bluetooth hardware (all modern Macs qualify).

### Swift Concurrency

The SDK uses `async/await` and `CheckedContinuation` for bridging CoreBluetooth delegate callbacks to structured concurrency. This requires Swift 5.5+ and a deployment target of iOS 15.0+ / macOS 12.0+.

Callbacks registered via `onPieceMove(_:)` and `onRawNotification(_:)` are invoked on the CoreBluetooth delegate queue. If you need to update UI, dispatch to the main actor:

```swift
board.onPieceMove { event in
    Task { @MainActor in
        self.handlePieceEvent(event)
    }
}
```

---

## Protocol Overview

The GoChess board uses an nRF52832 BLE SoC and communicates over the Nordic UART Service (NUS):

| UUID | Direction | Purpose |
|---|---|---|
| `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | -- | NUS Service |
| `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | App -> Board | RX Characteristic (write) |
| `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | Board -> App | TX Characteristic (notify) |

### Message Formats

**Raw messages** (no framing):

| Type | Format | Example |
|---|---|---|
| Piece move (board) | 3 ASCII bytes: `[row][col][d\|u]` | `"25d"` = row 2, col 5, placed |
| Piece move (border) | 3-4 ASCII bytes: `[side][pos][d\|u]` | `"r3u"` = right side, pos 3, lifted |
| Board state | `[0x03][8 bytes]` | 8 bytes of row bitmasks |
| Border state | `[0x0C][6 bytes]` | 6 bytes of border bitmasks |
| FW version | `"Ver" + version_byte` | `"Ver\x04"` = version 4 |

**Framed messages:**

```
[START=0x2A][LEN][TYPE][DATA...][CHECKSUM][CR][LF]
```

| Field | Description |
|---|---|
| START | `0x2A` (`*`) |
| LEN | Total bytes from START through CHECKSUM (inclusive) |
| TYPE | Response type (0x01 = battery, 0x02 = battery mV, 0x04 = current, 0x07 = charging) |
| DATA | Payload bytes (variable length) |
| CHECKSUM | Sum of all bytes from START through DATA |
| CR LF | `0x0D 0x0A` |

---

## Helpers

The `GoChessHelpers` enum provides static utility functions for coordinate conversion and LED mask encoding.

```swift
// Convert chess notation to (row, col)
let (row, col) = try GoChessHelpers.squareNotationToRC("e4")  // (4, 5)

// Convert (row, col) to chess notation
let notation = GoChessHelpers.rcToSquareNotation(row: 4, col: 5)  // "e4"

// Build LED bitmasks from square coordinates
let (mask1, mask2) = try GoChessHelpers.buildLedMasks([(2, 5), (4, 5)])

// Encode masks to bytes for the 0x32 command
let bytes = GoChessHelpers.encodeLedMasksToBytes(mask1, mask2)
```

---

## Troubleshooting

### "No boards found" during scan

- Ensure the GoChess board is powered on (LED indicator should be visible).
- Bring the board within 1-2 meters of the device during initial pairing.
- Check that Bluetooth is enabled in device Settings.
- On iOS, verify the app has been granted Bluetooth permission in **Settings > Privacy > Bluetooth**.
- Try increasing the scan timeout: `board.scan(timeout: 10.0)`.

### Bluetooth permission dialog does not appear

- Ensure `NSBluetoothAlwaysUsageDescription` is present in Info.plist.
- Clean the build folder (Xcode > Product > Clean Build Folder) and reinstall the app.
- On macOS, verify the Bluetooth sandbox entitlement is enabled.

### Commands time out after connecting

- The board may have entered sleep mode. Power-cycle the board and reconnect.
- Ensure only one app is connected to the board at a time (BLE is a single-connection protocol for NUS).
- Try increasing the timeout parameter: `board.getBattery(timeout: 10.0)`.

### "Border state request failed" on Mini/Lite

- Border state is only available on the GoChess XR (Robotic) model.
- Mini and Lite boards do not have border sensors and will not respond to the `getBorderState()` command, causing a timeout.

### LEDs not lighting up

- Verify the square coordinates are in the 1-8 range for both row and column.
- Check that the color values are non-zero (r, g, b are all `UInt8` 0-255).
- If using `overwrite: false`, previously set LEDs remain active; use `overwrite: true` or call `setLedsOff()` to clear.

### Build errors with async/await

- Ensure the deployment target is iOS 15.0+ or macOS 12.0+.
- Verify you are using Xcode 14.0 or later with Swift 5.5+ support.

### App crashes on Simulator

- CoreBluetooth is not supported in the iOS Simulator. Always test on a physical device.
