"""
GoChess Python SDK
==================

A Python SDK for communicating with GoChess smart chess boards via BLE.

The GoChess board uses an nRF52832 BLE SoC with the Nordic UART Service (NUS)
for bidirectional communication. This SDK provides a high-level API for:

- Scanning and connecting to GoChess boards
- Receiving real-time piece movement notifications (via Hall effect sensors)
- Querying battery level, board state, border state, and firmware version
- Controlling per-square RGB LEDs

Requirements:
    pip install bleak

Supported boards:
    - GoChess XR (Robotic) - advertises as "GoChessXR_XXXXXX"
    - GoChess Mini          - advertises as "GoChessM_XXXXXX"
    - GoChess Lite          - advertises as "GoChessL_XXXXXX"

Protocol notes:
    The board uses two message formats over the NUS TX characteristic:

    1. Raw messages (no framing):
       - Piece move notifications: 3-4 ASCII bytes  e.g. "81d"
       - Board state (0x03):  [0x03][8 bytes]
       - Border state (0x0C): [0x0C][6 bytes]
       - FW version:          "Ver" + version_byte

    2. Framed messages:
       [START][LEN][TYPE][DATA...][CHECKSUM][CR][LF]
       - START = 0x2A ('*')
       - LEN   = total bytes from START through CHECKSUM (inclusive)
       - CHECKSUM = sum of all bytes from START through DATA
       - CR LF = 0x0D 0x0A
       Used for: battery, charging state, etc.
"""

import asyncio
import logging
from dataclasses import dataclass, field
from enum import IntEnum
from typing import Callable, Optional, List, Dict, Any, Union

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice

logger = logging.getLogger("gochess")

# ---------------------------------------------------------------------------
# BLE UUIDs – Nordic UART Service (custom base)
# Base: 6E400000-B5A3-F393-E0A9-E50E24DCCA9E
# ---------------------------------------------------------------------------
NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
NUS_RX_CHAR_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # App  → Board (write)
NUS_TX_CHAR_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # Board → App  (notify)

# ---------------------------------------------------------------------------
# Command bytes  (App → Board)
# ---------------------------------------------------------------------------
CMD_GET_BORDER_STATE       = 0x22
CMD_SET_RGB_LEDS           = 0x32  # 12-byte payload: mask1(4) mask2(4) G R B overwrite
CMD_LED_ON_SENSOR          = 0x33  # 1 byte: 0x00/0x01
CMD_LED_ON_SPECIAL         = 0x34  # variable: per-square color groups
CMD_GET_BOARD_STATE        = 0x35
CMD_SET_LEDS_MODE          = 0x36  # 1 byte: mode 0/1/2
CMD_PATTERN_LED_ON         = 0x37
CMD_PATTERN2_LED_ON        = 0x38
CMD_CHECK_BATTERY          = 0x39
CMD_CHECK_BATTERY_MV       = 0x3A
CMD_INT_MASK               = 0x3B  # 8 bytes: interrupt mask
CMD_MEASURE_CURRENT        = 0x3C
CMD_GET_CHARGING_STATE     = 0x3D
CMD_COMPLEX_LED_MESSAGE    = 0x3E
CMD_COMPLEX_LED_PARTS      = 0x3F
CMD_GET_FW_VERSION         = 0x76  # 'v'

# ---------------------------------------------------------------------------
# Response message types  (Board → App)
# ---------------------------------------------------------------------------
RESP_BATTERY       = 0x01  # framed – 1 byte payload (percentage)
RESP_BATTERY_MV    = 0x02  # framed – 2 bytes (mV big-endian)
RESP_BOARD_STATE   = 0x03  # raw    – 8 bytes (bitfield per row)
RESP_CURRENT       = 0x04  # framed – 2 bytes (µA big-endian)
RESP_CHARGING      = 0x07  # framed – 1 byte  (0/1)
RESP_CHAMBER       = 0x0B  # framed – 1 byte  (0/1)
RESP_BORDER_STATE  = 0x0C  # raw    – 6 bytes

# Protocol constants
START_BYTE = 0x2A  # '*'


