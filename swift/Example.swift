/// GoChess SDK – iOS/macOS Example (SwiftUI)
/// ==========================================
///
/// Demonstrates scanning, connecting, querying, and controlling
/// a GoChess smart chess board via BLE.
///
/// Requirements:
///   - iOS 15+ / macOS 12+
///   - Info.plist: NSBluetoothAlwaysUsageDescription
///   - No external dependencies (CoreBluetooth via GoChessSdk.swift)

import SwiftUI

// MARK: - Data Models

/// Board information fetched after connection.
struct BoardInfo {
    /// Raw firmware version byte (e.g. 0x03, 0x04).
    let fwVersion: Int
    /// Human-readable label for the firmware version.
    let fwLabel: String
    /// Battery level as a percentage (0-100).
    let battery: Int
    /// Whether the board is a GoChess XR (Robotic) model.
    let isXR: Bool
}

/// Connection state for the UI.
enum ConnectionState {
    case disconnected
    case connecting
    case connected
}

// MARK: - Helpers

private let kFiles = "abcdefgh"

/// Convert a firmware border notification to a human-readable label.
///
/// The firmware sends 1-indexed positions (1-10) for border slots.
/// This maps them to the correct 0-indexed labels:
///   - "t" (top):    position 1-8 -> a9..h9
///   - "b" (bottom): position 1-8 -> a0..h0
///   - "l" (left):   position 1-10 -> q0..q9
///   - "r" (right):  position 1-10 -> i0..i9
func borderEventToLabel(side: String, position: Int) -> String {
    let files = Array(kFiles)
    if side == "t", (1...8).contains(position) {
        return "\(files[position - 1])9"
    } else if side == "b", (1...8).contains(position) {
        return "\(files[position - 1])0"
    } else if side == "l", (1...10).contains(position) {
        return "q\(position - 1)"
    } else if side == "r", (1...10).contains(position) {
        return "i\(position - 1)"
    }
    return "\(side)\(position)"
}

/// Parse a color string into an RGB tuple.
///
/// Supports named colors (red, green, blue, yellow, cyan, magenta, white,
/// orange, purple, off) and comma-separated RGB values like "255,0,0".
func parseColor(_ text: String) throws -> (UInt8, UInt8, UInt8) {
    let shortcuts: [String: (UInt8, UInt8, UInt8)] = [
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
    ]

    let trimmed = text.trimmingCharacters(in: .whitespaces).lowercased()

    if let named = shortcuts[trimmed] {
        return named
    }

    let parts = trimmed.split(separator: ",")
    guard parts.count == 3,
          let r = UInt8(parts[0].trimmingCharacters(in: .whitespaces)),
          let g = UInt8(parts[1].trimmingCharacters(in: .whitespaces)),
          let b = UInt8(parts[2].trimmingCharacters(in: .whitespaces)) else {
        throw GoChessError.invalidData(
            "Color must be R,G,B (e.g. 255,0,0) or a name (red, green, blue, ...)."
        )
    }
    return (r, g, b)
}

/// Parse a comma-separated list of chess-notation squares.
///
/// e.g. "e2, e4, d4" -> [(2, 5), (4, 5), (4, 4)]
func parseSquares(_ text: String) throws -> [(Int, Int)] {
    var squares: [(Int, Int)] = []
    for token in text.split(separator: ",") {
        let trimmed = token.trimmingCharacters(in: .whitespaces).lowercased()
        let rc = try GoChessHelpers.squareNotationToRC(trimmed)
        squares.append(rc)
    }
    return squares
}

// MARK: - View Model

/// Observable view model that drives the GoChess example UI.
///
/// All BLE operations are performed through the underlying ``GoChessBoard``
/// instance and results are published to the SwiftUI view layer.
@MainActor
class GoChessViewModel: ObservableObject {

    // MARK: Published State

