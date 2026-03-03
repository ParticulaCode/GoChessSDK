/**
 * GoChess SDK – Android Example (Jetpack Compose)
 * ================================================
 *
 * Demonstrates scanning, connecting, querying, and controlling
 * a GoChess smart chess board via BLE.
 *
 * Requirements:
 *   - Android API 21+ (recommended 23+)
 *   - Permissions: BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION
 *   - Dependencies: kotlinx-coroutines, Jetpack Compose
 */

package com.particula.gochess.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.particula.gochess.sdk.BoardState
import com.particula.gochess.sdk.BorderState
import com.particula.gochess.sdk.GoChessBoard
import com.particula.gochess.sdk.GoChessDevice
import com.particula.gochess.sdk.GoChessHelpers
import com.particula.gochess.sdk.LedGroup
import com.particula.gochess.sdk.PieceEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ==========================================================================
// Constants & Helpers
// ==========================================================================

private const val TAG = "GoChessExample"

private const val FILES = "abcdefgh"
private const val VALID_FILES = "abcdefgh"
private const val VALID_RANKS = "12345678"

/**
 * Convert a firmware border notification to a human-readable label.
 *
 * The firmware sends 1-indexed positions (1-10) for border slots.
 * This maps them to the correct 0-indexed labels:
 *   - 't' (top):    position 1-8 -> a9..h9
 *   - 'b' (bottom): position 1-8 -> a0..h0
 *   - 'l' (left):   position 1-10 -> q0..q9
 *   - 'r' (right):  position 1-10 -> i0..i9
 */
fun borderEventToLabel(side: String, position: Int): String {
    return when {
        side == "t" && position in 1..8 -> "${FILES[position - 1]}9"
        side == "b" && position in 1..8 -> "${FILES[position - 1]}0"
        side == "l" && position in 1..10 -> "q${position - 1}"
        side == "r" && position in 1..10 -> "i${position - 1}"
        else -> "$side$position"
    }
}

/**
 * Parse a comma-separated list of chess-notation squares.
 *
 * e.g. "e2, e4, d4" -> listOf(Pair(2,5), Pair(4,5), Pair(4,4))
 *
 * @throws IllegalArgumentException if any square is invalid.
 */
fun parseSquares(text: String): List<Pair<Int, Int>> {
    val squares = mutableListOf<Pair<Int, Int>>()
    for (raw in text.split(",")) {
        val token = raw.trim().lowercase()
        if (token.isEmpty()) continue
        require(token.length == 2) {
            "Invalid square '$token'. Use notation like e4."
        }
        val f = token[0]
        val r = token[1]
        require(f in 'a'..'h' && r in '1'..'8') {
            "Invalid square '$token'. File must be a-h, rank 1-8."
        }
        val col = f - 'a' + 1
        val row = r - '0'
        squares.add(Pair(row, col))
    }
    return squares
}

/**
 * Parse an RGB string like "255,0,0" or a named colour shorthand.
 *
 * Supported names: red, green, blue, yellow, cyan, magenta, white, orange, purple, off
 *
 * @return Triple(r, g, b) with values 0-255.
 * @throws IllegalArgumentException if the format is invalid.
 */
fun parseColor(text: String): Triple<Int, Int, Int> {
    val shortcuts = mapOf(
        "red" to Triple(255, 0, 0),
        "green" to Triple(0, 255, 0),
        "blue" to Triple(0, 0, 255),
        "yellow" to Triple(255, 255, 0),
        "cyan" to Triple(0, 255, 255),
        "magenta" to Triple(255, 0, 255),
        "white" to Triple(255, 255, 255),
        "orange" to Triple(255, 128, 0),
        "purple" to Triple(128, 0, 255),
        "off" to Triple(0, 0, 0),
    )
    val trimmed = text.trim().lowercase()
    shortcuts[trimmed]?.let { return it }

    val parts = trimmed.split(",")
    require(parts.size == 3) {
        "Color must be R,G,B (e.g. 255,0,0) or a name (red, green, blue, ...)."
    }
    return Triple(
        parts[0].trim().toInt(),
        parts[1].trim().toInt(),
        parts[2].trim().toInt()
    )
}

// ==========================================================================
// Connection State
// ==========================================================================

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected
}

