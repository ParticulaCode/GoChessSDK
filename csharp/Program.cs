// ============================================================================
// GoChess SDK - Interactive Console Example
// ============================================================================
//
// Scans for boards, lets you pick one, shows status info, then enters an
// interactive menu where you can send any supported command while
// piece-movement events are printed in real time.
//
// BLE backend:
//   Windows Runtime (WinRT) Bluetooth APIs - no external NuGet packages needed.
//   Target: net8.0-windows10.0.19041.0
//
// How to run:
//   dotnet run
//
// ============================================================================

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using GoChess.Sdk;

namespace GoChess.Sdk.Example
{
    class Program
    {
        // ─────────────────────────────────────────────────────────────────────
        // Constants
        // ─────────────────────────────────────────────────────────────────────

        private const string ValidFiles = "abcdefgh";
        private const string ValidRanks = "12345678";
        private const string Files = "abcdefgh";

        // ─────────────────────────────────────────────────────────────────────
        // Non-blocking input  (lets BLE notifications print while waiting)
        // ─────────────────────────────────────────────────────────────────────

        /// <summary>
        /// Read a line from stdin without blocking the async event loop.
        /// This runs the blocking Console.ReadLine() in a thread-pool thread so
        /// that BLE notification callbacks (piece-move events) can still fire
        /// and print to the console while the program waits for user input.
        /// </summary>
        private static async Task<string> AsyncInput(string prompt = "")
        {
            if (!string.IsNullOrEmpty(prompt))
                Console.Write(prompt);
            return await Task.Run(() => Console.ReadLine()) ?? "";
        }

        // ─────────────────────────────────────────────────────────────────────
        // Helpers
        // ─────────────────────────────────────────────────────────────────────

        /// <summary>
        /// Convert a firmware border notification to a human-readable label.
        ///
        /// The firmware sends 1-indexed positions (1-10) for border slots.
        /// This maps them to the correct 0-indexed labels:
        ///   - 't' (top):    position 1-8  -> a9..h9
        ///   - 'b' (bottom): position 1-8  -> a0..h0
        ///   - 'l' (left):   position 1-10 -> q0..q9
        ///   - 'r' (right):  position 1-10 -> i0..i9
        /// </summary>
        private static string BorderEventToLabel(string side, int position)
        {
            if (side == "t" && position >= 1 && position <= 8)
                return Files[position - 1] + "9";
            else if (side == "b" && position >= 1 && position <= 8)
                return Files[position - 1] + "0";
            else if (side == "l" && position >= 1 && position <= 10)
                return $"q{position - 1}";
            else if (side == "r" && position >= 1 && position <= 10)
                return $"i{position - 1}";
            return $"{side}{position}";
        }

        /// <summary>
        /// Piece-move callback. Called every time a piece is lifted or placed.
        /// </summary>
        private static void OnPieceMoved(PieceEvent evt)
        {
            if (evt.IsBorder)
            {
                string label = BorderEventToLabel(evt.BorderSide, evt.Col);
                string action = evt.IsDown ? "placed on" : "lifted from";
                Console.WriteLine($"\n  >> Border piece {action} {label}  (side='{evt.BorderSide}', pos={evt.Col})");
            }
            else
            {
                string square = GoChessHelpers.RCToSquareNotation(evt.Row, evt.Col);
                string action = evt.IsDown ? "PLACED on" : "LIFTED from";
                Console.WriteLine($"\n  >> Piece {action} {square}  (row={evt.Row}, col={evt.Col})");
            }
        }

        /// <summary>
        /// Parse a comma-separated list of chess-notation squares.
        /// e.g. "e2, e4, d4" -> [(2,5), (4,5), (4,4)]
        /// </summary>
        private static List<(int Row, int Col)> ParseSquares(string text)
        {
            var squares = new List<(int Row, int Col)>();
            foreach (string part in text.Split(','))
            {
                string token = part.Trim().ToLower();
                if (token.Length != 2)
                    throw new FormatException($"Invalid square '{token}'. Use notation like e4.");

                char f = token[0];
                char r = token[1];

                if (ValidFiles.IndexOf(f) < 0 || ValidRanks.IndexOf(r) < 0)
                    throw new FormatException($"Invalid square '{token}'. File must be a-h, rank 1-8.");

                int col = f - 'a' + 1;
                int row = r - '0';
                squares.Add((row, col));
            }
            return squares;
        }

