# GoChess Python SDK

![Python 3.7+](https://img.shields.io/badge/python-3.7%2B-blue)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey)
![BLE](https://img.shields.io/badge/transport-Bluetooth%20LE-blue)
![License](https://img.shields.io/badge/license-Proprietary-orange)

A Python SDK for communicating with **GoChess** smart chess boards over Bluetooth Low Energy (BLE). The SDK provides a high-level async API for scanning, connecting, receiving real-time piece movement events, querying board state, and controlling per-square RGB LEDs.

Built on top of [Bleak](https://github.com/hbldh/bleak), the SDK works cross-platform on Windows, macOS, and Linux without any platform-specific code.

---

## Features

- **BLE scanning and connection** -- discover and connect to nearby GoChess boards
- **Real-time piece tracking** -- receive callbacks when pieces are lifted or placed via Hall-effect sensors
- **Board state queries** -- read the full 8x8 occupancy grid on demand
- **Border state queries** -- read 36 surrounding storage slots (GoChess XR only)
- **Battery monitoring** -- query battery percentage
- **RGB LED control** -- set per-square colors with uniform or multi-color modes
- **Chess notation helpers** -- convert between algebraic notation (e.g. `"e4"`) and row/col coordinates
- **Async context manager** -- use `async with` for automatic cleanup
- **Single file, zero config** -- one `gochess_sdk.py` file, one dependency

---

## Installation

### 1. Install the BLE dependency

```bash
pip install bleak
```

### 2. Copy the SDK

Copy `gochess_sdk.py` into your project directory, or add the `sdk/python/` folder to your Python path.

```
your_project/
    gochess_sdk.py
    your_app.py
```

### 3. Verify

```python
from gochess_sdk import GoChessBoard
print("SDK loaded successfully")
```

> **Note:** Python 3.7 or later is required (for `asyncio` and `dataclasses`).

---

## Quick Start

```python
import asyncio
from gochess_sdk import GoChessBoard, PieceEvent

def on_move(event: PieceEvent):
    action = "placed" if event.is_down else "lifted"
    print(f"Piece {action} at row={event.row}, col={event.col}")

async def main():
    # Scan for boards
    devices = await GoChessBoard.scan(timeout=5.0)
    if not devices:
        print("No boards found")
        return

    print(f"Found: {devices[0].name} ({devices[0].address})")

    # Connect and interact
    async with GoChessBoard() as board:
        await board.connect(devices[0])
        board.on_piece_move(on_move)

        battery = await board.get_battery()
        print(f"Battery: {battery}%")

        state = await board.get_board_state()
        print(state)

        # Light up e2 and e4 in green
        await board.set_leds_by_notation({
            "e2": (0, 255, 0),
            "e4": (0, 255, 0),
        })

        # Keep running to receive piece events
        await asyncio.sleep(60)

asyncio.run(main())
```

---

## API Reference

### GoChessBoard

The main SDK class. Handles scanning, connection, commands, and event dispatch.

#### Constructor

```python
board = GoChessBoard()
```

No arguments. Create one instance per board connection.

---

#### `GoChessBoard.scan(timeout=5.0)` (static, async)

Scan for nearby GoChess boards via BLE.

| Parameter | Type    | Default | Description                    |
|-----------|---------|---------|--------------------------------|
| `timeout` | `float` | `5.0`   | How long to scan, in seconds.  |

**Returns:** `List[GoChessDevice]` -- discovered boards sorted by signal strength.

```python
devices = await GoChessBoard.scan(timeout=10.0)
for d in devices:
    print(f"[{d.index}] {d.name}  {d.address}")
```

---

#### `connect(device)` (async)

Connect to a GoChess board and start listening for BLE notifications.

| Parameter | Type           | Description                              |
|-----------|----------------|------------------------------------------|
| `device`  | `GoChessDevice`| A device returned by `GoChessBoard.scan` |

**Returns:** `None`

**Raises:** `BleakError` if connection fails.

```python
await board.connect(devices[0])
```

---

#### `disconnect()` (async)

Disconnect from the board, stop notifications, and cancel pending requests.

**Returns:** `None`

```python
await board.disconnect()
```

---

#### `is_connected` (property)

**Type:** `bool` -- `True` if currently connected to a board.

```python
if board.is_connected:
    print("Ready")
```

---

#### `on_piece_move(callback)`

Register a callback invoked whenever a piece is lifted or placed.

| Parameter  | Type                              | Description             |
|------------|-----------------------------------|-------------------------|
| `callback` | `Callable[[PieceEvent], None]`    | Function to call.       |

Multiple callbacks can be registered. They are called in registration order.

```python
def handler(event: PieceEvent):
    print(event)

board.on_piece_move(handler)
```

---

#### `on_raw_notification(callback)`

Register a callback for every raw BLE notification (useful for debugging and protocol analysis).

| Parameter  | Type                         | Description                      |
|------------|------------------------------|----------------------------------|
| `callback` | `Callable[[bytes], None]`    | Receives the raw notification.   |

```python
board.on_raw_notification(lambda data: print(f"RAW: {data.hex()}"))
```

---

#### `get_battery(timeout=5.0)` (async)

Request the battery percentage.

| Parameter | Type    | Default | Description                         |
|-----------|---------|---------|-------------------------------------|
| `timeout` | `float` | `5.0`   | Seconds to wait for a response.     |

**Returns:** `int` -- battery level, 0-100 (%).

**Raises:** `asyncio.TimeoutError` if the board does not respond in time.

```python
battery = await board.get_battery()
print(f"Battery: {battery}%")
```

---

#### `get_board_state(timeout=5.0)` (async)

Request the full 8x8 board occupancy state.

| Parameter | Type    | Default | Description                         |
|-----------|---------|---------|-------------------------------------|
| `timeout` | `float` | `5.0`   | Seconds to wait for a response.     |

**Returns:** `BoardState` -- the occupancy grid.

**Raises:** `asyncio.TimeoutError` if the board does not respond in time.

```python
state = await board.get_board_state()
if state.is_occupied(1, 5):  # e1
    print("King is home")
print(state)
```

---

#### `get_border_state(timeout=5.0)` (async)

Request the border/storage slot occupancy (36 positions around the board).

| Parameter | Type    | Default | Description                         |
|-----------|---------|---------|-------------------------------------|
| `timeout` | `float` | `5.0`   | Seconds to wait for a response.     |

**Returns:** `BorderState` -- the 36-slot occupancy map.

**Raises:** `asyncio.TimeoutError` if the board does not respond in time.

> **GoChess XR (Robotic) only.** Mini and Lite boards do not have border slots and will not respond (the call will time out).

```python
border = await board.get_border_state()
if border.is_occupied("a9"):
    print("Piece on top border a9")
print(border)
```

---

#### `get_fw_version(timeout=5.0)` (async)

Request the firmware version byte.

| Parameter | Type    | Default | Description                         |
|-----------|---------|---------|-------------------------------------|
| `timeout` | `float` | `5.0`   | Seconds to wait for a response.     |

**Returns:** `int` -- version number. Known values:

| Value  | Board                  |
|--------|------------------------|
| `0x03` | GoChess Mini / Lite    |
| `0x04` | GoChess XR (Robotic)   |

```python
fw = await board.get_fw_version()
print(f"Firmware: 0x{fw:02X}")
```

---

#### `set_leds(squares, r=0, g=0, b=0, overwrite=True)` (async)

Set LEDs for the given squares with a uniform color (uses BLE command `0x32`).

| Parameter   | Type           | Default | Description                                                                       |
|-------------|----------------|---------|-----------------------------------------------------------------------------------|
| `squares`   | `List[tuple]`  | --      | List of `(row, col)` tuples, 1-indexed (1-8). Empty list with `overwrite=True` turns all LEDs off. |
| `r`         | `int`          | `0`     | Red channel, 0-255.                                                               |
| `g`         | `int`          | `0`     | Green channel, 0-255.                                                             |
| `b`         | `int`          | `0`     | Blue channel, 0-255.                                                              |
| `overwrite` | `bool`         | `True`  | If `True`, squares not in the list are turned off. If `False`, only listed squares change; others keep their current color. |

```python
# Light up e2 and e4 in red
await board.set_leds([(2, 5), (4, 5)], r=255, g=0, b=0)

# Add d4 in red without affecting e2/e4
await board.set_leds([(4, 4)], r=255, g=0, b=0, overwrite=False)
```

---

#### `set_leds_off()` (async)

Turn off all board LEDs. Equivalent to `set_leds([], overwrite=True)`.

```python
await board.set_leds_off()
```

---

#### `set_leds_special(groups)` (async)

Set per-square LED colors with multiple color groups (uses BLE command `0x34`). This command first turns off all LEDs, then applies each group.

| Parameter | Type          | Description                                              |
|-----------|---------------|----------------------------------------------------------|
| `groups`  | `List[Dict]`  | List of group dicts, each with `"squares"`, `"r"`, `"g"`, `"b"` keys. |

Each group dict:

| Key        | Type           | Description                           |
|------------|----------------|---------------------------------------|
| `"squares"`| `List[tuple]`  | List of `(row, col)` tuples (1-8).    |
| `"r"`      | `int`          | Red 0-255.                            |
| `"g"`      | `int`          | Green 0-255.                          |
| `"b"`      | `int`          | Blue 0-255.                           |

```python
await board.set_leds_special([
    {"squares": [(4, 5), (5, 4)], "r": 0,   "g": 255, "b": 0  },  # green
    {"squares": [(2, 3)],          "r": 255, "g": 0,   "b": 0  },  # red
])
```

---

#### `set_leds_by_notation(squares_colors, overwrite=True)` (async)

Convenience method: set LEDs using chess notation. Uses `set_leds_special` internally, so each square can have its own color.

| Parameter        | Type                    | Description                                        |
|------------------|-------------------------|----------------------------------------------------|
| `squares_colors` | `Dict[str, tuple]`      | Mapping of notation string to `(r, g, b)` tuple.   |
| `overwrite`      | `bool`                  | Unused (0x34 always clears first).                  |

```python
await board.set_leds_by_notation({
    "e2": (0, 255, 0),    # green
    "e4": (0, 255, 0),    # green
    "d7": (255, 0, 0),    # red
})
```

---

#### Context Manager

`GoChessBoard` supports `async with` for automatic disconnection:

```python
async with GoChessBoard() as board:
    await board.connect(device)
    # ... use the board ...
# disconnect() is called automatically on exit
```

---

### Helper Functions

#### `square_notation_to_rc(notation)`

Convert chess notation to a `(row, col)` tuple.

| Parameter  | Type  | Description                       |
|------------|-------|-----------------------------------|
| `notation` | `str` | Algebraic notation, e.g. `"e4"`. |

**Returns:** `tuple` -- `(row, col)`, both 1-indexed.

```python
from gochess_sdk import square_notation_to_rc

row, col = square_notation_to_rc("e4")  # (4, 5)
```

---

#### `rc_to_square_notation(row, col)`

Convert a `(row, col)` pair to chess notation.

| Parameter | Type  | Description        |
|-----------|-------|--------------------|
| `row`     | `int` | Row, 1-indexed.    |
| `col`     | `int` | Column, 1-indexed. |

**Returns:** `str` -- algebraic notation, e.g. `"e4"`.

```python
from gochess_sdk import rc_to_square_notation

notation = rc_to_square_notation(4, 5)  # "e4"
```

---

## Data Types

### GoChessDevice

A discovered GoChess board. Returned by `GoChessBoard.scan()`.

```python
@dataclass
class GoChessDevice:
    index: int       # Position in the scan results list (0-based)
    name: str        # BLE advertised name, e.g. "GoChessXR_AB12CD"
    address: str     # BLE MAC address or UUID
```

---

### PieceEvent

A piece-movement event from the Hall-effect sensors. Passed to `on_piece_move` callbacks.

```python
@dataclass
class PieceEvent:
    row: int           # 1-8 for board squares, 0 for border slots
    col: int           # 1-8 for board squares, 1-10 for border slots
    is_down: bool      # True = piece placed, False = piece lifted
    is_border: bool    # True if this is a border/storage slot
    border_side: str   # 'r', 'l', 't', 'b' for border; '' for board squares
```

**Board square event example:**

| Field         | Value   | Meaning                    |
|---------------|---------|----------------------------|
| `row`         | `4`     | Rank 4                     |
| `col`         | `5`     | File e                     |
| `is_down`     | `True`  | Piece was placed           |
| `is_border`   | `False` | On the main board          |
| `border_side` | `""`    | Not a border slot          |

**Border slot event example (XR only):**

| Field         | Value   | Meaning                         |
|---------------|---------|---------------------------------|
| `row`         | `0`     | Not a board square              |
| `col`         | `3`     | Position 3 on this side         |
| `is_down`     | `False` | Piece was lifted                |
| `is_border`   | `True`  | Border storage slot             |
| `border_side` | `"r"`   | Right side of the board         |

---

### BoardState

8x8 board occupancy state. Each square is `True` (piece present) or `False` (empty).

Indexing convention (1-based):
- **Row 1** = White's back rank, **Row 8** = Black's back rank
- **Col 1** = a-file, **Col 8** = h-file

#### Methods and Properties

| Member                        | Type / Return        | Description                                                  |
|-------------------------------|----------------------|--------------------------------------------------------------|
| `is_occupied(row, col)`       | `bool`               | Check if square (row, col) has a piece. Both 1-indexed.      |
| `to_matrix()`                 | `List[List[bool]]`   | 8x8 nested list (row-major).                                 |
| `raw_bytes`                   | `bytes`              | The original 8-byte response from the board.                 |
| `__str__()`                   | `str`                | Pretty-printed grid with file/rank labels.                   |
| `__repr__()`                  | `str`                | Summary, e.g. `<BoardState pieces=32/64>`.                   |

```python
state = await board.get_board_state()

# Check a specific square
if state.is_occupied(1, 5):  # e1
    print("e1 is occupied")

# Get the full matrix
matrix = state.to_matrix()

# Pretty-print
print(state)
#   a b c d e f g h
# 8 . . . . . . . .
# 7 . . . . . . . .
# 6 . . . . . . . .
# 5 . . . . . . . .
# 4 . . . . . . . .
# 3 . . . . . . . .
# 2 * * * * * * * *
# 1 * * * * * * * *
```

---

### BorderState

Border/storage slot occupancy state. 36 positions surrounding the board (GoChess XR only).

#### Methods and Properties

| Member                        | Type / Return        | Description                                                     |
|-------------------------------|----------------------|-----------------------------------------------------------------|
| `is_occupied(position)`       | `bool`               | Check if a named position (e.g. `"a9"`, `"q3"`) is occupied.   |
| `slots`                       | `Dict[str, bool]`    | All 36 position labels mapped to their occupied state.          |
| `occupied_count`              | `int`                | Number of occupied border slots.                                |
| `raw_bytes`                   | `bytes`              | The original 6-byte response from the board.                    |
| `__str__()`                   | `str`                | ASCII art showing all border slots around the board.            |
| `__repr__()`                  | `str`                | Summary, e.g. `<BorderState occupied=4/36>`.                    |

```python
border = await board.get_border_state()
print(f"Occupied slots: {border.occupied_count}/36")

if border.is_occupied("a9"):
    print("Top-left border slot has a piece")

# Iterate all slots
for pos, occupied in border.slots.items():
    if occupied:
        print(f"  {pos}: occupied")
```

---

## LED Control

The GoChess board has individually addressable RGB LEDs under each of the 64 squares. The SDK provides two command modes:

### Uniform Color (command 0x32) -- `set_leds()`

All specified squares share one color. Supports an `overwrite` flag to control whether unspecified squares are turned off or left unchanged.

```python
# All of rank 4 in blue, turning off all other LEDs
await board.set_leds(
    [(4, c) for c in range(1, 9)],
    r=0, g=0, b=255,
    overwrite=True
)

# Add rank 5 in blue without affecting rank 4
await board.set_leds(
    [(5, c) for c in range(1, 9)],
    r=0, g=0, b=255,
    overwrite=False
)

# Turn off all LEDs
await board.set_leds_off()
```

### Multi-Color (command 0x34) -- `set_leds_special()`

Each group of squares can have a different color. This command always clears all LEDs first, then applies each group sequentially.

```python
await board.set_leds_special([
    {
        "squares": [(1, 5), (8, 5)],  # e1 and e8
        "r": 255, "g": 255, "b": 0     # yellow (kings)
    },
    {
        "squares": [(4, 4), (4, 5), (5, 4), (5, 5)],  # d4, e4, d5, e5
        "r": 0,   "g": 128, "b": 255   # light blue (center)
    },
])
```

### Chess Notation Convenience -- `set_leds_by_notation()`

Uses `set_leds_special` internally. Each square is specified by algebraic notation and can have its own color.

```python
await board.set_leds_by_notation({
    "e2": (0, 255, 0),   # green -- pawn origin
    "e4": (0, 255, 0),   # green -- pawn destination
    "d7": (255, 0, 0),   # red   -- threatened square
    "f7": (255, 0, 0),   # red   -- threatened square
})
```

---

## Border State (GoChess XR Only)

The GoChess XR (Robotic) board has 36 storage slots surrounding the 8x8 board. These are used by the robotic arm to store captured pieces. Mini and Lite boards do not have border slots.

### Position Labels

The 36 border positions use a special naming convention:

```
        a  b  c  d  e  f  g  h
   q9 [ a9 b9 c9 d9 e9 f9 g9 h9 ] i9    <- Top row (rank 9)
   q8 [                          ] i8
   q7 [                          ] i7
   q6 [        8 x 8             ] i6
   q5 [        main              ] i5    <- Left: file "q"
   q4 [        board             ] i4       Right: file "i"
   q3 [                          ] i3
   q2 [                          ] i2
   q1 [                          ] i1
   q0 [ a0 b0 c0 d0 e0 f0 g0 h0 ] i0    <- Bottom row (rank 0)
```

| Region        | Labels                                         | Count |
|---------------|-------------------------------------------------|-------|
| Top row       | `a9`, `b9`, `c9`, `d9`, `e9`, `f9`, `g9`, `h9` | 8     |
| Bottom row    | `a0`, `b0`, `c0`, `d0`, `e0`, `f0`, `g0`, `h0` | 8     |
| Left column   | `q0`, `q1`, `q2`, `q3`, `q4`, `q5`, `q6`, `q7`, `q8`, `q9` | 10 |
| Right column  | `i0`, `i1`, `i2`, `i3`, `i4`, `i5`, `i6`, `i7`, `i8`, `i9` | 10 |
| **Total**     |                                                 | **36** |

Corner slots are shared between rows and columns:
- `q9` = top-left corner
- `i9` = top-right corner
- `q0` = bottom-left corner
- `i0` = bottom-right corner

### Querying Border State

```python
border = await board.get_border_state()

# Check specific positions
if border.is_occupied("a9"):
    print("Top border a9 has a piece")

# Count occupied slots
print(f"{border.occupied_count} of 36 slots occupied")

# Pretty-print (ASCII art)
print(border)
```

### Border Piece Events

When a piece is placed or removed from a border slot, the `on_piece_move` callback fires with `is_border=True`:

```python
def on_move(event: PieceEvent):
    if event.is_border:
        print(f"Border: side={event.border_side}, pos={event.col}, down={event.is_down}")
    else:
        print(f"Board: row={event.row}, col={event.col}, down={event.is_down}")
```

---

## Running the Example

The included `example.py` is an interactive console application that demonstrates all SDK features.

```bash
pip install bleak
python example.py
```

The example will:

1. Scan for nearby GoChess boards
2. Let you select a board to connect to
3. Display firmware version and battery level
4. Enter an interactive menu with options for all commands
5. Print piece movement events in real time

### Example Menu

```
==============================================================
  GoChess Board -- Command Menu
==============================================================

  1  Get battery level
  2  Get board state (8x8 occupancy)
  3  Get border state (robotic storage slots)
  4  Get firmware version
  5  Set LEDs -- uniform colour (0x32)
  6  Set LEDs -- special / multi-colour (0x34)
  7  Turn off all LEDs
  0  Disconnect & exit

  Piece movements are shown in real-time as they happen.
--------------------------------------------------------------
```

---

## Supported Boards

| Board            | BLE Name Pattern      | Border Slots | FW Version | Robotic Arm |
|------------------|-----------------------|--------------|------------|-------------|
| GoChess XR       | `GoChessXR_XXXXXX`   | Yes (36)     | `0x04`     | Yes         |
| GoChess Mini     | `GoChessM_XXXXXX`    | No           | `0x03`     | No          |
| GoChess Lite     | `GoChessL_XXXXXX`    | No           | `0x03`     | No          |

All boards support:
- 8x8 piece detection (Hall-effect sensors)
- Per-square RGB LEDs
- Battery monitoring
- BLE connection via Nordic UART Service

Only the GoChess XR supports:
- Border/storage slot detection
- `get_border_state()` command
- Border piece-move events

---

## Protocol Overview

<details>
<summary>Click to expand -- for advanced users and protocol-level debugging</summary>

### Transport

The board uses an **nRF52832** BLE SoC with the **Nordic UART Service (NUS)**:

| UUID                                       | Direction      | Role            |
|--------------------------------------------|----------------|-----------------|
| `6e400001-b5a3-f393-e0a9-e50e24dcca9e`    | --             | Service UUID    |
| `6e400002-b5a3-f393-e0a9-e50e24dcca9e`    | App -> Board   | RX (write)      |
| `6e400003-b5a3-f393-e0a9-e50e24dcca9e`    | Board -> App   | TX (notify)     |

### Message Formats

The board uses two message formats over the NUS TX characteristic:

**1. Raw messages (no framing):**

| Type                | Format                            | Example          |
|---------------------|-----------------------------------|------------------|
| Piece move (board)  | 3-4 ASCII bytes: `row col d/u`    | `"81d"` = h1 down |
| Piece move (border) | 3-4 ASCII bytes: `side pos d/u`   | `"r3u"` = right slot 3 up |
| Board state         | `[0x03][8 bytes]`                 | Bitfield per row |
| Border state        | `[0x0C][6 bytes]`                 | Bitfield encoding |
| FW version          | `"Ver" + version_byte`            | `"Ver\x04"`      |

**2. Framed messages:**

```
[START][LEN][TYPE][DATA...][CHECKSUM][CR][LF]
```

| Field      | Value / Size      | Description                                           |
|------------|-------------------|-------------------------------------------------------|
| START      | `0x2A` (`'*'`)   | Start delimiter                                       |
| LEN        | 1 byte            | Total bytes from START through CHECKSUM (inclusive)    |
| TYPE       | 1 byte            | Response type (see below)                             |
| DATA       | variable          | Payload                                               |
| CHECKSUM   | 1 byte            | Sum of all bytes from START through DATA              |
| CR LF      | `0x0D 0x0A`      | Terminator                                            |

Framed response types:

| Type   | Payload          | Description                    |
|--------|------------------|--------------------------------|
| `0x01` | 1 byte           | Battery percentage (0-100)     |
| `0x02` | 2 bytes (BE)     | Battery voltage in mV          |
| `0x04` | 2 bytes (BE)     | Current draw in uA             |
| `0x07` | 1 byte           | Charging state (0/1)           |
| `0x0B` | 1 byte           | Chamber connected (0/1)        |

### Command Bytes (App -> Board)

| Byte   | Name                     | Payload                                              |
|--------|--------------------------|------------------------------------------------------|
| `0x22` | Get border state         | None                                                 |
| `0x32` | Set RGB LEDs (uniform)   | 12 bytes: mask1(4) + mask2(4) + G + R + B + overwrite |
| `0x33` | LED on sensor            | 1 byte: 0x00/0x01                                    |
| `0x34` | LED on special           | Variable: per-square color groups                    |
| `0x35` | Get board state          | None                                                 |
| `0x36` | Set LEDs mode            | 1 byte: mode 0/1/2                                   |
| `0x39` | Check battery            | None                                                 |
| `0x3A` | Check battery mV         | None                                                 |
| `0x3D` | Get charging state       | None                                                 |
| `0x76` | Get FW version           | None (sends ASCII `'v'`)                             |

</details>

---

## Troubleshooting

### Board not found during scan

- Make sure the board is **powered on** and within Bluetooth range (typically under 10 meters).
- Ensure no other application (mobile app, another script) is currently connected. BLE only allows one active connection at a time.
- On Linux, you may need to run with `sudo` or ensure your user is in the `bluetooth` group.
- Try increasing the scan timeout: `await GoChessBoard.scan(timeout=15.0)`.

### Connection drops or times out

- Keep the board within range throughout the session.
- BLE connections can be interrupted by Wi-Fi interference on 2.4 GHz. Try moving away from routers.
- On Windows, ensure the Bluetooth adapter driver is up to date.

### `get_border_state()` times out

- This command is **only supported on GoChess XR** (Robotic). Mini and Lite boards do not have border slots and will not respond.
- Check the board type with `get_fw_version()`. A return value of `0x04` confirms XR.

### LED colors appear wrong

- The board uses GRB-wired LEDs. The SDK handles the color mapping internally. If you see incorrect colors, ensure you are using the SDK's `set_leds` methods rather than sending raw bytes.

### `asyncio` errors or event loop issues

- Always run your program with `asyncio.run(main())` as the entry point.
- Do not mix blocking calls with async code. Use `loop.run_in_executor()` for blocking operations (see `example.py` for a pattern with `async_input`).
- On Windows with Python 3.7, you may need to set the event loop policy:
  ```python
  asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
  ```

### `ModuleNotFoundError: No module named 'bleak'`

- Install the dependency: `pip install bleak`.
- If using a virtual environment, make sure it is activated.

### Enabling debug logging

```python
import logging
logging.basicConfig(level=logging.DEBUG)
```

This will print all raw BLE TX/RX traffic, which is useful for diagnosing protocol-level issues.
