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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
    val dist0to60: Float? = null,
    val time0to100: Float? = null,
    val dist0to100: Float? = null,
    val time100to200: Float? = null,
    val time250m: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class SensorConfig(
    val name: String,
    val header: String,
    val command: String,
    val expectedReply: String,
    val offset: Int
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
            val logs by elmDriver.logs.collectAsState()
            val connectionStatus by elmDriver.connectionStatus.collectAsState()
            
            val scope = rememberCoroutineScope()
            val context = LocalContext.current
            val sharedPrefs = context.getSharedPreferences("BmwTelemetryPrefs", Context.MODE_PRIVATE)

            var showDeviceDialog by remember { mutableStateOf(false) }
            var showHistoryDialog by remember { mutableStateOf(false) }
            var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
            var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
            
            var selectedTab by remember { mutableStateOf(0) }

            LaunchedEffect(Unit) {
                val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = btManager?.adapter
                val lastMac = sharedPrefs.getString("last_bt_mac", null)
                if (lastMac != null && adapter != null) {
                    val dev = adapter.bondedDevices?.find { it.address == lastMac }
                    if (dev != null) selectedDevice = dev
                }
            }

            LaunchedEffect(showDeviceDialog) {
                if (showDeviceDialog) {
                    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    pairedDevices = btManager?.adapter?.bondedDevices?.toList() ?: emptyList()
                }
            }

            LaunchedEffect(selectedDevice) {
                selectedDevice?.let { dev ->
                    scope.launch {
                        elmDriver.stop()
                        delay(500)
                        elmDriver.startTelemetry(dev)
                    }
                }
            }

            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF101010))) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF1E1E1E),
                    contentColor = Color.Cyan
                ) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                        Text("ПРИБОРЫ", modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                    }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                        Text("ЛОГ АДАПТЕРА", modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                    }
                }

                if (selectedTab == 0) {
                    FullBmwDashboard(
                        metrics = metrics, dragData = dragData, status = connectionStatus,
                        onStatusClick = { showDeviceDialog = true }, onHistoryClick = { showHistoryDialog = true }
                    )
                } else {
                    LogsScreen(logs, onClear = { elmDriver.clearLogs() })
                }
            }

            if (showDeviceDialog) {
                AlertDialog(
                    onDismissRequest = { showDeviceDialog = false },
                    title = { Text("Выберите Bluetooth адаптер") },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            if (pairedDevices.isEmpty()) Text("Нет сопряженных устройств.", color = Color.Gray)
                            pairedDevices.forEach { device ->
                                val name = device.name ?: "Unknown"
                                Column(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedDevice = device
                                        sharedPrefs.edit().putString("last_bt_mac", device.address).apply()
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
                                    run.time0to60?.let { Text("0-60: ${String.format("%.2f", it)} s (${run.dist0to60?.toInt()} м)", color = Color.White) }
                                    run.time0to100?.let { Text("0-100: ${String.format("%.2f", it)} s (${run.dist0to100?.toInt()} м)", color = Color.White) }
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

@Composable
fun LogsScreen(logs: List<String>, onClear: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Button(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("ОЧИСТИТЬ ЛОГ") }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            items(logs) { logMsg ->
                Text(
                    text = logMsg,
                    color = if (logMsg.contains("ERR") || logMsg.contains("NODATA") || logMsg.contains("FAIL")) Color.Red else Color.Green,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
            }
        }
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

    private fun interpolateTime(target: Float, v1: Float, v2: Float, t1: Float, t2: Float): Float {
        if (v2 <= v1) return t2
        return t1 + (t2 - t1) * ((target - v1) / (v2 - v1))
    }
    
    private fun interpolateDist(target: Float, v1: Float, v2: Float, d1: Float, d2: Float): Float {
        if (v2 <= v1) return d2
        return d1 + (d2 - d1) * ((target - v1) / (v2 - v1))
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val speedKmH = (location.speed * 3.6f).coerceAtLeast(0f)
            
            val nowNano = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                location.elapsedRealtimeNanos
            } else SystemClock.elapsedRealtimeNanos()

            when (currentRun.state) {
                DragState.IDLE -> {
                    if (speedKmH >= 3f) {
                        startTimeNano = nowNano
                        lastSpeedKmH = speedKmH
                        lastElapsedSec = 0f
                        lastLocation = location
                        stopTimerStartNano = null
                        currentRun = DragResult(state = DragState.MEASURING, currentSpeedKmH = speedKmH)
                        _dragData.value = currentRun
                    } else {
                        _dragData.value = currentRun.copy(currentSpeedKmH = speedKmH)
                    }
                }
                DragState.MEASURING -> {
                    val elapsedSec = (nowNano - startTimeNano) / 1_000_000_000f
                    val distStep = lastLocation?.distanceTo(location) ?: 0f
                    val newDist = currentRun.distanceMeters + distStep

                    var t60 = currentRun.time0to60
                    var d60 = currentRun.dist0to60
                    var t100 = currentRun.time0to100
                    var d100 = currentRun.dist0to100
                    var t200 = currentRun.time100to200
                    var t250m = currentRun.time250m

                    if (t60 == null && speedKmH >= 60f) {
                        t60 = interpolateTime(60f, lastSpeedKmH, speedKmH, lastElapsedSec, elapsedSec)
                        d60 = interpolateDist(60f, lastSpeedKmH, speedKmH, currentRun.distanceMeters, newDist)
                    }
                    if (t100 == null && speedKmH >= 100f) {
                        t100 = interpolateTime(100f, lastSpeedKmH, speedKmH, lastElapsedSec, elapsedSec)
                        d100 = interpolateDist(100f, lastSpeedKmH, speedKmH, currentRun.distanceMeters, newDist)
                    }
                    if (t200 == null && speedKmH >= 200f && t100 != null) {
                        t200 = interpolateTime(200f, lastSpeedKmH, speedKmH, lastElapsedSec, elapsedSec) - t100
                    }
                    if (t250m == null && newDist >= 250f) {
                        t250m = interpolateTime(250f, currentRun.distanceMeters, newDist, lastElapsedSec, elapsedSec)
                    }

                    if (speedKmH < 3f) {
                        if (stopTimerStartNano == null) stopTimerStartNano = nowNano
                        else if ((nowNano - stopTimerStartNano!!) / 1_000_000_000f > 5f) {
                            if (t60 != null || t100 != null || t250m != null) {
                                val finalRun = currentRun.copy(time0to60 = t60, dist0to60 = d60, time0to100 = t100, dist0to100 = d100, time100to200 = t200, time250m = t250m)
                                _dragHistory.value = _dragHistory.value + finalRun
                            }
                            currentRun = DragResult(state = DragState.IDLE, currentSpeedKmH = speedKmH)
                            _dragData.value = currentRun
                            return
                        }
                    } else {
                        stopTimerStartNano = null
                    }

                    currentRun = currentRun.copy(
                        currentSpeedKmH = speedKmH, elapsedTimeSec = elapsedSec, distanceMeters = newDist,
                        time0to60 = t60, dist0to60 = d60, time0to100 = t100, dist0to100 = d100, time100to200 = t200, time250m = t250m
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
    
    @Volatile private var isRunning = false

    private val _metrics = MutableStateFlow(LiveMetrics())
    val metrics = _metrics.asStateFlow()
    
    val connectionStatus = MutableStateFlow("ОЖИДАНИЕ АДАПТЕРА")
    
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        _logs.value = (listOf("[$time] $msg") + _logs.value).take(150)
    }
    
    fun clearLogs() {
        _logs.value = emptyList()
    }

    @SuppressLint("MissingPermission")
    suspend fun startTelemetry(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        isRunning = true
        while (isRunning) {
            try {
                connectionStatus.value = "ПОДКЛЮЧЕНИЕ..."
                addLog("=== СТАРТ ПОДКЛЮЧЕНИЯ ===")
                addLog("MAC: ${device.address}")
                
                try {
                    socket = device.createRfcommSocketToServiceRecord(sppUuid)
                    socket?.connect()
                    addLog("Подключено через безопасный сокет")
                } catch (e: Exception) {
                    addLog("Безопасный сокет ERR: ${e.message}. Пробуем Insecure...")
                    socket = device.createInsecureRfcommSocketToServiceRecord(sppUuid)
                    socket?.connect()
                    addLog("Подключено через Insecure сокет")
                }
                
                input = socket?.inputStream
                output = socket?.outputStream

                connectionStatus.value = "ИНИЦИАЛИЗАЦИЯ ELM327..."
                sendRaw("ATZ", 1000)
                sendRaw("ATE0")
                sendRaw("ATL0")
                sendRaw("ATS0")
                
                // Пробуем жестко задать CAN 11bit 500k (самый частый для BMW)
                sendRaw("ATSP6") 
                delay(200)
                sendRaw("ATAT1")

                connectionStatus.value = "ПОИСК ПРОТОКОЛОВ ЭБУ..."
                addLog("--- СКАНИРОВАНИЕ ДАТЧИКОВ ---")

                // 1. Поиск датчика антифриза
                var coolantConf: SensorConfig? = null
                val coolantCandidates = listOf(
                    SensorConfig("COOLANT_OBD", "ATSH7DF", "0105", "4105", 40),
                    SensorConfig("COOLANT_BMW", "ATSH7E0", "22F405", "62F405", 40)
                )
                for (cand in coolantCandidates) {
                    sendRaw(cand.header)
                    val res = sendRaw(cand.command)
                    if (res.contains(cand.expectedReply)) {
                        coolantConf = cand
                        addLog("НАЙДЕН ОЖ: ${cand.name}")
                        break
                    }
                }

                // 2. Поиск датчика масла ДВС
                var oilConf: SensorConfig? = null
                val oilCandidates = listOf(
                    SensorConfig("OIL_OBD", "ATSH7DF", "015C", "415C", 40),
                    SensorConfig("OIL_BMW_B", "ATSH7E0", "22F45C", "62F45C", 40),
                    SensorConfig("OIL_BMW_OLD", "ATSH7E0", "222002", "622002", 40)
                )
                for (cand in oilCandidates) {
                    sendRaw(cand.header)
                    val res = sendRaw(cand.command)
                    if (res.contains(cand.expectedReply)) {
                        oilConf = cand
                        addLog("НАЙДЕНО МАСЛО: ${cand.name}")
                        break
                    }
                }
                
                // Барометр (единожды)
                sendRaw("ATSH7DF")
                var baroKpa = 100
                val baroResp = sendRaw("0133")
                parseHex(baroResp, "4133")?.let { baroKpa = it }

                connectionStatus.value = "ОПРОС ДАННЫХ..."
                addLog("--- СТАРТ ЦИКЛА ОПРОСА ---")

                while (socket?.isConnected == true && isRunning) {
                    var currentCoolant = _metrics.value.coolant
                    var currentOil = _metrics.value.engineOil
                    
                    // Читаем ОЖ
                    if (coolantConf != null) {
                        sendRaw(coolantConf.header)
                        val res = sendRaw(coolantConf.command)
                        parseHex(res, coolantConf.expectedReply)?.let { currentCoolant = it - coolantConf.offset }
                    }
                    
                    // Буст (универсальный)
                    sendRaw("ATSH7DF")
                    val mapKpa = parseHex(sendRaw("010B"), "410B") ?: baroKpa
                    val boost = ((mapKpa - baroKpa).coerceAtLeast(0)) / 100.0f
                    
                    // Читаем масло
                    if (oilConf != null) {
                        sendRaw(oilConf.header)
                        val res = sendRaw(oilConf.command)
                        parseHex(res, oilConf.expectedReply)?.let { currentOil = it - oilConf.offset }
                    }

                    // Читаем коробку
                    sendRaw("ATSH7E1")
                    val gearRaw = sendRaw("221E32")
                    val gearOil = if (gearRaw.contains("621E32")) (parseHex(gearRaw, "621E32") ?: 40) - 40 else 0

                    _metrics.value = LiveMetrics(currentCoolant, currentOil, gearOil, boost)
                    delay(250)
                }
            } catch (e: Exception) {
                val err = e.message ?: "Неизвестная ошибка"
                addLog("КРИТ. ОШИБКА: $err")
                withContext(Dispatchers.Main) { connectionStatus.value = "СБОЙ СВЯЗИ. ПЕРЕПОДКЛЮЧЕНИЕ..." }
                try { socket?.close() } catch (ex: Exception) {}
                socket = null
            }
            
            // Если мы всё ещё должны работать (не нажали Стоп руками), ждем и переподключаемся
            if (isRunning) {
                addLog("Пауза 3 сек перед авто-реконнектом...")
                delay(3000)
            }
        }
    }

    private fun sendRaw(cmd: String, overrideDelay: Long = 0): String {
        val out = output ?: return "ERR_NO_OUT"
        val inp = input ?: return "ERR_NO_IN"
        try {
            out.write((cmd + "\r").toByteArray())
            out.flush()
            if (overrideDelay > 0) Thread.sleep(overrideDelay)
            
            val buffer = ByteArray(256)
            val sb = StringBuilder()
            var noDataCounter = 0
            
            while (true) {
                if (inp.available() > 0) {
                    val len = inp.read(buffer)
                    if (len <= 0) break
                    val chunk = String(buffer, 0, len)
                    sb.append(chunk)
                    if (chunk.contains(">")) break
                } else {
                    noDataCounter++
                    if (noDataCounter > 10) break // Таймаут чтения ~1 сек
                    Thread.sleep(100)
                }
            }
            val res = sb.toString().replace(">", "").replace(" ", "").replace("\r", "").replace("\n", "").trim()
            addLog("CMD: $cmd | RES: $res")
            return res
        } catch (e: Exception) {
            addLog("IO_ERR на команде $cmd")
            return "ERR_IO"
        }
    }

    private fun parseHex(raw: String, prefix: String): Int? {
        val idx = raw.indexOf(prefix)
        if (idx != -1 && raw.length >= idx + prefix.length + 2) {
            return raw.substring(idx + prefix.length, idx + prefix.length + 2).toIntOrNull(16)
        }
        return null
    }

    fun stop() {
        isRunning = false
        try { socket?.close() } catch (e: Exception) {}
        socket = null
    }
}

@Composable
fun FullBmwDashboard(metrics: LiveMetrics, dragData: DragResult, status: String, onStatusClick: () -> Unit, onHistoryClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            modifier = Modifier.fillMaxWidth().clickable { onStatusClick() }
        ) {
            Text(
                text = status,
                color = if (status.contains("СБОЙ") || status.contains("ОШИБКА")) Color.Red else if (status.contains("ОПРОС")) Color.Cyan else Color.Yellow,
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
                
                val dragStatusText = when (dragData.state) {
                    DragState.IDLE -> if (dragData.currentSpeedKmH < 3f) "ГОТОВ К СТАРТУ" else "ОЖИДАНИЕ ОСТАНОВКИ"
                    DragState.MEASURING -> "ИДЕТ ЗАМЕР!"
                }
                val dragStatusBg = when (dragData.state) {
                    DragState.IDLE -> if (dragData.currentSpeedKmH < 3f) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    DragState.MEASURING -> Color(0xFFF44336)
                }
                
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(dragStatusBg.copy(alpha = 0.2f), RoundedCornerShape(8.dp)).padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = dragStatusText, color = dragStatusBg, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 1.sp)
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
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