        /// <summary>
        /// Parse an RGB string like "255,0,0" or a colour name shorthand.
        /// </summary>
        private static (byte R, byte G, byte B) ParseColor(string text)
        {
            var shortcuts = new Dictionary<string, (byte R, byte G, byte B)>
            {
                ["red"]     = (255, 0, 0),
                ["green"]   = (0, 255, 0),
                ["blue"]    = (0, 0, 255),
                ["yellow"]  = (255, 255, 0),
                ["cyan"]    = (0, 255, 255),
                ["magenta"] = (255, 0, 255),
                ["white"]   = (255, 255, 255),
                ["orange"]  = (255, 128, 0),
                ["purple"]  = (128, 0, 255),
                ["off"]     = (0, 0, 0),
            };

            text = text.Trim().ToLower();
            if (shortcuts.TryGetValue(text, out var colour))
                return colour;

            string[] parts = text.Split(',');
            if (parts.Length != 3)
                throw new FormatException("Color must be R,G,B (e.g. 255,0,0) or a name (red, green, blue, ...).");

            return (
                byte.Parse(parts[0].Trim()),
                byte.Parse(parts[1].Trim()),
                byte.Parse(parts[2].Trim())
            );
        }

        private static void PrintMenu()
        {
            Console.WriteLine();
            Console.WriteLine(new string('=', 62));
            Console.WriteLine("  GoChess Board \u2013 Command Menu");
            Console.WriteLine(new string('=', 62));
            Console.WriteLine();
            Console.WriteLine("  1  Get battery level");
            Console.WriteLine("  2  Get board state (8\u00d78 occupancy)");
            Console.WriteLine("  3  Get border state (robotic storage slots)");
            Console.WriteLine("  4  Get firmware version");
            Console.WriteLine("  5  Set LEDs \u2013 uniform colour (0x32)");
            Console.WriteLine("  6  Set LEDs \u2013 special / multi-colour (0x34)");
            Console.WriteLine("  7  Turn off all LEDs");
            Console.WriteLine("  0  Disconnect & exit");
            Console.WriteLine();
            Console.WriteLine("  Piece movements are shown in real-time as they happen.");
            Console.WriteLine(new string('-', 62));
        }

        // ─────────────────────────────────────────────────────────────────────
        // Interactive command handlers
        // ─────────────────────────────────────────────────────────────────────

        private static async Task CmdBattery(GoChessBoard board)
        {
            try
            {
                int battery = await board.GetBatteryAsync();
                Console.WriteLine($"\n  Battery: {battery}%");
            }
            catch (TimeoutException)
            {
                Console.WriteLine("\n  Battery request timed out.");
            }
        }

        private static async Task CmdBoardState(GoChessBoard board)
        {
            try
            {
                BoardState state = await board.GetBoardStateAsync();
                Console.WriteLine($"\n  {state.GetType().Name}(pieces={state.PieceCount})");
                Console.WriteLine(state);
            }
            catch (TimeoutException)
            {
                Console.WriteLine("\n  Board state request timed out.");
            }
        }

        private static async Task CmdBorderState(GoChessBoard board, bool isXr)
        {
            if (!isXr)
            {
                Console.WriteLine("\n  Border state is only available on GoChess XR (Robotic).");
                Console.WriteLine("  This board is a Mini/Lite model and does not have border slots.");
                return;
            }
            try
            {
                BorderState border = await board.GetBorderStateAsync();
                string hexStr = string.Join(" ", border.RawBytes.Select(b => $"0x{b:X2}"));
                Console.WriteLine($"\n  BorderState(occupied={border.OccupiedCount})");
                Console.WriteLine($"  Raw bytes: {hexStr}");
                Console.WriteLine(border);
            }
            catch (TimeoutException)
            {
                Console.WriteLine("\n  Border state request timed out.");
            }
        }

        private static async Task CmdFwVersion(GoChessBoard board)
        {
            try
            {
                int fw = await board.GetFwVersionAsync();
                var labels = new Dictionary<int, string>
                {
                    [0x03] = "GoChess Mini / Lite",
                    [0x04] = "GoChess Robotic (XR)",
                };
                string label = labels.TryGetValue(fw, out string? l) ? l : "Unknown";
                Console.WriteLine($"\n  Firmware version: 0x{fw:X2}  ({label})");
            }
            catch (TimeoutException)
            {
                Console.WriteLine("\n  FW version request timed out.");
            }
        }

