package com.example.guitartuner

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
// import androidx.compose.foundation.lazy.grid.GridCells // <-- 移除
// import androidx.compose.foundation.lazy.grid.LazyVerticalGrid // <-- 移除
// import androidx.compose.foundation.lazy.grid.items // <-- 移除
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*
import androidx.compose.foundation.BorderStroke // <-- 新增導入
import androidx.compose.ui.platform.LocalConfiguration // <-- 新增導入
import androidx.compose.ui.platform.LocalContext // <-- 新增導入
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlin.math.log2 // <-- 新增導入
import androidx.compose.foundation.border // <-- 導入 border

// --- 資料類別 ---

data class Tuning(val name: String, val notes: Map<String, Float>)
data class TunerResult(
    val note: String,
    val targetFreq: Float,
    val centsOff: Float,
    val freq: Float
)

// --- 核心：音訊處理 ---
object AudioProcessor {
    private const val SAMPLE_RATE = 44100
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT
//    private const val BUFFER_SIZE_FACTOR = 4
//    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
    private const val BUFFER_SIZE = 8192
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val _pitchFlow = MutableStateFlow(-1f)
    val pitchFlow: StateFlow<Float> = _pitchFlow

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (recordingJob?.isActive == true) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            BUFFER_SIZE
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioProcessor", "AudioRecord 無法初始化")
            return
        }

        audioRecord?.startRecording()

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = FloatArray(BUFFER_SIZE)
            while (isActive) {
                val readSize = audioRecord?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: 0
                if (readSize > 0) {
                    // --- 修改 1：在分析前套用低通濾波器 ---
                    // 這會就地(in-place)修改 buffer，移除高頻噪音
                    applyLowPassFilter(buffer)
                    val frequency = autoCorrelate(buffer)
                    _pitchFlow.value = frequency
                }
            }
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        _pitchFlow.value = -1f
    }
    // --- 新增：低通濾波器函式 ---
    /**
     * 套用一個簡單的一階低通濾波器 (IIR)
     * 這會就地(in-place)修改傳入的 buffer 陣列
     * @param buffer 要過濾的音訊緩衝區
     * @param alpha 平滑係數 (0.0 到 1.0)。越小，濾波越強 (截止頻率越低)。
     */
    private fun applyLowPassFilter(buffer: FloatArray, alpha: Float = 0.2f) {
        if (buffer.isEmpty()) return

        // 從第二個樣本開始，每個樣本都混入(1-alpha)的前一個樣本
        for (i in 1 until buffer.size) {
            buffer[i] = buffer[i-1] + alpha * (buffer[i] - buffer[i-1])
        }
    }

    // --- 音高偵測演算法 (從 JS 移植) ---
    private fun autoCorrelate(buffer: FloatArray): Float {
        val size = buffer.size
        var rms = 0f
        for (i in 0 until size) {
            val v = buffer[i]
            rms += v * v
        }
        rms = sqrt(rms / size)

        // --- (靜音門檻，保持不變) ---
        if (rms < 0.03) return -1f // 靜音

        val c = FloatArray(size)
        for (i in 0 until size) {
            for (j in 0 until size - i) {
                c[i] += buffer[j] * buffer[j + i]
            }
        }

        var d = 0
        // --- 修正點在這裡 ---
        // 增加了 (d + 1 < size) 的邊界檢查，防止 ArrayOutOfBoundsException
        while (d + 1 < size && c[d] > c[d + 1]) {
            d++
        }
        // --- 修正結束 ---

        var maxVal = -1f
        var maxPos = -1
        for (i in d until size) {
            if (c[i] > maxVal) {
                maxVal = c[i]
                maxPos = i
            }
        }

        if (maxPos == -1) return -1f

        var T0 = maxPos.toFloat()
        val x1 = if (T0 - 1 < 0) 0f else c[(T0 - 1).toInt()]
        val x2 = c[T0.toInt()]
        val x3 = if (T0 + 1 >= size) 0f else c[(T0 + 1).toInt()]

        val a = (x1 + x3 - 2 * x2) / 2f
        val b = (x3 - x1) / 2f
        if (a != 0f) {
            T0 -= b / (2 * a)
        }

        if (T0 == 0f) return -1f
        return SAMPLE_RATE / T0
    }
}