// ==========================================================================
// Board Info
// ==========================================================================

data class BoardInfo(
    val fwVersion: Int = 0,
    val fwLabel: String = "",
    val battery: Int = -1,
    val isXR: Boolean = false,
    val deviceName: String = ""
)

// ==========================================================================
// ViewModel
// ==========================================================================

class GoChessViewModel(private val appContext: android.content.Context) : ViewModel() {

    private var board: GoChessBoard? = null

    private val _devices = MutableStateFlow<List<GoChessDevice>>(emptyList())
    val devices: StateFlow<List<GoChessDevice>> = _devices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _boardInfo = MutableStateFlow<BoardInfo?>(null)
    val boardInfo: StateFlow<BoardInfo?> = _boardInfo.asStateFlow()

    private val _eventLog = MutableStateFlow<List<String>>(emptyList())
    val eventLog: StateFlow<List<String>> = _eventLog.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private fun log(message: String) {
        _eventLog.value = listOf(message) + _eventLog.value
        Log.d(TAG, message)
    }

    // -- Scanning --

    fun scan() {
        viewModelScope.launch {
            _isScanning.value = true
            _devices.value = emptyList()
            log("Scanning for GoChess boards (5 seconds)...")
            try {
                val found = GoChessBoard.scan(appContext, timeoutMs = 5000L)
                _devices.value = found
                if (found.isEmpty()) {
                    log("No GoChess boards found. Make sure the board is on and nearby.")
                } else {
                    log("Found ${found.size} board(s).")
                }
            } catch (e: Exception) {
                log("Scan error: ${e.message}")
            } finally {
                _isScanning.value = false
            }
        }
    }

    // -- Connection --