    /// Discovered devices from the most recent scan.
    @Published var devices: [GoChessDevice] = []
    /// Current connection state.
    @Published var connectionState: ConnectionState = .disconnected
    /// Board information populated after a successful connection.
    @Published var boardInfo: BoardInfo?
    /// Rolling event log (newest entries first).
    @Published var eventLog: [String] = []
    /// Whether a BLE scan is currently in progress.
    @Published var isScanning: Bool = false

    /// The name of the connected device (kept for display after disconnect).
    @Published var connectedDeviceName: String = ""

    // MARK: Private

    private let board = GoChessBoard()

    // MARK: - Logging

    /// Append a timestamped entry to the event log.
    private func log(_ message: String) {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        let timestamp = formatter.string(from: Date())
        eventLog.insert("[\(timestamp)] \(message)", at: 0)
    }

    // MARK: - Scanning

    /// Scan for GoChess boards for 5 seconds.
    func scan() async {
        isScanning = true
        devices = []
        log("Scanning for GoChess boards...")

        do {
            let found = try await board.scan(timeout: 5.0)
            devices = found
            if found.isEmpty {
                log("No boards found. Ensure the board is on and nearby.")
            } else {
                log("Found \(found.count) board(s).")
            }
        } catch let error as GoChessError {
            log("Scan error: \(error.localizedDescription)")
        } catch {
            log("Scan error: \(error.localizedDescription)")
        }

        isScanning = false
    }

    // MARK: - Connection

    /// Connect to a discovered device, detect board type, and fetch initial info.
    func connect(_ device: GoChessDevice) async {
        connectionState = .connecting
        connectedDeviceName = device.name
        log("Connecting to \(device.name)...")

        do {
            try await board.connect(device)
            connectionState = .connected

            // Register piece-move callback
            board.onPieceMove { [weak self] event in
                Task { @MainActor in
                    self?.handlePieceEvent(event)
                }
            }

            log("Connected to \(device.name).")

            // Detect board type from name first, then refine with FW version
            var isXR = device.name.hasPrefix("GoChessXR")

            // Fetch firmware version
            var fwVersion = 0
            var fwLabel = "Unknown"
            do {
                fwVersion = try await board.getFwVersion()
                let labels: [Int: String] = [
                    0x03: "GoChess Mini / Lite",
                    0x04: "GoChess Robotic (XR)",
                ]
                fwLabel = labels[fwVersion] ?? "Unknown (0x\(String(format: "%02X", fwVersion)))"
                if fwVersion == 0x04 {
                    isXR = true
                }
                log("Firmware: 0x\(String(format: "%02X", fwVersion)) (\(fwLabel))")
            } catch {
                log("Firmware request timed out.")
            }

            // Fetch battery
            var battery = 0
            do {
                battery = try await board.getBattery()
                log("Battery: \(battery)%")
            } catch {
                log("Battery request timed out.")
            }

            boardInfo = BoardInfo(
                fwVersion: fwVersion,
                fwLabel: fwLabel,
                battery: battery,
                isXR: isXR
            )

            let boardType = isXR
                ? "GoChess XR (Robotic) -- border state supported"
                : "GoChess Mini/Lite -- no border slots"
            log("Board type: \(boardType)")
            log("Piece movements will appear in real-time.")

        } catch let error as GoChessError {
            log("Connection failed: \(error.localizedDescription)")
            connectionState = .disconnected
        } catch {
            log("Connection failed: \(error.localizedDescription)")
            connectionState = .disconnected
        }
    }

    /// Disconnect from the board and return to scan view.
    func disconnect() async {
        await board.disconnect()
        connectionState = .disconnected
        boardInfo = nil
        log("Disconnected.")
    }

    // MARK: - Commands

    /// Query and log the current battery level.
    func getBattery() async {
        do {
            let battery = try await board.getBattery()
            log("Battery: \(battery)%")
        } catch {
            log("Battery request failed: \(error.localizedDescription)")
        }
    }