        /// <summary>
        /// Interactive flow for command 0x32 - uniform colour LEDs.
        /// </summary>
        private static async Task CmdSetLeds(GoChessBoard board)
        {
            Console.WriteLine();
            Console.WriteLine("  \u2500\u2500 Set LEDs (uniform colour) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
            Console.WriteLine("  Enter squares in chess notation, comma-separated.");
            Console.WriteLine("  Example: e2, e4, d4, d5");
            Console.WriteLine("  (empty = turn off all)");
            Console.WriteLine();

            string raw = (await AsyncInput("  Squares: ")).Trim();
            if (string.IsNullOrEmpty(raw))
            {
                await board.SetLedsOffAsync();
                Console.WriteLine("  All LEDs turned off.");
                return;
            }

            List<(int Row, int Col)> squares;
            try
            {
                squares = ParseSquares(raw);
            }
            catch (FormatException e)
            {
                Console.WriteLine($"  Error: {e.Message}");
                return;
            }

            Console.WriteLine();
            Console.WriteLine("  Enter colour as R,G,B (0-255) or a name:");
            Console.WriteLine("  Names: red, green, blue, yellow, cyan, magenta, white, orange, purple");
            Console.WriteLine();

            string rawColor = (await AsyncInput("  Colour: ")).Trim();
            byte r, g, b;
            try
            {
                (r, g, b) = ParseColor(rawColor);
            }
            catch (FormatException e)
            {
                Console.WriteLine($"  Error: {e.Message}");
                return;
            }

            Console.WriteLine();
            string rawOw = (await AsyncInput("  Overwrite other LEDs? (y/n) [y]: ")).Trim().ToLower();
            bool overwrite = rawOw != "n";

            var labels = squares.Select(s => GoChessHelpers.RCToSquareNotation(s.Row, s.Col));
            Console.WriteLine($"\n  Sending: squares={string.Join(", ", labels)}  colour=({r},{g},{b})  overwrite={overwrite}");
            await board.SetLedsAsync(squares, r: r, g: g, b: b, overwrite: overwrite);
            Console.WriteLine("  Done.");
        }

        /// <summary>
        /// Interactive flow for command 0x34 - multi-colour per-square LEDs.
        /// </summary>
        private static async Task CmdSetLedsSpecial(GoChessBoard board)
        {
            Console.WriteLine();
            Console.WriteLine("  \u2500\u2500 Set LEDs Special (multi-colour groups) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
            Console.WriteLine("  This command clears all LEDs first, then applies each");
            Console.WriteLine("  colour group you define.");
            Console.WriteLine();
            Console.WriteLine("  You will add one colour group at a time.");
            Console.WriteLine("  Each group = a set of squares + one colour.");
            Console.WriteLine();

            var groups = new List<LedGroup>();
            int groupNum = 1;

            while (true)
            {
                Console.WriteLine($"  \u2500\u2500 Group {groupNum} \u2500\u2500");
                string raw = (await AsyncInput("  Squares (e.g. e2, e4): ")).Trim();
                if (string.IsNullOrEmpty(raw))
                {
                    if (groups.Count == 0)
                    {
                        Console.WriteLine("  No groups added. Cancelled.");
                        return;
                    }
                    break;
                }

                List<(int Row, int Col)> squares;
                try
                {
                    squares = ParseSquares(raw);
                }
                catch (FormatException e)
                {
                    Console.WriteLine($"  Error: {e.Message}");
                    continue;
                }

                Console.WriteLine("  Colour as R,G,B or name (red, green, blue, yellow, ...):");
                string rawColor = (await AsyncInput("  Colour: ")).Trim();
                byte r, g, b;
                try
                {
                    (r, g, b) = ParseColor(rawColor);
                }
                catch (FormatException e)
                {
                    Console.WriteLine($"  Error: {e.Message}");
                    continue;
                }

                var labels = squares.Select(s => GoChessHelpers.RCToSquareNotation(s.Row, s.Col));
                groups.Add(new LedGroup
                {
                    Squares = squares,
                    R = r,
                    G = g,
                    B = b,
                });
                Console.WriteLine($"  Added group {groupNum}: {string.Join(", ", labels)} \u2192 ({r},{g},{b})");
                groupNum++;

                string more = (await AsyncInput("  Add another group? (y/n) [n]: ")).Trim().ToLower();
                if (more != "y")
                    break;
            }

            Console.WriteLine($"\n  Sending {groups.Count} colour group(s)...");
            await board.SetLedsSpecialAsync(groups);
            Console.WriteLine("  Done.");
        }

        // ─────────────────────────────────────────────────────────────────────
        // Main
        // ─────────────────────────────────────────────────────────────────────

        static async Task Main(string[] args)
        {
            // ── 1. Scan ─────────────────────────────────────────────────────
            Console.WriteLine("Scanning for GoChess boards (5 seconds)...");
            List<GoChessDevice> devices = await GoChessBoard.ScanAsync(TimeSpan.FromSeconds(5));

            if (devices.Count == 0)
            {
                Console.WriteLine("No GoChess boards found. Make sure the board is on and nearby.");
                return;
            }

            Console.WriteLine($"\nFound {devices.Count} board(s):");
            foreach (var d in devices)
            {
                Console.WriteLine($"  [{d.Index}]  {d.Name}  ({d.Address})");
            }

            // Let the user choose which board to connect to
            int choice;
            while (true)
            {
                try
                {
                    string input = await AsyncInput($"\nEnter board index to connect [0-{devices.Count - 1}]: ");
                    choice = int.Parse(input.Trim());
                    if (choice >= 0 && choice < devices.Count)
                        break;
                    Console.WriteLine($"  Invalid index. Please enter a number between 0 and {devices.Count - 1}.");
                }
                catch (FormatException)
                {
                    Console.WriteLine("  Please enter a valid number.");
                }
            }

            GoChessDevice chosen = devices[choice];
            Console.WriteLine($"\nConnecting to [{chosen.Index}] {chosen.Name} ...");

            // ── 2. Connect ──────────────────────────────────────────────────
            var board = new GoChessBoard();
            await board.ConnectAsync(chosen);
            Console.WriteLine("Connected!\n");

            // ── 3. Register piece-move callback ─────────────────────────────
            board.OnPieceMove(OnPieceMoved);

            // ── 4. Show board info ──────────────────────────────────────────
            // Detect board type: XR (Robotic) supports border state, Mini/Lite don't
            bool isXr = chosen.Name.StartsWith("GoChessXR", StringComparison.OrdinalIgnoreCase);

            Console.WriteLine(new string('\u2500', 50));

            var fwLabels = new Dictionary<int, string>
            {
                [0x03] = "GoChess Mini / Lite",
                [0x04] = "GoChess Robotic (XR)",
            };

            try
            {
                int fw = await board.GetFwVersionAsync();
                string fwLabel = fwLabels.TryGetValue(fw, out string? l) ? l : "Unknown";
                Console.WriteLine($"  Firmware : 0x{fw:X2}  ({fwLabel})");
                if (fw == 0x04)
                    isXr = true;
            }
            catch (TimeoutException)
            {
                Console.WriteLine("  Firmware : (timed out)");
            }

            try
            {
                int battery = await board.GetBatteryAsync();
                Console.WriteLine($"  Battery  : {battery}%");
            }
            catch (TimeoutException)
            {
                Console.WriteLine("  Battery  : (timed out)");
            }

            Console.WriteLine(isXr
                ? "  Board    : GoChess XR (Robotic) \u2013 border state supported"
                : "  Board    : GoChess Mini/Lite \u2013 no border slots");
            Console.WriteLine(new string('\u2500', 50));
            Console.WriteLine("  Piece movements will appear in real-time.");

            // ── 5. Interactive menu loop ─────────────────────────────────────
            while (true)
            {
                PrintMenu();
                string raw = (await AsyncInput("  Choose [0-7]: ")).Trim();

                switch (raw)
                {
                    case "0":
                        goto exit_loop;
                    case "1":
                        await CmdBattery(board);
                        break;
                    case "2":
                        await CmdBoardState(board);
                        break;
                    case "3":
                        await CmdBorderState(board, isXr);
                        break;
                    case "4":
                        await CmdFwVersion(board);
                        break;
                    case "5":
                        await CmdSetLeds(board);
                        break;
                    case "6":
                        await CmdSetLedsSpecial(board);
                        break;
                    case "7":
                        await board.SetLedsOffAsync();
                        Console.WriteLine("\n  All LEDs turned off.");
                        break;
                    default:
                        Console.WriteLine("  Unknown option. Please enter 0-7.");
                        break;
                }
            }

        exit_loop:
            // ── 6. Disconnect ───────────────────────────────────────────────
            await board.DisconnectAsync();
            Console.WriteLine("\nDisconnected. Bye!");
        }
    }
}