# ═══════════════════════════════════════════════════════════════════════════
# Data classes
# ═══════════════════════════════════════════════════════════════════════════

@dataclass
class GoChessDevice:
    """A discovered GoChess board."""
    index: int
    name: str
    address: str
    _ble_device: BLEDevice = field(repr=False)


@dataclass
class PieceEvent:
    """A piece-movement event from the Hall-effect sensors.

    For board squares:
        row  = 1-8,  col = 1-8,  is_border = False
    For border/storage slots (GoChess Robotic):
        border_side = 'r'|'l'|'t'|'b',  position = 1-10,  is_border = True
    """
    row: int              # 1-8 for board squares, 0 for border
    col: int              # 1-8 for board squares, position for border
    is_down: bool         # True = piece placed on square, False = lifted
    is_border: bool       # True if this is a border storage slot
    border_side: str      # 'r', 'l', 't', 'b' or '' for board squares


class BoardState:
    """8×8 board occupancy state.

    Each square is True (piece present) or False (empty).
    Indexed 1-8 for both row and column matching chess convention:
      - Row 1 = White's back rank, Row 8 = Black's back rank
      - Col 1 = a-file, Col 8 = h-file

    Usage::

        state = await board.get_board_state()
        if state.is_occupied(1, 5):   # e1
            print("King is home")
        print(state)                  # pretty-print
    """

    def __init__(self, raw: bytes):
        if len(raw) != 8:
            raise ValueError(f"Expected 8 bytes, got {len(raw)}")
        self._raw = raw

    def is_occupied(self, row: int, col: int) -> bool:
        """Check if a square has a piece.  row, col are 1-indexed (1-8)."""
        if not (1 <= row <= 8 and 1 <= col <= 8):
            raise IndexError(f"row and col must be 1-8, got ({row}, {col})")
        return bool(self._raw[row - 1] & (1 << (col - 1)))

    def to_matrix(self) -> List[List[bool]]:
        """Return 8×8 list-of-lists (row-major, 1-indexed internally)."""
        return [
            [self.is_occupied(r, c) for c in range(1, 9)]
            for r in range(1, 9)
        ]

    @property
    def raw_bytes(self) -> bytes:
        return self._raw

    def __str__(self) -> str:
        cols = "  a b c d e f g h"
        lines = [cols]
        for row in range(8, 0, -1):
            cells = " ".join(
                "■" if self.is_occupied(row, c) else "·" for c in range(1, 9)
            )
            lines.append(f"{row} {cells}")
        return "\n".join(lines)

    def __repr__(self) -> str:
        occupied = sum(
            1 for r in range(1, 9) for c in range(1, 9) if self.is_occupied(r, c)
        )
        return f"<BoardState pieces={occupied}/64>"