    /// Query and log the 8x8 board occupancy state.
    func getBoardState() async {
        do {
            let state = try await board.getBoardState()
            log("Board state (\(state.pieceCount) pieces):\n\(state)")
        } catch {
            log("Board state request failed: \(error.localizedDescription)")
        }
    }

    /// Query and log the border/storage slot occupancy (XR only).
    ///
    /// - Returns: `true` if the command was sent (board is XR), `false` if
    ///   the board is not XR and an alert should be shown.
    @discardableResult
    func getBorderState() async -> Bool {
        guard let info = boardInfo, info.isXR else {
            log("Border state is only available on GoChess XR (Robotic).")
            return false
        }
        do {
            let border = try await board.getBorderState()
            let hexStr = border.rawBytes.map { String(format: "0x%02X", $0) }.joined(separator: " ")
            log("Border state (\(border.occupiedCount) occupied):\nRaw: \(hexStr)\n\(border)")
        } catch {
            log("Border state request failed: \(error.localizedDescription)")
        }
        return true
    }

    /// Query and log the firmware version.
    func getFwVersion() async {
        do {
            let fw = try await board.getFwVersion()
            let labels: [Int: String] = [
                0x03: "GoChess Mini / Lite",
                0x04: "GoChess Robotic (XR)",
            ]
            let label = labels[fw] ?? "Unknown"
            log("Firmware version: 0x\(String(format: "%02X", fw)) (\(label))")
        } catch {
            log("FW version request failed: \(error.localizedDescription)")
        }
    }

    /// Set LEDs on the given squares with a uniform color (command 0x32).
    func setLeds(squares: [(Int, Int)], r: UInt8, g: UInt8, b: UInt8, overwrite: Bool) {
        do {
            try board.setLeds(squares, r: r, g: g, b: b, overwrite: overwrite)
            let labels = squares.map { GoChessHelpers.rcToSquareNotation(row: $0.0, col: $0.1) }
            log("LEDs set: \(labels.joined(separator: ", ")) -> (\(r),\(g),\(b)) overwrite=\(overwrite)")
        } catch {
            log("Set LEDs failed: \(error.localizedDescription)")
        }
    }

    /// Set per-square LED colors with multiple color groups (command 0x34).
    func setLedsSpecial(groups: [LedGroup]) {
        do {
            try board.setLedsSpecial(groups)
            log("LEDs special set with \(groups.count) color group(s).")
        } catch {
            log("Set LEDs special failed: \(error.localizedDescription)")
        }
    }

