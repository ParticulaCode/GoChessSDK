"""
GoChess SDK – Interactive Console
==================================

Scans for boards, lets you pick one, shows status info, then enters an
interactive menu where you can send any supported command while
piece-movement events are printed in real time.

    pip install bleak
    python example.py
"""

import asyncio
import logging
import sys
from gochess_sdk import GoChessBoard, PieceEvent, rc_to_square_notation

# Uncomment for verbose BLE debug output:
# logging.basicConfig(level=logging.DEBUG)

VALID_FILES = "abcdefgh"
VALID_RANKS = "12345678"


# ───────────────────────────────────────────────────────────────────────────
# Non-blocking input  (lets BLE notifications print while waiting for user)
# ───────────────────────────────────────────────────────────────────────────

async def async_input(prompt: str = "") -> str:
    """Read a line from stdin without blocking the asyncio event loop.

    This runs the blocking ``input()`` in a thread-pool executor so that
    BLE notification callbacks (piece-move events) can still fire and
    print to the console while the program waits for user input.
    """
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, input, prompt)


# ───────────────────────────────────────────────────────────────────────────
# Helpers
# ───────────────────────────────────────────────────────────────────────────

FILES = "abcdefgh"


def border_event_to_label(side: str, position: int) -> str:
    """Convert a firmware border notification to a human-readable label.

    The firmware sends 1-indexed positions (1-10) for border slots.
    This maps them to the correct 0-indexed labels:
      - 't' (top):    position 1-8 → a9..h9
      - 'b' (bottom): position 1-8 → a0..h0
      - 'l' (left):   position 1-10 → q0..q9
      - 'r' (right):  position 1-10 → i0..i9
    """
    if side == "t" and 1 <= position <= 8:
        return FILES[position - 1] + "9"
    elif side == "b" and 1 <= position <= 8:
        return FILES[position - 1] + "0"
    elif side == "l" and 1 <= position <= 10:
        return f"q{position - 1}"
    elif side == "r" and 1 <= position <= 10:
        return f"i{position - 1}"
    return f"{side}{position}"


def on_piece_moved(event: PieceEvent):
    """Called every time a piece is lifted or placed."""
    if event.is_border:
        label = border_event_to_label(event.border_side, event.col)
        action = "placed on" if event.is_down else "lifted from"
        print(f"\n  >> Border piece {action} {label}  (side='{event.border_side}', pos={event.col})")
    else:
        square = rc_to_square_notation(event.row, event.col)
        action = "PLACED on" if event.is_down else "LIFTED from"
        print(f"\n  >> Piece {action} {square}  (row={event.row}, col={event.col})")


def parse_squares(text: str) -> list:
    """Parse a comma-separated list of chess-notation squares.

    e.g. "e2, e4, d4" → [(2,5), (4,5), (4,4)]
    """
    squares = []
    for token in text.split(","):
        token = token.strip().lower()
        if len(token) != 2:
            raise ValueError(f"Invalid square '{token}'. Use notation like e4.")
        f, r = token[0], token[1]
        if f not in VALID_FILES or r not in VALID_RANKS:
            raise ValueError(f"Invalid square '{token}'. File must be a-h, rank 1-8.")
        col = ord(f) - ord("a") + 1
        row = int(r)
        squares.append((row, col))
    return squares


def parse_color(text: str) -> tuple:
    """Parse an RGB string like '255,0,0' or 'red' shorthand."""
    shortcuts = {
        "red":     (255, 0, 0),
        "green":   (0, 255, 0),
        "blue":    (0, 0, 255),
        "yellow":  (255, 255, 0),
        "cyan":    (0, 255, 255),
        "magenta": (255, 0, 255),
        "white":   (255, 255, 255),
        "orange":  (255, 128, 0),
        "purple":  (128, 0, 255),
        "off":     (0, 0, 0),
    }
    text = text.strip().lower()
    if text in shortcuts:
        return shortcuts[text]
    parts = text.split(",")
    if len(parts) != 3:
        raise ValueError("Color must be R,G,B (e.g. 255,0,0) or a name (red, green, blue, ...).")
    return (int(parts[0].strip()), int(parts[1].strip()), int(parts[2].strip()))


