# GoChess C# SDK

[![.NET](https://img.shields.io/badge/.NET-8.0-512BD4?logo=dotnet)](https://dotnet.microsoft.com/)
[![BLE](https://img.shields.io/badge/BLE-WinRT-0078D4?logo=windows)](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth)
[![Platform](https://img.shields.io/badge/Platform-Windows%2010%2B-green)]()
[![License](https://img.shields.io/badge/License-Proprietary-lightgrey)]()

A single-file C# SDK for communicating with **GoChess smart chess boards** via Bluetooth Low Energy (BLE). The SDK uses native Windows Runtime (WinRT) Bluetooth APIs — **no external NuGet packages required**. It provides a high-level async API for scanning, connecting, receiving real-time piece movements, querying board state, and controlling per-square RGB LEDs.

---

## Table of Contents

- [Features](#features)
- [Supported Boards](#supported-boards)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Running the Example](#running-the-example)
- [API Reference](#api-reference)
  - [GoChessBoard](#gochessboard)
  - [GoChessDevice](#gochessdevice)
  - [PieceEvent](#pieceevent)
  - [BoardState](#boardstate)
  - [BorderState](#borderstate)
  - [LedGroup](#ledgroup)
  - [GoChessHelpers](#gochesshelpers)
  - [GoChessConstants](#gochessconstants)
- [Data Types](#data-types)
- [LED Control](#led-control)
  - [Uniform Colour](#uniform-colour)
  - [Multi-Colour Groups](#multi-colour-groups)
  - [Chess Notation Shorthand](#chess-notation-shorthand)
  - [Turn Off All LEDs](#turn-off-all-leds)
- [Border State (XR Only)](#border-state-xr-only)
- [Platform Notes](#platform-notes)
- [Protocol Overview](#protocol-overview)
- [Troubleshooting](#troubleshooting)

---

## Features

- **BLE Scanning** -- Discover nearby GoChess boards with configurable timeout and cancellation support.
- **Async Connection** -- Connect to any discovered board using the Nordic UART Service (NUS).
- **Real-Time Piece Tracking** -- Receive callbacks when pieces are lifted or placed, including border/storage slots on the XR model.
- **Board State Query** -- Read the full 8x8 occupancy matrix from Hall-effect sensors.
- **Border State Query** -- Read the 36 border/storage slot positions (GoChess XR only).
- **Battery and Diagnostics** -- Query battery percentage, voltage (mV), charging state, current draw, and firmware version.
- **RGB LED Control** -- Set per-square LED colours with uniform colour, multi-colour groups, or chess notation addressing.
- **IAsyncDisposable** -- Proper resource cleanup with `await using` pattern support.
- **Zero Dependencies** -- Uses native Windows Runtime (WinRT) Bluetooth APIs. No NuGet packages needed.
- **Single File** -- The entire SDK is contained in `GoChessSdk.cs` with no code generation or build tooling required.

---

## Supported Boards

| Board | BLE Advertised Name | Border Slots | FW Version Byte |
|---|---|---|---|
| GoChess XR (Robotic) | `GoChessXR_XXXXXX` | Yes (36 positions) | `0x04` |
| GoChess Mini | `GoChessM_XXXXXX` | No | `0x03` |
| GoChess Lite | `GoChessL_XXXXXX` | No | `0x03` |

`XXXXXX` is a unique device identifier derived from the BLE MAC address.

---

## Installation

### 1. Add the SDK file

Copy `GoChessSdk.cs` into your project. The file is self-contained and provides the `GoChess.Sdk` namespace with all types needed to interact with the board.

### 2. Project configuration

The SDK uses Windows Runtime (WinRT) Bluetooth APIs, which require targeting a Windows 10 TFM. A minimal `.csproj` looks like:

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net8.0-windows10.0.19041.0</TargetFramework>
    <Nullable>enable</Nullable>
  </PropertyGroup>
</Project>
```

No NuGet packages are required — the WinRT Bluetooth APIs are included with the Windows SDK projection.

### Requirements

- **.NET 8.0** or later
- **Windows 10** version 2004 (build 19041) or later
- A Bluetooth Low Energy (BLE) adapter
- Bluetooth must be enabled in Windows Settings

---

## Quick Start

```csharp
using GoChess.Sdk;

// Create board instance
await using var board = new GoChessBoard();

// Scan for nearby boards (5-second default timeout)
var devices = await GoChessBoard.ScanAsync(TimeSpan.FromSeconds(5));
if (devices.Count == 0)
{
    Console.WriteLine("No boards found.");
    return;
}

// Connect to the first discovered board
await board.ConnectAsync(devices[0]);

// Register a callback for piece movements
board.OnPieceMove(evt =>
{
    string action = evt.IsDown ? "placed on" : "lifted from";
    string square = GoChessHelpers.RCToSquareNotation(evt.Row, evt.Col);
    Console.WriteLine($"Piece {action} {square}");
});

// Query board information
int battery = await board.GetBatteryAsync();
Console.WriteLine($"Battery: {battery}%");

BoardState state = await board.GetBoardStateAsync();
Console.WriteLine($"Pieces on board: {state.PieceCount}");
Console.WriteLine(state);

// Light up e2 and e4 in green
await board.SetLedsAsync(
    new List<(int Row, int Col)> { (2, 5), (4, 5) },
    r: 0, g: 255, b: 0);

// Wait for piece events...
await Task.Delay(TimeSpan.FromMinutes(5));

// Cleanup happens automatically via DisposeAsync
```

---

## Running the Example

The repository includes an interactive console demo (`Program.cs`) that showcases every SDK feature.

```bash
cd sdk/csharp
dotnet restore
dotnet run
```

The example will:

1. Scan for boards and list all discovered devices.
2. Prompt you to select a board by index.
3. Connect and display firmware version, battery level, and board type.
4. Enter an interactive menu for sending commands (battery, board state, border state, firmware version, LED control).
5. Print piece-movement events in real time as they occur.

---

## API Reference

All types are in the `GoChess.Sdk` namespace.

### GoChessBoard

The main SDK class. Implements `IAsyncDisposable` for automatic cleanup.

```csharp
public class GoChessBoard : IAsyncDisposable
```

#### Constructor

```csharp
GoChessBoard()
```

Creates a new board instance. WinRT BLE APIs are accessed directly — no adapter initialisation needed.

#### Scanning

```csharp
static async Task<List<GoChessDevice>> ScanAsync(
    TimeSpan? timeout = null,
    CancellationToken ct = default)
```

Scan for nearby GoChess boards. Returns devices sorted by discovery order. Only devices whose BLE advertised name starts with `"GoChess"` are included.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `timeout` | `TimeSpan?` | 5 seconds | How long to scan |
| `ct` | `CancellationToken` | `default` | Cancellation token |

**Returns:** `List<GoChessDevice>` -- all discovered boards.

#### Connection

```csharp
async Task ConnectAsync(GoChessDevice device, CancellationToken ct = default)
```

Connect to a board. Discovers the Nordic UART Service, locates RX/TX characteristics, and subscribes to notifications.

**Throws:** `InvalidOperationException` if the NUS service or required characteristics are not found.

```csharp
async Task DisconnectAsync()
```

Disconnect from the board. Unsubscribes from notifications, cancels pending command futures, and releases resources.

```csharp
bool IsConnected { get; }
```

Returns `true` if currently connected to a board.

```csharp
async ValueTask DisposeAsync()
```

Calls `DisconnectAsync()`. Enables `await using` pattern.

#### Callbacks

```csharp
void OnPieceMove(Action<PieceEvent> callback)
```

Register a callback invoked whenever a piece is lifted or placed. Multiple callbacks can be registered. Exceptions in callbacks are silently caught to protect the BLE notification loop.

```csharp
void OnRawNotification(Action<byte[]> callback)
```

Register a callback for every raw BLE notification from the TX characteristic. Useful for debugging or implementing custom protocol handling.

#### Query Commands

All query methods use a request/response pattern over BLE. They send a command byte and await the matching response with a configurable timeout.

```csharp
async Task<int> GetBatteryAsync(int timeoutMs = 5000)
```

Returns battery level as a percentage (0--100).

```csharp
async Task<int> GetBatteryMvAsync(int timeoutMs = 5000)
```

Returns battery voltage in millivolts.

```csharp
async Task<BoardState> GetBoardStateAsync(int timeoutMs = 5000)
```

Returns the full 8x8 board occupancy as a `BoardState` object.

```csharp
async Task<BorderState> GetBorderStateAsync(int timeoutMs = 5000)
```

Returns border/storage slot occupancy as a `BorderState` object. **GoChess XR (Robotic) only** -- Mini and Lite boards will timeout.

```csharp
async Task<int> GetFwVersionAsync(int timeoutMs = 5000)
```

Returns the firmware version byte (e.g., `0x04` for XR, `0x03` for Mini/Lite).

```csharp
async Task<int> GetCurrentAsync(int timeoutMs = 5000)
```

Returns current draw in microamps (uA).

```csharp
async Task<bool> GetChargingStateAsync(int timeoutMs = 5000)
```

Returns `true` if the board is currently charging.

All query methods throw `TimeoutException` if the board does not respond within `timeoutMs`.

#### LED Commands

```csharp
async Task SetLedsAsync(
    List<(int Row, int Col)> squares,
    byte r = 0, byte g = 0, byte b = 0,
    bool overwrite = true)
```

Set LEDs for given squares to a uniform colour. Row and column are 1-indexed (1--8).

| Parameter | Type | Default | Description |
|---|---|---|---|
| `squares` | `List<(int, int)>` | -- | Squares to light up (1-indexed) |
| `r` | `byte` | `0` | Red component (0--255) |
| `g` | `byte` | `0` | Green component (0--255) |
| `b` | `byte` | `0` | Blue component (0--255) |
| `overwrite` | `bool` | `true` | If `true`, unspecified squares are turned off. If `false`, only listed squares change. |

```csharp
async Task SetLedsOffAsync()
```

Turn off all board LEDs.

```csharp
async Task SetLedsSpecialAsync(List<LedGroup> groups)
```

Set per-square LED colours with multiple colour groups. Clears all LEDs first, then applies each group.

```csharp
async Task SetLedsByNotationAsync(
    Dictionary<string, (byte R, byte G, byte B)> squareColors)
```

Convenience method: set LEDs using chess notation (e.g., `"e2"`, `"d4"`). Squares with the same colour are automatically grouped.

---

### GoChessDevice

Represents a discovered GoChess board from a BLE scan.

```csharp
public class GoChessDevice
```

| Property | Type | Description |
|---|---|---|
| `Index` | `int` | Discovery index (0-based) |
| `Name` | `string` | BLE advertised name (e.g., `"GoChessXR_A1B2C3"`) |
| `Address` | `string` | BLE MAC address or platform-specific identifier |

---

### PieceEvent

A piece-movement event from the Hall-effect sensors.

```csharp
public class PieceEvent
```

| Property | Type | Description |
|---|---|---|
| `Row` | `int` | Row number: 1--8 for board squares, 0 for border slots |
| `Col` | `int` | Column number: 1--8 for board squares, 1--10 for border slots |
| `IsDown` | `bool` | `true` if a piece was placed, `false` if lifted |
| `IsBorder` | `bool` | `true` if this event is from a border/storage slot |
| `BorderSide` | `string` | `"r"` (right), `"l"` (left), `"t"` (top), `"b"` (bottom), or `""` for board squares |

**Board squares** use standard chess indexing: Row 1 = White's back rank, Row 8 = Black's back rank, Col 1 = a-file, Col 8 = h-file.

---

### BoardState

The 8x8 board occupancy state from Hall-effect sensors.

```csharp
public class BoardState
```

#### Constructor

```csharp
BoardState(byte[] raw)
```

Construct from 8 raw bytes (one per row, bit N represents column N+1). Throws `ArgumentException` if the array is not exactly 8 bytes.

#### Methods and Properties

```csharp
bool IsOccupied(int row, int col)
```

Check if a square has a piece. Row and col are 1-indexed (1--8). Throws `IndexOutOfRangeException` for out-of-range values.

```csharp
bool[,] ToMatrix()
```

Return the board as an 8x8 boolean matrix (0-indexed, row-major). `matrix[r, c]` corresponds to board row `r+1`, col `c+1`.

| Property | Type | Description |
|---|---|---|
| `PieceCount` | `int` | Number of occupied squares |
| `RawBytes` | `byte[]` | Copy of the raw 8-byte occupancy data |

`ToString()` produces a formatted board diagram with file/rank labels.

---

### BorderState

Border/storage slot occupancy for the 36 positions surrounding the board (GoChess XR only).

```csharp
public class BorderState
```

#### Constructor

```csharp
BorderState(byte[] raw)
```

Construct from 6 raw bytes. Throws `ArgumentException` if the array is not exactly 6 bytes.

#### Methods and Properties

```csharp
bool IsOccupied(string position)
```

Check if a border position is occupied. Pass a label like `"a9"`, `"q0"`, `"i5"`, etc.

| Property | Type | Description |
|---|---|---|
| `Slots` | `Dictionary<string, bool>` | All 36 position labels mapped to occupied state (copy) |
| `OccupiedCount` | `int` | Number of occupied border slots |
| `RawBytes` | `byte[]` | Copy of the raw 6-byte occupancy data |

`ToString()` produces an ASCII-art diagram of the border layout.

---

### LedGroup

Defines a group of squares that share the same LED colour. Used with `SetLedsSpecialAsync`.

```csharp
public class LedGroup
```

| Property | Type | Description |
|---|---|---|
| `Squares` | `List<(int Row, int Col)>` | Squares in this group (1-indexed) |
| `R` | `byte` | Red component (0--255) |
| `G` | `byte` | Green component (0--255) |
| `B` | `byte` | Blue component (0--255) |

---

### GoChessHelpers

Static utility methods for LED bitmask encoding and chess notation conversion.

```csharp
public static class GoChessHelpers
```

```csharp
static (uint Mask1, uint Mask2) BuildLedMasks(List<(int Row, int Col)> squares)
```

Convert a list of 1-indexed (row, col) squares to firmware LED bitmasks. `Mask1` covers rows 1--4, `Mask2` covers rows 5--8.

```csharp
static byte[] EncodeLedMasksToBytes(uint mask1, uint mask2)
```

Encode LED bitmasks into the 8-byte payload expected by the firmware. Returns little-endian encoded bytes.

```csharp
static (int Row, int Col) SquareNotationToRC(string notation)
```

Convert chess notation like `"e4"` to a 1-indexed (row, col) tuple. Throws `ArgumentException` for invalid notation.

```csharp
static string RCToSquareNotation(int row, int col)
```

Convert a 1-indexed (row, col) tuple to chess notation like `"e4"`.

---

### GoChessConstants

BLE UUIDs and protocol constants.

```csharp
public static class GoChessConstants
```

| Constant | Value | Description |
|---|---|---|
| `NusServiceUuid` | `6e400001-b5a3-...` | Nordic UART Service UUID |
| `NusRxCharUuid` | `6e400002-b5a3-...` | NUS RX characteristic (App to Board, write) |
| `NusTxCharUuid` | `6e400003-b5a3-...` | NUS TX characteristic (Board to App, notify) |

Command bytes (`Cmd*`) and response type bytes (`Resp*`) are documented in the source.

---

## Data Types

### Coordinate System

The SDK uses 1-indexed coordinates throughout:

- **Row 1** = White's back rank (rank 1), **Row 8** = Black's back rank (rank 8)
- **Col 1** = a-file, **Col 8** = h-file

This matches standard chess convention. The `GoChessHelpers.SquareNotationToRC` and `RCToSquareNotation` methods convert between notation (e.g., `"e4"`) and (row, col) tuples.

### BoardState Matrix

`BoardState.ToMatrix()` returns a `bool[8, 8]` array that is 0-indexed:

```
matrix[0, 0] = a1 (bottom-left)
matrix[7, 7] = h8 (top-right)
```

---

## LED Control

### Uniform Colour

Light up specific squares with a single colour using `SetLedsAsync`:

```csharp
// Highlight e2 and e4 in green
await board.SetLedsAsync(
    new List<(int, int)> { (2, 5), (4, 5) },
    r: 0, g: 255, b: 0);

// Highlight d4 in red, keeping existing LEDs on
await board.SetLedsAsync(
    new List<(int, int)> { (4, 4) },
    r: 255, g: 0, b: 0,
    overwrite: false);
```

When `overwrite` is `true` (default), squares not in the list are turned off. When `false`, only the listed squares change.

### Multi-Colour Groups

Use `SetLedsSpecialAsync` to assign different colours to different squares:

```csharp
await board.SetLedsSpecialAsync(new List<LedGroup>
{
    new LedGroup
    {
        Squares = { (2, 5), (4, 5) },
        R = 0, G = 255, B = 0  // Green for e2, e4
    },
    new LedGroup
    {
        Squares = { (1, 4), (1, 5) },
        R = 0, G = 0, B = 255  // Blue for d1, e1
    },
});
```

This command clears all LEDs first, then applies each group sequentially.

### Chess Notation Shorthand

Use `SetLedsByNotationAsync` for a more readable approach:

```csharp
await board.SetLedsByNotationAsync(new Dictionary<string, (byte, byte, byte)>
{
    ["e2"] = (0, 255, 0),   // Green
    ["e4"] = (0, 255, 0),   // Green
    ["d7"] = (255, 0, 0),   // Red
    ["d5"] = (255, 0, 0),   // Red
});
```

Squares with the same colour are automatically grouped internally.

### Turn Off All LEDs

```csharp
await board.SetLedsOffAsync();
```

---

## Border State (XR Only)

The GoChess XR (Robotic) has 36 storage slots arranged around the board. Mini and Lite boards do not have border slots and will timeout if `GetBorderStateAsync` is called.

### Position Layout

```
        a   b   c   d   e   f   g   h
   q9 [ a9  b9  c9  d9  e9  f9  g9  h9 ] i9    <- Top border (rank 9)
   q8 [                                 ] i8
   q7 [                                 ] i7
   q6 [          8 x 8  Board           ] i6
   q5 [                                 ] i5
   q4 [                                 ] i4
   q3 [                                 ] i3
   q2 [                                 ] i2
   q1 [                                 ] i1
   q0 [ a0  b0  c0  d0  e0  f0  g0  h0 ] i0    <- Bottom border (rank 0)
        Left column (file "q")             Right column (file "i")
```

**Corners:** `q9` = top-left, `i9` = top-right, `q0` = bottom-left, `i0` = bottom-right.

### Querying Border State

```csharp
BorderState border = await board.GetBorderStateAsync();

// Check a specific slot
bool topLeftCorner = border.IsOccupied("q9");
bool bottomA = border.IsOccupied("a0");

// Iterate all slots
foreach (var kvp in border.Slots)
{
    if (kvp.Value)
        Console.WriteLine($"Piece at {kvp.Key}");
}

Console.WriteLine($"Total pieces on border: {border.OccupiedCount}");
```

### Raw Byte Mapping

| Byte | Covers | Bit Mapping |
|---|---|---|
| 0 | Top border (rank 9) | bits 0--7 map to a9..h9 |
| 1 | Bottom border (rank 0) | bits 0--7 map to a0..h0 |
| 2 | Left column (file q) | bit N = q(N) for N=0..7 |
| 3 | Left extension | bit 0 = q8, bit 1 = q9 |
| 4 | Right column (file i) | bit N = i(N) for N=0..7 |
| 5 | Right extension | bit 0 = i8, bit 1 = i9 |

---

## Platform Notes

### Windows Console / WPF / WinForms

This SDK uses native WinRT Bluetooth APIs (`Windows.Devices.Bluetooth`), which are available on Windows 10 version 2004 (build 19041) and later. The SDK works in any .NET 8.0+ application targeting `net8.0-windows10.0.19041.0`, including:

- Console applications
- WPF applications
- WinForms applications
- Windows Services

No NuGet packages are required — the WinRT projections are included with the Windows SDK.

### Bluetooth Requirements

- The PC must have a Bluetooth Low Energy (BLE) adapter (built-in or USB dongle).
- Bluetooth must be enabled in **Windows Settings > Bluetooth & devices**.
- No pairing is required — the GoChess board uses unencrypted BLE connections.
- Only one BLE central (host) can be connected to the board at a time.

### Threading Model

- BLE notification callbacks (`OnPieceMove`, `OnRawNotification`) are invoked on a thread-pool thread by the WinRT runtime.
- If updating UI elements from these callbacks, use `Dispatcher.Invoke` (WPF) or `Control.Invoke` (WinForms).
- The `ConcurrentDictionary` used for pending command responses is thread-safe.

### Cross-Platform Note

This SDK is Windows-only due to its use of WinRT Bluetooth APIs. For cross-platform .NET BLE support, consider adapting the SDK to use [Plugin.BLE](https://github.com/dotnet-bluetooth-le/dotnet-bluetooth-le) for Xamarin/MAUI, or [InTheHand.Net.Bluetooth](https://github.com/inthehand/32feet) for broader desktop support.

---

## Protocol Overview

The GoChess board uses an nRF52832 BLE SoC with the Nordic UART Service (NUS) for bidirectional communication.

### BLE Service

| UUID | Direction | Usage |
|---|---|---|
| `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | -- | Nordic UART Service |
| `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | App to Board (Write) | RX Characteristic |
| `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | Board to App (Notify) | TX Characteristic |

### Message Formats

The board uses two message formats over the NUS TX characteristic:

**1. Raw messages (no framing)**

| Message | Format | Example |
|---|---|---|
| Piece move (board) | `[row_ascii][col_ascii]['d'/'u']` | `"81d"` = row 8, col 1, piece down |
| Piece move (border) | `[side][pos_ascii]['d'/'u']` | `"r3u"` = right side, position 3, piece up |
| Board state | `[0x03][8 bytes]` | 8 bytes, one per row |
| Border state | `[0x0C][6 bytes]` | 6 bytes of border occupancy |
| FW version | `"Ver" + version_byte` | `"Ver\x04"` = version 4 |

**2. Framed messages**

```
[START][LEN][TYPE][DATA...][CHECKSUM][CR][LF]
```

| Field | Value | Description |
|---|---|---|
| START | `0x2A` (`'*'`) | Frame start marker |
| LEN | 1 byte | Total bytes from START through CHECKSUM (inclusive) |
| TYPE | 1 byte | Response type identifier |
| DATA | variable | Payload bytes |
| CHECKSUM | 1 byte | Sum of all bytes from START through DATA |
| CR LF | `0x0D 0x0A` | Frame terminator |

**Framed response types:**

| Type Byte | Key | Payload | Description |
|---|---|---|---|
| `0x01` | `battery` | 1 byte | Battery percentage (0--100) |
| `0x02` | `battery_mv` | 2 bytes (big-endian) | Battery voltage in mV |
| `0x04` | `current` | 2 bytes (big-endian) | Current draw in uA |
| `0x07` | `charging` | 1 byte (0/1) | Charging state |
| `0x0B` | `chamber` | 1 byte (0/1) | Chamber state |

---

## Troubleshooting

### No boards found during scan

- Ensure the GoChess board is powered on and not connected to another device. Only one BLE central can be connected at a time.
- Move the board closer to the scanning device.
- Ensure Bluetooth is enabled in **Windows Settings > Bluetooth & devices**.
- Check that your PC has a BLE-capable Bluetooth adapter.
- Increase the scan timeout: `GoChessBoard.ScanAsync(TimeSpan.FromSeconds(15))`.

### Connection fails or NUS service not found

- Verify you are connecting to a GoChess board (name starts with `"GoChess"`).
- Power-cycle the board and retry.
- Ensure no other app or device is already connected to the board.

### Commands timeout (TimeoutException)

- The default timeout is 5000ms. Increase it if the board responds slowly: `GetBatteryAsync(timeoutMs: 10000)`.
- `GetBorderStateAsync` will always timeout on Mini/Lite boards. Check the board type first using `GetFwVersionAsync` (XR returns `0x04`).
- Verify the board has not disconnected. Check `board.IsConnected` before sending commands.

### LEDs do not light up

- Verify the row/col values are 1-indexed (1--8), not 0-indexed.
- Check that the RGB values are not all zero (which would be "off").
- If using `overwrite: false`, previously set LEDs remain active; use `overwrite: true` to clear others.
- Try `SetLedsOffAsync()` first, then set new LEDs to rule out stale state.

### Piece events not received

- Ensure `OnPieceMove` is called **after** `ConnectAsync` completes.
- Exceptions thrown inside the callback are silently caught. Add try/catch inside your callback to diagnose errors.
- For raw debugging, register a callback with `OnRawNotification` to see all incoming BLE data.

### Platform-specific BLE issues

- **Windows 10/11:** Ensure you target a Windows 10 TFM (e.g., `net8.0-windows10.0.19041.0`) in your `.csproj`. Without this, WinRT Bluetooth APIs will not be available.
- **Older Windows:** Windows versions before 10 build 19041 are not supported by the WinRT Bluetooth APIs.
- **USB Bluetooth Adapters:** Some cheap USB BLE dongles have poor driver support. If scanning fails, try a different adapter or the built-in Bluetooth on a laptop.
- **Antivirus/Firewall:** Some security software can block BLE operations. If scanning returns no devices, try temporarily disabling your antivirus.