class BorderState:
    """Border/storage slot occupancy state (36 positions surrounding the board).

    Position labels:
      - Top row (rank 9):    a9, b9, c9, d9, e9, f9, g9, h9
      - Bottom row (rank 0): a0, b0, c0, d0, e0, f0, g0, h0
      - Left col (file "q"): q0, q1, q2, q3, q4, q5, q6, q7, q8, q9
      - Right col (file "i"): i0, i1, i2, i3, i4, i5, i6, i7, i8, i9
      - Corners: q9 = top-left, i9 = top-right, q0 = bottom-left, i0 = bottom-right

    Raw byte mapping (6 bytes after the 0x0C message-type byte):
      byte 0 – Top border:    bits 0-3 = a9,b9,c9,d9   bits 4-7 = e9,f9,g9,h9
      byte 1 – Bottom border: bits 0-3 = a0,b0,c0,d0   bits 4-7 = e0,f0,g0,h0
      byte 2 – Left column:   bit N = q(N)  for N = 0..7  (bit 0=q0 .. bit 7=q7)
      byte 3 – Left extension: bit 0 = q8, bit 1 = q9
      byte 4 – Right column:  bit N = i(N)  for N = 0..7  (bit 0=i0 .. bit 7=i7)
      byte 5 – Right extension: bit 0 = i8, bit 1 = i9

    Usage::

        border = await board.get_border_state()
        if border.is_occupied("a9"):
            print("Piece on top border a9")
        print(border)
    """

    _FILES = ["a", "b", "c", "d", "e", "f", "g", "h"]

    def __init__(self, raw: bytes):
        if len(raw) != 6:
            raise ValueError(f"Expected 6 bytes, got {len(raw)}")
        self._raw = raw
        self._slots: Dict[str, bool] = {}
        self._parse()

    def _parse(self) -> None:
        raw = self._raw
        # Byte 0: Top border (rank 9) – bit N → file[N] + "9"
        for i in range(8):
            self._slots[self._FILES[i] + "9"] = bool(raw[0] & (1 << i))
        # Byte 1: Bottom border (rank 0) – bit N → file[N] + "0"
        for i in range(8):
            self._slots[self._FILES[i] + "0"] = bool(raw[1] & (1 << i))
        # Byte 2: Left column (file "q") – bit N → "q" + str(N)  (N=0..7)
        for i in range(8):
            self._slots[f"q{i}"] = bool(raw[2] & (1 << i))
        # Byte 3: Left extension – bit 0 → q8, bit 1 → q9
        self._slots["q8"] = bool(raw[3] & 0x01)
        self._slots["q9"] = bool(raw[3] & 0x02)
        # Byte 4: Right column (file "i") – bit N → "i" + str(N)  (N=0..7)
        for i in range(8):
            self._slots[f"i{i}"] = bool(raw[4] & (1 << i))
        # Byte 5: Right extension – bit 0 → i8, bit 1 → i9
        self._slots["i8"] = bool(raw[5] & 0x01)
        self._slots["i9"] = bool(raw[5] & 0x02)

    def is_occupied(self, position: str) -> bool:
        """Check if a border position is occupied.

        Args:
            position: Label like ``"a9"``, ``"q0"``, ``"i5"``, etc.
        """
        return self._slots.get(position, False)

    @property
    def slots(self) -> Dict[str, bool]:
        """All 36 position labels mapped to occupied state."""
        return dict(self._slots)

    @property
    def occupied_count(self) -> int:
        """Count of occupied border slots."""
        return sum(1 for v in self._slots.values() if v)

    @property
    def raw_bytes(self) -> bytes:
        return self._raw

    def __str__(self) -> str:
        c = lambda pos: "■" if self.is_occupied(pos) else "·"
        lines = []
        # Top row
        top = f"{c('q9')} " + " ".join(c(f + "9") for f in self._FILES) + f" {c('i9')}"
        lines.append(top)
        # Side rows 8→1
        for r in range(8, 0, -1):
            lines.append(f"{c(f'q{r}')} {'· ' * 8}  {c(f'i{r}')}")
        # Bottom row
        bot = f"{c('q0')} " + " ".join(c(f + "0") for f in self._FILES) + f" {c('i0')}"
        lines.append(bot)
        return "\n".join(lines)

    def __repr__(self) -> str:
        return f"<BorderState occupied={self.occupied_count}/36>"


# ═══════════════════════════════════════════════════════════════════════════
# Helpers
# ═══════════════════════════════════════════════════════════════════════════

_NIBBLE_REVERSE = [
    0x0, 0x8, 0x4, 0xC, 0x2, 0xA, 0x6, 0xE,
    0x1, 0x9, 0x5, 0xD, 0x3, 0xB, 0x7, 0xF,
]


def _reverse_bits_byte(b: int) -> int:
    """Reverse the bit order of a single byte."""
    return (_NIBBLE_REVERSE[b & 0x0F] << 4) | _NIBBLE_REVERSE[b >> 4]


def _reverse_num(val: int) -> int:
    """Full 32-bit reversal (swap bytes + reverse bits within each byte).

    Mirrors the firmware's ``reverse_num()`` function.
    """
    b0 = val & 0xFF
    b1 = (val >> 8) & 0xFF
    b2 = (val >> 16) & 0xFF
    b3 = (val >> 24) & 0xFF
    return (
        _reverse_bits_byte(b3)
        | (_reverse_bits_byte(b2) << 8)
        | (_reverse_bits_byte(b1) << 16)
        | (_reverse_bits_byte(b0) << 24)
    )


