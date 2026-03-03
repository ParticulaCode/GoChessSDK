/**
 * GoChess TypeScript / JavaScript SDK
 * ====================================
 *
 * A browser SDK for communicating with GoChess smart chess boards via
 * Web Bluetooth (BLE).
 *
 * The GoChess board uses an nRF52832 BLE SoC with the Nordic UART Service
 * (NUS) for bidirectional communication. This SDK provides a high-level API:
 *
 *  - Connect to a GoChess board (browser device-picker)
 *  - Receive real-time piece movement notifications (Hall effect sensors)
 *  - Query battery level, board state, border state, and firmware version
 *  - Control per-square RGB LEDs
 *
 * Requirements:
 *  - A browser that supports Web Bluetooth (Chrome, Edge, Opera)
 *  - Page served over HTTPS or localhost
 *
 * Supported boards:
 *  - GoChess XR (Robotic)  – advertises as "GoChessXR_XXXXXX"
 *  - GoChess Mini           – advertises as "GoChessM_XXXXXX"
 *  - GoChess Lite           – advertises as "GoChessL_XXXXXX"
 *
 * Usage:
 * ```ts
 *   const board = new GoChessBoard();
 *   await board.connect();                           // opens browser picker
 *   board.onPieceMove(evt => console.log(evt));
 *   const battery = await board.getBattery();
 *   await board.setLeds([[1,1],[2,2]], 255, 0, 0);   // red on a1, b2
 *   await board.disconnect();
 * ```
 */

// ═══════════════════════════════════════════════════════════════════════════
// BLE UUIDs – Nordic UART Service (custom base)
// Base: 6E400000-B5A3-F393-E0A9-E50E24DCCA9E
// ═══════════════════════════════════════════════════════════════════════════

const NUS_SERVICE_UUID  = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
const NUS_RX_CHAR_UUID  = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"; // App  → Board (write)
const NUS_TX_CHAR_UUID  = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"; // Board → App  (notify)

// ═══════════════════════════════════════════════════════════════════════════
// Command bytes  (App → Board)
// ═══════════════════════════════════════════════════════════════════════════

const CMD_GET_BORDER_STATE    = 0x22;
const CMD_SET_RGB_LEDS        = 0x32;
const CMD_LED_ON_SPECIAL      = 0x34;
const CMD_GET_BOARD_STATE     = 0x35;
const CMD_CHECK_BATTERY       = 0x39;
const CMD_GET_FW_VERSION      = 0x76;

// ═══════════════════════════════════════════════════════════════════════════
// Response types  (Board → App)
// ═══════════════════════════════════════════════════════════════════════════

const RESP_BATTERY      = 0x01;
const RESP_BATTERY_MV   = 0x02;
const RESP_BOARD_STATE  = 0x03;
const RESP_CURRENT      = 0x04;
const RESP_CHARGING     = 0x07;
const RESP_CHAMBER      = 0x0B;
const RESP_BORDER_STATE = 0x0C;

const START_BYTE = 0x2A; // '*'

// ═══════════════════════════════════════════════════════════════════════════
// Data types
// ═══════════════════════════════════════════════════════════════════════════

/** A piece-movement event from the Hall-effect sensors. */
export interface PieceEvent {
    /** Row 1-8 for board squares, 0 for border slots */
    row: number;
    /** Col 1-8 for board squares, position 1-10 for border slots */
    col: number;
    /** true = piece placed on square, false = piece lifted */
    isDown: boolean;
    /** true if this is a border/storage slot (GoChess Robotic) */
    isBorder: boolean;
    /** 'r' | 'l' | 't' | 'b' for border, '' for board squares */
    borderSide: string;
}

/** A colour group for the special LED command (0x34). */
export interface LedGroup {
    /** List of [row, col] pairs (1-indexed, 1-8) */
    squares: [number, number][];
    /** Red 0-255 */
    r: number;
    /** Green 0-255 */
    g: number;
    /** Blue 0-255 */
    b: number;
}

// ═══════════════════════════════════════════════════════════════════════════
// BoardState
// ═══════════════════════════════════════════════════════════════════════════

/** 8×8 board occupancy state (row/col 1-indexed, 1-8). */
export class BoardState {
    private raw: Uint8Array;