// --- 主 Activity ---
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GuitarTunerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TunerApp()
                }
            }
        }
    }
}

// --- UI 佈景主題 ---
// --- 移除：刪除重複的舊佈景主題 ---

// --- Gemini API 服務 ---
//object GeminiService {
//    // API 金鑰。在真實應用中，不應硬編碼
//    private const val API_KEY = "" // 由 Canvas 環境自動提供
//    private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-09-2025:generateContent?key=$API_KEY"
//
////    data class GeminiResponse(val text: String, val sources: List<Pair<String, String>>)
////    data class GeminiError(val message: String)
//
//    @Throws(Exception::class)
//    suspend fun findSongsForTuning(tuningName: String): GeminiResponse = withContext(Dispatchers.IO) {
//        val systemPrompt = "You are a helpful music expert. The user wants to find songs for a specific guitar tuning. Respond concisely in Traditional Chinese. Format the list as bullet points: * Song Title - Artist"
//        val userQuery = "請推薦 5 到 10 首使用 \"$tuningName\" 吉他調音的知名歌曲。"
//
//        val payload = JSONObject().apply {
//            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", userQuery)))))
//            put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
//            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
//        }
//
//        val url = URL(API_URL)
//        (url.openConnection() as HttpURLConnection).run {
//            requestMethod = "POST"
//            setRequestProperty("Content-Type", "application/json")
//            doOutput = true
//
//            OutputStreamWriter(outputStream).use { it.write(payload.toString()) }
//
//            if (responseCode != HttpURLConnection.HTTP_OK) {
//                val errorMsg = errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "Unknown error"
//                throw Exception("API Error $responseCode: $errorMsg")
//            }
//
//            val responseBody = inputStream.bufferedReader().use(BufferedReader::readText)
//            val jsonResponse = JSONObject(responseBody)
//
//            val candidate = jsonResponse.getJSONArray("candidates").getJSONObject(0)
//            val text = candidate.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
//
//            val sources = mutableListOf<Pair<String, String>>()
//            val groundingMetadata = candidate.optJSONObject("groundingMetadata")
//            if (groundingMetadata != null) {
//                val attributions = groundingMetadata.optJSONArray("groundingAttributions")
//                if (attributions != null) {
//                    for (i in 0 until attributions.length()) {
//                        val web = attributions.getJSONObject(i).optJSONObject("web")
//                        if (web != null) {
//                            sources.add(Pair(web.getString("uri"), web.getString("title")))
//                        }
//                    }
//                }
//            }
//            GeminiResponse(text, sources)
//        }
//    }
//}

// --- Jetpack Compose UI ---

@Composable
fun GuitarTunerTheme(content: @Composable () -> Unit) {
    // --- 修改：高對比 E-Ink 亮色佈景主題 ---
    val eInkColorScheme = lightColorScheme(
        background = Color.White,    // 背景 (白)
        surface = Color.White,    // 按鈕等元素的背景 (白)
        primary = Color.Black,    // 強調色 (黑) - 用於「開始」按鈕背景
        onPrimary = Color.White,  // 在強調色上的文字 (白)
        onBackground = Color.Black, // 在背景上的文字 (黑)
        onSurface = Color.Black,  // 在 surface 上的文字 (黑)
        error = Color.White,      // 錯誤狀態 (白) - 用於「停止」按鈕背景
        onError = Color.Black     // 在錯誤狀態上的文字 (黑)
    )
    MaterialTheme(
        colorScheme = eInkColorScheme, // <-- 使用新的 E-Ink 佈景主題
        typography = Typography(),
        content = content
    )
}

