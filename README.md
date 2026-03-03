# GoChess Board SDK

Official SDK for communicating with GoChess smart chess boards via Bluetooth Low Energy (BLE). Available in 5 languages for all major platforms.

All SDKs share the same protocol implementation and provide identical functionality: scanning, connecting, querying board state, receiving real-time piece events, and controlling per-square RGB LEDs.

---

## Supported Boards

| Board | BLE Name Pattern | Border Slots | FW Version |
|-------|-----------------|-------------|------------|
| GoChess XR (Robotic) | `GoChessXR_XXXXXX` | Yes (36 slots) | `0x04` |
| GoChess Mini | `GoChessM_XXXXXX` | No | `0x03` |
| GoChess Lite | `GoChessL_XXXXXX` | No | `0x03` |

---

## Available SDKs

| Language | Platform | BLE Library | Directory | README |
|----------|----------|-------------|-----------|--------|
| Python | Windows, macOS, Linux | [Bleak](https://github.com/hbldh/bleak) | `python/` | [README](python/README.md) |
| TypeScript / JavaScript | Browser (Chrome, Edge) | Web Bluetooth | `javascript/` | [README](javascript/README.md) |
| C# | .NET MAUI, Xamarin, UWP | [Plugin.BLE](https://github.com/dotnet-bluetooth-le/dotnet-bluetooth-le) | `csharp/` | [README](csharp/README.md) |
| Kotlin | Android | `android.bluetooth` | `kotlin/` | [README](kotlin/README.md) |
| Swift | iOS, macOS | CoreBluetooth | `swift/` | [README](swift/README.md) |

---

## Features

All SDKs provide the following capabilities:

- **Scan and connect** to GoChess boards via BLE
- **Real-time piece movement notifications** from Hall effect sensors (board squares and border slots)
- **Query board state** -- full 8x8 occupancy as a bitfield matrix
- **Query border state** -- 36 surrounding storage slots (GoChess XR only)
- **Query battery level**, charging state, and firmware version
- **Control 64 per-square RGB LEDs** -- uniform color mode and multi-color group mode
- **Chess notation helpers** -- convert between algebraic notation (e.g., `"e4"`) and `(row, col)` coordinates

---

## Quick Comparison

The same operation -- connect to a board and read the battery level -- in all five languages:

### Python

```python
import asyncio
from gochess_sdk import GoChessBoard

async def main():
    board = GoChessBoard()
    devices = await GoChessBoard.scan()
    await board.connect(devices[0])
    battery = await board.get_battery()
    print(f"Battery: {battery}%")
    await board.disconnect()

asyncio.run(main())
```

### TypeScript (Browser)

```typescript
import { GoChessBoard } from "./gochess_sdk";

const board = new GoChessBoard();
await board.connect();                   // opens browser device picker
const battery = await board.getBattery();
console.log(`Battery: ${battery}%`);
await board.disconnect();
```

### C# (.NET)

```csharp
using GoChess.Sdk;

var board = new GoChessBoard();
var devices = await GoChessBoard.ScanAsync();
await board.ConnectAsync(devices[0]);
int battery = await board.GetBatteryAsync();
Console.WriteLine($"Battery: {battery}%");
await board.DisconnectAsync();
```

### Kotlin (Android)

```kotlin
import com.particula.gochess.sdk.*

val board = GoChessBoard(context)
val devices = GoChessBoard.scan(context)
board.connect(devices[0])
val battery = board.getBattery()
println("Battery: $battery%")
board.disconnect()
```

### Swift (iOS / macOS)

```swift
import CoreBluetooth

let board = GoChessBoard()
let devices = try await board.scan()
try await board.connect(devices[0])
let battery = try await board.getBattery()
print("Battery: \(battery)%")
await board.disconnect()
```

---

## Architecture

All five SDKs follow the same architecture and protocol implementation:

**Transport layer.** Communication uses the Nordic UART Service (NUS) over BLE. The app writes commands to the NUS RX characteristic and receives responses and events via notifications on the NUS TX characteristic.

**Two message formats.** The board sends two types of messages:

1. **Raw messages** -- no framing. Used for piece movement events (3-4 ASCII bytes), board state (`[0x03][8 bytes]`), border state (`[0x0C][6 bytes]`), and firmware version (`"Ver" + byte`).

2. **Framed messages** -- `[0x2A][LEN][TYPE][DATA...][CHECKSUM][CR][LF]`. Used for battery percentage, battery voltage, charging state, current measurement, and chamber state.

**Async command-response pattern.** Each query command (battery, board state, etc.) registers a pending future/promise, writes the command byte to the board, and awaits the matching response from the notification handler.

**Callback-based real-time events.** Piece lift and place events are dispatched immediately to registered callbacks as they arrive from the board's Hall effect sensors. Each event carries the square coordinates, direction (up/down), and whether it is a board square or border slot.

---

## Board Layout

The GoChess XR (Robotic) board includes 36 border/storage slots surrounding the 8x8 playing area. Mini and Lite boards have only the 8x8 area.

```
     a9  b9  c9  d9  e9  f9  g9  h9
q9 +---+---+---+---+---+---+---+---+ i9
q8 |   |   |   |   |   |   |   |   | i8
q7 | 8 |   |   |   |   |   |   |   | i7
q6 | 7 |   |   |   |   |   |   |   | i6
q5 | 6 |   |   |   |   |   |   |   | i5
q4 | 5 |   |   8x8 Board            | i4
q3 | 4 |   |   |   |   |   |   |   | i3
q2 | 3 |   |   |   |   |   |   |   | i2
q1 | 2 |   |   |   |   |   |   |   | i1
q0 | 1 |   |   |   |   |   |   |   | i0
   +---+---+---+---+---+---+---+---+
     a0  b0  c0  d0  e0  f0  g0  h0
```

**Coordinate system:**
- Board squares use standard chess notation: files `a`-`h` (columns 1-8), ranks `1`-`8` (rows 1-8).
- Border slots use extended labels: top row `a9`-`h9`, bottom row `a0`-`h0`, left column `q0`-`q9`, right column `i0`-`i9`.
- Row 1 is White's back rank. Column 1 (file `a`) is on the left from White's perspective.

---

## Directory Structure

```
sdk/
├── README.md                 ← this file
├── python/
│   ├── README.md
│   ├── gochess_sdk.py
│   └── example.py
├── javascript/
│   ├── README.md
│   ├── gochess_sdk.ts
│   └── example.html
├── csharp/
│   ├── README.md
│   ├── GoChessSdk.cs
│   ├── Program.cs
│   └── GoChessExample.csproj
├── kotlin/
│   ├── README.md
│   ├── GoChessSdk.kt
│   └── Example.kt
└── swift/
    ├── README.md
    ├── GoChessSdk.swift
    └── Example.swift
```

---

## Protocol Overview

| Property | Value |
|----------|-------|
| BLE Service | Nordic UART Service (NUS) |
| Service UUID | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| RX Characteristic (App to Board) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| TX Characteristic (Board to App) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |
| Write Type | Write Without Response |
| Notification | Enabled on TX characteristic via CCCD |

### Command Bytes (App to Board)

| Command | Byte | Description |
|---------|------|-------------|
| Get Battery | `0x39` | Returns battery percentage (0-100) |
| Get Battery mV | `0x3A` | Returns battery voltage in millivolts |
| Get Board State | `0x35` | Returns 8-byte occupancy bitfield |
| Get Border State | `0x22` | Returns 6-byte border occupancy (XR only) |
| Get FW Version | `0x76` | Returns firmware version byte |
| Get Charging State | `0x3D` | Returns charging flag (0/1) |
| Set RGB LEDs | `0x32` | Uniform color with bitmask (13 bytes) |
| Set LEDs Special | `0x34` | Per-square color groups (variable length) |

### Message Formats

**Raw messages** (no framing) -- the first byte identifies the message type:

```
Piece move:    [row_ascii][col_ascii]['d'|'u']       e.g. 0x38 0x31 0x64 = "81d"
Board state:   [0x03][row1][row2]...[row8]           8 bytes, 1 bit per square
Border state:  [0x0C][top][bot][left][leftX][right][rightX]   6 bytes
FW version:    [0x56][0x65][0x72][version]           "Ver" + version byte
```

**Framed messages** -- wrapped with start byte, length, checksum, and CRLF:

```
[0x2A][LEN][TYPE][DATA...][CHECKSUM][0x0D][0x0A]

LEN      = total bytes from 0x2A through CHECKSUM (inclusive)
CHECKSUM = sum of all bytes from 0x2A through last DATA byte
```

---

## License

Copyright Particula Ltd. All rights reserved.

License terms to be determined. Contact Particula for licensing inquiries.
