package com.ridelink.intercom

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ridelink.intercom.ui.theme.RideLinkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PHASE 5.2: POLISHED UI - MOTORCYCLE-INSPIRED DESIGN
 * 
 * Optimized for:
 * - One-handed use with gloves
 * - High-visibility status monitoring
 * - Professional audio routing
 */
@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private var audioService: AudioService? = null
    private var serviceBound by mutableStateOf(false)
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioService.AudioServiceBinder
            audioService = binder.getService()
            serviceBound = true
            Log.d(TAG, "AudioService connected")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            serviceBound = false
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { !it }) {
            Toast.makeText(this, "Permissions required for Full Intercom", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            RideLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RideLinkMasterScreen()
                }
            }
        }

        checkAndRequestPermissions()

        Intent(this, AudioService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
    @Composable
    fun RideLinkMasterScreen() {
        var primeStatus by remember { mutableStateOf(PrimeLinkManager.Status.DISCONNECTED) }
        var meshStatus by remember { mutableStateOf(ChainLinkManager.MeshStatus.IDLE) }
        var discoveredRiders by remember { mutableStateOf(listOf<WifiP2pDevice>()) }
        
        var transmitMode by remember { mutableStateOf(AudioService.TransmitMode.PTT) }
        var isPTTTransmitting by remember { mutableStateOf(false) }
        var amplitude by remember { mutableIntStateOf(0) }
        
        var showBTDevicePicker by remember { mutableStateOf(false) }
        var pairedBTDevices by remember { mutableStateOf(setOf<BluetoothDevice>()) }

        // Sync with Service
        LaunchedEffect(serviceBound) {
            if (serviceBound) {
                audioService?.setPrimeStatusCallback { primeStatus = it }
                audioService?.setChainDiscoveryCallback { discoveredRiders = it }
                audioService?.setMeshStatusCallback { meshStatus = it }
            }
        }

        // VU Meter update
        LaunchedEffect(serviceBound) {
            while (serviceBound) {
                amplitude = audioService?.getVolumeLevel() ?: 0
                delay(100)
            }
        }

        val isAnyLinkActive = primeStatus != PrimeLinkManager.Status.DISCONNECTED ||
                primeStatus == PrimeLinkManager.Status.HOSTING ||
                meshStatus != ChainLinkManager.MeshStatus.IDLE

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("RideLink", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Intercom Active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    actions = {
                        IconButton(onClick = { audioService?.resetEngine() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reset")
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Status Badge Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatusDisplay("PRIME", primeStatus.name, getPrimeColor(primeStatus))
                        StatusDisplay("MESH", meshStatus.name, getMeshColor(meshStatus))
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Pro VU Meter
                    EnhancedVUMeter(amplitude, isAnyLinkActive, transmitMode, isPTTTransmitting)

                    Spacer(modifier = Modifier.height(24.dp))
                    Divider()

                    // Role Management
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        item {
                            PrimeSection(
                                status = primeStatus,
                                onHost = { audioService?.startPrimeHost() },
                                onJoin = { 
                                    pairedBTDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
                                    showBTDevicePicker = true 
                                },
                                onStop = { audioService?.stopAllComms() }
                            )
                        }

                        item {
                            MeshSection(
                                status = meshStatus,
                                riders = discoveredRiders,
                                onHost = { audioService?.createParty() },
                                onDiscover = { audioService?.startManualDiscovery() },
                                onJoin = { rider -> audioService?.joinParty(rider) },
                                onStop = { audioService?.stopChainMesh() }
                            )
                        }
                    }
                }

                // Dynamic FAB (Bottom Right)
                Box(modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
                    DynamicMicButton(
                        mode = transmitMode,
                        isTransmitting = isPTTTransmitting,
                        isActive = isAnyLinkActive,
                        onModeChange = { 
                            transmitMode = it
                            audioService?.setTransmitMode(it)
                        },
                        onPTTChange = { 
                            isPTTTransmitting = it
                            audioService?.setPTTActive(it)
                        }
                    )
                }
            }
        }

        if (showBTDevicePicker) {
            BTDevicePicker(
                devices = pairedBTDevices.toList(),
                onDismiss = { showBTDevicePicker = false },
                onSelected = { 
                    audioService?.connectPrime(it)
                    showBTDevicePicker = false
                }
            )
        }
    }

    @Composable
    fun StatusDisplay(label: String, value: String, color: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Surface(
                color = color.copy(alpha = 0.1f),
                border = androidx.compose.foundation.BorderStroke(1.dp, color),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = value,
                    color = color,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    fun EnhancedVUMeter(amp: Int, active: Boolean, mode: AudioService.TransmitMode, ptt: Boolean) {
        val progress = (amp.toFloat() / 15000f).coerceIn(0f, 1f)
        val isHot = active && (mode == AudioService.TransmitMode.AUTO || ptt)
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Signal strength", style = MaterialTheme.typography.labelMedium)
                    if (isHot) Text("🔴 TRANSMITTING", color = Color.Red, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = if(active) progress else 0f,
                    modifier = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape),
                    color = if(progress > 0.8f) Color.Red else Color.Green
                )
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun DynamicMicButton(
        mode: AudioService.TransmitMode,
        isTransmitting: Boolean,
        isActive: Boolean,
        onModeChange: (AudioService.TransmitMode) -> Unit,
        onPTTChange: (Boolean) -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }
        var pressStart by remember { mutableLongStateOf(0L) }
        val threshold = 160L

        Column(horizontalAlignment = Alignment.End) {
            AnimatedVisibility(visible = expanded) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 8.dp)) {
                    Button(onClick = { onModeChange(AudioService.TransmitMode.AUTO); expanded = false }) { Text("AUTO") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { onModeChange(AudioService.TransmitMode.PTT); expanded = false }) { Text("PTT") }
                }
            }

            val color = when {
                !isActive -> Color.Gray
                mode == AudioService.TransmitMode.AUTO -> Color.Green
                isTransmitting -> Color.Green
                else -> Color.Red
            }

            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(color)
                    .pointerInteropFilter {
                        when (it.action) {
                            MotionEvent.ACTION_DOWN -> { pressStart = System.currentTimeMillis(); true }
                            MotionEvent.ACTION_MOVE -> {
                                if (mode == AudioService.TransmitMode.PTT && isActive && !expanded) {
                                    if (System.currentTimeMillis() - pressStart > threshold) onPTTChange(true)
                                }
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (System.currentTimeMillis() - pressStart < threshold) expanded = !expanded
                                onPTTChange(false)
                                true
                            }
                            else -> false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(if(mode == AudioService.TransmitMode.AUTO) "🎙️" else "🎤", fontSize = 28.sp)
            }
        }
    }

    @Composable
    fun PrimeSection(status: PrimeLinkManager.Status, onHost: () -> Unit, onJoin: () -> Unit, onStop: () -> Unit) {
        Column {
            Text("Prime Link (Bluetooth)", style = MaterialTheme.typography.titleMedium)
            if (status == PrimeLinkManager.Status.DISCONNECTED || status == PrimeLinkManager.Status.FAILED) {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = onHost, modifier = Modifier.weight(1f)) { Text("Host") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onJoin, modifier = Modifier.weight(1f)) { Text("Join") }
                }
            } else {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                    Text("Disconnect Prime")
                }
            }
        }
    }

    @Composable
    fun MeshSection(status: ChainLinkManager.MeshStatus, riders: List<WifiP2pDevice>, onHost: () -> Unit, onDiscover: () -> Unit, onJoin: (WifiP2pDevice) -> Unit, onStop: () -> Unit) {
        Column {
            Text("Chain Link (WiFi)", style = MaterialTheme.typography.titleMedium)
            if (status == ChainLinkManager.MeshStatus.IDLE || status == ChainLinkManager.MeshStatus.FAILED) {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = onHost, modifier = Modifier.weight(1f)) { Text("Host") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onDiscover, modifier = Modifier.weight(1f)) { Text("Scan") }
                }
            } else {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                    Text("Leave Mesh")
                }
            }
            if (riders.isNotEmpty() && status == ChainLinkManager.MeshStatus.IDLE) {
                riders.forEach { r ->
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable { onJoin(r) }) {
                        Text(r.deviceName, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }

    @Composable
    fun BTDevicePicker(devices: List<BluetoothDevice>, onDismiss: () -> Unit, onSelected: (BluetoothDevice) -> Unit) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Select Pillion") }, text = {
            LazyColumn { items(devices) { d ->
                Text(d.name ?: "Unknown", modifier = Modifier.fillMaxWidth().padding(12.dp).clickable { onSelected(d) })
                Divider()
            }}
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    }

    private fun getPrimeColor(s: PrimeLinkManager.Status) = when(s) {
        PrimeLinkManager.Status.CONNECTED -> Color.Green
        PrimeLinkManager.Status.HOSTING -> Color.Red
        PrimeLinkManager.Status.RECONNECTING -> Color.Yellow
        else -> Color.Gray
    }

    private fun getMeshColor(s: ChainLinkManager.MeshStatus) = when(s) {
        ChainLinkManager.MeshStatus.LEADER -> Color.Cyan
        ChainLinkManager.MeshStatus.MEMBER -> Color.Green
        ChainLinkManager.MeshStatus.DISCOVERING -> Color.Yellow
        else -> Color.Gray
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) unbindService(serviceConnection)
    }
}