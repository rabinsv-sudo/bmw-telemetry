package com.bmw.telemetry

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class EngineFamily { BMW_N_SERIES, BMW_B_SERIES }
enum class DragState { IDLE, MEASURING, FINISHED }

data class LiveMetrics(
    val coolant: Int = 0,
    val engineOil: Int = 0,
    val gearboxOil: Int = 0,
    val boostBar: Float = 0.0f
)

data class DragResult(
    val state: DragState = DragState.IDLE,
    val currentSpeedKmH: Float = 0f,
    val elapsedTimeSec: Float = 0.0f,
    val final0to100Sec: Float? = null
)

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    private val elmDriver = BmwElm327Driver()
    private lateinit var gpsTracker: GpsSpeedTracker

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            gpsTracker.startTracking()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gpsTracker = GpsSpeedTracker(this)

        checkAndRequestPermissions()

        setContent {
            val metrics by elmDriver.metrics.collectAsState()
            val dragData by gpsTracker.dragData.collectAsState()
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = btManager?.adapter
                
                // Исправлено: явно указали тип device: BluetoothDevice
                val elmDevice = adapter?.bondedDevices?.firstOrNull { device: BluetoothDevice ->
                    val devName = device.name ?: ""
                    devName.contains("OBD", ignoreCase = true) || devName.contains("ELM", ignoreCase = true)
                }

                elmDevice?.let { dev ->
                    scope.launch {
                        elmDriver.startTelemetry(dev, EngineFamily.BMW_B_SERIES)
                    }
                }
            }

            FullBmwDashboard(
                metrics = metrics,
                dragData = dragData
            )
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val missing = permissions.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            gpsTracker.startTracking()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        elmDriver.stop()
        gpsTracker.stopTracking()
    }
}