def print_menu():
    print()
    print("=" * 62)
    print("  GoChess Board – Command Menu")
    print("=" * 62)
    print()
    print("  1  Get battery level")
    print("  2  Get board state (8x8 occupancy)")
    print("  3  Get border state (robotic storage slots)")
    print("  4  Get firmware version")
    print("  5  Set LEDs – uniform colour (0x32)")
    print("  6  Set LEDs – special / multi-colour (0x34)")
    print("  7  Turn off all LEDs")
    print("  0  Disconnect & exit")
    print()
    print("  Piece movements are shown in real-time as they happen.")
    print("-" * 62)


# ───────────────────────────────────────────────────────────────────────────
# Interactive command handlers
# ───────────────────────────────────────────────────────────────────────────

async def cmd_battery(board: GoChessBoard):
    try:
        battery = await board.get_battery()
        print(f"\n  Battery: {battery}%")
    except asyncio.TimeoutError:
        print("\n  Battery request timed out.")


async def cmd_board_state(board: GoChessBoard):
    try:
        state = await board.get_board_state()
        print(f"\n  {state!r}")
        print(state)
    except asyncio.TimeoutError:
        print("\n  Board state request timed out.")


async def cmd_border_state(board: GoChessBoard, is_xr: bool):
    if not is_xr:
        print("\n  Border state is only available on GoChess XR (Robotic).")
        print("  This board is a Mini/Lite model and does not have border slots.")
        return
    try:
        border = await board.get_border_state()
        hex_str = " ".join(f"0x{b:02X}" for b in border.raw_bytes)
        print(f"\n  {border!r}")
        print(f"  Raw bytes: {hex_str}")
        print(border)
    except asyncio.TimeoutError:
        print("\n  Border state request timed out.")


async def cmd_fw_version(board: GoChessBoard):
    try:
        fw = await board.get_fw_version()
        labels = {0x03: "GoChess Mini / Lite", 0x04: "GoChess Robotic (XR)"}
        label = labels.get(fw, "Unknown")
        print(f"\n  Firmware version: 0x{fw:02X}  ({label})")
    except asyncio.TimeoutError:
        print("\n  FW version request timed out.")


async def cmd_set_leds(board: GoChessBoard):
    """Interactive flow for command 0x32 – uniform colour LEDs."""
    print()
    print("  ── Set LEDs (uniform colour) ──────────────────────────")
    print("  Enter squares in chess notation, comma-separated.")
    print("  Example: e2, e4, d4, d5")
    print("  (empty = turn off all)")
    print()

    raw = (await async_input("  Squares: ")).strip()
    if not raw:
        await board.set_leds_off()
        print("  All LEDs turned off.")
        return

    try:
        squares = parse_squares(raw)
    except ValueError as e:
        print(f"  Error: {e}")
        return

    print()
    print("  Enter colour as R,G,B (0-255) or a name:")
    print("  Names: red, green, blue, yellow, cyan, magenta, white, orange, purple")
    print()

    raw_color = (await async_input("  Colour: ")).strip()
    try:
        r, g, b = parse_color(raw_color)
    except ValueError as e:
        print(f"  Error: {e}")
        return

    print()
    raw_ow = (await async_input("  Overwrite other LEDs? (y/n) [y]: ")).strip().lower()
    overwrite = raw_ow != "n"

    labels = [rc_to_square_notation(row, col) for row, col in squares]
    print(f"\n  Sending: squares={', '.join(labels)}  colour=({r},{g},{b})  overwrite={overwrite}")
    await board.set_leds(squares, r=r, g=g, b=b, overwrite=overwrite)
    print("  Done.")