// --- 修正：恢復 @Composable fun TunerApp() ---
@Composable
fun TunerApp() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    val pitch by AudioProcessor.pitchFlow.collectAsState()

    // 調音設定
    val tunings = remember {
        listOf(
            Tuning("Standard (E A D G B E)", mapOf("E2" to 82.41f, "A2" to 110.00f, "D3" to 146.83f, "G3" to 196.00f, "B3" to 246.94f, "E4" to 329.63f)),
            Tuning("Eb Standard", mapOf("Eb2" to 77.78f, "Ab2" to 103.83f, "Db3" to 138.59f, "Gb3" to 185.00f, "Bb3" to 233.08f, "Eb4" to 311.13f)),
            Tuning("Drop D", mapOf("D2" to 73.42f, "A2" to 110.00f, "D3" to 146.83f, "G3" to 196.00f, "B3" to 246.94f, "E4" to 329.63f))
        )
    }
    var selectedTuning by remember { mutableStateOf(tunings[0]) }
    var isAutoMode by remember { mutableStateOf(true) }
    var manualTargetNote by remember { mutableStateOf<String?>(null) }

    // UI 狀態
    var tunerResult by remember { mutableStateOf<TunerResult?>(null) }
    var noSoundFrames by remember { mutableStateOf(0) }
    val NO_SOUND_THRESHOLD = 15

    // Gemini