class GpsSpeedTracker(context: Context) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val _dragData = MutableStateFlow(DragResult())
    val dragData = _dragData.asStateFlow()

    private var startTimeNano: Long = 0L
    private var lastSpeedKmH: Float = 0f
    private var lastTimestampNano: Long = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val speedKmH = (location.speed * 3.6f).coerceAtLeast(0f)
            val nowNano = SystemClock.elapsedRealtimeNanos()
            val current = _dragData.value

            when (current.state) {
                DragState.IDLE -> {
                    if (speedKmH >= 2.5f) {
                        startTimeNano = nowNano
                        lastSpeedKmH = speedKmH
                        lastTimestampNano = nowNano
                        _dragData.value = current.copy(state = DragState.MEASURING, currentSpeedKmH = speedKmH)
                    } else {
                        _dragData.value = current.copy(currentSpeedKmH = speedKmH)
                    }
                }
                DragState.MEASURING -> {
                    val elapsedSec = (nowNano - startTimeNano) / 1_000_000_000f
                    if (speedKmH >= 100f) {
                        val diff = speedKmH - lastSpeedKmH
                        val interp = if (diff > 0.1f) {
                            val fraction = (100f - lastSpeedKmH) / diff
                            ((lastTimestampNano - startTimeNano) / 1_000_000_000f) + (((nowNano - lastTimestampNano) / 1_000_000_000f) * fraction)
                        } else {
                            elapsedSec
                        }
                        _dragData.value = current.copy(state = DragState.FINISHED, currentSpeedKmH = speedKmH, final0to100Sec = interp)
                    } else {
                        lastSpeedKmH = speedKmH
                        lastTimestampNano = nowNano
                        _dragData.value = current.copy(currentSpeedKmH = speedKmH, elapsedTimeSec = elapsedSec)
                    }
                }
                DragState.FINISHED -> {
                    if (speedKmH < 1.5f) {
                        _dragData.value = DragResult(state = DragState.IDLE)
                    } else {
                        _dragData.value = current.copy(currentSpeedKmH = speedKmH)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 200).setMinUpdateIntervalMillis(100).build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    fun stopTracking() {
        fusedClient.removeLocationUpdates(locationCallback)
    }
}

class BmwElm327Driver {
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val _metrics = MutableStateFlow(LiveMetrics())
    val metrics = _metrics.asStateFlow()

    @SuppressLint("MissingPermission")
    suspend fun startTelemetry(device: BluetoothDevice, engine: EngineFamily) = withContext(Dispatchers.IO) {
        try {
            socket = device.createRfcommSocketToServiceRecord(sppUuid)
            socket?.connect()
            input = socket?.inputStream
            output = socket?.outputStream

            sendRaw("ATZ")
            sendRaw("ATE0")
            sendRaw("ATL0")
            sendRaw("ATS0")
            sendRaw("ATSP6")
            sendRaw("ATAT1")

            var baroKpa = 100
            val baroResp = sendRaw("0133")
            val parsedBaro = parseHex(baroResp, "4133")
            if (parsedBaro != null) {
                baroKpa = parsedBaro
            }

            while (socket?.isConnected == true) {
                sendRaw("ATSH7E0")
                val coolant = (parseHex(sendRaw("0105"), "4105") ?: 40) - 40
                val mapKpa = parseHex(sendRaw("010B"), "410B") ?: baroKpa
                val boost = ((mapKpa - baroKpa).coerceAtLeast(0)) / 100.0f

                val engOil = when (engine) {
                    EngineFamily.BMW_B_SERIES -> (parseHex(sendRaw("015C"), "415C") ?: 40) - 40
                    EngineFamily.BMW_N_SERIES -> {
                        val resp = sendRaw("22F45C")
                        if (resp.contains("62F45C")) (parseHex(resp, "62F45C") ?: 40) - 40
                        else (parseHex(sendRaw("222002"), "622002") ?: 40) - 40
                    }
                }

                sendRaw("ATSH7E1")
                val gearRaw = sendRaw("221E32")
                val gearOil = if (gearRaw.contains("621E32")) (parseHex(gearRaw, "621E32") ?: 40) - 40 else 0

                _metrics.value = LiveMetrics(coolant, engOil, gearOil, boost)
            }
        } catch (e: Exception) {
            stop()
        }
    }

    private fun sendRaw(cmd: String): String {
        val out = output ?: return ""
        val inp = input ?: return ""
        out.write((cmd + "\r").toByteArray())
        out.flush()
        val buffer = ByteArray(128)
        val sb = StringBuilder()
        while (true) {
            val len = inp.read(buffer)
            if (len <= 0) break
            val chunk = String(buffer, 0, len)
            sb.append(chunk)
            if (chunk.contains(">")) break
        }
        return sb.toString().replace(">", "").replace(" ", "").replace("\r", "").replace("\n", "").trim()
    }

    private fun parseHex(raw: String, prefix: String): Int? {
        val idx = raw.indexOf(prefix)
        if (idx != -1 && raw.length >= idx + prefix.length + 2) {
            return raw.substring(idx + prefix.length, idx + prefix.length + 2).toIntOrNull(16)
        }
        return null
    }

    fun stop() {
        try { socket?.close() } catch (e: Exception) {}
        socket = null
    }
}

@Composable
fun FullBmwDashboard(metrics: LiveMetrics, dragData: DragResult) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF101010)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "0 - 100 KM/H DRAG", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    val statusText = when (dragData.state) {
                        DragState.IDLE -> "ГОТОВ"
                        DragState.MEASURING -> "ЗАМЕР..."
                        DragState.FINISHED -> "ФИНИШ"
                    }
                    val statusColor = when (dragData.state) {
                        DragState.IDLE -> Color.Yellow
                        DragState.MEASURING -> Color.Green
                        DragState.FINISHED -> Color.Cyan
                    }
                    Text(text = statusText, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                val displayTime = when (dragData.state) {
                    DragState.FINISHED -> String.format("%.2f s", dragData.final0to100Sec ?: 0f)
                    DragState.MEASURING -> String.format("%.1f s", dragData.elapsedTimeSec)
                    DragState.IDLE -> "--.- s"
                }
                val resultColor = if (dragData.state == DragState.FINISHED) Color.Green else Color.White
                Text(text = displayTime, fontSize = 54.sp, fontWeight = FontWeight.Black, color = resultColor)
                Text(text = "${dragData.currentSpeedKmH.toInt()} км/ч", fontSize = 20.sp, color = Color.LightGray)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.weight(1f)) { MiniGauge("НАДДУВ", String.format("%.2f", metrics.boostBar), "BAR", Color(0xFFFF9800)) }
            Box(modifier = Modifier.weight(1f)) { MiniGauge("АНТИФРИЗ", "${metrics.coolant}", "°C", Color(0xFF2196F3)) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val oilCol = if (metrics.engineOil > 115) Color.Red else Color(0xFF4CAF50)
            val gearCol = if (metrics.gearboxOil > 105) Color.Red else Color(0xFFFF5722)
            Box(modifier = Modifier.weight(1f)) { MiniGauge("ДВС МАСЛО", "${metrics.engineOil}", "°C", oilCol) }
            Box(modifier = Modifier.weight(1f)) { MiniGauge("АКПП МАСЛО", "${metrics.gearboxOil}", "°C", gearCol) }
        }
    }
}

@Composable
fun MiniGauge(title: String, value: String, unit: String, color: Color) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier.fillMaxWidth().height(110.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(text = title, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = value, fontSize = 34.sp, color = color, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = unit, fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}