def _build_led_masks(squares: List[tuple]) -> tuple:
    """Convert a list of ``(row, col)`` (1-indexed) to firmware LED bitmasks.

    Returns ``(mask_rows1to4, mask_rows5to8)``.
    """
    mask1 = 0  # rows 1-4 (LED indices 0-31)
    mask2 = 0  # rows 5-8 (LED indices 32-63)
    for row, col in squares:
        if not (1 <= row <= 8 and 1 <= col <= 8):
            raise ValueError(f"Square ({row}, {col}) out of range 1-8.")
        if row <= 4:
            mask1 |= 1 << ((row - 1) * 8 + (col - 1))
        else:
            mask2 |= 1 << ((row - 5) * 8 + (col - 1))
    return mask1, mask2


def _encode_led_masks_to_bytes(mask_led: int, mask_led2: int) -> bytes:
    """Encode LED masks into the 8 data bytes expected by the 0x32 command.

    The firmware decodes the bytes as follows::

        tmp  = LE32(data[1..4])  →  g_mask_led2 = reverse_num(tmp)   # rows 5-8
        tmp2 = LE32(data[5..8])  →  g_mask_led  = reverse_num(tmp2)  # rows 1-4

    The 0x34 path (which works correctly) builds the same logical mask and
    then calls ``setLedsRGB_I2C(reverse_num(mask2), reverse_num(mask1), ...)``.
    For the 0x32 path to match, we send the raw masks directly so that the
    firmware's single ``reverse_num`` produces the same result::

        data[1..4] = LE32(mask_led)    →  reverse_num  →  correct rows-1-4 bits
        data[5..8] = LE32(mask_led2)   →  reverse_num  →  correct rows-5-8 bits
    """
    return (
        mask_led.to_bytes(4, "little")
        + mask_led2.to_bytes(4, "little")
    )


def square_notation_to_rc(notation: str) -> tuple:
    """Convert chess notation like ``"e4"`` to ``(row, col)`` tuple.

    Returns 1-indexed (row, col).
    """
    if len(notation) != 2:
        raise ValueError(f"Invalid notation: {notation}")
    file_char = notation[0].lower()
    rank_char = notation[1]
    if file_char < "a" or file_char > "h":
        raise ValueError(f"Invalid file: {file_char}")
    if rank_char < "1" or rank_char > "8":
        raise ValueError(f"Invalid rank: {rank_char}")
    col = ord(file_char) - ord("a") + 1
    row = int(rank_char)
    return (row, col)


def rc_to_square_notation(row: int, col: int) -> str:
    """Convert ``(row, col)`` (1-indexed) to chess notation like ``"e4"``."""
    return chr(ord("a") + col - 1) + str(row)


# ═══════════════════════════════════════════════════════════════════════════
# Main SDK class
# ═══════════════════════════════════════════════════════════════════════════