    constructor(raw: Uint8Array) {
        if (raw.length !== 8) throw new Error(`Expected 8 bytes, got ${raw.length}`);
        this.raw = new Uint8Array(raw);
    }

    /** Check if a square has a piece.  row, col are 1-indexed (1-8). */
    isOccupied(row: number, col: number): boolean {
        if (row < 1 || row > 8 || col < 1 || col > 8)
            throw new RangeError(`row and col must be 1-8, got (${row}, ${col})`);
        return (this.raw[row - 1] & (1 << (col - 1))) !== 0;
    }

    /** Return a plain 8×8 boolean matrix (row-major, 0-indexed). */
    toMatrix(): boolean[][] {
        const m: boolean[][] = [];
        for (let r = 1; r <= 8; r++) {
            const row: boolean[] = [];
            for (let c = 1; c <= 8; c++) row.push(this.isOccupied(r, c));
            m.push(row);
        }
        return m;
    }

    /** Pretty-print the board (rank 8 at top). */
    toString(): string {
        const lines: string[] = ["  a b c d e f g h"];
        for (let r = 8; r >= 1; r--) {
            let line = `${r} `;
            for (let c = 1; c <= 8; c++)
                line += (this.isOccupied(r, c) ? "■" : "·") + " ";
            lines.push(line);
        }
        return lines.join("\n");
    }

    /** Count of occupied squares. */
    get pieceCount(): number {
        let n = 0;
        for (let r = 1; r <= 8; r++)
            for (let c = 1; c <= 8; c++)
                if (this.isOccupied(r, c)) n++;
        return n;
    }

    /** The underlying 8 raw bytes. */
    get rawBytes(): Uint8Array { return this.raw; }
}

// ═══════════════════════════════════════════════════════════════════════════
// BorderState
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Parsed border/storage slot occupancy state (36 positions surrounding the board).
 *
 * Position labels:
 *   - Top row (rank 9):    a9, b9, c9, d9, e9, f9, g9, h9
 *   - Bottom row (rank 0): a0, b0, c0, d0, e0, f0, g0, h0
 *   - Left col (file "q"): q0, q1, q2, q3, q4, q5, q6, q7, q8, q9
 *   - Right col (file "i"): i0, i1, i2, i3, i4, i5, i6, i7, i8, i9
 *   - Corners: q9 = top-left, i9 = top-right, q0 = bottom-left, i0 = bottom-right
 *
 * Raw byte mapping (6 bytes after the 0x0C message-type byte):
 *   byte 0 – Top border:    bits 0-3 = a9,b9,c9,d9   bits 4-7 = e9,f9,g9,h9
 *   byte 1 – Bottom border: bits 0-3 = a0,b0,c0,d0   bits 4-7 = e0,f0,g0,h0
 *   byte 2 – Left column:   bit N = q(N)  for N = 0..7  (bit 0=q0 .. bit 7=q7)
 *   byte 3 – Left extension: bit 0 = q8, bit 1 = q9
 *   byte 4 – Right column:  bit N = i(N)  for N = 0..7  (bit 0=i0 .. bit 7=i7)
 *   byte 5 – Right extension: bit 0 = i8, bit 1 = i9
 */
export class BorderState {
    private _slots: Record<string, boolean>;
    private _raw: Uint8Array;

    constructor(raw: Uint8Array) {
        if (raw.length !== 6) throw new Error(`Expected 6 bytes, got ${raw.length}`);
        this._raw = new Uint8Array(raw);
        this._slots = {};
        this.parse();
    }

    /** Check if a border position is occupied. */
    isOccupied(position: string): boolean {
        return this._slots[position] ?? false;
    }

    /** All 36 position labels mapped to occupied state. */
    get slots(): Record<string, boolean> { return { ...this._slots }; }

    /** Count of occupied border slots. */
    get occupiedCount(): number {
        return Object.values(this._slots).filter(v => v).length;
    }

    /** The underlying 6 raw bytes. */
    get rawBytes(): Uint8Array { return this._raw; }

