///
/// GoChessSdk.swift
/// ================
///
/// A single-file BLE SDK for communicating with GoChess smart chess boards
/// from iOS and macOS applications using CoreBluetooth.
///
/// The GoChess board uses an nRF52832 BLE SoC with the Nordic UART Service (NUS)
/// for bidirectional communication. This SDK provides a high-level async/await API for:
///
/// - Scanning and connecting to GoChess boards
/// - Receiving real-time piece movement notifications (via Hall effect sensors)
/// - Querying battery level, board state, border state, and firmware version
/// - Controlling per-square RGB LEDs
///
/// **No external dependencies** -- uses CoreBluetooth only.
///
/// Requirements:
///   - iOS 15.0+ / macOS 12.0+  (for Swift concurrency / async-await)
///   - Info.plist must include `NSBluetoothAlwaysUsageDescription`
///
/// Supported boards:
///   - GoChess XR (Robotic) -- advertises as "GoChessXR_XXXXXX"
///   - GoChess Mini          -- advertises as "GoChessM_XXXXXX"
///   - GoChess Lite          -- advertises as "GoChessL_XXXXXX"
///
/// Protocol notes:
///   The board uses two message formats over the NUS TX characteristic:
///
///   1. Raw messages (no framing):
///      - Piece move notifications: 3-4 ASCII bytes  e.g. "81d"
///      - Board state (0x03):  [0x03][8 bytes]
///      - Border state (0x0C): [0x0C][6 bytes]
///      - FW version:          "Ver" + version_byte
///
///   2. Framed messages:
///      [START][LEN][TYPE][DATA...][CHECKSUM][CR][LF]
///      - START = 0x2A ('*')
///      - LEN   = total bytes from START through CHECKSUM (inclusive)
///      - CHECKSUM = sum of all bytes from START through DATA
///      - CR LF = 0x0D 0x0A
///      Used for: battery, charging state, etc.
///

import Foundation
import CoreBluetooth
import os

// MARK: - Logger

private let log = OSLog(subsystem: "com.gochess.sdk", category: "GoChess")

// MARK: - Constants

/// BLE UUIDs and command/response bytes for the GoChess protocol.
enum GoChessConstants {

    // MARK: Nordic UART Service UUIDs