    /// Turn off all LEDs.
    func setLedsOff() {
        do {
            try board.setLedsOff()
            log("All LEDs turned off.")
        } catch {
            log("LEDs off failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Piece Event Handling

    /// Process an incoming piece movement event and append it to the log.
    private func handlePieceEvent(_ event: PieceEvent) {
        if event.isBorder {
            let label = borderEventToLabel(side: event.borderSide, position: event.col)
            let action = event.isDown ? "placed on" : "lifted from"
            log("Border piece \(action) \(label)  (side='\(event.borderSide)', pos=\(event.col))")
        } else {
            let square = GoChessHelpers.rcToSquareNotation(row: event.row, col: event.col)
            let action = event.isDown ? "PLACED on" : "LIFTED from"
            log("Piece \(action) \(square)  (row=\(event.row), col=\(event.col))")
        }
    }
}

// MARK: - Content View

/// Root SwiftUI view that switches between the scan screen and the connected
/// board control screen based on the current connection state.
struct ContentView: View {

    @StateObject private var viewModel = GoChessViewModel()

    /// Alert flag for non-XR border state attempts.
    @State private var showBorderAlert = false

    var body: some View {
        NavigationStack {
            Group {
                switch viewModel.connectionState {
                case .disconnected:
                    scanView
                case .connecting:
                    connectingView
                case .connected:
                    connectedView
                }
            }
            .navigationTitle("GoChess")
            .alert("Not Supported", isPresented: $showBorderAlert) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("Border state is only available on GoChess XR (Robotic). This board is a Mini/Lite model and does not have border slots.")
            }
        }
    }

    // MARK: - Screen 1: Scan & Connect

    private var scanView: some View {
        VStack(spacing: 0) {
            // Scan button
            Button {
                Task { await viewModel.scan() }
            } label: {
                HStack {
                    if viewModel.isScanning {
                        ProgressView()
                            .padding(.trailing, 4)
                    }
                    Image(systemName: "antenna.radiowaves.left.and.right")
                    Text(viewModel.isScanning ? "Scanning..." : "Scan for Boards")
                }
                .frame(maxWidth: .infinity)
                .padding()
            }
            .buttonStyle(.borderedProminent)
            .disabled(viewModel.isScanning)
            .padding()

            if viewModel.devices.isEmpty && !viewModel.isScanning {
                Spacer()
                VStack(spacing: 12) {
                    Image(systemName: "chess.board")
                        .font(.system(size: 48))
                        .foregroundStyle(.secondary)
                    Text("No boards found")
                        .font(.headline)
                        .foregroundStyle(.secondary)
                    Text("Make sure your GoChess board is turned on and nearby.")
                        .font(.subheadline)
                        .foregroundStyle(.tertiary)
                        .multilineTextAlignment(.center)
                }
                .padding()
                Spacer()
            } else {
                List(viewModel.devices, id: \.identifier) { device in
                    Button {
                        Task { await viewModel.connect(device) }
                    } label: {
                        HStack {
                            Image(systemName: "checkerboard.rectangle")
                                .foregroundStyle(.blue)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(device.name)
                                    .font(.body)
                                    .fontWeight(.medium)
                                Text(device.identifier.uuidString)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .foregroundStyle(.secondary)
                        }
                    }
                    .tint(.primary)
                }
            }
        }
    }

    // MARK: - Connecting

    private var connectingView: some View {
        VStack(spacing: 16) {
            ProgressView()
                .scaleEffect(1.5)
            Text("Connecting to \(viewModel.connectedDeviceName)...")
                .font(.headline)
        }
    }

    // MARK: - Screen 2: Connected

    private var connectedView: some View {
        VStack(spacing: 0) {
            // Header section
            headerSection
                .padding(.horizontal)
                .padding(.top, 8)

            Divider()
                .padding(.vertical, 8)

            // Scrollable content: commands + event log
            ScrollView {
                VStack(spacing: 16) {
                    commandSection
                    ledSection
                    eventLogSection
                }
                .padding(.horizontal)
                .padding(.bottom, 16)
            }
        }
    }

    // MARK: Connected Sub-Views

    /// Header showing board name, FW version, battery, and connection indicator.
    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: "checkerboard.rectangle")
                    .foregroundStyle(.green)
                Text(viewModel.connectedDeviceName)
                    .font(.headline)
                Spacer()
                Circle()
                    .fill(.green)
                    .frame(width: 10, height: 10)
                Text("Connected")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let info = viewModel.boardInfo {
                HStack(spacing: 16) {
                    Label(
                        "FW 0x\(String(format: "%02X", info.fwVersion)) (\(info.fwLabel))",
                        systemImage: "cpu"
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)

                    Spacer()

                    Label(
                        "\(info.battery)%",
                        systemImage: batteryIcon(for: info.battery)
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }

                if info.isXR {
                    Label("GoChess XR (Robotic) -- border state supported", systemImage: "gearshape.2")
                        .font(.caption2)
                        .foregroundStyle(.blue)
                } else {
                    Label("GoChess Mini/Lite -- no border slots", systemImage: "info.circle")
                        .font(.caption2)
                        .foregroundStyle(.orange)
                }
            }
        }
    }

    /// Returns an SF Symbol name appropriate for the given battery percentage.
    private func batteryIcon(for level: Int) -> String {
        switch level {
        case 0..<13:    return "battery.0percent"
        case 13..<38:   return "battery.25percent"
        case 38..<63:   return "battery.50percent"
        case 63..<88:   return "battery.75percent"
        default:        return "battery.100percent"
        }
    }

