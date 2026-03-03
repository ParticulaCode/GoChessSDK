# GoChess JavaScript / TypeScript SDK

![Language](https://img.shields.io/badge/language-TypeScript-3178C6)
![Platform](https://img.shields.io/badge/platform-Web%20Bluetooth-4285F4)
![Dependencies](https://img.shields.io/badge/dependencies-none-brightgreen)
![License](https://img.shields.io/badge/license-proprietary-lightgrey)

A browser SDK for communicating with GoChess smart chess boards over Bluetooth Low Energy (BLE). The SDK uses the Web Bluetooth API to connect directly from a web page -- no native app, browser extension, or npm dependency required.

The GoChess board is powered by an nRF52832 BLE SoC and communicates through the Nordic UART Service (NUS). This SDK wraps that protocol into a clean async/await API for connecting, reading sensors, and controlling LEDs.

---

## Features

- **Zero dependencies** -- runs entirely on the Web Bluetooth API built into modern browsers.
- **Real-time piece tracking** -- receive callbacks when pieces are lifted or placed (Hall-effect sensors).
- **Board state queries** -- read the full 8x8 occupancy bitmap on demand.
- **Border state queries** -- read all 36 border/storage slots on GoChess XR (Robotic) boards.
- **Per-square RGB LED control** -- uniform colour mode (0x32) and multi-colour group mode (0x34).
- **Chess notation helpers** -- convert between algebraic notation (`"e4"`) and row/col coordinates.
- **Battery and firmware** -- query charge level (0-100%) and firmware version byte.
- **TypeScript-first** -- full type annotations; also works as plain JavaScript when transpiled or inlined.

---

## Requirements

### Browser Compatibility

| Browser          | Platform        | Supported |
|------------------|-----------------|-----------|
| Chrome 56+       | Windows, macOS, Linux, Android, ChromeOS | Yes |
| Edge 79+         | Windows, macOS  | Yes       |
| Opera 43+        | Windows, macOS  | Yes       |
| Firefox          | All             | No        |
| Safari           | All             | No        |
| Chrome (iOS)     | iOS             | No        |

### Additional Requirements

- The page **must** be served over **HTTPS** or from **localhost**. Web Bluetooth is blocked on insecure origins.
- The user must trigger `connect()` from a user gesture (click, tap). The browser will show a device-picker dialog.

---

## Quick Start

The simplest way to get started is to include the SDK inline in a single HTML file. No build step is needed.

```html
<!DOCTYPE html>
<html>
<head><meta charset="UTF-8"><title>GoChess Quick Start</title></head>
<body>
  <button id="connectBtn">Connect to GoChess</button>
  <pre id="output"></pre>

  <!-- Paste or include the transpiled SDK here (see gochess_sdk.ts) -->
  <script src="gochess_sdk.js"></script>
  <script>
    const board = new GoChessBoard();

    document.getElementById("connectBtn").addEventListener("click", async () => {
      const name = await board.connect();
      document.getElementById("output").textContent = "Connected to " + name;

      // Listen for piece movements
      board.onPieceMove(evt => {
        const action = evt.isDown ? "placed on" : "lifted from";
        const square = evt.isBorder
          ? `border ${evt.borderSide} slot ${evt.col}`
          : rcToSquareNotation(evt.row, evt.col);
        console.log(`Piece ${action} ${square}`);
      });

      // Read battery
      const battery = await board.getBattery();
      console.log("Battery:", battery + "%");

      // Read board occupancy
      const state = await board.getBoardState();
      console.log("Pieces on board:", state.pieceCount);
      console.log(state.toString());

      // Light up e2 and e4 in green
      await board.setLeds([[2, 5], [4, 5]], 0, 255, 0);
    });
  </script>
</body>
</html>
```

---

## API Reference

### `GoChessBoard` -- Main SDK Class

The primary class that manages the BLE connection and exposes all board commands.

#### Constructor

```ts
const board = new GoChessBoard();
```

No arguments. The instance is inert until `connect()` is called.

#### Connection

| Method / Property | Signature | Description |
|-------------------|-----------|-------------|
| `connect`    | `async connect(namePrefix?: string): Promise<string>` | Opens the browser Bluetooth device picker filtered to GoChess boards. Returns the device name (e.g. `"GoChessXR_A1B2C3"`). The `namePrefix` parameter defaults to `"GoChess"` and matches all board types. |
| `disconnect` | `async disconnect(): Promise<void>` | Disconnects from the board, stops BLE notifications, and cancels any pending requests. |
| `isConnected` | `boolean` (getter) | `true` while the GATT connection is active. Automatically set to `false` on unexpected disconnection. |
| `deviceName` | `string` (getter) | The BLE advertised name of the connected device, or `""` if not connected. |

#### Callbacks

| Method | Signature | Description |
|--------|-----------|-------------|
| `onPieceMove`       | `onPieceMove(cb: (evt: PieceEvent) => void): void`    | Register a callback for piece lift/place events. Multiple callbacks can be registered. |
| `onRawNotification` | `onRawNotification(cb: (data: Uint8Array) => void): void` | Register a callback that receives every raw BLE notification. Useful for debugging or implementing custom protocol handling. |

#### Queries

All query methods send a command byte and wait for the board's response. Each accepts an optional `timeoutMs` parameter (default: `5000` ms). The returned `Promise` rejects with a timeout error if no response is received.

| Method | Signature | Description |
|--------|-----------|-------------|
| `getBattery`     | `async getBattery(timeoutMs?: number): Promise<number>`      | Returns battery percentage (0--100). |
| `getBoardState`  | `async getBoardState(timeoutMs?: number): Promise<BoardState>` | Returns an 8x8 occupancy bitmap. See `BoardState` below. |
| `getBorderState` | `async getBorderState(timeoutMs?: number): Promise<BorderState>` | Returns the 36-slot border occupancy. **GoChess XR (Robotic) only.** Mini and Lite boards will not respond. See `BorderState` below. |
| `getFwVersion`   | `async getFwVersion(timeoutMs?: number): Promise<number>`    | Returns the firmware version byte. `0x04` = Robotic (XR), `0x03` = Mini/Lite. |

#### LED Control

| Method | Signature | Description |
|--------|-----------|-------------|
| `setLeds`          | `async setLeds(squares: [number, number][], r?: number, g?: number, b?: number, overwrite?: boolean): Promise<void>` | Set LEDs for specific squares with a uniform colour (command `0x32`). Squares are `[row, col]` pairs, 1-indexed. RGB values are 0--255. When `overwrite` is `true` (default), unselected squares are turned off. |
| `setLedsOff`       | `async setLedsOff(): Promise<void>` | Turn off all LEDs. Equivalent to `setLeds([], 0, 0, 0, true)`. |
| `setLedsSpecial`   | `async setLedsSpecial(groups: LedGroup[]): Promise<void>` | Set per-square colours with multiple colour groups (command `0x34`). Clears all LEDs first, then applies each group in order. |
| `setLedsByNotation` | `async setLedsByNotation(squareColors: Record<string, [number, number, number]>): Promise<void>` | Convenience method accepting chess notation. Automatically groups squares by colour and delegates to `setLedsSpecial`. |

---

## Data Types

### `PieceEvent`

Emitted by the `onPieceMove` callback when a piece is lifted or placed.

```ts
interface PieceEvent {
  row: number;        // 1-8 for board squares, 0 for border slots
  col: number;        // 1-8 for board squares, 1-10 for border slots
  isDown: boolean;    // true = piece placed, false = piece lifted
  isBorder: boolean;  // true if this is a border/storage slot (XR only)
  borderSide: string; // "r" | "l" | "t" | "b" for border, "" for board
}
```

For board squares, the `row` corresponds to the chess rank (1 = rank 1, 8 = rank 8) and `col` to the file (1 = a, 8 = h).

For border slots (XR only), `borderSide` indicates the edge: `"t"` = top, `"b"` = bottom, `"l"` = left, `"r"` = right. The `col` field holds the position index along that edge (1--10).

### `LedGroup`

Defines a set of squares that share a single colour, used by `setLedsSpecial`.

```ts
interface LedGroup {
  squares: [number, number][];  // [row, col] pairs, 1-indexed (1-8)
  r: number;                    // Red   0-255
  g: number;                    // Green 0-255
  b: number;                    // Blue  0-255
}
```

### `BoardState`

Represents the 8x8 occupancy state of the board. Constructed internally from the 8 raw bytes returned by the firmware.

| Method / Property | Signature | Description |
|-------------------|-----------|-------------|
| `isOccupied` | `isOccupied(row: number, col: number): boolean` | Check if a square has a piece. Row and col are 1-indexed (1--8). |
| `toMatrix`   | `toMatrix(): boolean[][]` | Returns a plain 8x8 boolean matrix (row-major, 0-indexed). |
| `toString`   | `toString(): string` | Pretty-prints the board with rank 8 at top, using `#` for occupied and `.` for empty. |
| `pieceCount` | `number` (getter) | Total number of occupied squares. |
| `rawBytes`   | `Uint8Array` (getter) | The underlying 8 raw bytes from the firmware. |

### `BorderState`

Represents the 36 border/storage slot positions surrounding the board. **GoChess XR (Robotic) only.**

| Method / Property | Signature | Description |
|-------------------|-----------|-------------|
| `isOccupied`    | `isOccupied(position: string): boolean` | Check if a border position is occupied (e.g. `"a9"`, `"q5"`, `"i0"`). |
| `slots`         | `Record<string, boolean>` (getter) | All 36 position labels mapped to their occupied state. |
| `occupiedCount` | `number` (getter) | Count of occupied border slots. |
| `rawBytes`      | `Uint8Array` (getter) | The underlying 6 raw bytes from the firmware. |
| `toString`      | `toString(): string` | Pretty-prints the border layout. |
| `ALL_POSITIONS` | `static string[]` (getter) | All 36 position labels in canonical order. |

### Helper Functions

```ts
// Convert chess notation to [row, col] (1-indexed)
squareNotationToRC("e4")  // returns [4, 5]
squareNotationToRC("a1")  // returns [1, 1]
squareNotationToRC("h8")  // returns [8, 8]

// Convert [row, col] to chess notation
rcToSquareNotation(4, 5)  // returns "e4"
rcToSquareNotation(1, 1)  // returns "a1"
```

---

## LED Control

### Uniform Colour (Command 0x32)

Set one or more squares to the same colour. The `overwrite` flag controls whether unselected squares are turned off.

```ts
// Light up e2 and e4 in red
await board.setLeds([[2, 5], [4, 5]], 255, 0, 0);

// Add d4 in red without clearing the previous LEDs
await board.setLeds([[4, 4]], 255, 0, 0, false);

// Turn off all LEDs
await board.setLedsOff();
```

### Multi-Colour Groups (Command 0x34)

Set different squares to different colours in a single command.

```ts
await board.setLedsSpecial([
  { squares: [[4, 5], [5, 4]], r: 0,   g: 255, b: 0   },  // green
  { squares: [[2, 3]],         r: 255, g: 0,   b: 0   },  // red
  { squares: [[7, 7], [8, 8]], r: 0,   g: 0,   b: 255 },  // blue
]);
```

### Using Chess Notation

The `setLedsByNotation` method accepts an object keyed by square name:

```ts
await board.setLedsByNotation({
  e2: [0, 255, 0],    // green
  e4: [0, 255, 0],    // green
  d7: [255, 0, 0],    // red
  d5: [255, 0, 0],    // red
});
```

Squares with the same colour are automatically grouped and sent as a single `0x34` command.

---

## Border State (GoChess XR Only)

The GoChess XR (Robotic) board has 36 border/storage slots arranged around the 8x8 playing area. Mini and Lite boards do not have border slots and will not respond to `getBorderState()`.

### Position Label Diagram

```
   q9  a9 b9 c9 d9 e9 f9 g9 h9  i9
   q8                              i8
   q7                              i7
   q6                              i6
   q5        8x8 Board             i5
   q4                              i4
   q3                              i3
   q2                              i2
   q1                              i1
   q0  a0 b0 c0 d0 e0 f0 g0 h0  i0
```

- **Top row (rank 9):** `a9` through `h9`
- **Bottom row (rank 0):** `a0` through `h0`
- **Left column (file "q"):** `q0` through `q9`
- **Right column (file "i"):** `i0` through `i9`
- **Corners:** `q9` (top-left), `i9` (top-right), `q0` (bottom-left), `i0` (bottom-right)

### Example

```ts
const border = await board.getBorderState();

console.log("Occupied slots:", border.occupiedCount, "/ 36");
console.log("Top-left corner (q9):", border.isOccupied("q9"));
console.log("Right side slot 5 (i5):", border.isOccupied("i5"));

// Iterate all positions
for (const [pos, occupied] of Object.entries(border.slots)) {
  if (occupied) console.log(pos, "has a piece");
}
```

### Raw Byte Mapping

The firmware returns 6 bytes:

| Byte | Content | Bits |
|------|---------|------|
| 0    | Top border (rank 9) | bit 0 = a9, bit 1 = b9, ... bit 7 = h9 |
| 1    | Bottom border (rank 0) | bit 0 = a0, bit 1 = b0, ... bit 7 = h0 |
| 2    | Left column (q0--q7) | bit N = qN |
| 3    | Left extension | bit 0 = q8, bit 1 = q9 |
| 4    | Right column (i0--i7) | bit N = iN |
| 5    | Right extension | bit 0 = i8, bit 1 = i9 |

---

## Running the Example

The included `example.html` is a fully self-contained browser demo with a visual board, LED controls, and an event log. No build step or server is required -- just open it in Chrome.

1. Open `example.html` in Chrome, Edge, or Opera.
   - If opening from the filesystem (`file://`), Web Bluetooth may be blocked. Use a local server instead:
     ```bash
     # Python 3
     python -m http.server 8080

     # Node.js (npx, no install)
     npx serve .
     ```
   - Then navigate to `http://localhost:8080/example.html`.
2. Click **Connect** and select your GoChess board from the browser dialog.
3. The demo will automatically read firmware version, battery, and board state.
4. Move pieces on the board to see real-time events in the log and visual updates.
5. Use the LED panels to control per-square colours.

---

## Using with TypeScript

The source file `gochess_sdk.ts` exports all public types and classes. You can import it directly in a TypeScript project.

### Option 1: Direct Import (with a bundler)

```ts
import { GoChessBoard, BoardState, BorderState, squareNotationToRC } from "./gochess_sdk";
import type { PieceEvent, LedGroup } from "./gochess_sdk";

const board = new GoChessBoard();
await board.connect();
```

Requires a bundler (Vite, webpack, esbuild, etc.) or a TypeScript-aware dev server that can handle `.ts` imports.

### Option 2: Compile to JavaScript

```bash
npx tsc gochess_sdk.ts --target ES2020 --module ESNext --outDir dist
```

Then reference `dist/gochess_sdk.js` from your HTML or import it as an ES module.

### Option 3: Inline in HTML

The `example.html` file demonstrates this approach -- the SDK is transpiled by hand into the `<script>` block. This is the simplest option for prototyping and avoids any build tooling.

---

## Supported Boards

| Board | BLE Advertised Name | Border Slots | FW Version Byte |
|-------|---------------------|--------------|-----------------|
| GoChess XR (Robotic) | `GoChessXR_XXXXXX` | 36 slots | `0x04` |
| GoChess Mini         | `GoChessM_XXXXXX`  | None     | `0x03` |
| GoChess Lite         | `GoChessL_XXXXXX`  | None     | `0x03` |

All boards share the same BLE protocol for board state, piece events, battery, and LED control. The `getBorderState()` method and border piece events are exclusive to the XR (Robotic) model.

The default `namePrefix` filter (`"GoChess"`) in `connect()` matches all three board types. To target a specific model, pass a more specific prefix:

```ts
await board.connect("GoChessXR");  // only show XR boards in the picker
await board.connect("GoChessM");   // only show Mini boards
await board.connect("GoChessL");   // only show Lite boards
```

---

## Protocol Overview

Communication uses the **Nordic UART Service (NUS)** over BLE GATT:

| UUID | Role |
|------|------|
| `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | NUS Service |
| `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | RX Characteristic (App writes to board) |
| `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | TX Characteristic (Board notifies app) |

### Command Bytes (App to Board)

| Byte | Command |
|------|---------|
| `0x22` | Get border state |
| `0x32` | Set RGB LEDs (uniform colour) |
| `0x34` | Set RGB LEDs (multi-colour groups) |
| `0x35` | Get board state |
| `0x39` | Check battery |
| `0x76` | Get firmware version |

### Response Types (Board to App)

| Byte | Response |
|------|----------|
| `0x01` | Battery percentage (framed) |
| `0x02` | Battery millivolts (framed) |
| `0x03` | Board state (8 bytes, raw) |
| `0x04` | Current draw (framed) |
| `0x07` | Charging status (framed) |
| `0x0B` | Chamber status (framed) |
| `0x0C` | Border state (6 bytes, raw) |

Framed messages use the format: `[0x2A][length][type][payload...][checksum][\r\n]`.

Piece-move notifications are sent as raw bytes: the first byte is the ASCII row digit (`'1'`--`'8'`) for board moves, or a side letter (`'r'`, `'l'`, `'t'`, `'b'`) for border moves.

---

## Troubleshooting

### "navigator.bluetooth is undefined"

- Web Bluetooth is not supported in this browser. Use Chrome 56+, Edge 79+, or Opera 43+.
- On Linux, you may need to enable `chrome://flags/#enable-web-bluetooth`.
- Firefox and Safari do not support Web Bluetooth.

### "SecurityError: requestDevice requires a secure context"

- The page must be served over **HTTPS** or from **localhost**. Opening the HTML file directly via `file://` may trigger this error.
- Start a local server: `python -m http.server 8080` or `npx serve .`

### "No devices found" or the board does not appear

- Make sure the board is powered on and not connected to another device (phone app, another browser tab).
- Verify Bluetooth is enabled on your computer.
- On macOS, grant Chrome permission to use Bluetooth in System Settings > Privacy & Security > Bluetooth.
- On Linux, ensure the `bluetooth` service is running: `sudo systemctl start bluetooth`.

### "GATT operation failed" or connection drops

- Move the board closer to the computer. BLE range is typically 5--10 meters.
- Disconnect from any other app using the board. BLE GATT allows only one central connection at a time.
- Power-cycle the board (turn it off and on again).
- Try calling `disconnect()` and then `connect()` again.

### Timeout errors on `getBattery()`, `getBoardState()`, etc.

- The default timeout is 5000 ms. If the board is busy or the connection is slow, increase it:
  ```ts
  const battery = await board.getBattery(10000);  // 10-second timeout
  ```
- If `getBorderState()` times out, confirm you are using a GoChess XR board. Mini and Lite boards do not support this command.

### LEDs not changing colour or wrong colour

- The firmware uses GRB colour ordering internally. The SDK handles this conversion. If you see incorrect colours, make sure you are passing RGB values in the standard order `(r, g, b)` to the SDK methods.
- Verify the square coordinates are 1-indexed: `[1, 1]` is a1, `[8, 8]` is h8.

### Piece events not firing

- Events only fire after calling `onPieceMove()` to register a callback.
- Ensure you register the callback **after** `connect()` resolves, or at least before moving pieces.
- Some boards require an initial `getBoardState()` call to begin streaming piece events.