//    var showGeminiDialog by remember { mutableStateOf(false) }
//    var geminiResult by remember { mutableStateOf<Result<GeminiService.GeminiResponse>?>(null) }
//    val coroutineScope = rememberCoroutineScope()

    // 權限請求
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted) {
            isRecording = true
            AudioProcessor.startRecording()
        }
    }

    // 檢查初始權限
    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // --- 音高處理邏輯 ---
    LaunchedEffect(pitch, isAutoMode, manualTargetNote, selectedTuning) {
        if (!isRecording) {
            tunerResult = null
            return@LaunchedEffect
        }

        if (pitch == -1f) {
            noSoundFrames++
            if (noSoundFrames > NO_SOUND_THRESHOLD) {
                tunerResult = null // 清除回饋
            }
            return@LaunchedEffect
        }

        noSoundFrames = 0 // 偵測到聲音，重置

        val notes = selectedTuning.notes
        var targetNote: String
        var targetFreq: Float
        var centsOff: Float

        if (isAutoMode) {
            // 自動模式：尋找最接近的音符
            val closest = notes.minByOrNull { abs(pitch - it.value) }!!
            targetNote = closest.key
            targetFreq = closest.value
        } else {
            // 手動模式
            if (manualTargetNote == null) {
                tunerResult = null
                return@LaunchedEffect // 尚未選擇琴弦
            }
            targetNote = manualTargetNote!!
            targetFreq = notes[targetNote]!!
        }

        centsOff = (1200 * log2(pitch / targetFreq)).toFloat() // <-- 修正：轉換為 Float
        tunerResult = TunerResult(targetNote, targetFreq, centsOff, pitch)
    }


    // --- 主 UI 介面 ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("吉他調音器", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("請彈奏一根弦", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)

            Spacer(Modifier.height(24.dp))

            // 調音選擇
            TuningSelector(tunings, selectedTuning, onTuningSelected = { newTuning ->
                selectedTuning = newTuning
                manualTargetNote = null // <-- 新增這一行來修復崩潰
            }) {
                // Gemini 按鈕
//                IconButton(
//                    onClick = {
//                        geminiResult = null // 重置
//                        showGeminiDialog = true
//                        coroutineScope.launch {
//                            geminiResult = try {
//                                Result.success(GeminiService.findSongsForTuning(selectedTuning.name))
//                            } catch (e: Exception) {
//                                Result.failure(e)
//                            }
//                        }
//                    },
//                    enabled = isRecording,
//                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
//                ) {
//                    Text("✨", fontSize = 24.sp)
//                }
            }

            Spacer(Modifier.height(16.dp))

            // Auto/Manual 切換
            AutoManualToggle(isAutoMode, onToggle = {
                isAutoMode = it
                manualTargetNote = null
            })

            // 手動選弦按鈕
            if (!isAutoMode) {
                Spacer(Modifier.height(16.dp))
                StringButtons(selectedTuning.notes.keys.toList(), manualTargetNote) {
                    manualTargetNote = it
                }
            }

            Spacer(Modifier.height(32.dp))

            // 調音器顯示
            TunerDisplay(tunerResult, isAutoMode, manualTargetNote)
        }

        // 開始/停止按鈕
        Button(
            onClick = {
                if (isRecording) {
                    isRecording = false
                    AudioProcessor.stopRecording()
                } else {
                    if (hasPermission) {
                        isRecording = true
                        AudioProcessor.startRecording()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            // --- 修改：使用新的主題顏色 ---
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, // 停止: 白, 開始: 黑
                contentColor = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary // 停止: 黑, 開始: 白
            )
        ) {
            Text(if (isRecording) "停止" else "開始調音", fontSize = 18.sp)
        }
    }

    // Gemini 彈窗
//    if (showGeminiDialog) {
//        GeminiResultDialog(geminiResult) {
//            showGeminiDialog = false
//        }
//    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningSelector(
    tunings: List<Tuning>,
    selected: Tuning,
    onTuningSelected: (Tuning) -> Unit,
    geminiButton: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = selected.name,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                tunings.forEach { tuning ->
                    DropdownMenuItem(
                        text = { Text(tuning.name) },
                        onClick = {
                            onTuningSelected(tuning)
                            expanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        geminiButton()
    }
}

@Composable
fun AutoManualToggle(isAutoMode: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // --- 修改：非啟用狀態使用 Color.Gray 以提高對比度 ---
        Text("手動", color = if (isAutoMode) Color.Gray else MaterialTheme.colorScheme.primary) // <-- primary 為黑色
        Switch(
            checked = isAutoMode,
            onCheckedChange = onToggle,
            modifier = Modifier.padding(horizontal = 16.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.primary
            )
        )
        Text("自動", color = if (isAutoMode) MaterialTheme.colorScheme.primary else Color.Gray) // <-- primary 為黑色
    }
}

// --- 唯一的修改點在這裡 ---
@Composable
fun StringButtons(notes: List<String>, selectedNote: String?, onNoteSelected: (String) -> Unit) {
    // --- 修正：將 LazyVerticalGrid 替換為 Row ---
    // 因為 LazyVerticalGrid (可垂直捲動) 不能巢狀放在 Column(verticalScroll) (也可垂直捲動) 中
    // 且此處只有 6 個按鈕 (一排)，使用 Row 即可達到相同效果並修復崩潰
    Row(
        modifier = Modifier.fillMaxWidth(),
        // 增加 Alignment.CenterHorizontally 確保按鈕水平置中
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 使用 forEach 迭代
        notes.forEach { note ->
            val isSelected = note == selectedNote
            val simpleName = note.dropLast(1)
            Button(
                onClick = { onNoteSelected(note) },
                shape = CircleShape,
                modifier = Modifier.size(50.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), // <-- 修改：使用 primary (黑色) 邊框
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, // 選中: 黑, 未選中: 白
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface // 選中: 白, 未選中: 黑
                )
            ) {
                Text(simpleName, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun TunerDisplay(result: TunerResult?, isAutoMode: Boolean, manualNote: String?) {
    val note = result?.note?.dropLast(1) ?: (if (isAutoMode) "..." else manualNote?.dropLast(1) ?: "...")
    val freq = result?.freq
    val cents = result?.centsOff

    val statusText: String
    val noteText: String

    when {
        cents == null -> {
            statusText = if (isAutoMode) "請彈奏一根弦" else "請選擇或彈奏琴弦"
            noteText = note
        }
        abs(cents) < 2 -> {
            statusText = "$note - 準確 (Perfect)"
            noteText = "✓"
        }
        cents < -2 -> {
            statusText = "太低 (Too Low)"
            noteText = note
        }
        else -> {
            statusText = "太高 (Too High)"
            noteText = note
        }
    }

    // --- onBackground 現在是黑色 ---
    val displayColor = MaterialTheme.colorScheme.onBackground // <-- 恆為黑色

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // 音名 / 勾勾
        Text(
            text = noteText,
            fontSize = 80.sp,
            fontWeight = FontWeight.Bold,
            color = displayColor, // <-- 使用恆定的顯示顏色 (黑)
            modifier = Modifier.height(100.dp)
        )

        Spacer(Modifier.height(16.dp))

        // 指示器
        TunerMeter(cents = cents ?: 0f)

        Spacer(Modifier.height(16.dp))

        // 狀態文字
        Text(text = statusText, fontSize = 20.sp, color = displayColor, modifier = Modifier.height(30.dp)) // <-- 使用恆定的顯示顏色 (黑)

        Spacer(Modifier.height(8.dp))

        // 頻率
        Text(
            text = if (freq != null) "%.2f Hz".format(freq) else " ",
            fontSize = 14.sp,
            color = Color.Gray, // <-- 恆定為灰色 (在白底上可見)
            modifier = Modifier.height(20.dp)
        )
    }
}

@Composable
fun TunerMeter(cents: Float) {
    // 將 -50 到 +50 的音分映射到 0.0 到 1.0 的進度
    val progress = ((cents.coerceIn(-50f, 50f) + 50f) / 100f)
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "MeterProgress")

    val indicatorColor by animateColorAsState(
        targetValue = when {
            abs(cents) < 2 -> MaterialTheme.colorScheme.primary // Black
            else -> Color.Gray // <-- 修改：非準確時為灰色
        },
        label = "IndicatorColor"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface) // <-- 儀表板背景為白色
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(20.dp)) // <-- 加上黑色邊框
    ) {
        // 中間的線
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(Color.Gray) // <-- 實心灰色 (在白底上可見)
                .align(Alignment.Center)
        )
        // 指示器
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(8.dp)
                .align(Alignment.CenterStart)
                .offset(x = (LocalConfiguration.current.screenWidthDp.dp - 32.dp - 8.dp) * animatedProgress) // (容器寬度 - padding - 指示器寬度) * 進度
                .background(indicatorColor, CircleShape) // <-- 準確: 黑, 否則: 灰
        )
    }
}