    /// Command buttons for querying the board.
    private var commandSection: some View {
        VStack(spacing: 8) {
            Text("Commands")
                .font(.subheadline)
                .fontWeight(.semibold)
                .frame(maxWidth: .infinity, alignment: .leading)

            LazyVGrid(columns: [
                GridItem(.flexible()),
                GridItem(.flexible()),
            ], spacing: 8) {
                commandButton("Battery", icon: "battery.100percent") {
                    await viewModel.getBattery()
                }
                commandButton("Board State", icon: "square.grid.3x3") {
                    await viewModel.getBoardState()
                }
                commandButton("Border State", icon: "square.dashed") {
                    let supported = await viewModel.getBorderState()
                    if !supported {
                        showBorderAlert = true
                    }
                }
                commandButton("FW Version", icon: "cpu") {
                    await viewModel.getFwVersion()
                }
                commandButton("LEDs Off", icon: "lightbulb.slash") {
                    viewModel.setLedsOff()
                }
                commandButton("Disconnect", icon: "xmark.circle", role: .destructive) {
                    await viewModel.disconnect()
                }
            }
        }
    }

    /// A reusable command button used in the grid.
    private func commandButton(
        _ title: String,
        icon: String,
        role: ButtonRole? = nil,
        action: @escaping () async -> Void
    ) -> some View {
        Button(role: role) {
            Task { await action() }
        } label: {
            Label(title, systemImage: icon)
                .font(.caption)
                .fontWeight(.medium)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
        }
        .buttonStyle(.bordered)
    }

    // MARK: - LED Control Section

    @State private var ledExpanded = false
    @State private var ledSquaresText = "e2, e4"
    @State private var ledColorText = "red"
    @State private var ledOverwrite = true

    @State private var specialExpanded = false
    @State private var specialGroups: [(squares: String, color: String)] = [
        (squares: "e2, e4", color: "green"),
    ]

    private var ledSection: some View {
        VStack(spacing: 8) {
            Text("LED Control")
                .font(.subheadline)
                .fontWeight(.semibold)
                .frame(maxWidth: .infinity, alignment: .leading)

            // Uniform color LEDs (command 0x32)
            DisclosureGroup("Set LEDs (Uniform Color)", isExpanded: $ledExpanded) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Squares (chess notation, comma-separated)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    TextField("e.g. e2, e4, d4", text: $ledSquaresText)
                        .textFieldStyle(.roundedBorder)
                        .font(.body.monospaced())
                        #if os(iOS)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                        #endif

                    Text("Color (name or R,G,B)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    TextField("e.g. red or 255,0,0", text: $ledColorText)
                        .textFieldStyle(.roundedBorder)
                        .font(.body.monospaced())
                        #if os(iOS)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                        #endif

                    Toggle("Overwrite other LEDs", isOn: $ledOverwrite)
                        .font(.caption)

                    Button {
                        sendUniformLeds()
                    } label: {
                        Label("Send", systemImage: "paperplane")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(.top, 8)
            }
            .padding()
            .background(Color(.systemGray6))
            .clipShape(RoundedRectangle(cornerRadius: 8))

            // Multi-color LEDs (command 0x34)
            DisclosureGroup("Set LEDs Special (Multi-Color Groups)", isExpanded: $specialExpanded) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Define one or more color groups. Each group lights the specified squares with a single color.")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    ForEach(specialGroups.indices, id: \.self) { index in
                        groupEditor(index: index)
                    }

                    HStack {
                        Button {
                            specialGroups.append((squares: "", color: "blue"))
                        } label: {
                            Label("Add Group", systemImage: "plus.circle")
                                .font(.caption)
                        }

                        Spacer()

                        Button {
                            sendSpecialLeds()
                        } label: {
                            Label("Send All Groups", systemImage: "paperplane")
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .padding(.top, 8)
            }
            .padding()
            .background(Color(.systemGray6))
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }

    /// Editor row for a single color group in the special LEDs section.
    private func groupEditor(index: Int) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Group \(index + 1)")
                    .font(.caption)
                    .fontWeight(.semibold)
                Spacer()
                if specialGroups.count > 1 {
                    Button(role: .destructive) {
                        specialGroups.remove(at: index)
                    } label: {
                        Image(systemName: "trash")
                            .font(.caption)
                    }
                }
            }
            TextField("Squares (e.g. e2, e4)", text: $specialGroups[index].squares)
                .textFieldStyle(.roundedBorder)
                .font(.caption.monospaced())
                #if os(iOS)
                .autocapitalization(.none)
                .disableAutocorrection(true)
                #endif
            TextField("Color (e.g. green or 0,255,0)", text: $specialGroups[index].color)
                .textFieldStyle(.roundedBorder)
                .font(.caption.monospaced())
                #if os(iOS)
                .autocapitalization(.none)
                .disableAutocorrection(true)
                #endif
        }
        .padding(8)
        .background(Color(.systemGray5))
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    /// Parse the uniform LED fields and send the command.
    private func sendUniformLeds() {
        do {
            let squares = try parseSquares(ledSquaresText)
            let (r, g, b) = try parseColor(ledColorText)
            viewModel.setLeds(squares: squares, r: r, g: g, b: b, overwrite: ledOverwrite)
        } catch {
            viewModel.eventLog.insert(
                "[Error] \(error.localizedDescription)",
                at: 0
            )
        }
    }