    /// Nordic UART Service UUID.
    static let nusServiceUUID = CBUUID(string: "6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    /// NUS RX Characteristic UUID (App -> Board, write).
    static let nusRxCharUUID  = CBUUID(string: "6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    /// NUS TX Characteristic UUID (Board -> App, notify).
    static let nusTxCharUUID  = CBUUID(string: "6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    // MARK: Command Bytes (App -> Board)

    /// Request border/storage slot occupancy (GoChess XR only).
    static let cmdGetBorderState: UInt8       = 0x22
    /// Set LEDs with uniform colour using bitmask (13-byte payload).
    static let cmdSetRgbLeds: UInt8           = 0x32
    /// Enable/disable LED-on-sensor mode (1 byte: 0x00/0x01).
    static let cmdLedOnSensor: UInt8          = 0x33
    /// Set per-square LED colours with multiple colour groups (variable length).
    static let cmdLedOnSpecial: UInt8         = 0x34
    /// Request full 8x8 board occupancy state.
    static let cmdGetBoardState: UInt8        = 0x35
    /// Set LED mode (1 byte: mode 0/1/2).
    static let cmdSetLedsMode: UInt8          = 0x36
    /// Pattern LED on.
    static let cmdPatternLedOn: UInt8         = 0x37
    /// Pattern 2 LED on.
    static let cmdPattern2LedOn: UInt8        = 0x38
    /// Request battery percentage.
    static let cmdCheckBattery: UInt8         = 0x39
    /// Request battery voltage in millivolts.
    static let cmdCheckBatteryMv: UInt8       = 0x3A
    /// Set interrupt mask (8 bytes).
    static let cmdIntMask: UInt8              = 0x3B
    /// Measure current draw.
    static let cmdMeasureCurrent: UInt8       = 0x3C
    /// Request charging state.
    static let cmdGetChargingState: UInt8     = 0x3D
    /// Complex LED message.
    static let cmdComplexLedMessage: UInt8    = 0x3E
    /// Complex LED parts.
    static let cmdComplexLedParts: UInt8      = 0x3F
    /// Request firmware version ('v').
    static let cmdGetFwVersion: UInt8         = 0x76

    // MARK: Response Types (Board -> App)

    /// Framed response: battery percentage (1-byte payload).
    static let respBattery: UInt8      = 0x01
    /// Framed response: battery voltage in mV (2-byte big-endian payload).
    static let respBatteryMv: UInt8    = 0x02
    /// Raw response: board occupancy state (8 bytes following type byte).
    static let respBoardState: UInt8   = 0x03
    /// Framed response: current draw in uA (2-byte big-endian payload).
    static let respCurrent: UInt8      = 0x04
    /// Framed response: charging state (1-byte payload, 0/1).
    static let respCharging: UInt8     = 0x07
    /// Framed response: chamber state (1-byte payload, 0/1).
    static let respChamber: UInt8      = 0x0B
    /// Raw response: border/storage slot occupancy (6 bytes following type byte).
    static let respBorderState: UInt8  = 0x0C

    // MARK: Protocol Constants

    /// Start byte for framed messages ('*').
    static let startByte: UInt8 = 0x2A
}

// MARK: - Errors

/// Errors that can be thrown by the GoChess SDK.
enum GoChessError: Error, LocalizedError {
    /// No board is currently connected.
    case notConnected
    /// A command timed out waiting for a response.
    case timeout(String)
    /// Bluetooth is powered off.
    case bluetoothOff
    /// The app is not authorized to use Bluetooth.
    case bluetoothUnauthorized
    /// No GoChess device was found during scanning.
    case deviceNotFound
    /// The NUS service was not found on the peripheral.
    case serviceNotFound
    /// A required NUS characteristic was not found.
    case characteristicNotFound
    /// A square coordinate is out of the valid 1-8 range.
    case invalidSquare(Int, Int)
    /// An invalid chess notation string was provided.
    case invalidNotation(String)
    /// Invalid or unexpected data was received.
    case invalidData(String)

    var errorDescription: String? {
        switch self {
        case .notConnected:
            return "Not connected to a GoChess board."
        case .timeout(let key):
            return "Timed out waiting for response: \(key)"
        case .bluetoothOff:
            return "Bluetooth is powered off."
        case .bluetoothUnauthorized:
            return "Bluetooth authorization denied."
        case .deviceNotFound:
            return "No GoChess device found."
        case .serviceNotFound:
            return "NUS service not found on peripheral."
        case .characteristicNotFound:
            return "NUS characteristic not found."
        case .invalidSquare(let row, let col):
            return "Square (\(row), \(col)) out of range 1-8."
        case .invalidNotation(let notation):
            return "Invalid chess notation: \(notation)"
        case .invalidData(let detail):
            return "Invalid data: \(detail)"
        }
    }
}

// MARK: - Data Types

/// A discovered GoChess board from BLE scanning.
struct GoChessDevice {
    /// Index in the scan results list.
    let index: Int
    /// Advertised device name (e.g. "GoChessXR_ABC123").
    let name: String
    /// The peripheral's identifier UUID.
    let identifier: UUID

    /// Internal reference to the CoreBluetooth peripheral (not part of public API).
    internal let peripheral: CBPeripheral
}

/// A piece-movement event from the Hall-effect sensors.
///
/// For board squares: `row` = 1-8, `col` = 1-8, `isBorder` = false.
/// For border/storage slots (GoChess XR): `borderSide` is one of "r","l","t","b",
/// `col` holds the position (1-10), `isBorder` = true.
struct PieceEvent {
    /// Row on the board (1-8), or 0 for border events.
    let row: Int
    /// Column on the board (1-8), or position (1-10) for border events.
    let col: Int
    /// `true` if a piece was placed (down), `false` if lifted (up).
    let isDown: Bool
    /// `true` if this event is from a border/storage slot.
    let isBorder: Bool
    /// Border side character ("r", "l", "t", "b"), or empty string for board squares.
    let borderSide: String
}

/// 8x8 board occupancy state.
///
/// Each square is either occupied (piece present) or empty.
/// Indexed 1-8 for both row and column matching chess convention:
///   - Row 1 = White's back rank, Row 8 = Black's back rank
///   - Col 1 = a-file, Col 8 = h-file
///
/// Usage:
/// ```swift
/// let state = try await board.getBoardState()
/// if state.isOccupied(row: 1, col: 5) {  // e1
///     print("King is home")
/// }
/// print(state)  // pretty-print
/// ```
class BoardState: CustomStringConvertible {
    /// The raw 8 bytes of occupancy data.
    let rawBytes: Data

    /// Initialize from 8 raw bytes where each byte is a row's bitmask.
    ///
    /// - Parameter raw: Exactly 8 bytes of board data.
    /// - Throws: `GoChessError.invalidData` if the data is not 8 bytes.
    init(raw: Data) throws {
        guard raw.count == 8 else {
            throw GoChessError.invalidData("BoardState expected 8 bytes, got \(raw.count)")
        }
        self.rawBytes = raw
    }

    /// Check if a square has a piece.
    ///
    /// - Parameters:
    ///   - row: Row (1-8).
    ///   - col: Column (1-8).
    /// - Returns: `true` if the square is occupied.
    /// - Throws: `GoChessError.invalidSquare` if coordinates are out of range.
    func isOccupied(row: Int, col: Int) throws -> Bool {
        guard (1...8).contains(row), (1...8).contains(col) else {
            throw GoChessError.invalidSquare(row, col)
        }
        return rawBytes[row - 1] & (1 << (col - 1)) != 0
    }

    /// Return an 8x8 matrix of booleans (row-major, 0-indexed).
    ///
    /// `matrix[r][c]` corresponds to board row `r+1`, column `c+1`.
    func toMatrix() -> [[Bool]] {
        return (1...8).map { r in
            (1...8).map { c in
                rawBytes[r - 1] & (1 << (c - 1)) != 0
            }
        }
    }

    /// Number of occupied squares on the board.
    var pieceCount: Int {
        var count = 0
        for r in 1...8 {
            for c in 1...8 {
                if rawBytes[r - 1] & (1 << (c - 1)) != 0 {
                    count += 1
                }
            }
        }
        return count
    }

    var description: String {
        let header = "  a b c d e f g h"
        var lines = [header]
        for row in stride(from: 8, through: 1, by: -1) {
            let cells = (1...8).map { col -> String in
                (rawBytes[row - 1] & (1 << (col - 1)) != 0) ? "\u{25A0}" : "\u{00B7}"
            }.joined(separator: " ")
            lines.append("\(row) \(cells)")
        }
        return lines.joined(separator: "\n")
    }
}

/// Border/storage slot occupancy state (36 positions surrounding the board).
///
/// Position labels:
///   - Top row (rank 9):    a9, b9, c9, d9, e9, f9, g9, h9
///   - Bottom row (rank 0): a0, b0, c0, d0, e0, f0, g0, h0
///   - Left col (file "q"): q0, q1, q2, q3, q4, q5, q6, q7, q8, q9
///   - Right col (file "i"): i0, i1, i2, i3, i4, i5, i6, i7, i8, i9
///   - Corners: q9 = top-left, i9 = top-right, q0 = bottom-left, i0 = bottom-right
///
/// Raw byte mapping (6 bytes after the 0x0C message-type byte):
///   - byte 0: Top border    -- bits 0-7 map to a9,b9,c9,d9,e9,f9,g9,h9
///   - byte 1: Bottom border -- bits 0-7 map to a0,b0,c0,d0,e0,f0,g0,h0
///   - byte 2: Left column   -- bit N = q(N) for N=0..7
///   - byte 3: Left extension -- bit 0 = q8, bit 1 = q9
///   - byte 4: Right column  -- bit N = i(N) for N=0..7
///   - byte 5: Right extension -- bit 0 = i8, bit 1 = i9
///
/// Usage:
/// ```swift
/// let border = try await board.getBorderState()
/// if border.isOccupied("a9") {
///     print("Piece on top border a9")
/// }
/// print(border)
/// ```
class BorderState: CustomStringConvertible {

    private static let files = ["a", "b", "c", "d", "e", "f", "g", "h"]

    /// The raw 6 bytes of border data.
    let rawBytes: Data

    /// All 36 position labels mapped to their occupied state.
    private(set) var slots: [String: Bool] = [:]

    /// Initialize from 6 raw bytes of border data.
    ///
    /// - Parameter raw: Exactly 6 bytes of border data.
    /// - Throws: `GoChessError.invalidData` if the data is not 6 bytes.
    init(raw: Data) throws {
        guard raw.count == 6 else {
            throw GoChessError.invalidData("BorderState expected 6 bytes, got \(raw.count)")
        }
        self.rawBytes = raw
        self.parse()
    }

    private func parse() {
        let raw = rawBytes

        // Byte 0: Top border (rank 9) -- bit N -> file[N] + "9"
        for i in 0..<8 {
            slots[Self.files[i] + "9"] = raw[0] & (1 << i) != 0
        }
        // Byte 1: Bottom border (rank 0) -- bit N -> file[N] + "0"
        for i in 0..<8 {
            slots[Self.files[i] + "0"] = raw[1] & (1 << i) != 0
        }
        // Byte 2: Left column (file "q") -- bit N -> "q" + str(N) for N=0..7
        for i in 0..<8 {
            slots["q\(i)"] = raw[2] & (1 << i) != 0
        }
        // Byte 3: Left extension -- bit 0 -> q8, bit 1 -> q9
        slots["q8"] = raw[3] & 0x01 != 0
        slots["q9"] = raw[3] & 0x02 != 0
        // Byte 4: Right column (file "i") -- bit N -> "i" + str(N) for N=0..7
        for i in 0..<8 {
            slots["i\(i)"] = raw[4] & (1 << i) != 0
        }
        // Byte 5: Right extension -- bit 0 -> i8, bit 1 -> i9
        slots["i8"] = raw[5] & 0x01 != 0
        slots["i9"] = raw[5] & 0x02 != 0
    }

    /// Check if a border position is occupied.
    ///
    /// - Parameter position: Label like "a9", "q0", "i5", etc.
    /// - Returns: `true` if occupied, `false` if empty or unknown.
    func isOccupied(_ position: String) -> Bool {
        return slots[position] ?? false
    }

    /// Count of occupied border slots.
    var occupiedCount: Int {
        return slots.values.filter { $0 }.count
    }

    var description: String {
        func c(_ pos: String) -> String {
            isOccupied(pos) ? "\u{25A0}" : "\u{00B7}"
        }

        var lines: [String] = []
        // Top row
        let topFiles = Self.files.map { c($0 + "9") }.joined(separator: " ")
        lines.append("\(c("q9")) \(topFiles) \(c("i9"))")
        // Side rows 8 -> 1
        for r in stride(from: 8, through: 1, by: -1) {
            let innerCells = String(repeating: "\u{00B7} ", count: 8)
            lines.append("\(c("q\(r)")) \(innerCells) \(c("i\(r)"))")
        }
        // Bottom row
        let botFiles = Self.files.map { c($0 + "0") }.joined(separator: " ")
        lines.append("\(c("q0")) \(botFiles) \(c("i0"))")

        return lines.joined(separator: "\n")
    }
}

/// A group of squares with a uniform LED colour, used with `setLedsSpecial`.
struct LedGroup {
    /// List of (row, col) tuples (1-indexed, 1-8).
    let squares: [(row: Int, col: Int)]
    /// Red component (0-255).
    let r: UInt8
    /// Green component (0-255).
    let g: UInt8
    /// Blue component (0-255).
    let b: UInt8
}

// MARK: - Helpers

/// Helper functions for LED mask encoding and chess notation conversion.
enum GoChessHelpers {

    /// Convert a list of (row, col) pairs (1-indexed) to firmware LED bitmasks.
    ///
    /// - Parameter squares: Array of (row, col) tuples where row and col are 1-8.
    /// - Returns: Tuple of `(mask_rows1to4, mask_rows5to8)`.
    /// - Throws: `GoChessError.invalidSquare` if any coordinate is out of range.
    static func buildLedMasks(_ squares: [(Int, Int)]) throws -> (UInt32, UInt32) {
        var mask1: UInt32 = 0  // rows 1-4 (LED indices 0-31)
        var mask2: UInt32 = 0  // rows 5-8 (LED indices 32-63)
        for (row, col) in squares {
            guard (1...8).contains(row), (1...8).contains(col) else {
                throw GoChessError.invalidSquare(row, col)
            }
            if row <= 4 {
                mask1 |= 1 << ((row - 1) * 8 + (col - 1))
            } else {
                mask2 |= 1 << ((row - 5) * 8 + (col - 1))
            }
        }
        return (mask1, mask2)
    }

    /// Encode LED masks into the 8 data bytes expected by the 0x32 command.
    ///
    /// Both masks are encoded as little-endian 32-bit integers. No bit reversal
    /// is performed here -- the firmware applies its own `reverse_num()` after
    /// decoding, so we send the raw logical masks directly.
    ///
    /// - Parameters:
    ///   - mask1: Bitmask for rows 1-4.
    ///   - mask2: Bitmask for rows 5-8.
    /// - Returns: 8 bytes (mask1 LE32 + mask2 LE32).
    static func encodeLedMasksToBytes(_ mask1: UInt32, _ mask2: UInt32) -> Data {
        var data = Data(count: 8)
        data[0] = UInt8(mask1 & 0xFF)
        data[1] = UInt8((mask1 >> 8) & 0xFF)
        data[2] = UInt8((mask1 >> 16) & 0xFF)
        data[3] = UInt8((mask1 >> 24) & 0xFF)
        data[4] = UInt8(mask2 & 0xFF)
        data[5] = UInt8((mask2 >> 8) & 0xFF)
        data[6] = UInt8((mask2 >> 16) & 0xFF)
        data[7] = UInt8((mask2 >> 24) & 0xFF)
        return data
    }

    /// Convert chess notation like "e4" to a (row, col) tuple (1-indexed).
    ///
    /// - Parameter notation: A two-character string like "a1" through "h8".
    /// - Returns: Tuple of (row, col).
    /// - Throws: `GoChessError.invalidNotation` if the notation is malformed.
    static func squareNotationToRC(_ notation: String) throws -> (Int, Int) {
        guard notation.count == 2 else {
            throw GoChessError.invalidNotation(notation)
        }
        let chars = Array(notation.lowercased())
        let fileChar = chars[0]
        let rankChar = chars[1]

        guard fileChar >= "a", fileChar <= "h" else {
            throw GoChessError.invalidNotation(notation)
        }
        guard rankChar >= "1", rankChar <= "8" else {
            throw GoChessError.invalidNotation(notation)
        }

        let col = Int(fileChar.asciiValue! - Character("a").asciiValue!) + 1
        let row = Int(String(rankChar))!
        return (row, col)
    }

    /// Convert a (row, col) tuple (1-indexed) to chess notation like "e4".
    ///
    /// - Parameters:
    ///   - row: Row (1-8).
    ///   - col: Column (1-8).
    /// - Returns: Two-character chess notation string.
    static func rcToSquareNotation(row: Int, col: Int) -> String {
        let file = Character(UnicodeScalar(UInt8(col - 1) + Character("a").asciiValue!))
        return "\(file)\(row)"
    }
}

// MARK: - GoChessBoard

/// High-level interface to a GoChess smart chess board.
///
/// Usage:
/// ```swift
/// let board = GoChessBoard()
/// let devices = try await board.scan()
/// try await board.connect(devices[0])
/// board.onPieceMove { event in print(event) }
/// let battery = try await board.getBattery()
/// await board.disconnect()
/// ```
class GoChessBoard: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {

    // MARK: - Private Types

    /// A pending response waiting for a BLE notification.
    private struct PendingResponse {
        let continuation: CheckedContinuation<Any, Error>
        let timeoutTask: Task<Void, Never>?
    }

    // MARK: - Private State

    private var centralManager: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var rxCharacteristic: CBCharacteristic?
    private var txCharacteristic: CBCharacteristic?
    private var _connected: Bool = false

    /// Callbacks registered for piece movement events.
    private var pieceCallbacks: [(PieceEvent) -> Void] = []
    /// Callbacks registered for raw BLE notifications.
    private var rawCallbacks: [(Data) -> Void] = []

    /// Pending response continuations keyed by response type string.
    private var pending: [String: PendingResponse] = [:]
    /// Lock protecting the `pending` dictionary.
    private var pendingLock = NSLock()

    /// Continuations for one-shot delegate callbacks during scan/connect/setup.
    private var centralStateContinuation: CheckedContinuation<Void, Error>?
    private var scanContinuation: CheckedContinuation<[GoChessDevice], Error>?
    private var connectContinuation: CheckedContinuation<Void, Error>?
    private var serviceDiscoveryContinuation: CheckedContinuation<Void, Error>?
    private var characteristicDiscoveryContinuation: CheckedContinuation<Void, Error>?
    private var notifyContinuation: CheckedContinuation<Void, Error>?

    /// Accumulated scan results.
    private var scanResults: [GoChessDevice] = []

    /// Whether the central manager has reached `.poweredOn`.
    private var centralReady: Bool = false

    // MARK: - Initializer

    /// Create a new GoChessBoard instance.
    ///
    /// The CoreBluetooth central manager is initialized lazily when needed
    /// (during scan or connect) to avoid triggering Bluetooth permission
    /// dialogs at construction time.
    override init() {
        super.init()
    }

    /// Ensure the central manager is initialized and powered on.
    private func ensureCentralReady() async throws {
        if centralManager == nil {
            centralManager = CBCentralManager(delegate: self, queue: nil)
        }

        if centralReady {
            return
        }

        // Wait for the central manager state to become .poweredOn
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            if self.centralManager.state == .poweredOn {
                self.centralReady = true
                continuation.resume()
            } else if self.centralManager.state == .poweredOff {
                continuation.resume(throwing: GoChessError.bluetoothOff)
            } else if self.centralManager.state == .unauthorized {
                continuation.resume(throwing: GoChessError.bluetoothUnauthorized)
            } else {
                // State is .unknown or .resetting -- wait for delegate callback
                self.centralStateContinuation = continuation
            }
        }
    }

    // MARK: - Public API: Connection State

    /// Whether the board is currently connected.
    var isConnected: Bool {
        return _connected
    }

    // MARK: - Public API: Scanning

    /// Scan for nearby GoChess boards.
    ///
    /// Starts a BLE scan filtered by the NUS service UUID and collects results
    /// for the specified duration, then stops and returns the discovered devices.
    ///
    /// - Parameter timeout: How long to scan in seconds (default 5.0).
    /// - Returns: A list of discovered `GoChessDevice` objects.
    /// - Throws: `GoChessError.bluetoothOff` if Bluetooth is not available.
    func scan(timeout: TimeInterval = 5.0) async throws -> [GoChessDevice] {
        try await ensureCentralReady()

        scanResults = []

        centralManager.scanForPeripherals(
            withServices: [GoChessConstants.nusServiceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )

        // Wait for the specified timeout, then stop scanning
        try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))

        centralManager.stopScan()

        return scanResults
    }

    // MARK: - Public API: Connection

    /// Connect to a GoChess board and start listening for notifications.
    ///
    /// Connection flow:
    /// 1. Connect to the peripheral
    /// 2. Discover the NUS service
    /// 3. Discover RX and TX characteristics
    /// 4. Enable notifications on the TX characteristic
    ///
    /// - Parameter device: One of the devices returned by `scan()`.
    /// - Throws: Various `GoChessError` cases if connection fails.
    func connect(_ device: GoChessDevice) async throws {
        try await ensureCentralReady()

        let cbPeripheral = device.peripheral
        cbPeripheral.delegate = self
        self.peripheral = cbPeripheral

        // Step 1: Connect to the peripheral
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            self.connectContinuation = continuation
            self.centralManager.connect(cbPeripheral, options: nil)
        }

        // Step 2: Discover the NUS service
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            self.serviceDiscoveryContinuation = continuation
            cbPeripheral.discoverServices([GoChessConstants.nusServiceUUID])
        }

