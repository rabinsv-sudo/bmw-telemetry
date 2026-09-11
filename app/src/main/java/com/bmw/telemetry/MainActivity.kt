package com.bmw.telemetry

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class EngineFamily { BMW_N_SERIES, BMW_B_SERIES }
enum class DragState { IDLE, MEASURING }

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
    val distanceMeters: Float = 0f,
    val time0to60: Float? = null,
    val time0to100: Float? = null,
    val time100to200: Float? = null,
    val time250m: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
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
            val history by gpsTracker.dragHistory.collectAsState()
            val connectionStatus by elmDriver.connectionStatus.collectAsState()
            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            var showDeviceDialog by remember { mutableStateOf(false) }
            var showHistoryDialog by remember { mutableStateOf(false) }
            var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
            var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }

            LaunchedEffect(showDeviceDialog) {
                if (showDeviceDialog) {
                    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val adapter = btManager?.adapter
                    pairedDevices = adapter?.bondedDevices?.toList() ?: emptyList()
                }
            }

            LaunchedEffect(selectedDevice) {
                selectedDevice?.let { dev ->
                    scope.launch {
                        elmDriver.stop()
                        elmDriver.startTelemetry(dev, EngineFamily.BMW_B_SERIES)
                    }
                }
            }

            FullBmwDashboard(
                metrics = metrics,
                dragData = dragData,
                status = connectionStatus,
                onStatusClick = { showDeviceDialog = true },
                onHistoryClick = { showHistoryDialog = true }
            )

            if (showDeviceDialog) {
                AlertDialog(
                    onDismissRequest = { showDeviceDialog = false },
                    title = { Text("Выберите Bluetooth адаптер") },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            if (pairedDevices.isEmpty()) {
                                Text("Нет сопряженных устройств. Привяжите ELM327 в настройках телефона.", color = Color.Gray)
                            }
                            pairedDevices.forEach { device ->
                                val name = device.name ?: "Неизвестное устройство"
                                Column(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedDevice = device
                                        showDeviceDialog = false
                                    }.padding(vertical = 12.dp)
                                ) {
                                    Text(text = name, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(text = device.address, fontSize = 12.sp, color = Color.Gray)
                                }
                                HorizontalDivider(color = Color.DarkGray)
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showDeviceDialog = false }) { Text("ОТМЕНА", color = Color.Red) } },
                    containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White
                )
            }

            if (showHistoryDialog) {
                AlertDialog(
                    onDismissRequest = { showHistoryDialog = false },
                    title = { Text("История заездов") },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            if (history.isEmpty()) Text("Нет сохраненных заездов.", color = Color.Gray)
                            history.asReversed().forEachIndexed { index, run ->
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                    Text("Заезд #${history.size - index}", fontWeight = FontWeight.Bold, color = Color.Cyan)
                                    run.time0to60?.let { Text("0-60: ${String.format("%.2f", it)} s", color = Color.White) }
                                    run.time0to100?.let { Text("0-100: ${String.format("%.2f", it)} s", color = Color.White) }
                                    run.time100to200?.let { Text("100-200: ${String.format("%.2f", it)} s", color = Color.White) }
                                    run.time250m?.let { Text("250 м: ${String.format("%.2f", it)} s", color = Color.White) }
                                }
                                HorizontalDivider(color = Color.DarkGray)
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showHistoryDialog = false }) { Text("ЗАКРЫТЬ", color = Color.White) } },
                    containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else gpsTracker.startTracking()
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

    private val _dragHistory = MutableStateFlow<List<DragResult>>(emptyList())
    val dragHistory = _dragHistory.asStateFlow()

    private var startTimeNano: Long = 0L
    private var lastSpeedKmH: Float = 0f
    private var lastElapsedSec: Float = 0f
    private var lastLocation: Location? = null
    
    private var currentRun = DragResult()
    private var stopTimerStartNano: Long? = null

    private fun interpolate(target: Float, v1: Float, v2: Float, t1: Float, t2: Float): Float {
        if (v2 <= v1) return t2
        return t1 + (t2 - t1) * ((target - v1) / (v2 - v1))
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val speedKmH = (location.speed * 3.6f).coerceAtLeast(0f)
            
            // Используем время от GPS для точности
            val nowNano = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                location.elapsedRealtimeNanos
            } else SystemClock.elapsedRealtimeNanos()

            when (currentRun.state) {
                DragState.IDLE -> {
                    if (speedKmH >= 3f) {
                        // Старт замера
                        startTimeNano = nowNano
                        lastSpeedKmH = speedKmH
                        lastElapsedSec = 0f
                        lastLocation = location
                        stopTimerStartNano = null
                        currentRun = DragResult(state = DragState.MEASURING, currentSpeedKmH = speedKmH)
                        _dragData.value = currentRun
                    } else {
                        // Обновляем только скорость
                        _dragData.value = currentRun.copy(currentSpeedKmH = speedKmH)
                    }
                }
                DragState.MEASURING -> {
                    val elapsedSec = (nowNano - startTimeNano) / 1_000_000_000f
                    val distStep = lastLocation?.distanceTo(location) ?: 0f
                    val newDist = currentRun.distanceMeters + distStep

                    var t60 = currentRun.time0to60
                    var t100 = currentRun.time0to100
                    var t200 = currentRun.time100to200
                    var t250m = currentRun.time250m

                    // Отсечки скорости
                    if (t60 == null && speedKmH >= 60f) t60 = interpolate(60f, lastSpeedKmH, speedKmH, lastElapsedSec, elapsedSec)
                    if (t100 == null && speedKmH >= 100f) t100 = interpolate(100f, lastSpeedKmH, speedKmH, lastElapsedSec, elapsedSec)
                    if (t200 == null && speedKmH >= 200f && t100 != null) {
                        val raw200 = interpolate(200f, lastSpeedKmH, speedKmH, lastElapsedSec, elapsedSec)
                        t200 = raw200 - t100
                    }

                    // Отсечка дистанции 250м
                    if (t250m == null && newDist >= 250f) {
                        t250m = interpolate(250f, currentRun.distanceMeters, newDist, lastElapsedSec, elapsedSec)
                    }

                    // Логика авто-сброса (если машина стоит > 5 секунд)
                    if (speedKmH < 3f) {
                        if (stopTimerStartNano == null) stopTimerStartNano = nowNano
                        else if ((nowNano - stopTimerStartNano!!) / 1_000_000_000f > 5f) {
                            // Сохраняем результат, если есть хоть одна отсечка
                            if (t60 != null || t100 != null || t250m != null) {
                                val finalRun = currentRun.copy(time0to60 = t60, time0to100 = t100, time100to200 = t200, time250m = t250m)
                                _dragHistory.value = _dragHistory.value + finalRun
                            }
                            // Сброс в IDLE
                            currentRun = DragResult(state = DragState.IDLE, currentSpeedKmH = speedKmH)
                            _dragData.value = currentRun
                            return
                        }
                    } else {
                        stopTimerStartNano = null
                    }

                    currentRun = currentRun.copy(
                        currentSpeedKmH = speedKmH,
                        elapsedTimeSec = elapsedSec,
                        distanceMeters = newDist,
                        time0to60 = t60,
                        time0to100 = t100,
                        time100to200 = t200,
                        time250m = t250m
                    )

                    lastSpeedKmH = speedKmH
                    lastElapsedSec = elapsedSec
                    lastLocation = location
                    _dragData.value = currentRun
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 100).setMinUpdateIntervalMillis(50).build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }
    fun stopTracking() = fusedClient.removeLocationUpdates(locationCallback)
}