    /// Parse the special LED group fields and send the command.
    private func sendSpecialLeds() {
        do {
            var groups: [LedGroup] = []
            for entry in specialGroups {
                guard !entry.squares.trimmingCharacters(in: .whitespaces).isEmpty else { continue }
                let squares = try parseSquares(entry.squares)
                let (r, g, b) = try parseColor(entry.color)
                groups.append(LedGroup(
                    squares: squares.map { ($0.0, $0.1) },
                    r: r, g: g, b: b
                ))
            }
            if groups.isEmpty {
                viewModel.eventLog.insert(
                    "[Error] No valid groups defined.",
                    at: 0
                )
                return
            }
            viewModel.setLedsSpecial(groups: groups)
        } catch {
            viewModel.eventLog.insert(
                "[Error] \(error.localizedDescription)",
                at: 0
            )
        }
    }

    // MARK: - Event Log

    private var eventLogSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Event Log")
                    .font(.subheadline)
                    .fontWeight(.semibold)
                Spacer()
                if !viewModel.eventLog.isEmpty {
                    Button {
                        viewModel.eventLog.removeAll()
                    } label: {
                        Label("Clear", systemImage: "trash")
                            .font(.caption)
                    }
                    .tint(.secondary)
                }
            }

            if viewModel.eventLog.isEmpty {
                Text("No events yet. Piece movements and command results will appear here.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 8)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(viewModel.eventLog.enumerated()), id: \.offset) { _, entry in
                            Text(entry)
                                .font(.system(.caption2, design: .monospaced))
                                .foregroundStyle(logColor(for: entry))
                                .textSelection(.enabled)
                        }
                    }
                }
                .frame(maxHeight: 300)
                .padding(8)
                .background(Color(.systemGray6))
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
        }
    }

    /// Choose a color for log entries based on content.
    private func logColor(for entry: String) -> Color {
        if entry.contains("[Error]") || entry.contains("failed") || entry.contains("timed out") {
            return .red
        } else if entry.contains("PLACED") || entry.contains("placed on") {
            return .green
        } else if entry.contains("LIFTED") || entry.contains("lifted from") {
            return .orange
        }
        return .primary
    }
}

// MARK: - System Color Compatibility

#if os(macOS)
extension Color {
    static let systemGray6 = Color(NSColor.controlBackgroundColor)
    static let systemGray5 = Color(NSColor.windowBackgroundColor)
}
#endif

// MARK: - App Entry Point

@main
struct GoChessExampleApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