        // Find the NUS service
        guard let service = cbPeripheral.services?.first(where: {
            $0.uuid == GoChessConstants.nusServiceUUID
        }) else {
            throw GoChessError.serviceNotFound
        }

        // Step 3: Discover RX and TX characteristics
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            self.characteristicDiscoveryContinuation = continuation
            cbPeripheral.discoverCharacteristics(
                [GoChessConstants.nusRxCharUUID, GoChessConstants.nusTxCharUUID],
                for: service
            )
        }

        // Store characteristic references
        guard let chars = service.characteristics else {
            throw GoChessError.characteristicNotFound
        }
        for char in chars {
            if char.uuid == GoChessConstants.nusRxCharUUID {
                rxCharacteristic = char
            } else if char.uuid == GoChessConstants.nusTxCharUUID {
                txCharacteristic = char
            }
        }
        guard rxCharacteristic != nil, let txChar = txCharacteristic else {
            throw GoChessError.characteristicNotFound
        }

        // Step 4: Enable notifications on TX characteristic
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            self.notifyContinuation = continuation
            cbPeripheral.setNotifyValue(true, for: txChar)
        }

        _connected = true
        os_log("Connected to %{public}@", log: log, type: .info, device.name)
    }

    /// Disconnect from the board.
    ///
    /// Cancels any pending response futures and releases all BLE resources.
    func disconnect() async {
        if let p = peripheral, _connected {
            if let txChar = txCharacteristic {
                p.setNotifyValue(false, for: txChar)
            }
            centralManager.cancelPeripheralConnection(p)
        }

        _connected = false
        peripheral = nil
        rxCharacteristic = nil
        txCharacteristic = nil

        // Cancel all pending futures
        pendingLock.lock()
        let pendingCopy = pending
        pending.removeAll()
        pendingLock.unlock()

        for (key, entry) in pendingCopy {
            entry.timeoutTask?.cancel()
            entry.continuation.resume(throwing: GoChessError.timeout(key))
        }

        os_log("Disconnected", log: log, type: .info)
    }

    // MARK: - Public API: Callbacks

    /// Register a callback invoked whenever a piece is lifted or placed.
    ///
    /// The callback receives a `PieceEvent`. Multiple callbacks can be registered.
    ///
    /// - Parameter callback: Closure to invoke on piece movement.
    func onPieceMove(_ callback: @escaping (PieceEvent) -> Void) {
        pieceCallbacks.append(callback)
    }

    /// Register a callback for every raw BLE notification (for debugging).
    ///
    /// - Parameter callback: Closure receiving the raw notification data.
    func onRawNotification(_ callback: @escaping (Data) -> Void) {
        rawCallbacks.append(callback)
    }

    // MARK: - Public API: Commands

    /// Request the battery percentage.
    ///
    /// - Parameter timeout: How long to wait for the response in seconds (default 5.0).
    /// - Returns: Battery level 0-100 (%).
    /// - Throws: `GoChessError.timeout` if no response within the timeout.
    func getBattery(timeout: TimeInterval = 5.0) async throws -> Int {
        let result = try await awaitResponse(
            "battery",
            command: Data([GoChessConstants.cmdCheckBattery]),
            timeout: timeout
        )
        return result as! Int
    }

    /// Request the battery voltage in millivolts.
    ///
    /// - Parameter timeout: How long to wait for the response in seconds (default 5.0).
    /// - Returns: Battery voltage in mV.
    /// - Throws: `GoChessError.timeout` if no response within the timeout.
    func getBatteryMv(timeout: TimeInterval = 5.0) async throws -> Int {
        let result = try await awaitResponse(
            "battery_mv",
            command: Data([GoChessConstants.cmdCheckBatteryMv]),
            timeout: timeout
        )
        return result as! Int
    }

    /// Request the full 8x8 board occupancy.
    ///
    /// - Parameter timeout: How long to wait for the response in seconds (default 5.0).
    /// - Returns: A `BoardState` object.
    /// - Throws: `GoChessError.timeout` if no response within the timeout.
    func getBoardState(timeout: TimeInterval = 5.0) async throws -> BoardState {
        let result = try await awaitResponse(
            "board_state",
            command: Data([GoChessConstants.cmdGetBoardState]),
            timeout: timeout
        )
        return result as! BoardState
    }

    /// Request border/storage slot occupancy (36 positions around the board).
    ///
    /// **GoChess XR (Robotic) only** -- Mini and Lite boards do not have
    /// border slots and will not respond to this command (will timeout).
    ///
    /// - Parameter timeout: How long to wait for the response in seconds (default 5.0).
    /// - Returns: A `BorderState` object with the 36 border positions.
    /// - Throws: `GoChessError.timeout` if no response within the timeout.
    func getBorderState(timeout: TimeInterval = 5.0) async throws -> BorderState {
        let result = try await awaitResponse(
            "border_state",
            command: Data([GoChessConstants.cmdGetBorderState]),
            timeout: timeout
        )
        return result as! BorderState
    }

    /// Request the firmware version byte.
    ///
    /// - Parameter timeout: How long to wait for the response in seconds (default 5.0).
    /// - Returns: Version number (e.g. 0x04 for GoChess Robotic, 0x03 for Mini/Lite).
    /// - Throws: `GoChessError.timeout` if no response within the timeout.
    func getFwVersion(timeout: TimeInterval = 5.0) async throws -> Int {
        let result = try await awaitResponse(
            "fw_version",
            command: Data([GoChessConstants.cmdGetFwVersion]),
            timeout: timeout
        )
        return result as! Int
    }

    /// Request the charging state.
    ///
    /// - Parameter timeout: How long to wait for the response in seconds (default 5.0).
    /// - Returns: `true` if charging, `false` otherwise.
    /// - Throws: `GoChessError.timeout` if no response within the timeout.
    func getChargingState(timeout: TimeInterval = 5.0) async throws -> Bool {
        let result = try await awaitResponse(
            "charging",
            command: Data([GoChessConstants.cmdGetChargingState]),
            timeout: timeout
        )
        return result as! Bool
    }

    /// Measure the current draw in microamps.
    ///
    /// - Parameter timeout: How long to wait for the response in seconds (default 5.0).
    /// - Returns: Current draw in uA.
    /// - Throws: `GoChessError.timeout` if no response within the timeout.
    func measureCurrent(timeout: TimeInterval = 5.0) async throws -> Int {
        let result = try await awaitResponse(
            "current",
            command: Data([GoChessConstants.cmdMeasureCurrent]),
            timeout: timeout
        )
        return result as! Int
    }

    // MARK: - Public API: LED Control

    /// Turn on LEDs for the given squares with a uniform colour (command 0x32).
    ///
    /// Uses the 13-byte command format: `[0x32][mask1_LE32][mask2_LE32][R][G][B][overwrite]`
    ///
    /// - Parameters:
    ///   - squares: Array of `(row, col)` tuples (1-indexed, 1-8).
    ///              An empty list with `overwrite: true` turns all LEDs off.
    ///   - r: Red component 0-255.
    ///   - g: Green component 0-255.
    ///   - b: Blue component 0-255.
    ///   - overwrite: If `true`, squares not in the list are turned off.
    ///                If `false`, only the listed squares are changed.
    /// - Throws: `GoChessError.invalidSquare` or `GoChessError.notConnected`.
    func setLeds(
        _ squares: [(Int, Int)],
        r: UInt8 = 0,
        g: UInt8 = 0,
        b: UInt8 = 0,
        overwrite: Bool = true
    ) throws {
        let (maskLed, maskLed2) = try GoChessHelpers.buildLedMasks(squares)
        let maskBytes = GoChessHelpers.encodeLedMasksToBytes(maskLed, maskLed2)

        var data = Data(count: 13)
        data[0] = GoChessConstants.cmdSetRgbLeds
        data.replaceSubrange(1..<9, with: maskBytes)
        // The firmware stores data[9]->g_green, data[10]->g_red, but the main
        // loop passes them swapped to setLedsRGB_I2C. Net result: data[9] drives
        // physical RED, data[10] drives physical GREEN.
        data[9]  = r
        data[10] = g
        data[11] = b
        data[12] = overwrite ? 0x01 : 0x00

        try write(data)
    }

    /// Turn off all board LEDs.
    ///
    /// - Throws: `GoChessError.notConnected`.
    func setLedsOff() throws {
        try setLeds([], overwrite: true)
    }

    /// Set per-square LED colours with multiple colour groups (command 0x34).
    ///
    /// This command first turns off all LEDs, then applies each group.
    /// Format: `[0x34][count1][sq...][R][G][B][count2][sq...][R][G][B]...`
    /// where each square byte = `(row << 4) | col`.
    ///
    /// - Parameter groups: Array of `LedGroup` specifying squares and their colours.
    /// - Throws: `GoChessError.invalidSquare` or `GoChessError.notConnected`.
    func setLedsSpecial(_ groups: [LedGroup]) throws {
        var data = Data([GoChessConstants.cmdLedOnSpecial])

        for group in groups {
            data.append(UInt8(group.squares.count))
            for (row, col) in group.squares {
                guard (1...8).contains(row), (1...8).contains(col) else {
                    throw GoChessError.invalidSquare(row, col)
                }
                data.append(UInt8((row << 4) | col))
            }
            // Same GRB colour swap as 0x32 -- first byte drives physical RED,
            // second byte drives physical GREEN (see setLeds for details).
            data.append(group.r)
            data.append(group.g)
            data.append(group.b)
        }

        try write(data)
    }

    /// Convenience: set LEDs using chess notation with per-square colours.
    ///
    /// Uses `setLedsSpecial` under the hood so each square can have its own colour.
    /// Squares sharing the same colour are grouped automatically.
    ///
    /// - Parameter squareColors: Dictionary mapping notation (e.g. "e2") to (R, G, B) tuples.
    /// - Throws: `GoChessError.invalidNotation`, `GoChessError.invalidSquare`,
    ///           or `GoChessError.notConnected`.
    func setLedsByNotation(_ squareColors: [String: (UInt8, UInt8, UInt8)]) throws {
        // Group squares by colour
        var colourGroups: [String: [(Int, Int)]] = [:]  // key = "R,G,B"
        var colourMap: [String: (UInt8, UInt8, UInt8)] = [:]

        for (notation, rgb) in squareColors {
            let rc = try GoChessHelpers.squareNotationToRC(notation)
            let key = "\(rgb.0),\(rgb.1),\(rgb.2)"
            colourGroups[key, default: []].append(rc)
            colourMap[key] = rgb
        }

        let groups: [LedGroup] = colourGroups.map { key, squares in
            let rgb = colourMap[key]!
            return LedGroup(squares: squares.map { ($0.0, $0.1) }, r: rgb.0, g: rgb.1, b: rgb.2)
        }

        try setLedsSpecial(groups)
    }

    // MARK: - Internal: Send / Receive

    /// Ensure a board is connected before writing.
    private func ensureConnected() throws {
        guard _connected, peripheral != nil, rxCharacteristic != nil else {
            throw GoChessError.notConnected
        }
    }

    /// Write raw bytes to the NUS RX characteristic (App -> Board).
    private func write(_ data: Data) throws {
        try ensureConnected()
        guard let p = peripheral, let rxChar = rxCharacteristic else {
            throw GoChessError.notConnected
        }
        os_log("TX -> %{public}@", log: log, type: .debug, data.map { String(format: "%02x", $0) }.joined())
        p.writeValue(data, for: rxChar, type: .withoutResponse)
    }

    /// Register a pending response, write a command, and await the result with timeout.
    ///
    /// This method atomically:
    /// 1. Registers a continuation in the `pending` dictionary
    /// 2. Writes the command to the board
    /// 3. Awaits the response (resolved by the notification handler)
    /// 4. Times out if no response arrives within the specified duration
    ///
    /// Thread safety: The `pendingLock` ensures only one of the timeout task
    /// or the `resolve()` call can resume the continuation.
    ///
    /// - Parameters:
    ///   - key: Response type key (e.g. "battery", "board_state").
    ///   - command: Command bytes to write.
    ///   - timeout: Maximum time to wait in seconds.
    /// - Returns: The resolved response value (caller must cast).
    /// - Throws: `GoChessError.timeout` or `GoChessError.notConnected`.
    private func awaitResponse(_ key: String, command: Data, timeout: TimeInterval) async throws -> Any {
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Any, Error>) in
            let timeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                guard let self = self else { return }
                self.pendingLock.lock()
                if let entry = self.pending.removeValue(forKey: key) {
                    self.pendingLock.unlock()
                    entry.continuation.resume(throwing: GoChessError.timeout(key))
                } else {
                    self.pendingLock.unlock()
                }
            }

            self.pendingLock.lock()
            self.pending[key] = PendingResponse(continuation: continuation, timeoutTask: timeoutTask)
            self.pendingLock.unlock()

            // Write the command within the continuation setup to ensure
            // the pending entry is registered before any response can arrive.
            do {
                try self.write(command)
            } catch {
                self.pendingLock.lock()
                let removed = self.pending.removeValue(forKey: key)
                self.pendingLock.unlock()
                if removed != nil {
                    timeoutTask.cancel()
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    /// Resolve a pending response future.
    ///
    /// Thread-safe: uses `pendingLock` to ensure the continuation is
    /// resumed exactly once (either by this method or by the timeout task).
    ///
    /// - Parameters:
    ///   - key: Response type key.
    ///   - value: The resolved value.
    private func resolve(_ key: String, value: Any) {
        pendingLock.lock()
        guard let entry = pending.removeValue(forKey: key) else {
            pendingLock.unlock()
            return
        }
        pendingLock.unlock()
        entry.timeoutTask?.cancel()
        entry.continuation.resume(returning: value)
    }

    // MARK: - Notification Handler

    /// Dispatch incoming BLE notifications by inspecting the first byte.
    ///
    /// This method exactly replicates the Python SDK's `_on_notify` logic:
    ///
    /// 1. `0x2A` + count >= 5 -> framed message (battery, charging, etc.)
    /// 2. `0x03` + count >= 9 -> board state (8 bytes)
    /// 3. `0x0C` + count >= 7 -> border state (6 bytes)
    /// 4. `0x56` ('V') + "er" + count >= 4 -> firmware version
    /// 5. `0x31`-`0x38` + count >= 3 -> board piece move
    /// 6. `r`,`l`,`t`,`b` + count >= 3 -> border piece move
    private func onNotify(_ data: Data) {
        guard !data.isEmpty else { return }

        os_log("RX <- %{public}@", log: log, type: .debug, data.map { String(format: "%02x", $0) }.joined())

        // Invoke raw callbacks
        for cb in rawCallbacks {
            cb(data)
        }

        let first = data[0]
        let count = data.count

        // --- Framed message: [*][len][type][payload...][checksum][\r\n] ---
        if first == GoChessConstants.startByte && count >= 5 {
            parseFramed(data)
        }
        // --- Raw: Board state [0x03][8 bytes] ---
        else if first == GoChessConstants.respBoardState && count >= 9 {
            if let state = try? BoardState(raw: data.subdata(in: 1..<9)) {
                resolve("board_state", value: state)
            }
        }
        // --- Raw: Border state [0x0C][6 bytes] ---
        else if first == GoChessConstants.respBorderState && count >= 7 {
            if let state = try? BorderState(raw: data.subdata(in: 1..<7)) {
                resolve("border_state", value: state)
            }
        }
        // --- Raw: FW version "Ver" + byte ---
        else if first == 0x56 && count >= 4 && data[1] == 0x65 && data[2] == 0x72 {
            resolve("fw_version", value: Int(data[3]))
        }
        // --- Raw: Piece move on board ('1'-'8' first byte) ---
        else if first >= 0x31 && first <= 0x38 && count >= 3 {
            emitPieceMove(data)
        }
        // --- Raw: Piece move on border ('r','l','t','b') ---
        else if (first == 0x72 || first == 0x6C || first == 0x74 || first == 0x62) && count >= 3 {
            emitBorderMove(data)
        }
        else {
            os_log("Unknown notification: %{public}@", log: log, type: .debug,
                   data.map { String(format: "%02x", $0) }.joined())
        }
    }

    /// Parse a framed message and resolve the matching pending future.
    ///
    /// Framed format: `[START=0x2A][LEN][TYPE][DATA...][CHECKSUM][CR][LF]`
    ///
    /// Dispatch by `data[2]` (TYPE byte):
    ///   - `0x01` -> battery percentage (1 byte)
    ///   - `0x02` -> battery mV (2 bytes, big-endian)
    ///   - `0x04` -> current uA (2 bytes, big-endian)
    ///   - `0x07` -> charging state (1 byte, bool)
    ///   - `0x0B` -> chamber state (1 byte, bool)
    private func parseFramed(_ data: Data) {
        let msgType = data[2]
        let count = data.count

        if msgType == GoChessConstants.respBattery && count >= 4 {
            resolve("battery", value: Int(data[3]))
        }
        else if msgType == GoChessConstants.respBatteryMv && count >= 5 {
            let mv = (Int(data[3]) << 8) | Int(data[4])
            resolve("battery_mv", value: mv)
        }
        else if msgType == GoChessConstants.respCharging && count >= 4 {
            resolve("charging", value: data[3] != 0)
        }
        else if msgType == GoChessConstants.respCurrent && count >= 5 {
            let ua = (Int(data[3]) << 8) | Int(data[4])
            resolve("current", value: ua)
        }
        else if msgType == GoChessConstants.respChamber && count >= 4 {
            resolve("chamber", value: data[3] != 0)
        }
        else {
            os_log("Unknown framed type 0x%02X", log: log, type: .debug, msgType)
        }
    }

    /// Emit a PieceEvent for a board-square move.
    ///
    /// Format: 3 ASCII bytes `[row_ascii][col_ascii][direction]`
    /// where row_ascii = '1'-'8', col_ascii = '1'-'8', direction = 'd' or 'u'.
    private func emitPieceMove(_ data: Data) {
        let row = Int(data[0]) - 0x30  // ASCII '1'-'8' -> 1-8
        let col = Int(data[1]) - 0x30
        let isDown = data[2] == UInt8(ascii: "d")

        let event = PieceEvent(
            row: row,
            col: col,
            isDown: isDown,
            isBorder: false,
            borderSide: ""
        )
        dispatchPieceEvent(event)
    }

    /// Emit a PieceEvent for a border/storage slot move.
    ///
    /// Format: 3 bytes for positions 1-9 `[side][pos_ascii][direction]`,
    /// or 4 bytes for position 10 `[side]['1']['0'][direction]`.
    private func emitBorderMove(_ data: Data) {
        let side = String(UnicodeScalar(data[0]))
        let position: Int
        let isDown: Bool

        // Position 10 is a 4-byte message: "r10d" or "r10u" etc.
        if data.count >= 4 && data[1] == 0x31 && data[2] == 0x30 {
            position = 10
            isDown = data[3] == UInt8(ascii: "d")
        } else {
            position = Int(data[1]) - 0x30
            isDown = data[2] == UInt8(ascii: "d")
        }

        let event = PieceEvent(
            row: 0,
            col: position,
            isDown: isDown,
            isBorder: true,
            borderSide: side
        )
        dispatchPieceEvent(event)
    }

    /// Dispatch a piece event to all registered callbacks.
    private func dispatchPieceEvent(_ event: PieceEvent) {
        for cb in pieceCallbacks {
            cb(event)
        }
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            centralReady = true
            centralStateContinuation?.resume()
            centralStateContinuation = nil
        case .poweredOff:
            centralReady = false
            centralStateContinuation?.resume(throwing: GoChessError.bluetoothOff)
            centralStateContinuation = nil
        case .unauthorized:
            centralReady = false
            centralStateContinuation?.resume(throwing: GoChessError.bluetoothUnauthorized)
            centralStateContinuation = nil
        case .unsupported:
            centralReady = false
            centralStateContinuation?.resume(throwing: GoChessError.bluetoothOff)
            centralStateContinuation = nil
        default:
            // .unknown, .resetting -- wait for further updates
            break
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let name = peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? ""
        guard name.hasPrefix("GoChess") else { return }

        // Avoid duplicates
        if scanResults.contains(where: { $0.identifier == peripheral.identifier }) {
            return
        }

        let device = GoChessDevice(
            index: scanResults.count,
            name: name,
            identifier: peripheral.identifier,
            peripheral: peripheral
        )
        scanResults.append(device)
        os_log("Discovered: %{public}@ (%{public}@)", log: log, type: .info,
               name, peripheral.identifier.uuidString)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        os_log("Peripheral connected", log: log, type: .debug)
        connectContinuation?.resume()
        connectContinuation = nil
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        let err = error ?? GoChessError.notConnected
        os_log("Connection failed: %{public}@", log: log, type: .error, String(describing: err))
        connectContinuation?.resume(throwing: err)
        connectContinuation = nil
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        os_log("Peripheral disconnected", log: log, type: .info)
        _connected = false
    }

    // MARK: - CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error = error {
            serviceDiscoveryContinuation?.resume(throwing: error)
        } else {
            serviceDiscoveryContinuation?.resume()
        }
        serviceDiscoveryContinuation = nil
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        if let error = error {
            characteristicDiscoveryContinuation?.resume(throwing: error)
        } else {
            characteristicDiscoveryContinuation?.resume()
        }
        characteristicDiscoveryContinuation = nil
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard characteristic.uuid == GoChessConstants.nusTxCharUUID,
              let data = characteristic.value else {
            return
        }
        onNotify(data)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if let error = error {
            notifyContinuation?.resume(throwing: error)
        } else {
            notifyContinuation?.resume()
        }
        notifyContinuation = nil
    }
}