    fun connect(device: GoChessDevice) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Connecting
            log("Connecting to ${device.name} ...")
            try {
                val newBoard = GoChessBoard(appContext)
                newBoard.connect(device)
                board = newBoard

                // Register piece-move callback
                newBoard.onPieceMove { event ->
                    handlePieceEvent(event)
                }

                _connectionState.value = ConnectionState.Connected

                // Detect XR by name
                var isXR = device.name.startsWith("GoChessXR")

                // Fetch firmware version
                var fwVersion = 0
                var fwLabel = "Unknown"
                try {
                    fwVersion = newBoard.getFwVersion()
                    fwLabel = when (fwVersion) {
                        0x03 -> "GoChess Mini / Lite"
                        0x04 -> "GoChess Robotic (XR)"
                        else -> "Unknown (0x${"%02X".format(fwVersion)})"
                    }
                    if (fwVersion == 0x04) isXR = true
                    log("Firmware: 0x${"%02X".format(fwVersion)} ($fwLabel)")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Firmware request failed: ${e.message}")
                }

                // Fetch battery
                var battery = -1
                try {
                    battery = newBoard.getBattery()
                    log("Battery: $battery%")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Battery request failed: ${e.message}")
                }

                _boardInfo.value = BoardInfo(
                    fwVersion = fwVersion,
                    fwLabel = fwLabel,
                    battery = battery,
                    isXR = isXR,
                    deviceName = device.name
                )

                val boardType = if (isXR) {
                    "GoChess XR (Robotic) - border state supported"
                } else {
                    "GoChess Mini/Lite - no border slots"
                }
                log("Board type: $boardType")
                log("Connected! Piece movements will appear in real-time.")

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Connection failed: ${e.message}")
                _connectionState.value = ConnectionState.Disconnected
                board = null
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                board?.disconnect()
                log("Disconnected.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Disconnect error: ${e.message}")
            } finally {
                board = null
                _connectionState.value = ConnectionState.Disconnected
                _boardInfo.value = null
            }
        }
    }

    // -- Commands --

    fun getBattery() {
        viewModelScope.launch {
            try {
                val battery = board?.getBattery()
                if (battery != null) {
                    _boardInfo.value = _boardInfo.value?.copy(battery = battery)
                    log("Battery: $battery%")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Battery request failed: ${e.message}")
            }
        }
    }

    fun getBoardState() {
        viewModelScope.launch {
            try {
                val state: BoardState? = board?.getBoardState()
                if (state != null) {
                    log("Board state (${state.pieceCount} pieces):\n${state}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Board state request failed: ${e.message}")
            }
        }
    }

    fun getBorderState() {
        val info = _boardInfo.value
        if (info != null && !info.isXR) {
            log("Border state is only available on GoChess XR (Robotic).\nThis board is a Mini/Lite model and does not have border slots.")
            return
        }
        viewModelScope.launch {
            try {
                val border: BorderState? = board?.getBorderState()
                if (border != null) {
                    val hexStr = border.rawBytes.joinToString(" ") { "0x${"%02X".format(it)}" }
                    log("Border state (${border.occupiedCount} occupied):\nRaw: $hexStr\n${border}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Border state request failed: ${e.message}")
            }
        }
    }

    fun getFwVersion() {
        viewModelScope.launch {
            try {
                val fw = board?.getFwVersion()
                if (fw != null) {
                    val label = when (fw) {
                        0x03 -> "GoChess Mini / Lite"
                        0x04 -> "GoChess Robotic (XR)"
                        else -> "Unknown"
                    }
                    log("Firmware version: 0x${"%02X".format(fw)} ($label)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("FW version request failed: ${e.message}")
            }
        }
    }

    fun setLeds(squaresText: String, colorText: String, overwrite: Boolean) {
        viewModelScope.launch {
            try {
                val squares = parseSquares(squaresText)
                val (r, g, b) = parseColor(colorText)
                val labels = squares.map { (row, col) ->
                    GoChessHelpers.rcToSquareNotation(row, col)
                }
                log("Setting LEDs: squares=${labels.joinToString(", ")} color=($r,$g,$b) overwrite=$overwrite")
                board?.setLeds(squares, r = r, g = g, b = b, overwrite = overwrite)
                log("LEDs set.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Set LEDs error: ${e.message}")
            }
        }
    }

    fun setLedsSpecial(groupsInput: List<Triple<String, String, Unit>>) {
        // This overload is unused; see the simpler setLeds for the example UI.
    }

    fun setLedsOff() {
        viewModelScope.launch {
            try {
                board?.setLedsOff()
                log("All LEDs turned off.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("LEDs off error: ${e.message}")
            }
        }
    }

    // -- Piece events --

    private fun handlePieceEvent(event: PieceEvent) {
        val message = if (event.isBorder) {
            val label = borderEventToLabel(event.borderSide, event.col)
            val action = if (event.isDown) "placed on" else "lifted from"
            "Border piece $action $label  (side='${event.borderSide}', pos=${event.col})"
        } else {
            val square = GoChessHelpers.rcToSquareNotation(event.row, event.col)
            val action = if (event.isDown) "PLACED on" else "LIFTED from"
            "Piece $action $square  (row=${event.row}, col=${event.col})"
        }
        log(message)
    }

    // -- Factory --

    class Factory(private val appContext: android.content.Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GoChessViewModel(appContext) as T
        }
    }
}

// ==========================================================================
// Activity
// ==========================================================================

class MainActivity : ComponentActivity() {

    private lateinit var vm: GoChessViewModel

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vm = ViewModelProvider(
            this,
            GoChessViewModel.Factory(applicationContext)
        )[GoChessViewModel::class.java]

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val allGranted = results.values.all { it }
            if (!allGranted) {
                Log.w(TAG, "BLE permissions denied: $results")
            }
            showUi()
        }

        val requiredPermissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()

        val allGranted = requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            showUi()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun showUi() {
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GoChessApp(vm)
                }
            }
        }
    }
}

// ==========================================================================
// Compose UI
// ==========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoChessApp(vm: GoChessViewModel) {
    val connectionState by vm.connectionState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GoChess SDK Example") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (connectionState) {
                ConnectionState.Disconnected,
                ConnectionState.Connecting -> ScanScreen(vm)
                ConnectionState.Connected -> ConnectedScreen(vm)
            }
        }
    }
}

// --------------------------------------------------------------------------
// Screen 1: Scan & Connect
// --------------------------------------------------------------------------