class BmwElm327Driver {
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val _metrics = MutableStateFlow(LiveMetrics())
    val metrics = _metrics.asStateFlow()
    val connectionStatus = MutableStateFlow("НАЖМИТЕ СЮДА ДЛЯ ВЫБОРА АДАПТЕРА")

    @SuppressLint("MissingPermission")
    suspend fun startTelemetry(device: BluetoothDevice, engine: EngineFamily) = withContext(Dispatchers.IO) {
        try {
            connectionStatus.value = "ПОДКЛЮЧЕНИЕ К ${device.name}..."
            try {
                socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket?.connect()
            } catch (e: Exception) {
                socket = device.createInsecureRfcommSocketToServiceRecord(sppUuid)
                socket?.connect()
            }
            
            input = socket?.inputStream
            output = socket?.outputStream

            connectionStatus.value = "СБРОС ELM327 (ATZ)..."
            sendRaw("ATZ")
            delay(1000)

            connectionStatus.value = "НАСТРОЙКА ПРОТОКОЛА..."
            sendRaw("ATE0")
            sendRaw("ATL0")
            sendRaw("ATS0")
            sendRaw("ATSP0") 
            delay(500)
            sendRaw("ATAT1")

            var baroKpa = 100
            val baroResp = sendRaw("0133")
            parseHex(baroResp, "4133")?.let { baroKpa = it }

            while (socket?.isConnected == true) {
                sendRaw("ATSH7E0")
                val rawCoolantResp = sendRaw("0105")
                withContext(Dispatchers.Main) { connectionStatus.value = "ЭБУ: $rawCoolantResp" }

                val coolant = (parseHex(rawCoolantResp, "4105") ?: 40) - 40
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
                delay(200)
            }
        } catch (e: Exception) {
            connectionStatus.value = "ОШИБКА: ${e.message?.uppercase()} (НАЖМИТЕ ДЛЯ ПОВТОРА)"
            stop()
        }
    }

    private fun sendRaw(cmd: String): String {
        val out = output ?: return ""
        val inp = input ?: return ""
        try {
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
        } catch (e: Exception) { return "" }
    }

    private fun parseHex(raw: String, prefix: String): Int? {
        val idx = raw.indexOf(prefix)
        if (idx != -1 && raw.length >= idx + prefix.length + 2) return raw.substring(idx + prefix.length, idx + prefix.length + 2).toIntOrNull(16)
        return null
    }

    fun stop() {
        try { socket?.close() } catch (e: Exception) {}
        socket = null
    }
}

@Composable
fun FullBmwDashboard(metrics: LiveMetrics, dragData: DragResult, status: String, onStatusClick: () -> Unit, onHistoryClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF101010)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            modifier = Modifier.fillMaxWidth().clickable { onStatusClick() }
        ) {
            Text(
                text = status,
                color = if (status.contains("ОШИБКА")) Color.Red else if (status.contains("ЭБУ:")) Color.Cyan else Color.Yellow,
                fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "DRAG METER", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { onHistoryClick() }) { Text("ИСТОРИЯ", color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("СКОРОСТЬ", color = Color.Gray, fontSize = 10.sp)
                        Text("${dragData.currentSpeedKmH.toInt()}", fontSize = 42.sp, fontWeight = FontWeight.Black, color = Color.White)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ДИСТАНЦИЯ", color = Color.Gray, fontSize = 10.sp)
                        Text("${dragData.distanceMeters.toInt()} м", fontSize = 42.sp, fontWeight = FontWeight.Black, color = Color.White)
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TimeBox("0-60", dragData.time0to60)
                    TimeBox("0-100", dragData.time0to100)
                    TimeBox("100-200", dragData.time100to200)
                    TimeBox("250 м", dragData.time250m)
                }
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
fun TimeBox(label: String, time: Float?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        val displayTime = if (time != null) String.format("%.2f", time) else "--.--"
        Text(text = displayTime, color = if (time != null) Color.Green else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