//@Composable
//fun GeminiResultDialog(
//    result: Result<GeminiService.GeminiResponse>?,
//    onDismiss: () -> Unit
//) {
//    Dialog(onDismissRequest = onDismiss) {
//        Card(
//            modifier = Modifier
//                .fillMaxWidth()
//                .heightIn(max = 500.dp),
//            shape = RoundedCornerShape(16.dp),
//            // --- Card 會自動使用 surface (白) 和 onSurface (黑) ---
//        ) {
//            Column(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .padding(16.dp)
//            ) {
//                Text("✨ 相關歌曲", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) // <-- primary 為黑色
//                Spacer(Modifier.height(16.dp))
//
//                when {
//                    result == null -> {
//                        // Loading
//                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
//                            CircularProgressIndicator()
//                        }
//                    }
//                    result.isSuccess -> {
//                        val data = result.getOrThrow()
//                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
//                            Text(
//                                data.text
//                                    .replace("*", "•")
//                                    .replaceFirst("•", "")
//                                    .trim(),
//                                lineHeight = 24.sp
//                            )
//                            Spacer(Modifier.height(16.dp))
//                            if (data.sources.isNotEmpty()) {
//                                Text("資訊來源:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
//                                data.sources.forEachIndexed { index, (uri, title) ->
//                                    Text(
//                                        text = "[${index + 1}] $title",
//                                        fontSize = 12.sp,
//                                        color = MaterialTheme.colorScheme.primary, // <-- primary 為黑色
//                                        modifier = Modifier.clickable { /* 在瀏覽器中開啟 URI */ }
//                                    )
//                                }
//                            }
//                        }
//                    }
//                    result.isFailure -> {
//                        // Error
//                        Text(
//                            text = "發生錯誤：${result.exceptionOrNull()?.message}",
//                            color = MaterialTheme.colorScheme.onError // <-- onError 為黑色
//                        )
//                    }
//                }
//                Spacer(Modifier.height(16.dp))
//                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
//                    Text("關閉")
//                }
//            }
//        }
//    }
//}