@Composable
fun ScanScreen(vm: GoChessViewModel) {
    val devices by vm.devices.collectAsState()
    val isScanning by vm.isScanning.collectAsState()
    val connectionState by vm.connectionState.collectAsState()
    val eventLog by vm.eventLog.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Scan button
        Button(
            onClick = { vm.scan() },
            enabled = !isScanning && connectionState == ConnectionState.Disconnected,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scanning...")
            } else {
                Text("Scan for Boards")
            }
        }

        if (connectionState == ConnectionState.Connecting) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connecting...", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Device list
        if (devices.isNotEmpty()) {
            Text(
                text = "Found ${devices.size} board(s):",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(devices) { device ->
                DeviceCard(
                    device = device,
                    enabled = connectionState == ConnectionState.Disconnected,
                    onClick = { vm.connect(device) }
                )
            }
        }

        // Event log at bottom
        if (eventLog.isNotEmpty()) {
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Log",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.height(150.dp)
            ) {
                items(eventLog) { entry ->
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceCard(device: GoChessDevice, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// --------------------------------------------------------------------------
// Screen 2: Connected (main screen)
// --------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectedScreen(vm: GoChessViewModel) {
    val boardInfo by vm.boardInfo.collectAsState()
    val eventLog by vm.eventLog.collectAsState()
    var showLedDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // -- Header --
        boardInfo?.let { info ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = info.deviceName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (info.fwVersion > 0) {
                                "FW: 0x${"%02X".format(info.fwVersion)} (${info.fwLabel})"
                            } else {
                                "FW: --"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (info.battery >= 0) "Battery: ${info.battery}%" else "Battery: --",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = if (info.isXR) "XR (Robotic)" else "Mini/Lite",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Status: Connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E7D32)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // -- Command Buttons --
        Text(
            text = "Commands",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CommandButton(label = "Battery") { vm.getBattery() }
            CommandButton(label = "Board State") { vm.getBoardState() }
            CommandButton(label = "Border State") { vm.getBorderState() }
            CommandButton(label = "FW Version") { vm.getFwVersion() }
            CommandButton(label = "Set LEDs") { showLedDialog = true }
            CommandButton(label = "LEDs Off") { vm.setLedsOff() }
            CommandButton(
                label = "Disconnect",
                isDestructive = true
            ) { vm.disconnect() }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(8.dp))

        // -- Event Log --
        Text(
            text = "Event Log",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))

        val listState = rememberLazyListState()

        // Auto-scroll to top when new events arrive (newest first)
        LaunchedEffect(eventLog.size) {
            if (eventLog.isNotEmpty()) {
                listState.animateScrollToItem(0)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(eventLog) { entry ->
                Text(
                    text = entry,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp),
                    overflow = TextOverflow.Visible
                )
            }
        }
    }

    // -- LED Dialog --
    if (showLedDialog) {
        SetLedDialog(
            onDismiss = { showLedDialog = false },
            onSend = { squares, color, overwrite ->
                vm.setLeds(squares, color, overwrite)
                showLedDialog = false
            }
        )
    }
}

@Composable
fun CommandButton(
    label: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    if (isDestructive) {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(label)
        }
    } else {
        Button(onClick = onClick) {
            Text(label)
        }
    }
}

// --------------------------------------------------------------------------
// Set LED Dialog
// --------------------------------------------------------------------------

@Composable
fun SetLedDialog(
    onDismiss: () -> Unit,
    onSend: (squares: String, color: String, overwrite: Boolean) -> Unit
) {
    var squaresText by remember { mutableStateOf("e2, e4") }
    var colorText by remember { mutableStateOf("red") }
    var overwrite by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set LEDs") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Enter squares in chess notation, comma-separated.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = squaresText,
                    onValueChange = {
                        squaresText = it
                        errorText = null
                    },
                    label = { Text("Squares (e.g. e2, e4, d4)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Color: name (red, green, blue, yellow, cyan, magenta, white, orange, purple) or R,G,B.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = colorText,
                    onValueChange = {
                        colorText = it
                        errorText = null
                    },
                    label = { Text("Color (e.g. red or 255,0,0)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = overwrite,
                        onCheckedChange = { overwrite = it }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Overwrite other LEDs", style = MaterialTheme.typography.bodyMedium)
                }

                if (errorText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorText ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Validate inputs before sending
                    try {
                        parseSquares(squaresText)
                        parseColor(colorText)
                        onSend(squaresText, colorText, overwrite)
                    } catch (e: Exception) {
                        errorText = e.message ?: "Invalid input"
                    }
                }
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