class GoChessBoard:
    """High-level interface to a GoChess smart chess board.

    Usage::

        board = GoChessBoard()
        devices = await GoChessBoard.scan()
        await board.connect(devices[0])
        board.on_piece_move(lambda evt: print(evt))
        battery = await board.get_battery()
        await board.disconnect()

    Or as an async context manager::

        async with GoChessBoard() as board:
            devices = await GoChessBoard.scan()
            await board.connect(devices[0])
            ...
    """

    def __init__(self):
        self._client: Optional[BleakClient] = None
        self._connected: bool = False
        self._piece_callbacks: List[Callable[[PieceEvent], None]] = []
        self._raw_callbacks: List[Callable[[bytes], None]] = []
        self._pending: Dict[str, asyncio.Future] = {}

    # ------------------------------------------------------------------
    # Scanning
    # ------------------------------------------------------------------

    @staticmethod
    async def scan(timeout: float = 5.0) -> List[GoChessDevice]:
        """Scan for nearby GoChess boards.

        Args:
            timeout: How long to scan (seconds).

        Returns:
            A list of :class:`GoChessDevice` objects sorted by signal strength.
        """
        devices = await BleakScanner.discover(timeout=timeout)
        result: List[GoChessDevice] = []
        for dev in devices:
            name = dev.name or ""
            if name.startswith("GoChess"):
                result.append(GoChessDevice(
                    index=len(result),
                    name=name,
                    address=dev.address,
                    _ble_device=dev,
                ))
        return result

    # ------------------------------------------------------------------
    # Connection
    # ------------------------------------------------------------------

    async def connect(self, device: GoChessDevice) -> None:
        """Connect to a GoChess board and start listening for notifications.

        Args:
            device: One of the devices returned by :meth:`scan`.
        """
        self._client = BleakClient(device._ble_device)
        await self._client.connect()
        self._connected = True
        logger.info("Connected to %s (%s)", device.name, device.address)
        await self._client.start_notify(NUS_TX_CHAR_UUID, self._on_notify)

    async def disconnect(self) -> None:
        """Disconnect from the board."""
        if self._client and self._connected:
            try:
                await self._client.stop_notify(NUS_TX_CHAR_UUID)
            except Exception:
                pass
            await self._client.disconnect()
        self._connected = False
        self._client = None
        # Cancel any pending futures
        for fut in self._pending.values():
            if not fut.done():
                fut.cancel()
        self._pending.clear()
        logger.info("Disconnected")

    @property
    def is_connected(self) -> bool:
        return self._connected

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        await self.disconnect()

    # ------------------------------------------------------------------
    # Callbacks
    # ------------------------------------------------------------------

    def on_piece_move(self, callback: Callable[[PieceEvent], None]) -> None:
        """Register a callback invoked whenever a piece is lifted or placed.

        The callback receives a :class:`PieceEvent`.
        Multiple callbacks can be registered.
        """
        self._piece_callbacks.append(callback)

    def on_raw_notification(self, callback: Callable[[bytes], None]) -> None:
        """Register a callback for every raw BLE notification (for debugging)."""
        self._raw_callbacks.append(callback)

    # ------------------------------------------------------------------
    # Commands  (App → Board)
    # ------------------------------------------------------------------

    async def get_battery(self, timeout: float = 5.0) -> int:
        """Request the battery percentage.

        Returns:
            Battery level 0-100 (%).
        """
        fut = self._expect("battery")
        await self._write(bytes([CMD_CHECK_BATTERY]))
        return await asyncio.wait_for(fut, timeout)

    async def get_board_state(self, timeout: float = 5.0) -> BoardState:
        """Request the full 8×8 board occupancy.

        Returns:
            A :class:`BoardState` object.
        """
        fut = self._expect("board_state")
        await self._write(bytes([CMD_GET_BOARD_STATE]))
        return await asyncio.wait_for(fut, timeout)

    async def get_border_state(self, timeout: float = 5.0) -> BorderState:
        """Request border/storage slot occupancy (36 positions around the board).

        **GoChess XR (Robotic) only** – Mini and Lite boards do not have
        border slots and will not respond to this command (will timeout).

        Returns:
            A :class:`BorderState` object with the 36 border positions.
        """
        fut = self._expect("border_state")
        await self._write(bytes([CMD_GET_BORDER_STATE]))
        return await asyncio.wait_for(fut, timeout)

    async def get_fw_version(self, timeout: float = 5.0) -> int:
        """Request the firmware version byte.

        Returns:
            Version number (e.g. ``0x04`` for GoChess Robotic, ``0x03`` for Mini/Lite).
        """
        fut = self._expect("fw_version")
        await self._write(bytes([CMD_GET_FW_VERSION]))
        return await asyncio.wait_for(fut, timeout)

    async def set_leds(
        self,
        squares: List[tuple],
        r: int = 0,
        g: int = 0,
        b: int = 0,
        overwrite: bool = True,
    ) -> None:
        """Turn on LEDs for the given squares with a uniform colour (command 0x32).

        Args:
            squares: List of ``(row, col)`` tuples (1-indexed, 1-8).
                     An empty list with ``overwrite=True`` turns all LEDs off.
            r: Red   0-255.
            g: Green 0-255.
            b: Blue  0-255.
            overwrite: If ``True``, squares *not* in the list are turned off.
                       If ``False``, only the listed squares are changed;
                       others keep their current colour.
        """
        mask_led, mask_led2 = _build_led_masks(squares)
        mask_bytes = _encode_led_masks_to_bytes(mask_led, mask_led2)

        data = bytearray(13)
        data[0] = CMD_SET_RGB_LEDS
        data[1:9] = mask_bytes
        # The firmware stores data[9]→g_green, data[10]→g_red, but the main
        # loop passes them swapped to setLedsRGB_I2C (g_red as r-param,
        # g_green as g-param) and the physical LEDs are GRB-wired.
        # Net result: data[9] drives physical RED, data[10] drives physical GREEN.
        data[9]  = r & 0xFF
        data[10] = g & 0xFF
        data[11] = b & 0xFF
        data[12] = 0x01 if overwrite else 0x00

        await self._write(data)

    async def set_leds_off(self) -> None:
        """Turn off all board LEDs."""
        await self.set_leds([], overwrite=True)

    async def set_leds_special(self, groups: List[Dict[str, Any]]) -> None:
        """Set per-square LED colours with multiple colour groups (command 0x34).

        This command first turns off all LEDs, then applies each group.

        Args:
            groups: A list of dicts, each containing:

                - ``"squares"``: list of ``(row, col)`` tuples (1-indexed)
                - ``"r"``:  Red   0-255
                - ``"g"``:  Green 0-255
                - ``"b"``:  Blue  0-255

        Example::

            await board.set_leds_special([
                {"squares": [(4, 5), (5, 4)], "r": 0,   "g": 255, "b": 0},
                {"squares": [(2, 3)],          "r": 255, "g": 0,   "b": 0},
            ])
        """
        data = bytearray([CMD_LED_ON_SPECIAL])
        for grp in groups:
            sqs = grp["squares"]
            data.append(len(sqs))
            for row, col in sqs:
                if not (1 <= row <= 8 and 1 <= col <= 8):
                    raise ValueError(f"Square ({row}, {col}) out of range 1-8.")
                data.append((row << 4) | col)  # upper nibble = row, lower = col
            # Same GRB colour swap as 0x32 – first byte drives physical RED,
            # second byte drives physical GREEN (see set_leds for details).
            data.append(grp.get("r", 0) & 0xFF)
            data.append(grp.get("g", 0) & 0xFF)
            data.append(grp.get("b", 0) & 0xFF)

        await self._write(data)

    async def set_leds_by_notation(
        self,
        squares_colors: Dict[str, tuple],
        overwrite: bool = True,
    ) -> None:
        """Convenience: set LEDs using chess notation.

        Uses :meth:`set_leds_special` under the hood so each square can have
        its own colour.

        Args:
            squares_colors: Mapping of notation → ``(r, g, b)`` tuple.
                            Example: ``{"e2": (0, 255, 0), "e4": (0, 255, 0)}``
            overwrite: Unused here (0x34 always clears first).
        """
        # Group squares by colour
        colour_groups: Dict[tuple, List[tuple]] = {}
        for notation, rgb in squares_colors.items():
            rc = square_notation_to_rc(notation)
            colour_groups.setdefault(rgb, []).append(rc)

        groups = [
            {"squares": sqs, "r": rgb[0], "g": rgb[1], "b": rgb[2]}
            for rgb, sqs in colour_groups.items()
        ]
        await self.set_leds_special(groups)

    # ------------------------------------------------------------------
    # Internal: send / receive
    # ------------------------------------------------------------------

    def _ensure_connected(self):
        if not self._client or not self._connected:
            raise RuntimeError("Not connected to a GoChess board.")

    async def _write(self, data: Union[bytes, bytearray]) -> None:
        """Write raw bytes to the NUS RX characteristic."""
        self._ensure_connected()
        logger.debug("TX → %s", data.hex())
        await self._client.write_gatt_char(NUS_RX_CHAR_UUID, data, response=False)

    def _expect(self, key: str) -> asyncio.Future:
        """Create a Future that will be resolved when a matching response arrives."""
        loop = asyncio.get_running_loop()
        fut = loop.create_future()
        self._pending[key] = fut
        return fut

    def _resolve(self, key: str, value: Any) -> None:
        """Resolve a pending Future."""
        fut = self._pending.pop(key, None)
        if fut and not fut.done():
            fut.set_result(value)

    # ------------------------------------------------------------------
    # Notification handler
    # ------------------------------------------------------------------

    def _on_notify(self, _sender, data: bytearray) -> None:
        """Dispatch incoming BLE notifications."""
        if not data:
            return

        logger.debug("RX ← %s", data.hex())

        for cb in self._raw_callbacks:
            try:
                cb(bytes(data))
            except Exception:
                logger.exception("Error in raw callback")

        first = data[0]

        # --- Framed message: [*][len][type][payload...][checksum][\r\n] ---
        if first == START_BYTE and len(data) >= 5:
            self._parse_framed(data)

        # --- Raw: Board state [0x03][8 bytes] ---
        elif first == RESP_BOARD_STATE and len(data) >= 9:
            self._resolve("board_state", BoardState(bytes(data[1:9])))

        # --- Raw: Border state [0x0C][6 bytes] ---
        elif first == RESP_BORDER_STATE and len(data) >= 7:
            self._resolve("border_state", BorderState(bytes(data[1:7])))

        # --- Raw: FW version "Ver" + byte ---
        elif first == 0x56 and len(data) >= 4 and data[1:3] == b"er":
            self._resolve("fw_version", data[3])

        # --- Raw: Piece move on board ('1'-'8' first byte) ---
        elif 0x31 <= first <= 0x38 and len(data) >= 3:
            self._emit_piece_move(data)

        # --- Raw: Piece move on border ('r','l','t','b') ---
        elif chr(first) in "rltb" and len(data) >= 3:
            self._emit_border_move(data)

        else:
            logger.debug("Unknown notification: %s", data.hex())

    def _parse_framed(self, data: bytearray) -> None:
        """Parse a framed message and resolve the matching Future."""
        msg_type = data[2]

        if msg_type == RESP_BATTERY and len(data) >= 4:
            self._resolve("battery", data[3])

        elif msg_type == RESP_BATTERY_MV and len(data) >= 5:
            mv = (data[3] << 8) | data[4]
            self._resolve("battery_mv", mv)

        elif msg_type == RESP_CHARGING and len(data) >= 4:
            self._resolve("charging", bool(data[3]))

        elif msg_type == RESP_CURRENT and len(data) >= 5:
            ua = (data[3] << 8) | data[4]
            self._resolve("current", ua)

        elif msg_type == RESP_CHAMBER and len(data) >= 4:
            self._resolve("chamber", bool(data[3]))

        else:
            logger.debug("Unknown framed type 0x%02X", msg_type)

    def _emit_piece_move(self, data: bytearray) -> None:
        """Emit a PieceEvent for a board-square move."""
        row = data[0] - 0x30  # ASCII '1'-'8' → 1-8
        col = data[1] - 0x30
        is_down = (data[2] == ord("d"))

        evt = PieceEvent(
            row=row, col=col, is_down=is_down,
            is_border=False, border_side="",
        )
        self._dispatch_piece(evt)

    def _emit_border_move(self, data: bytearray) -> None:
        """Emit a PieceEvent for a border/storage slot move."""
        side = chr(data[0])

        # Positions 1-9 are 3-byte messages, position 10 is 4-byte ("r10d")
        if len(data) >= 4 and data[1] == 0x31 and data[2] == 0x30:
            position = 10
            is_down = (data[3] == ord("d"))
        else:
            position = data[1] - 0x30
            is_down = (data[2] == ord("d"))

        evt = PieceEvent(
            row=0, col=position, is_down=is_down,
            is_border=True, border_side=side,
        )
        self._dispatch_piece(evt)

    def _dispatch_piece(self, evt: PieceEvent) -> None:
        for cb in self._piece_callbacks:
            try:
                cb(evt)
            except Exception:
                logger.exception("Error in piece-move callback")
