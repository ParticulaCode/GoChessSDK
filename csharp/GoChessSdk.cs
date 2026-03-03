// ============================================================================
// GoChess C# SDK
// ============================================================================
//
// A single-file C# SDK for communicating with GoChess smart chess boards
// via Bluetooth Low Energy (BLE).
//
// The GoChess board uses an nRF52832 BLE SoC with the Nordic UART Service
// (NUS) for bidirectional communication. This SDK provides a high-level API
// for:
//
//   - Scanning and connecting to GoChess boards
//   - Receiving real-time piece movement notifications (via Hall effect sensors)
//   - Querying battery level, board state, border state, and firmware version
//   - Controlling per-square RGB LEDs
//
// BLE backend:
//   Windows Runtime (WinRT) Bluetooth APIs (Windows.Devices.Bluetooth).
//   No external NuGet packages required — just target net8.0-windows10.0.19041.0
//   or later in your .csproj.
//
// Supported boards:
//   - GoChess XR (Robotic) - advertises as "GoChessXR_XXXXXX"
//   - GoChess Mini          - advertises as "GoChessM_XXXXXX"
//   - GoChess Lite          - advertises as "GoChessL_XXXXXX"
//
// Protocol notes:
//   The board uses two message formats over the NUS TX characteristic:
//
//   1. Raw messages (no framing):
//      - Piece move notifications: 3-4 ASCII bytes  e.g. "81d"
//      - Board state (0x03):  [0x03][8 bytes]
//      - Border state (0x0C): [0x0C][6 bytes]
//      - FW version:          "Ver" + version_byte
//
//   2. Framed messages:
//      [START][LEN][TYPE][DATA...][CHECKSUM][CR][LF]
//      - START = 0x2A ('*')
//      - LEN   = total bytes from START through CHECKSUM (inclusive)
//      - CHECKSUM = sum of all bytes from START through DATA
//      - CR LF = 0x0D 0x0A
//      Used for: battery, charging state, etc.
//
// ============================================================================