    /** All 36 position labels in order. */
    static get ALL_POSITIONS(): string[] {
        const files = ["a", "b", "c", "d", "e", "f", "g", "h"];
        return [
            ...files.map(f => f + "9"),
            ...files.map(f => f + "0"),
            ...Array.from({ length: 10 }, (_, i) => "q" + i),
            ...Array.from({ length: 10 }, (_, i) => "i" + i),
        ];
    }

    private parse(): void {
        const raw = this._raw;
        const files = ["a", "b", "c", "d", "e", "f", "g", "h"];

        // Byte 0: Top border (rank 9) – bit N → file[N] + "9"
        for (let i = 0; i < 8; i++)
            this._slots[files[i] + "9"] = !!(raw[0] & (1 << i));

        // Byte 1: Bottom border (rank 0) – bit N → file[N] + "0"
        for (let i = 0; i < 8; i++)
            this._slots[files[i] + "0"] = !!(raw[1] & (1 << i));

        // Byte 2: Left column (file "q") – bit N → "q" + N  (N = 0..7)
        for (let i = 0; i < 8; i++)
            this._slots["q" + i] = !!(raw[2] & (1 << i));

        // Byte 3: Left column extension – bit 0 → q8, bit 1 → q9
        this._slots["q8"] = !!(raw[3] & 0x01);
        this._slots["q9"] = !!(raw[3] & 0x02);

        // Byte 4: Right column (file "i") – bit N → "i" + N  (N = 0..7)
        for (let i = 0; i < 8; i++)
            this._slots["i" + i] = !!(raw[4] & (1 << i));

        // Byte 5: Right column extension – bit 0 → i8, bit 1 → i9
        this._slots["i8"] = !!(raw[5] & 0x01);
        this._slots["i9"] = !!(raw[5] & 0x02);
    }