async def cmd_set_leds_special(board: GoChessBoard):
    """Interactive flow for command 0x34 – multi-colour per-square LEDs."""
    print()
    print("  ── Set LEDs Special (multi-colour groups) ─────────────")
    print("  This command clears all LEDs first, then applies each")
    print("  colour group you define.")
    print()
    print("  You will add one colour group at a time.")
    print("  Each group = a set of squares + one colour.")
    print()

    groups = []
    group_num = 1

    while True:
        print(f"  ── Group {group_num} ──")
        raw = (await async_input("  Squares (e.g. e2, e4): ")).strip()
        if not raw:
            if not groups:
                print("  No groups added. Cancelled.")
                return
            break

        try:
            squares = parse_squares(raw)
        except ValueError as e:
            print(f"  Error: {e}")
            continue

        print("  Colour as R,G,B or name (red, green, blue, yellow, ...):")
        raw_color = (await async_input("  Colour: ")).strip()
        try:
            r, g, b = parse_color(raw_color)
        except ValueError as e:
            print(f"  Error: {e}")
            continue

        labels = [rc_to_square_notation(row, col) for row, col in squares]
        groups.append({"squares": squares, "r": r, "g": g, "b": b})
        print(f"  Added group {group_num}: {', '.join(labels)} → ({r},{g},{b})")
        group_num += 1

        more = (await async_input("  Add another group? (y/n) [n]: ")).strip().lower()
        if more != "y":
            break

    print(f"\n  Sending {len(groups)} colour group(s)...")
    await board.set_leds_special(groups)
    print("  Done.")


# ───────────────────────────────────────────────────────────────────────────
# Main
# ───────────────────────────────────────────────────────────────────────────

async def main():
    # ── 1. Scan ─────────────────────────────────────────────────────────
    print("Scanning for GoChess boards (5 seconds)...")
    devices = await GoChessBoard.scan(timeout=5.0)

    if not devices:
        print("No GoChess boards found. Make sure the board is on and nearby.")
        return

    print(f"\nFound {len(devices)} board(s):")
    for d in devices:
        print(f"  [{d.index}]  {d.name}  ({d.address})")

    # Let the user choose which board to connect to
    while True:
        try:
            choice = int(await async_input(f"\nEnter board index to connect [0-{len(devices)-1}]: "))
            if 0 <= choice < len(devices):
                break
            print(f"  Invalid index. Please enter a number between 0 and {len(devices)-1}.")
        except ValueError:
            print("  Please enter a valid number.")

    chosen = devices[choice]
    print(f"\nConnecting to [{chosen.index}] {chosen.name} ...")

    # ── 2. Connect ──────────────────────────────────────────────────────
    board = GoChessBoard()
    await board.connect(chosen)
    print("Connected!\n")

    # ── 3. Register piece-move callback ─────────────────────────────────
    board.on_piece_move(on_piece_moved)

    # ── 4. Show board info ──────────────────────────────────────────────
    # Detect board type: XR (Robotic) supports border state, Mini/Lite don't
    is_xr = chosen.name.startswith("GoChessXR")

    print("─" * 50)
    try:
        fw = await board.get_fw_version()
        labels = {0x03: "GoChess Mini / Lite", 0x04: "GoChess Robotic (XR)"}
        print(f"  Firmware : 0x{fw:02X}  ({labels.get(fw, 'Unknown')})")
        if fw == 0x04:
            is_xr = True
    except asyncio.TimeoutError:
        print("  Firmware : (timed out)")

    try:
        battery = await board.get_battery()
        print(f"  Battery  : {battery}%")
    except asyncio.TimeoutError:
        print("  Battery  : (timed out)")

    print(f"  Board    : {'GoChess XR (Robotic) – border state supported' if is_xr else 'GoChess Mini/Lite – no border slots'}")
    print("─" * 50)
    print("  Piece movements will appear in real-time.")

    # ── 5. Interactive menu loop ────────────────────────────────────────
    while True:
        print_menu()
        raw = (await async_input("  Choose [0-7]: ")).strip()

        if raw == "0":
            break
        elif raw == "1":
            await cmd_battery(board)
        elif raw == "2":
            await cmd_board_state(board)
        elif raw == "3":
            await cmd_border_state(board, is_xr)
        elif raw == "4":
            await cmd_fw_version(board)
        elif raw == "5":
            await cmd_set_leds(board)
        elif raw == "6":
            await cmd_set_leds_special(board)
        elif raw == "7":
            await board.set_leds_off()
            print("\n  All LEDs turned off.")
        else:
            print("  Unknown option. Please enter 0-7.")

    # ── 6. Disconnect ───────────────────────────────────────────────────
    await board.disconnect()
    print("\nDisconnected. Bye!")


if __name__ == "__main__":
    asyncio.run(main())