using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace GoChess.Sdk
{
    // ========================================================================
    // BLE UUIDs - Nordic UART Service (custom base)
    // Base: 6E400000-B5A3-F393-E0A9-E50E24DCCA9E
    // ========================================================================

    /// <summary>
    /// BLE UUIDs and protocol constants for the GoChess board communication.
    /// </summary>
    public static class GoChessConstants
    {
        /// <summary>Nordic UART Service UUID.</summary>
        public static readonly Guid NusServiceUuid =
            new Guid("6e400001-b5a3-f393-e0a9-e50e24dcca9e");

        /// <summary>NUS RX characteristic UUID (App -> Board, write).</summary>
        public static readonly Guid NusRxCharUuid =
            new Guid("6e400002-b5a3-f393-e0a9-e50e24dcca9e");

        /// <summary>NUS TX characteristic UUID (Board -> App, notify).</summary>
        public static readonly Guid NusTxCharUuid =
            new Guid("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

        // --------------------------------------------------------------------
        // Command bytes (App -> Board)
        // --------------------------------------------------------------------

        /// <summary>Request border/storage slot occupancy state.</summary>
        public const byte CmdGetBorderState = 0x22;

        /// <summary>Set RGB LEDs with uniform colour and bitmask (12-byte payload: mask1(4) mask2(4) R G B overwrite).</summary>
        public const byte CmdSetRgbLeds = 0x32;

        /// <summary>Toggle LED-on-sensor feature (1 byte: 0x00/0x01).</summary>
        public const byte CmdLedOnSensor = 0x33;

        /// <summary>Set per-square LED colours with multiple colour groups (variable-length payload).</summary>
        public const byte CmdLedOnSpecial = 0x34;

        /// <summary>Request full 8x8 board occupancy state.</summary>
        public const byte CmdGetBoardState = 0x35;

        /// <summary>Set LEDs mode (1 byte: mode 0/1/2).</summary>
        public const byte CmdSetLedsMode = 0x36;

        /// <summary>Pattern LED on (pattern 1).</summary>
        public const byte CmdPatternLedOn = 0x37;

        /// <summary>Pattern LED on (pattern 2).</summary>
        public const byte CmdPattern2LedOn = 0x38;

        /// <summary>Request battery percentage.</summary>
        public const byte CmdCheckBattery = 0x39;

        /// <summary>Request battery voltage in millivolts.</summary>
        public const byte CmdCheckBatteryMv = 0x3A;

        /// <summary>Set interrupt mask (8 bytes).</summary>
        public const byte CmdIntMask = 0x3B;

        /// <summary>Measure current draw.</summary>
        public const byte CmdMeasureCurrent = 0x3C;

        /// <summary>Request charging state.</summary>
        public const byte CmdGetChargingState = 0x3D;

        /// <summary>Complex LED message.</summary>
        public const byte CmdComplexLedMessage = 0x3E;

        /// <summary>Complex LED parts.</summary>
        public const byte CmdComplexLedParts = 0x3F;

        /// <summary>Request firmware version ('v' = 0x76).</summary>
        public const byte CmdGetFwVersion = 0x76;

        // --------------------------------------------------------------------
        // Response message types (Board -> App)
        // --------------------------------------------------------------------

        /// <summary>Battery percentage (framed, 1 byte payload).</summary>
        public const byte RespBattery = 0x01;

        /// <summary>Battery voltage in mV (framed, 2 bytes big-endian).</summary>
        public const byte RespBatteryMv = 0x02;

        /// <summary>Board state (raw, 8 bytes bitfield per row).</summary>
        public const byte RespBoardState = 0x03;

        /// <summary>Current draw in uA (framed, 2 bytes big-endian).</summary>
        public const byte RespCurrent = 0x04;

        /// <summary>Charging state (framed, 1 byte: 0/1).</summary>
        public const byte RespCharging = 0x07;

        /// <summary>Chamber state (framed, 1 byte: 0/1).</summary>
        public const byte RespChamber = 0x0B;

        /// <summary>Border state (raw, 6 bytes).</summary>
        public const byte RespBorderState = 0x0C;

        /// <summary>Start byte for framed messages ('*' = 0x2A).</summary>
        public const byte StartByte = 0x2A;
    }

    // ========================================================================
    // Data classes
    // ========================================================================

    /// <summary>
    /// A discovered GoChess board from a BLE scan.
    /// </summary>
    public class GoChessDevice
    {
        /// <summary>Discovery index (0-based, sorted by discovery order).</summary>
        public int Index { get; }

        /// <summary>Advertised device name (e.g. "GoChessXR_A1B2C3").</summary>
        public string Name { get; }

        /// <summary>BLE address as a hex string.</summary>
        public string Address { get; }

        /// <summary>Internal BLE address for connection (not for external use).</summary>
        internal ulong BleAddress { get; }

        /// <summary>
        /// Initializes a new instance of <see cref="GoChessDevice"/>.
        /// </summary>
        /// <param name="index">Discovery index.</param>
        /// <param name="name">Advertised device name.</param>
        /// <param name="address">BLE address hex string.</param>
        /// <param name="bleAddress">Raw BLE address for WinRT connection.</param>
        public GoChessDevice(int index, string name, string address, ulong bleAddress)
        {
            Index = index;
            Name = name;
            Address = address;
            BleAddress = bleAddress;
        }

        /// <inheritdoc/>
        public override string ToString() => $"GoChessDevice(index={Index}, name=\"{Name}\", address=\"{Address}\")";
    }

    /// <summary>
    /// A piece-movement event from the Hall-effect sensors.
    /// <para>
    /// For board squares: <see cref="Row"/> = 1-8, <see cref="Col"/> = 1-8, <see cref="IsBorder"/> = false.
    /// </para>
    /// <para>
    /// For border/storage slots (GoChess Robotic): <see cref="Row"/> = 0,
    /// <see cref="Col"/> = position (1-10), <see cref="IsBorder"/> = true,
    /// <see cref="BorderSide"/> = "r", "l", "t", or "b".
    /// </para>
    /// </summary>
    public class PieceEvent
    {
        /// <summary>Row number: 1-8 for board squares, 0 for border slots.</summary>
        public int Row { get; }

        /// <summary>Column number: 1-8 for board squares, or position (1-10) for border slots.</summary>
        public int Col { get; }

        /// <summary>True if a piece was placed on the square, false if lifted.</summary>
        public bool IsDown { get; }

        /// <summary>True if this event is from a border/storage slot.</summary>
        public bool IsBorder { get; }

        /// <summary>
        /// Border side identifier: "r" (right), "l" (left), "t" (top), "b" (bottom),
        /// or empty string for board squares.
        /// </summary>
        public string BorderSide { get; }

        /// <summary>
        /// Initializes a new instance of <see cref="PieceEvent"/>.
        /// </summary>
        /// <param name="row">Row number (1-8 board, 0 border).</param>
        /// <param name="col">Column number (1-8 board, 1-10 border).</param>
        /// <param name="isDown">True if piece placed, false if lifted.</param>
        /// <param name="isBorder">True for border/storage slot events.</param>
        /// <param name="borderSide">Side identifier ("r","l","t","b" or "").</param>
        public PieceEvent(int row, int col, bool isDown, bool isBorder, string borderSide)
        {
            Row = row;
            Col = col;
            IsDown = isDown;
            IsBorder = isBorder;
            BorderSide = borderSide;
        }

        /// <inheritdoc/>
        public override string ToString()
        {
            string action = IsDown ? "down" : "up";
            if (IsBorder)
                return $"PieceEvent(border={BorderSide}{Col}, {action})";
            return $"PieceEvent(row={Row}, col={Col}, {action})";
        }
    }

    /// <summary>
    /// 8x8 board occupancy state.
    /// <para>
    /// Each square is either occupied (piece present) or empty.
    /// Indexed 1-8 for both row and column, matching chess convention:
    /// Row 1 = White's back rank, Row 8 = Black's back rank,
    /// Col 1 = a-file, Col 8 = h-file.
    /// </para>
    /// </summary>
    public class BoardState
    {
        private readonly byte[] _raw;

        /// <summary>
        /// Initializes a new instance of <see cref="BoardState"/> from 8 raw bytes.
        /// </summary>
        /// <param name="raw">8 bytes, one per row, with bit N representing column N+1.</param>
        /// <exception cref="ArgumentException">Thrown when <paramref name="raw"/> is not exactly 8 bytes.</exception>
        public BoardState(byte[] raw)
        {
            if (raw == null || raw.Length != 8)
                throw new ArgumentException($"Expected 8 bytes, got {raw?.Length ?? 0}");
            _raw = new byte[8];
            Array.Copy(raw, _raw, 8);
        }

        /// <summary>
        /// Check if a square has a piece. Row and col are 1-indexed (1-8).
        /// </summary>
        /// <param name="row">Row (1 = White's back rank, 8 = Black's back rank).</param>
        /// <param name="col">Column (1 = a-file, 8 = h-file).</param>
        /// <returns>True if a piece occupies the square.</returns>
        /// <exception cref="IndexOutOfRangeException">Thrown when row or col is outside 1-8.</exception>
        public bool IsOccupied(int row, int col)
        {
            if (row < 1 || row > 8 || col < 1 || col > 8)
                throw new IndexOutOfRangeException($"Row and col must be 1-8, got ({row}, {col})");
            return (_raw[row - 1] & (1 << (col - 1))) != 0;
        }

        /// <summary>
        /// Return the board as an 8x8 boolean matrix (row-major, 0-indexed).
        /// <c>matrix[r, c]</c> corresponds to board row <c>r+1</c>, col <c>c+1</c>.
        /// </summary>
        /// <returns>An 8x8 two-dimensional boolean array.</returns>
        public bool[,] ToMatrix()
        {
            var matrix = new bool[8, 8];
            for (int r = 1; r <= 8; r++)
            {
                for (int c = 1; c <= 8; c++)
                {
                    matrix[r - 1, c - 1] = IsOccupied(r, c);
                }
            }
            return matrix;
        }

        /// <summary>
        /// Count of occupied squares on the board.
        /// </summary>
        public int PieceCount
        {
            get
            {
                int count = 0;
                for (int r = 1; r <= 8; r++)
                    for (int c = 1; c <= 8; c++)
                        if (IsOccupied(r, c))
                            count++;
                return count;
            }
        }

        /// <summary>
        /// The raw 8 bytes representing board occupancy.
        /// </summary>
        public byte[] RawBytes
        {
            get
            {
                var copy = new byte[8];
                Array.Copy(_raw, copy, 8);
                return copy;
            }
        }

        /// <summary>
        /// Pretty-print the board with occupied/empty indicators and file/rank labels.
        /// Uses the black square character for occupied squares and a dot for empty.
        /// </summary>
        /// <returns>A multi-line string representation of the board.</returns>
        public override string ToString()
        {
            var lines = new List<string> { "  a b c d e f g h" };
            for (int row = 8; row >= 1; row--)
            {
                var cells = new List<string>();
                for (int c = 1; c <= 8; c++)
                {
                    cells.Add(IsOccupied(row, c) ? "\u25A0" : "\u00B7");
                }
                lines.Add($"{row} {string.Join(" ", cells)}");
            }
            return string.Join("\n", lines);
        }
    }

    /// <summary>
    /// Border/storage slot occupancy state (36 positions surrounding the board).
    /// <para>
    /// Position labels:
    ///   Top row (rank 9):     a9, b9, c9, d9, e9, f9, g9, h9
    ///   Bottom row (rank 0):  a0, b0, c0, d0, e0, f0, g0, h0
    ///   Left col (file "q"):  q0, q1, q2, q3, q4, q5, q6, q7, q8, q9
    ///   Right col (file "i"): i0, i1, i2, i3, i4, i5, i6, i7, i8, i9
    ///   Corners: q9 = top-left, i9 = top-right, q0 = bottom-left, i0 = bottom-right
    /// </para>
    /// <para>
    /// Raw byte mapping (6 bytes after the 0x0C message-type byte):
    ///   byte 0: Top border    - bits 0-7 map to a9..h9
    ///   byte 1: Bottom border - bits 0-7 map to a0..h0
    ///   byte 2: Left column   - bit N = q(N) for N=0..7
    ///   byte 3: Left extension  - bit 0 = q8, bit 1 = q9
    ///   byte 4: Right column  - bit N = i(N) for N=0..7
    ///   byte 5: Right extension - bit 0 = i8, bit 1 = i9
    /// </para>
    /// </summary>
    public class BorderState
    {
        private static readonly string[] Files = { "a", "b", "c", "d", "e", "f", "g", "h" };
        private readonly byte[] _raw;
        private readonly Dictionary<string, bool> _slots;

        /// <summary>
        /// Initializes a new instance of <see cref="BorderState"/> from 6 raw bytes.
        /// </summary>
        /// <param name="raw">6 bytes of border occupancy data.</param>
        /// <exception cref="ArgumentException">Thrown when <paramref name="raw"/> is not exactly 6 bytes.</exception>
        public BorderState(byte[] raw)
        {
            if (raw == null || raw.Length != 6)
                throw new ArgumentException($"Expected 6 bytes, got {raw?.Length ?? 0}");
            _raw = new byte[6];
            Array.Copy(raw, _raw, 6);
            _slots = new Dictionary<string, bool>();
            Parse();
        }

        private void Parse()
        {
            // Byte 0: Top border (rank 9) - bit N -> file[N] + "9"
            for (int i = 0; i < 8; i++)
                _slots[Files[i] + "9"] = (_raw[0] & (1 << i)) != 0;

            // Byte 1: Bottom border (rank 0) - bit N -> file[N] + "0"
            for (int i = 0; i < 8; i++)
                _slots[Files[i] + "0"] = (_raw[1] & (1 << i)) != 0;

            // Byte 2: Left column (file "q") - bit N -> "q" + N (N=0..7)
            for (int i = 0; i < 8; i++)
                _slots[$"q{i}"] = (_raw[2] & (1 << i)) != 0;

            // Byte 3: Left extension - bit 0 -> q8, bit 1 -> q9
            _slots["q8"] = (_raw[3] & 0x01) != 0;
            _slots["q9"] = (_raw[3] & 0x02) != 0;

            // Byte 4: Right column (file "i") - bit N -> "i" + N (N=0..7)
            for (int i = 0; i < 8; i++)
                _slots[$"i{i}"] = (_raw[4] & (1 << i)) != 0;

            // Byte 5: Right extension - bit 0 -> i8, bit 1 -> i9
            _slots["i8"] = (_raw[5] & 0x01) != 0;
            _slots["i9"] = (_raw[5] & 0x02) != 0;
        }

        /// <summary>
        /// Check if a border position is occupied.
        /// </summary>
        /// <param name="position">Label like "a9", "q0", "i5", etc.</param>
        /// <returns>True if the position is occupied, false otherwise or if the position is unknown.</returns>
        public bool IsOccupied(string position)
        {
            return _slots.TryGetValue(position, out bool occupied) && occupied;
        }

        /// <summary>
        /// All 36 position labels mapped to their occupied state. Returns a copy.
        /// </summary>
        public Dictionary<string, bool> Slots => new Dictionary<string, bool>(_slots);

        /// <summary>
        /// Count of occupied border slots.
        /// </summary>
        public int OccupiedCount => _slots.Values.Count(v => v);

        /// <summary>
        /// The raw 6 bytes representing border occupancy.
        /// </summary>
        public byte[] RawBytes
        {
            get
            {
                var copy = new byte[6];
                Array.Copy(_raw, copy, 6);
                return copy;
            }
        }

        /// <summary>
        /// Pretty-print the border state as ASCII art with corners.
        /// Uses the black square character for occupied slots and a dot for empty.
        /// </summary>
        /// <returns>A multi-line string representation of the border layout.</returns>
        public override string ToString()
        {
            string C(string pos) => IsOccupied(pos) ? "\u25A0" : "\u00B7";

            var lines = new List<string>();

            // Top row
            string top = $"{C("q9")} {string.Join(" ", Files.Select(f => C(f + "9")))} {C("i9")}";
            lines.Add(top);

            // Side rows 8 -> 1
            for (int r = 8; r >= 1; r--)
            {
                lines.Add($"{C($"q{r}")} {string.Join(" ", Enumerable.Repeat("\u00B7", 8))}  {C($"i{r}")}");
            }

            // Bottom row
            string bot = $"{C("q0")} {string.Join(" ", Files.Select(f => C(f + "0")))} {C("i0")}";
            lines.Add(bot);

            return string.Join("\n", lines);
        }
    }

    /// <summary>
    /// Defines a group of squares that share the same LED colour, used with
    /// <see cref="GoChessBoard.SetLedsSpecialAsync"/>.
    /// </summary>
    public class LedGroup
    {
        /// <summary>List of (row, col) squares in this group, 1-indexed.</summary>
        public List<(int Row, int Col)> Squares { get; set; } = new List<(int, int)>();

        /// <summary>Red component (0-255).</summary>
        public byte R { get; set; }

        /// <summary>Green component (0-255).</summary>
        public byte G { get; set; }

        /// <summary>Blue component (0-255).</summary>
        public byte B { get; set; }
    }

    // ========================================================================
    // Helper functions
    // ========================================================================

    /// <summary>
    /// Static helper methods for LED bitmask encoding and chess notation conversion.
    /// </summary>
    public static class GoChessHelpers
    {
        /// <summary>
        /// Convert a list of (row, col) squares (1-indexed) to firmware LED bitmasks.
        /// <para>
        /// Rows 1-4 map to <paramref name="mask1"/> (LED indices 0-31).
        /// Rows 5-8 map to <paramref name="mask2"/> (LED indices 32-63).
        /// Within each group, the bit index is <c>(row - base) * 8 + (col - 1)</c>.
        /// </para>
        /// </summary>
        /// <param name="squares">List of (row, col) tuples, each 1-indexed in range 1-8.</param>
        /// <returns>A tuple of (mask1, mask2) where mask1 covers rows 1-4 and mask2 covers rows 5-8.</returns>
        /// <exception cref="ArgumentException">Thrown when any square is outside the 1-8 range.</exception>
        public static (uint Mask1, uint Mask2) BuildLedMasks(List<(int Row, int Col)> squares)
        {
            uint mask1 = 0; // rows 1-4 (LED indices 0-31)
            uint mask2 = 0; // rows 5-8 (LED indices 32-63)

            foreach (var (row, col) in squares)
            {
                if (row < 1 || row > 8 || col < 1 || col > 8)
                    throw new ArgumentException($"Square ({row}, {col}) out of range 1-8.");

                if (row <= 4)
                    mask1 |= 1u << ((row - 1) * 8 + (col - 1));
                else
                    mask2 |= 1u << ((row - 5) * 8 + (col - 1));
            }

            return (mask1, mask2);
        }

        /// <summary>
        /// Encode LED masks into the 8 data bytes expected by the 0x32 command.
        /// <para>
        /// Each mask is encoded as a little-endian 32-bit integer. No bit reversal
        /// is applied here because the firmware performs its own <c>reverse_num()</c>
        /// on the received values.
        /// </para>
        /// <para>
        /// The firmware decodes:
        ///   <c>tmp  = LE32(data[1..4]) -> g_mask_led2 = reverse_num(tmp)</c> (rows 5-8),
        ///   <c>tmp2 = LE32(data[5..8]) -> g_mask_led  = reverse_num(tmp2)</c> (rows 1-4).
        /// We send raw masks directly so the firmware's single <c>reverse_num</c>
        /// produces the correct result.
        /// </para>
        /// </summary>
        /// <param name="mask1">Bitmask for rows 1-4.</param>
        /// <param name="mask2">Bitmask for rows 5-8.</param>
        /// <returns>8 bytes: LE32(mask1) followed by LE32(mask2).</returns>
        public static byte[] EncodeLedMasksToBytes(uint mask1, uint mask2)
        {
            var result = new byte[8];
            // Little-endian 32-bit for mask1
            result[0] = (byte)(mask1 & 0xFF);
            result[1] = (byte)((mask1 >> 8) & 0xFF);
            result[2] = (byte)((mask1 >> 16) & 0xFF);
            result[3] = (byte)((mask1 >> 24) & 0xFF);
            // Little-endian 32-bit for mask2
            result[4] = (byte)(mask2 & 0xFF);
            result[5] = (byte)((mask2 >> 8) & 0xFF);
            result[6] = (byte)((mask2 >> 16) & 0xFF);
            result[7] = (byte)((mask2 >> 24) & 0xFF);
            return result;
        }

        /// <summary>
        /// Convert chess notation like "e4" to a (row, col) tuple (1-indexed).
        /// </summary>
        /// <param name="notation">Standard algebraic notation (e.g. "e4", "a1").</param>
        /// <returns>A tuple of (row, col), both 1-indexed.</returns>
        /// <exception cref="ArgumentException">Thrown when the notation is invalid.</exception>
        public static (int Row, int Col) SquareNotationToRC(string notation)
        {
            if (string.IsNullOrEmpty(notation) || notation.Length != 2)
                throw new ArgumentException($"Invalid notation: {notation}");

            char fileChar = char.ToLower(notation[0]);
            char rankChar = notation[1];

            if (fileChar < 'a' || fileChar > 'h')
                throw new ArgumentException($"Invalid file: {fileChar}");
            if (rankChar < '1' || rankChar > '8')
                throw new ArgumentException($"Invalid rank: {rankChar}");

            int col = fileChar - 'a' + 1;
            int row = rankChar - '0';
            return (row, col);
        }

        /// <summary>
        /// Convert a (row, col) tuple (1-indexed) to chess notation like "e4".
        /// </summary>
        /// <param name="row">Row (1-8).</param>
        /// <param name="col">Column (1-8).</param>
        /// <returns>Standard algebraic notation string.</returns>
        public static string RCToSquareNotation(int row, int col)
        {
            return $"{(char)('a' + col - 1)}{row}";
        }
    }

    // ========================================================================
    // Main SDK class
    // ========================================================================

    /// <summary>
    /// High-level interface to a GoChess smart chess board.
    /// <para>
    /// Provides scanning, connection management, piece-move callbacks, board/border
    /// state queries, battery/firmware queries, and LED control.
    /// </para>
    /// <para>
    /// Uses Windows Runtime (WinRT) Bluetooth APIs directly — no external NuGet
    /// packages required. Target <c>net8.0-windows10.0.19041.0</c> or later.
    /// </para>
    /// <example>
    /// <code>
    /// var board = new GoChessBoard();
    /// var devices = await GoChessBoard.ScanAsync();
    /// await board.ConnectAsync(devices[0]);
    /// board.OnPieceMove(evt => Console.WriteLine(evt));
    /// int battery = await board.GetBatteryAsync();
    /// await board.DisconnectAsync();
    /// </code>
    /// </example>
    /// <example>
    /// Or using <see cref="IAsyncDisposable"/>:
    /// <code>
    /// await using var board = new GoChessBoard();
    /// var devices = await GoChessBoard.ScanAsync();
    /// await board.ConnectAsync(devices[0]);
    /// // ...
    /// </code>
    /// </example>
    /// </summary>
    public class GoChessBoard : IAsyncDisposable
    {
        private BluetoothLEDevice? _device;
        private GattDeviceService? _nusService;
        private GattCharacteristic? _rxChar;
        private GattCharacteristic? _txChar;
        private bool _connected;
        private readonly List<Action<PieceEvent>> _pieceCallbacks = new List<Action<PieceEvent>>();
        private readonly List<Action<byte[]>> _rawCallbacks = new List<Action<byte[]>>();
        private readonly ConcurrentDictionary<string, TaskCompletionSource<object>> _pending =
            new ConcurrentDictionary<string, TaskCompletionSource<object>>();

        /// <summary>
        /// Initializes a new instance of <see cref="GoChessBoard"/>.
        /// WinRT BLE APIs are accessed directly — no adapter initialisation needed.
        /// </summary>
        public GoChessBoard()
        {
        }

        // ------------------------------------------------------------------
        // Scanning
        // ------------------------------------------------------------------

        /// <summary>
        /// Scan for nearby GoChess boards via BLE.
        /// <para>
        /// Uses <see cref="BluetoothLEAdvertisementWatcher"/> to discover nearby
        /// devices whose name starts with "GoChess".
        /// </para>
        /// </summary>
        /// <param name="timeout">
        /// How long to scan. Defaults to 5 seconds if null.
        /// </param>
        /// <param name="ct">Cancellation token.</param>
        /// <returns>A list of <see cref="GoChessDevice"/> objects, sorted by discovery order.</returns>
        public static async Task<List<GoChessDevice>> ScanAsync(
            TimeSpan? timeout = null,
            CancellationToken ct = default)
        {
            var watcher = new BluetoothLEAdvertisementWatcher
            {
                ScanningMode = BluetoothLEScanningMode.Active
            };

            var discovered = new List<GoChessDevice>();
            var seen = new ConcurrentDictionary<ulong, byte>();
            var lockObj = new object();

            watcher.Received += (sender, args) =>
            {
                string name = args.Advertisement.LocalName ?? "";
                if (name.StartsWith("GoChess", StringComparison.OrdinalIgnoreCase)
                    && seen.TryAdd(args.BluetoothAddress, 0))
                {
                    lock (lockObj)
                    {
                        discovered.Add(new GoChessDevice(
                            index: discovered.Count,
                            name: name,
                            address: args.BluetoothAddress.ToString("X12"),
                            bleAddress: args.BluetoothAddress));
                    }
                }
            };

            watcher.Start();

            var scanTime = timeout ?? TimeSpan.FromSeconds(5);
            try
            {
                await Task.Delay(scanTime, ct);
            }
            catch (TaskCanceledException)
            {
                // Scan cancelled early — return what we found
            }

            watcher.Stop();

            return discovered;
        }

        // ------------------------------------------------------------------
        // Connection
        // ------------------------------------------------------------------

        /// <summary>
        /// Connect to a GoChess board and start listening for BLE notifications.
        /// <para>
        /// Creates a <see cref="BluetoothLEDevice"/> from the discovered address,
        /// discovers the Nordic UART Service, locates RX and TX characteristics,
        /// and subscribes to TX notifications for incoming data.
        /// </para>
        /// </summary>
        /// <param name="device">A device returned by <see cref="ScanAsync"/>.</param>
        /// <param name="ct">Cancellation token.</param>
        /// <exception cref="InvalidOperationException">
        /// Thrown when the device cannot be reached, or the NUS service or required
        /// characteristics are not found.
        /// </exception>
        public async Task ConnectAsync(GoChessDevice device, CancellationToken ct = default)
        {
            var bleDevice = await BluetoothLEDevice.FromBluetoothAddressAsync(device.BleAddress);
            if (bleDevice == null)
                throw new InvalidOperationException(
                    $"Could not connect to device '{device.Name}'. Make sure it is in range and powered on.");

            _device = bleDevice;

            // Discover NUS service
            var serviceResult = await _device.GetGattServicesForUuidAsync(
                GoChessConstants.NusServiceUuid, BluetoothCacheMode.Uncached);

            if (serviceResult.Status != GattCommunicationStatus.Success
                || serviceResult.Services.Count == 0)
            {
                _device.Dispose();
                _device = null;
                throw new InvalidOperationException(
                    "Nordic UART Service not found on device. Is this a GoChess board?");
            }

            _nusService = serviceResult.Services[0];

            // Discover RX characteristic (App -> Board, write)
            var rxResult = await _nusService.GetCharacteristicsForUuidAsync(
                GoChessConstants.NusRxCharUuid, BluetoothCacheMode.Uncached);

            if (rxResult.Status != GattCommunicationStatus.Success
                || rxResult.Characteristics.Count == 0)
            {
                CleanupConnection();
                throw new InvalidOperationException("NUS RX characteristic not found.");
            }

            _rxChar = rxResult.Characteristics[0];

            // Discover TX characteristic (Board -> App, notify)
            var txResult = await _nusService.GetCharacteristicsForUuidAsync(
                GoChessConstants.NusTxCharUuid, BluetoothCacheMode.Uncached);

            if (txResult.Status != GattCommunicationStatus.Success
                || txResult.Characteristics.Count == 0)
            {
                CleanupConnection();
                throw new InvalidOperationException("NUS TX characteristic not found.");
            }

            _txChar = txResult.Characteristics[0];

            // Subscribe to TX notifications
            var notifyStatus = await _txChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                GattClientCharacteristicConfigurationDescriptorValue.Notify);

            if (notifyStatus != GattCommunicationStatus.Success)
            {
                CleanupConnection();
                throw new InvalidOperationException(
                    $"Failed to enable TX notifications (status: {notifyStatus}).");
            }

            _txChar.ValueChanged += OnCharacteristicUpdated;
            _connected = true;
        }

        /// <summary>
        /// Disconnect from the board and clean up resources.
        /// Cancels any pending command futures.
        /// </summary>
        public async Task DisconnectAsync()
        {
            if (_device != null && _connected)
            {
                try
                {
                    if (_txChar != null)
                    {
                        _txChar.ValueChanged -= OnCharacteristicUpdated;
                        await _txChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                            GattClientCharacteristicConfigurationDescriptorValue.None);
                    }
                }
                catch
                {
                    // Ignore errors during cleanup
                }
            }

            CleanupConnection();

            // Cancel any pending futures
            foreach (var kvp in _pending)
            {
                if (_pending.TryRemove(kvp.Key, out var tcs))
                    tcs.TrySetCanceled();
            }
        }

        /// <summary>
        /// Dispose WinRT BLE objects and reset connection state.
        /// </summary>
        private void CleanupConnection()
        {
            _connected = false;
            _rxChar = null;
            _txChar = null;
            _nusService?.Dispose();
            _nusService = null;
            _device?.Dispose();
            _device = null;
        }

        /// <summary>
        /// Whether the board is currently connected.
        /// </summary>
        public bool IsConnected => _connected;

        /// <summary>
        /// Disposes of the board connection asynchronously.
        /// </summary>
        public async ValueTask DisposeAsync()
        {
            await DisconnectAsync();
        }

        // ------------------------------------------------------------------
        // Callbacks
        // ------------------------------------------------------------------

        /// <summary>
        /// Register a callback invoked whenever a piece is lifted or placed.
        /// The callback receives a <see cref="PieceEvent"/>.
        /// Multiple callbacks can be registered.
        /// </summary>
        /// <param name="callback">The callback to invoke on piece movement.</param>
        public void OnPieceMove(Action<PieceEvent> callback)
        {
            _pieceCallbacks.Add(callback);
        }

        /// <summary>
        /// Register a callback for every raw BLE notification (for debugging).
        /// The callback receives the raw byte array from the TX characteristic.
        /// </summary>
        /// <param name="callback">The callback to invoke on each raw notification.</param>
        public void OnRawNotification(Action<byte[]> callback)
        {
            _rawCallbacks.Add(callback);
        }

        // ------------------------------------------------------------------
        // Commands (App -> Board)
        // ------------------------------------------------------------------

        /// <summary>
        /// Request the battery percentage.
        /// </summary>
        /// <param name="timeoutMs">Timeout in milliseconds (default 5000).</param>
        /// <returns>Battery level 0-100 (%).</returns>
        /// <exception cref="TimeoutException">Thrown when the board does not respond in time.</exception>
        public async Task<int> GetBatteryAsync(int timeoutMs = 5000)
        {
            var tcs = Expect("battery");
            await WriteAsync(new byte[] { GoChessConstants.CmdCheckBattery });
            var result = await WaitWithTimeout(tcs, timeoutMs, "battery");
            return (int)(byte)result;
        }

        /// <summary>
        /// Request the battery voltage in millivolts.
        /// </summary>
        /// <param name="timeoutMs">Timeout in milliseconds (default 5000).</param>
        /// <returns>Battery voltage in mV.</returns>
        /// <exception cref="TimeoutException">Thrown when the board does not respond in time.</exception>
        public async Task<int> GetBatteryMvAsync(int timeoutMs = 5000)
        {
            var tcs = Expect("battery_mv");
            await WriteAsync(new byte[] { GoChessConstants.CmdCheckBatteryMv });
            var result = await WaitWithTimeout(tcs, timeoutMs, "battery_mv");
            return (int)result;
        }

        /// <summary>
        /// Request the full 8x8 board occupancy.
        /// </summary>
        /// <param name="timeoutMs">Timeout in milliseconds (default 5000).</param>
        /// <returns>A <see cref="BoardState"/> object.</returns>
        /// <exception cref="TimeoutException">Thrown when the board does not respond in time.</exception>
        public async Task<BoardState> GetBoardStateAsync(int timeoutMs = 5000)
        {
            var tcs = Expect("board_state");
            await WriteAsync(new byte[] { GoChessConstants.CmdGetBoardState });
            var result = await WaitWithTimeout(tcs, timeoutMs, "board_state");
            return (BoardState)result;
        }

        /// <summary>
        /// Request border/storage slot occupancy (36 positions around the board).
        /// <para>
        /// <b>GoChess XR (Robotic) only</b> - Mini and Lite boards do not have
        /// border slots and will not respond to this command (will timeout).
        /// </para>
        /// </summary>
        /// <param name="timeoutMs">Timeout in milliseconds (default 5000).</param>
        /// <returns>A <see cref="BorderState"/> object with the 36 border positions.</returns>
        /// <exception cref="TimeoutException">Thrown when the board does not respond in time.</exception>
        public async Task<BorderState> GetBorderStateAsync(int timeoutMs = 5000)
        {
            var tcs = Expect("border_state");
            await WriteAsync(new byte[] { GoChessConstants.CmdGetBorderState });
            var result = await WaitWithTimeout(tcs, timeoutMs, "border_state");
            return (BorderState)result;
        }

        /// <summary>
        /// Request the firmware version byte.
        /// </summary>
        /// <param name="timeoutMs">Timeout in milliseconds (default 5000).</param>
        /// <returns>
        /// Version number (e.g. 0x04 for GoChess Robotic, 0x03 for Mini/Lite).
        /// </returns>
        /// <exception cref="TimeoutException">Thrown when the board does not respond in time.</exception>
        public async Task<int> GetFwVersionAsync(int timeoutMs = 5000)
        {
            var tcs = Expect("fw_version");
            await WriteAsync(new byte[] { GoChessConstants.CmdGetFwVersion });
            var result = await WaitWithTimeout(tcs, timeoutMs, "fw_version");
            return (int)(byte)result;
        }

        /// <summary>
        /// Request the current draw measurement.
        /// </summary>
        /// <param name="timeoutMs">Timeout in milliseconds (default 5000).</param>
        /// <returns>Current in microamps (uA).</returns>
        /// <exception cref="TimeoutException">Thrown when the board does not respond in time.</exception>
        public async Task<int> GetCurrentAsync(int timeoutMs = 5000)
        {
            var tcs = Expect("current");
            await WriteAsync(new byte[] { GoChessConstants.CmdMeasureCurrent });
            var result = await WaitWithTimeout(tcs, timeoutMs, "current");
            return (int)result;
        }

        /// <summary>
        /// Request the charging state.
        /// </summary>
        /// <param name="timeoutMs">Timeout in milliseconds (default 5000).</param>
        /// <returns>True if the board is currently charging.</returns>
        /// <exception cref="TimeoutException">Thrown when the board does not respond in time.</exception>
        public async Task<bool> GetChargingStateAsync(int timeoutMs = 5000)
        {
            var tcs = Expect("charging");
            await WriteAsync(new byte[] { GoChessConstants.CmdGetChargingState });
            var result = await WaitWithTimeout(tcs, timeoutMs, "charging");
            return (bool)result;
        }

        /// <summary>
        /// Turn on LEDs for the given squares with a uniform colour (command 0x32).
        /// <para>
        /// An empty list with <paramref name="overwrite"/> = true turns all LEDs off.
        /// </para>
        /// </summary>
        /// <param name="squares">List of (row, col) tuples (1-indexed, 1-8).</param>
        /// <param name="r">Red component 0-255.</param>
        /// <param name="g">Green component 0-255.</param>
        /// <param name="b">Blue component 0-255.</param>
        /// <param name="overwrite">
        /// If true, squares not in the list are turned off.
        /// If false, only the listed squares are changed; others keep their current colour.
        /// </param>
        public async Task SetLedsAsync(
            List<(int Row, int Col)> squares,
            byte r = 0,
            byte g = 0,
            byte b = 0,
            bool overwrite = true)
        {
            var (mask1, mask2) = GoChessHelpers.BuildLedMasks(squares);
            byte[] maskBytes = GoChessHelpers.EncodeLedMasksToBytes(mask1, mask2);

            var data = new byte[13];
            data[0] = GoChessConstants.CmdSetRgbLeds;
            // data[1..8] = mask bytes (LE32 mask1, LE32 mask2)
            Array.Copy(maskBytes, 0, data, 1, 8);
            // The firmware stores data[9]->g_green, data[10]->g_red, but the main
            // loop passes them swapped to setLedsRGB_I2C (g_red as r-param,
            // g_green as g-param) and the physical LEDs are GRB-wired.
            // Net result: data[9] drives physical RED, data[10] drives physical GREEN.
            data[9] = r;
            data[10] = g;
            data[11] = b;
            data[12] = overwrite ? (byte)0x01 : (byte)0x00;

            await WriteAsync(data);
        }

        /// <summary>
        /// Turn off all board LEDs.
        /// </summary>
        public async Task SetLedsOffAsync()
        {
            await SetLedsAsync(new List<(int, int)>(), overwrite: true);
        }

        /// <summary>
        /// Set per-square LED colours with multiple colour groups (command 0x34).
        /// <para>
        /// This command first turns off all LEDs, then applies each group.
        /// </para>
        /// </summary>
        /// <param name="groups">
        /// A list of <see cref="LedGroup"/> objects, each specifying a set of squares
        /// and their shared RGB colour.
        /// </param>
        /// <exception cref="ArgumentException">Thrown when any square is outside the 1-8 range.</exception>
        /// <example>
        /// <code>
        /// await board.SetLedsSpecialAsync(new List&lt;LedGroup&gt;
        /// {
        ///     new LedGroup { Squares = { (4, 5), (5, 4) }, R = 0, G = 255, B = 0 },
        ///     new LedGroup { Squares = { (2, 3) }, R = 255, G = 0, B = 0 },
        /// });
        /// </code>
        /// </example>
        public async Task SetLedsSpecialAsync(List<LedGroup> groups)
        {
            var data = new List<byte> { GoChessConstants.CmdLedOnSpecial };

            foreach (var group in groups)
            {
                data.Add((byte)group.Squares.Count);
                foreach (var (row, col) in group.Squares)
                {
                    if (row < 1 || row > 8 || col < 1 || col > 8)
                        throw new ArgumentException($"Square ({row}, {col}) out of range 1-8.");
                    // Upper nibble = row, lower nibble = col
                    data.Add((byte)((row << 4) | col));
                }
                // Same GRB colour swap as 0x32 - first byte drives physical RED,
                // second byte drives physical GREEN (see SetLedsAsync for details).
                data.Add(group.R);
                data.Add(group.G);
                data.Add(group.B);
            }

            await WriteAsync(data.ToArray());
        }

        /// <summary>
        /// Convenience method: set LEDs using chess notation.
        /// <para>
        /// Uses <see cref="SetLedsSpecialAsync"/> under the hood so each square can
        /// have its own colour. Squares with the same colour are automatically grouped.
        /// </para>
        /// </summary>
        /// <param name="squareColors">
        /// Mapping of notation (e.g. "e2") to (R, G, B) colour tuple.
        /// Example: <c>new Dictionary&lt;string, (byte, byte, byte)&gt; { ["e2"] = (0, 255, 0), ["e4"] = (0, 255, 0) }</c>
        /// </param>
        public async Task SetLedsByNotationAsync(Dictionary<string, (byte R, byte G, byte B)> squareColors)
        {
            // Group squares by colour
            var colourGroups = new Dictionary<(byte, byte, byte), List<(int Row, int Col)>>();
            foreach (var kvp in squareColors)
            {
                var rc = GoChessHelpers.SquareNotationToRC(kvp.Key);
                var rgb = kvp.Value;
                if (!colourGroups.ContainsKey(rgb))
                    colourGroups[rgb] = new List<(int, int)>();
                colourGroups[rgb].Add(rc);
            }

            var groups = new List<LedGroup>();
            foreach (var kvp in colourGroups)
            {
                groups.Add(new LedGroup
                {
                    Squares = kvp.Value,
                    R = kvp.Key.Item1,
                    G = kvp.Key.Item2,
                    B = kvp.Key.Item3,
                });
            }

            await SetLedsSpecialAsync(groups);
        }

        // ------------------------------------------------------------------
        // Internal: send / receive
        // ------------------------------------------------------------------

        /// <summary>
        /// Ensures the board is currently connected. Throws if not.
        /// </summary>
        private void EnsureConnected()
        {
            if (_device == null || !_connected)
                throw new InvalidOperationException("Not connected to a GoChess board.");
        }

        /// <summary>
        /// Write raw bytes to the NUS RX characteristic (App -> Board).
        /// Automatically selects WriteWithoutResponse if the characteristic supports it.
        /// </summary>
        private async Task WriteAsync(byte[] data)
        {
            EnsureConnected();

            var writer = new DataWriter();
            writer.WriteBytes(data);
            IBuffer buffer = writer.DetachBuffer();

            // Prefer WriteWithoutResponse for lower latency (standard for NUS RX)
            GattWriteOption writeOption =
                _rxChar!.CharacteristicProperties.HasFlag(GattCharacteristicProperties.WriteWithoutResponse)
                    ? GattWriteOption.WriteWithoutResponse
                    : GattWriteOption.WriteWithResponse;

            var result = await _rxChar.WriteValueAsync(buffer, writeOption);
            if (result != GattCommunicationStatus.Success)
                throw new InvalidOperationException($"BLE write failed with status: {result}");
        }

        /// <summary>
        /// Create a <see cref="TaskCompletionSource{Object}"/> that will be
        /// resolved when a matching response arrives from the board.
        /// </summary>
        private TaskCompletionSource<object> Expect(string key)
        {
            var tcs = new TaskCompletionSource<object>(TaskCreationOptions.RunContinuationsAsynchronously);
            _pending[key] = tcs;
            return tcs;
        }

        /// <summary>
        /// Resolve a pending <see cref="TaskCompletionSource{Object}"/> by key.
        /// </summary>
        private void Resolve(string key, object value)
        {
            if (_pending.TryRemove(key, out var tcs))
                tcs.TrySetResult(value);
        }

        /// <summary>
        /// Wait for a <see cref="TaskCompletionSource{Object}"/> to complete
        /// with a timeout. Cleans up the pending entry on timeout.
        /// </summary>
        private async Task<object> WaitWithTimeout(
            TaskCompletionSource<object> tcs,
            int timeoutMs,
            string key)
        {
            var completed = await Task.WhenAny(tcs.Task, Task.Delay(timeoutMs));
            if (completed == tcs.Task)
                return await tcs.Task;

            // Timeout: clean up and throw
            _pending.TryRemove(key, out _);
            tcs.TrySetCanceled();
            throw new TimeoutException($"GoChess board did not respond to '{key}' within {timeoutMs}ms.");
        }

        // ------------------------------------------------------------------
        // Notification handler
        // ------------------------------------------------------------------

        /// <summary>
        /// Dispatches incoming BLE notifications from the TX characteristic.
        /// Routes messages to the appropriate parser based on the first byte.
        /// </summary>
        private void OnCharacteristicUpdated(GattCharacteristic sender, GattValueChangedEventArgs args)
        {
            var reader = DataReader.FromBuffer(args.CharacteristicValue);
            byte[] data = new byte[reader.UnconsumedBufferLength];
            reader.ReadBytes(data);

            if (data.Length == 0)
                return;

            // Invoke raw callbacks
            foreach (var cb in _rawCallbacks)
            {
                try
                {
                    cb(data);
                }
                catch
                {
                    // Swallow errors in user callbacks to protect notification loop
                }
            }

            byte first = data[0];

            // --- Framed message: [*][len][type][payload...][checksum][\r\n] ---
            if (first == GoChessConstants.StartByte && data.Length >= 5)
            {
                ParseFramed(data);
            }
            // --- Raw: Board state [0x03][8 bytes] ---
            else if (first == GoChessConstants.RespBoardState && data.Length >= 9)
            {
                var raw = new byte[8];
                Array.Copy(data, 1, raw, 0, 8);
                Resolve("board_state", new BoardState(raw));
            }
            // --- Raw: Border state [0x0C][6 bytes] ---
            else if (first == GoChessConstants.RespBorderState && data.Length >= 7)
            {
                var raw = new byte[6];
                Array.Copy(data, 1, raw, 0, 6);
                Resolve("border_state", new BorderState(raw));
            }
            // --- Raw: FW version "Ver" + byte ---
            else if (first == 0x56 && data.Length >= 4 && data[1] == (byte)'e' && data[2] == (byte)'r')
            {
                Resolve("fw_version", data[3]);
            }
            // --- Raw: Piece move on board ('1'-'8' first byte) ---
            else if (first >= 0x31 && first <= 0x38 && data.Length >= 3)
            {
                EmitPieceMove(data);
            }
            // --- Raw: Piece move on border ('r','l','t','b') ---
            else if ((first == (byte)'r' || first == (byte)'l' || first == (byte)'t' || first == (byte)'b')
                     && data.Length >= 3)
            {
                EmitBorderMove(data);
            }
        }

        /// <summary>
        /// Parse a framed message and resolve the matching pending future.
        /// Framed format: [START][LEN][TYPE][DATA...][CHECKSUM][CR][LF]
        /// </summary>
        private void ParseFramed(byte[] data)
        {
            byte msgType = data[2];

            if (msgType == GoChessConstants.RespBattery && data.Length >= 4)
            {
                Resolve("battery", data[3]);
            }
            else if (msgType == GoChessConstants.RespBatteryMv && data.Length >= 5)
            {
                int mv = (data[3] << 8) | data[4];
                Resolve("battery_mv", mv);
            }
            else if (msgType == GoChessConstants.RespCurrent && data.Length >= 5)
            {
                int ua = (data[3] << 8) | data[4];
                Resolve("current", ua);
            }
            else if (msgType == GoChessConstants.RespCharging && data.Length >= 4)
            {
                Resolve("charging", data[3] != 0);
            }
            else if (msgType == GoChessConstants.RespChamber && data.Length >= 4)
            {
                Resolve("chamber", data[3] != 0);
            }
        }

        /// <summary>
        /// Emit a <see cref="PieceEvent"/> for a board-square move.
        /// Data format: [row_ascii][col_ascii][direction_ascii]
        /// where row = '1'-'8', col = '1'-'8', direction = 'd' (down) or 'u' (up).
        /// </summary>
        private void EmitPieceMove(byte[] data)
        {
            int row = data[0] - 0x30; // ASCII '1'-'8' -> 1-8
            int col = data[1] - 0x30;
            bool isDown = data[2] == (byte)'d';

            var evt = new PieceEvent(
                row: row,
                col: col,
                isDown: isDown,
                isBorder: false,
                borderSide: "");

            DispatchPiece(evt);
        }

        /// <summary>
        /// Emit a <see cref="PieceEvent"/> for a border/storage slot move.
        /// <para>
        /// Positions 1-9 are 3-byte messages: [side][pos_ascii][direction].
        /// Position 10 is a 4-byte message: [side]['1']['0'][direction].
        /// </para>
        /// </summary>
        private void EmitBorderMove(byte[] data)
        {
            char side = (char)data[0];
            int position;
            bool isDown;

            // Positions 1-9 are 3-byte messages, position 10 is 4-byte ("r10d")
            if (data.Length >= 4 && data[1] == 0x31 && data[2] == 0x30)
            {
                // Position 10: side + '1' + '0' + direction
                position = 10;
                isDown = data[3] == (byte)'d';
            }
            else
            {
                // Positions 1-9: side + position_ascii + direction
                position = data[1] - 0x30;
                isDown = data[2] == (byte)'d';
            }

            var evt = new PieceEvent(
                row: 0,
                col: position,
                isDown: isDown,
                isBorder: true,
                borderSide: side.ToString());

            DispatchPiece(evt);
        }

        /// <summary>
        /// Invoke all registered piece-move callbacks with the given event.
        /// Exceptions in callbacks are silently caught to protect the notification loop.
        /// </summary>
        private void DispatchPiece(PieceEvent evt)
        {
            foreach (var cb in _pieceCallbacks)
            {
                try
                {
                    cb(evt);
                }
                catch
                {
                    // Swallow errors in user callbacks to protect notification loop
                }
            }
        }
    }
}