    /** Pretty-print the border (occupied = ■, empty = ·). */
    toString(): string {
        const files = ["a", "b", "c", "d", "e", "f", "g", "h"];
        const c = (pos: string) => this.isOccupied(pos) ? "■" : "·";
        const lines: string[] = [];
        // Top row
        lines.push(`${c("q9")} ${files.map(f => c(f + "9")).join(" ")} ${c("i9")}`);
        // Side rows 8→1
        for (let r = 8; r >= 1; r--)
            lines.push(`${c("q" + r)} ${"· ".repeat(8).trim()} ${c("i" + r)}`);
        // Bottom row
        lines.push(`${c("q0")} ${files.map(f => c(f + "0")).join(" ")} ${c("i0")}`);
        return lines.join("\n");
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════════

/** Build LED bitmasks from a list of [row, col] pairs (1-indexed). */
function buildLedMasks(squares: [number, number][]): [number, number] {
    let mask1 = 0; // rows 1-4
    let mask2 = 0; // rows 5-8
    for (const [row, col] of squares) {
        if (row < 1 || row > 8 || col < 1 || col > 8)
            throw new RangeError(`Square (${row}, ${col}) out of range 1-8.`);
        if (row <= 4)
            mask1 |= 1 << ((row - 1) * 8 + (col - 1));
        else
            mask2 |= 1 << ((row - 5) * 8 + (col - 1));
    }
    return [mask1, mask2];
}

/**
 * Write a 32-bit integer as 4 little-endian bytes into `buf` at `offset`.
 * JavaScript bitwise ops are signed 32-bit, so we use `>>> 0` for safety.
 */
function writeLE32(buf: Uint8Array, offset: number, val: number): void {
    const v = val >>> 0;
    buf[offset]     = v & 0xFF;
    buf[offset + 1] = (v >>> 8) & 0xFF;
    buf[offset + 2] = (v >>> 16) & 0xFF;
    buf[offset + 3] = (v >>> 24) & 0xFF;
}

/** Convert chess notation like "e4" to [row, col] (1-indexed). */
export function squareNotationToRC(notation: string): [number, number] {
    if (notation.length !== 2) throw new Error(`Invalid notation: ${notation}`);
    const file = notation[0].toLowerCase();
    const rank = notation[1];
    if (file < "a" || file > "h") throw new Error(`Invalid file: ${file}`);
    if (rank < "1" || rank > "8") throw new Error(`Invalid rank: ${rank}`);
    return [parseInt(rank), file.charCodeAt(0) - "a".charCodeAt(0) + 1];
}

/** Convert [row, col] (1-indexed) to chess notation like "e4". */
export function rcToSquareNotation(row: number, col: number): string {
    return String.fromCharCode("a".charCodeAt(0) + col - 1) + row;
}

// ═══════════════════════════════════════════════════════════════════════════
// Pending-response helper
// ═══════════════════════════════════════════════════════════════════════════

interface Pending<T> {
    resolve: (value: T) => void;
    reject:  (reason?: any) => void;
    timer:   ReturnType<typeof setTimeout>;
}

// ═══════════════════════════════════════════════════════════════════════════
// Main SDK class
// ═══════════════════════════════════════════════════════════════════════════

export class GoChessBoard {
    private device:   BluetoothDevice | null = null;
    private server:   BluetoothRemoteGATTServer | null = null;
    private rxChar:   BluetoothRemoteGATTCharacteristic | null = null;
    private txChar:   BluetoothRemoteGATTCharacteristic | null = null;
    private _connected = false;

    private pieceCallbacks:  ((evt: PieceEvent) => void)[] = [];
    private rawCallbacks:    ((data: Uint8Array) => void)[] = [];
    private pending = new Map<string, Pending<any>>();

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    /**
     * Open the browser's Bluetooth device picker filtered to GoChess boards
     * and connect.
     *
     * @param namePrefix  Optional name prefix filter (default "GoChess").
     */
    async connect(namePrefix = "GoChess"): Promise<string> {
        const device = await navigator.bluetooth.requestDevice({
            filters: [{ namePrefix }],
            optionalServices: [NUS_SERVICE_UUID],
        });

        this.device = device;
        const server = await device.gatt!.connect();
        this.server = server;

        const service = await server.getPrimaryService(NUS_SERVICE_UUID);
        this.rxChar   = await service.getCharacteristic(NUS_RX_CHAR_UUID);
        this.txChar   = await service.getCharacteristic(NUS_TX_CHAR_UUID);

        await this.txChar.startNotifications();
        this.txChar.addEventListener(
            "characteristicvaluechanged",
            this.onNotify.bind(this),
        );

        device.addEventListener("gattserverdisconnected", () => {
            this._connected = false;
        });

        this._connected = true;
        return device.name ?? device.id;
    }

    /** Disconnect from the board. */
    async disconnect(): Promise<void> {
        if (this.txChar) {
            try { await this.txChar.stopNotifications(); } catch { /* ignore */ }
        }
        if (this.server?.connected) this.server.disconnect();
        this._connected = false;
        this.device = null;
        this.server = null;
        this.rxChar = null;
        this.txChar = null;
        // Cancel all pending
        for (const p of this.pending.values()) {
            clearTimeout(p.timer);
            p.reject(new Error("Disconnected"));
        }
        this.pending.clear();
    }

    get isConnected(): boolean { return this._connected; }

    get deviceName(): string { return this.device?.name ?? ""; }

    // ------------------------------------------------------------------
    // Callbacks
    // ------------------------------------------------------------------

    /** Register a callback for piece lift / place events. */
    onPieceMove(cb: (evt: PieceEvent) => void): void {
        this.pieceCallbacks.push(cb);
    }

    /** Register a callback for every raw BLE notification (debugging). */
    onRawNotification(cb: (data: Uint8Array) => void): void {
        this.rawCallbacks.push(cb);
    }

    // ------------------------------------------------------------------
    // Commands  (App → Board)
    // ------------------------------------------------------------------

    /** Get battery percentage (0-100). */
    async getBattery(timeoutMs = 5000): Promise<number> {
        const p = this.expect<number>("battery", timeoutMs);
        await this.write(new Uint8Array([CMD_CHECK_BATTERY]));
        return p;
    }

    /** Get the full 8×8 board occupancy. */
    async getBoardState(timeoutMs = 5000): Promise<BoardState> {
        const p = this.expect<BoardState>("board_state", timeoutMs);
        await this.write(new Uint8Array([CMD_GET_BOARD_STATE]));
        return p;
    }

    /**
     * Get border/storage slot occupancy (36 positions around the board).
     *
     * **GoChess XR (Robotic) only** – Mini and Lite boards do not have
     * border slots and will not respond to this command.
     */
    async getBorderState(timeoutMs = 5000): Promise<BorderState> {
        const p = this.expect<BorderState>("border_state", timeoutMs);
        await this.write(new Uint8Array([CMD_GET_BORDER_STATE]));
        return p;
    }

    /** Get firmware version byte (0x04 = Robotic, 0x03 = Mini/Lite). */
    async getFwVersion(timeoutMs = 5000): Promise<number> {
        const p = this.expect<number>("fw_version", timeoutMs);
        await this.write(new Uint8Array([CMD_GET_FW_VERSION]));
        return p;
    }

    /**
     * Set LEDs for specific squares with a uniform colour (command 0x32).
     *
     * @param squares    Array of [row, col] pairs (1-indexed, 1-8).
     *                   Empty array + overwrite=true turns all LEDs off.
     * @param r          Red   0-255
     * @param g          Green 0-255
     * @param b          Blue  0-255
     * @param overwrite  true = unselected squares turn off; false = keep current.
     */
    async setLeds(
        squares: [number, number][],
        r = 0, g = 0, b = 0,
        overwrite = true,
    ): Promise<void> {
        const [maskLed, maskLed2] = buildLedMasks(squares);

        const data = new Uint8Array(13);
        data[0] = CMD_SET_RGB_LEDS;
        // Send raw masks as LE32 – firmware's reverse_num does the rest.
        writeLE32(data, 1, maskLed);
        writeLE32(data, 5, maskLed2);
        // GRB colour swap: data[9] drives physical RED, data[10] drives GREEN.
        data[9]  = r & 0xFF;
        data[10] = g & 0xFF;
        data[11] = b & 0xFF;
        data[12] = overwrite ? 0x01 : 0x00;

        await this.write(data);
    }

    /** Turn off all LEDs. */
    async setLedsOff(): Promise<void> {
        await this.setLeds([], 0, 0, 0, true);
    }

    /**
     * Set per-square LED colours with multiple colour groups (command 0x34).
     * This clears all LEDs first, then applies each group.
     *
     * ```ts
     * await board.setLedsSpecial([
     *     { squares: [[4,5],[5,4]], r: 0,   g: 255, b: 0   },
     *     { squares: [[2,3]],       r: 255, g: 0,   b: 0   },
     * ]);
     * ```
     */
    async setLedsSpecial(groups: LedGroup[]): Promise<void> {
        const parts: number[] = [CMD_LED_ON_SPECIAL];

        for (const grp of groups) {
            parts.push(grp.squares.length);
            for (const [row, col] of grp.squares) {
                if (row < 1 || row > 8 || col < 1 || col > 8)
                    throw new RangeError(`Square (${row}, ${col}) out of range 1-8.`);
                parts.push((row << 4) | col);
            }
            // GRB colour swap (same as setLeds)
            parts.push(grp.r & 0xFF);
            parts.push(grp.g & 0xFF);
            parts.push(grp.b & 0xFF);
        }

        await this.write(new Uint8Array(parts));
    }

    /**
     * Convenience: set LEDs using chess notation.
     *
     * ```ts
     * await board.setLedsByNotation({ e2: [0,255,0], e4: [0,255,0] });
     * ```
     */
    async setLedsByNotation(
        squareColors: Record<string, [number, number, number]>,
    ): Promise<void> {
        // Group squares by colour
        const colourMap = new Map<string, { squares: [number, number][]; r: number; g: number; b: number }>();
        for (const [notation, [r, g, b]] of Object.entries(squareColors)) {
            const rc = squareNotationToRC(notation);
            const key = `${r},${g},${b}`;
            if (!colourMap.has(key))
                colourMap.set(key, { squares: [], r, g, b });
            colourMap.get(key)!.squares.push(rc);
        }
        await this.setLedsSpecial([...colourMap.values()]);
    }

    // ------------------------------------------------------------------
    // Internal: write
    // ------------------------------------------------------------------

    private async write(data: Uint8Array): Promise<void> {
        if (!this.rxChar || !this._connected)
            throw new Error("Not connected to a GoChess board.");
        await this.rxChar.writeValueWithoutResponse(data);
    }

    // ------------------------------------------------------------------
    // Internal: pending response helpers
    // ------------------------------------------------------------------

    private expect<T>(key: string, timeoutMs: number): Promise<T> {
        return new Promise<T>((resolve, reject) => {
            const timer = setTimeout(() => {
                this.pending.delete(key);
                reject(new Error(`Timeout waiting for "${key}" response`));
            }, timeoutMs);
            this.pending.set(key, { resolve, reject, timer });
        });
    }

    private resolve(key: string, value: any): void {
        const p = this.pending.get(key);
        if (p) {
            this.pending.delete(key);
            clearTimeout(p.timer);
            p.resolve(value);
        }
    }

    // ------------------------------------------------------------------
    // Notification handler
    // ------------------------------------------------------------------

    private onNotify(event: Event): void {
        const char = event.target as BluetoothRemoteGATTCharacteristic;
        const dv = char.value;
        if (!dv || dv.byteLength === 0) return;

        const data = new Uint8Array(dv.buffer, dv.byteOffset, dv.byteLength);

        // Raw callbacks
        for (const cb of this.rawCallbacks) {
            try { cb(data); } catch { /* swallow */ }
        }

        const first = data[0];

        // --- Framed message: [*][len][type][payload...][checksum][\r\n] ---
        if (first === START_BYTE && data.length >= 5) {
            this.parseFramed(data);
        }
        // --- Raw: Board state [0x03][8 bytes] ---
        else if (first === RESP_BOARD_STATE && data.length >= 9) {
            this.resolve("board_state", new BoardState(data.slice(1, 9)));
        }
        // --- Raw: Border state [0x0C][6 bytes] ---
        else if (first === RESP_BORDER_STATE && data.length >= 7) {
            this.resolve("border_state", new BorderState(data.slice(1, 7)));
        }
        // --- Raw: FW version "Ver" + byte ---
        else if (first === 0x56 && data.length >= 4 && data[1] === 0x65 && data[2] === 0x72) {
            this.resolve("fw_version", data[3]);
        }
        // --- Raw: Piece move on board ('1'-'8') ---
        else if (first >= 0x31 && first <= 0x38 && data.length >= 3) {
            this.emitBoardMove(data);
        }
        // --- Raw: Piece move on border ('r','l','t','b') ---
        else if ("rltb".includes(String.fromCharCode(first)) && data.length >= 3) {
            this.emitBorderMove(data);
        }
    }

    private parseFramed(data: Uint8Array): void {
        const msgType = data[2];

        if (msgType === RESP_BATTERY && data.length >= 4) {
            this.resolve("battery", data[3]);
        } else if (msgType === RESP_BATTERY_MV && data.length >= 5) {
            this.resolve("battery_mv", (data[3] << 8) | data[4]);
        } else if (msgType === RESP_CHARGING && data.length >= 4) {
            this.resolve("charging", data[3] !== 0);
        } else if (msgType === RESP_CURRENT && data.length >= 5) {
            this.resolve("current", (data[3] << 8) | data[4]);
        } else if (msgType === RESP_CHAMBER && data.length >= 4) {
            this.resolve("chamber", data[3] !== 0);
        }
    }

    private emitBoardMove(data: Uint8Array): void {
        const evt: PieceEvent = {
            row:        data[0] - 0x30,
            col:        data[1] - 0x30,
            isDown:     data[2] === 0x64, // 'd'
            isBorder:   false,
            borderSide: "",
        };
        this.dispatchPiece(evt);
    }

    private emitBorderMove(data: Uint8Array): void {
        const side = String.fromCharCode(data[0]);
        let position: number;
        let isDown: boolean;

        if (data.length >= 4 && data[1] === 0x31 && data[2] === 0x30) {
            position = 10;
            isDown = data[3] === 0x64;
        } else {
            position = data[1] - 0x30;
            isDown = data[2] === 0x64;
        }

        this.dispatchPiece({
            row: 0, col: position, isDown,
            isBorder: true, borderSide: side,
        });
    }

    private dispatchPiece(evt: PieceEvent): void {
        for (const cb of this.pieceCallbacks) {
            try { cb(evt); } catch { /* swallow */ }
        }
    }
}
