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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.*

// ─────────────────────────────────────────
// 資料類別
// ─────────────────────────────────────────

data class Tuning(val name: String, val notes: Map<String, Float>)

data class TunerResult(
    val note: String,
    val targetFreq: Float,
    val centsOff: Float,
    val freq: Float
)

// ─────────────────────────────────────────
// 核心：音訊處理器（改為 class，不再是 object）
//
// 修正 #1：生命週期問題
//   原本 AudioProcessor 是全域 object，用裸 CoroutineScope(Dispatchers.IO) 啟動協程，
//   不受任何生命週期管理。旋轉螢幕時 Activity 重建，舊 Job 仍在執行，可能造成雙重錄音。
//   → 改為 class，由 TunerViewModel 持有並在 viewModelScope 內啟動協程，
//     ViewModel 被清除時 viewModelScope 自動取消所有 Job。
//
// 修正 #3：FFT + HPS 音高偵測
//   原始 autoCorrelate 是 O(n²)，buffer=8192 時每幀約 6700 萬次運算。
//   → 改用 FFT（Cooley–Tukey radix-2）+ HPS（Harmonic Product Spectrum）。
//   FFT 複雜度 O(n log n)，8192 點約 10 萬次，快約 670 倍。
//   HPS 將頻譜做諧波降採樣相乘，能準確找出泛音豐富的吉他基頻，
//   是商業調音器（GuitarTuna、Pano Tuner）的主流做法。
// ─────────────────────────────────────────

class AudioProcessor {
    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT

    // FFT 需要 2 的冪次。8192 點在 44100Hz 下頻率解析度 = 44100/8192 ≈ 5.4 Hz，
    // 足以區分相鄰半音（E2=82Hz，F2=87Hz，差距 5Hz）。
    private val FFT_SIZE = 8192
    private val BUFFER_SIZE = FFT_SIZE

    private var audioRecord: AudioRecord? = null

    private val _pitchFlow = MutableStateFlow(-1f)
    val pitchFlow: StateFlow<Float> = _pitchFlow

    // 中度修正 #4：自適應靜音門檻
    //   原本 rms < 0.03 是魔法數字，各品牌麥克風靈敏度差異可達 10 倍以上。
    //   → 錄音啟動後先採樣 CALIBRATION_FRAMES 幀的環境底噪，
    //     計算其 RMS 均值後乘上 NOISE_MULTIPLIER 作為動態門檻。
    //   → 門檻上下限 clamp 至 [0.005, 0.08]，避免無聲室或極嘈雜環境失效。
    private val CALIBRATION_FRAMES = 10
    private val NOISE_MULTIPLIER = 3.0f
    private val SILENCE_THRESHOLD_MIN = 0.005f
    private val SILENCE_THRESHOLD_MAX = 0.08f

    @Volatile private var silenceThreshold = 0.03f // 校準完成前使用保守預設值

    @SuppressLint("MissingPermission")
    fun startRecording(scope: kotlinx.coroutines.CoroutineScope) {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            BUFFER_SIZE * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioProcessor", "AudioRecord 無法初始化")
            return
        }

        audioRecord?.startRecording()

        // 修正 #1：協程由外部傳入的 scope（即 viewModelScope）啟動
        scope.launch(Dispatchers.IO) {
            val buffer = FloatArray(BUFFER_SIZE)
            var calibrationFrame = 0
            var calibrationRmsSum = 0f

            while (isActive) {
                val readSize = audioRecord?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: 0
                if (readSize > 0) {
                    // 中度修正 #4：前 N 幀用於校準環境底噪，不輸出音高
                    if (calibrationFrame < CALIBRATION_FRAMES) {
                        var frameRms = 0f
                        for (sample in buffer) frameRms += sample * sample
                        calibrationRmsSum += sqrt(frameRms / readSize)
                        calibrationFrame++
                        if (calibrationFrame == CALIBRATION_FRAMES) {
                            val avgNoiseRms = calibrationRmsSum / CALIBRATION_FRAMES
                            silenceThreshold = (avgNoiseRms * NOISE_MULTIPLIER)
                                .coerceIn(SILENCE_THRESHOLD_MIN, SILENCE_THRESHOLD_MAX)
                            Log.d("AudioProcessor", "校準完成，靜音門檻 = $silenceThreshold")
                        }
                        continue // 校準期間不偵測音高
                    }

                    // 中度修正 #1（低通濾波器移除）：
                    //   原本 applyLowPassFilter(buffer, alpha=0.2) 截止頻率約 1.4kHz，
                    //   會截掉 B3(246Hz)/E4(329Hz) 的泛音，影響 HPS 諧波乘積。
                    //   FFT+HPS 只在 70~400Hz 範圍內搜尋，已天然排除高頻噪音，
                    //   不再需要時域濾波。

                    val frequency = detectPitchFftHps(buffer, SAMPLE_RATE)
                    _pitchFlow.value = frequency
                }
            }
        }
    }

    fun stopRecording() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        _pitchFlow.value = -1f
    }

    // ─────────────────────────────────────────
    // 修正 #3：FFT + HPS 音高偵測演算法
    //
    // 流程：
    //   1. RMS 靜音偵測
    //   2. Hann 窗函數：減少頻譜洩漏（spectral leakage），讓峰值更銳利
    //   3. Cooley–Tukey radix-2 FFT：O(n log n)，將時域訊號轉為頻域
    //   4. 計算幅度頻譜（magnitude spectrum）
    //   5. HPS（Harmonic Product Spectrum）：將頻譜乘上自身的 2x、3x、4x 降採樣版本，
    //      基頻在所有諧波對齊處會得到最大乘積，泛音強的吉他訊號特別有效
    //   6. 在吉他頻率範圍（70~400Hz）內找最大 HPS 值，加拋物線插值
    // ─────────────────────────────────────────
    private fun detectPitchFftHps(buffer: FloatArray, sampleRate: Int): Float {
        val n = buffer.size

        // 1. 靜音偵測（使用啟動時校準的動態門檻，見 silenceThreshold）
        var rms = 0f
        for (sample in buffer) rms += sample * sample
        rms = sqrt(rms / n)
        if (rms < silenceThreshold) return -1f

        // 2. 套用 Hann 窗函數
        //    w(i) = 0.5 * (1 - cos(2π*i/(n-1)))
        //    目的：讓 buffer 兩端平滑降到 0，避免截斷造成的頻譜洩漏
        val windowed = FloatArray(n)
        for (i in 0 until n) {
            val hann = 0.5f * (1f - cos(2.0 * PI * i / (n - 1)).toFloat())
            windowed[i] = buffer[i] * hann
        }

        // 3. FFT（in-place Cooley–Tukey radix-2，不需外部依賴）
        //    輸入為實數，使用 real[] + imag[] 分開儲存
        val real = windowed.copyOf()
        val imag = FloatArray(n) // 初始虛部全為 0

        fftInPlace(real, imag, n)

        // 4. 計算幅度頻譜（只取前半，後半為對稱的鏡像）
        //    magnitude[k] = sqrt(real[k]² + imag[k]²)
        val halfN = n / 2
        val magnitude = FloatArray(halfN)
        for (k in 0 until halfN) {
            magnitude[k] = sqrt(real[k] * real[k] + imag[k] * imag[k])
        }

        // 5. HPS：H(k) = magnitude[k] * magnitude[k/2] * magnitude[k/3] * magnitude[k/4]
        //    降採樣時用線性插值取值，避免整數截斷帶來的誤差
        val hpsOrder = 4 // 使用 4 次諧波乘積，吉他基頻辨識效果佳
        val hps = FloatArray(halfN)
        for (k in 0 until halfN) {
            var product = magnitude[k]
            for (h in 2..hpsOrder) {
                val downIdx = k.toFloat() / h
                val lo = downIdx.toInt()
                val hi = lo + 1
                val frac = downIdx - lo
                val interpolated = if (hi < halfN) {
                    magnitude[lo] * (1f - frac) + magnitude[hi] * frac
                } else {
                    magnitude[lo]
                }
                product *= interpolated
            }
            hps[k] = product
        }

        // 6. 在吉他頻率範圍內找 HPS 最大值
        //    頻率 f 對應的 bin k = f * n / sampleRate
        val minBin = (70.0 * n / sampleRate).toInt().coerceAtLeast(1)
        val maxBin = (400.0 * n / sampleRate).toInt().coerceAtMost(halfN - 2)

        var bestBin = minBin
        var bestVal = hps[minBin]
        for (k in minBin + 1..maxBin) {
            if (hps[k] > bestVal) {
                bestVal = hps[k]
                bestBin = k
            }
        }

        // 7. 拋物線插值，提升頻率精度到 sub-bin 等級
        val y1 = hps[bestBin - 1]
        val y2 = hps[bestBin]
        val y3 = hps[bestBin + 1]
        val a = (y1 + y3 - 2f * y2) / 2f
        val b = (y3 - y1) / 2f
        val refinedBin = if (a != 0f) bestBin - b / (2f * a) else bestBin.toFloat()

        return (refinedBin * sampleRate / n).toFloat()
    }

    // ─────────────────────────────────────────
    // Cooley–Tukey radix-2 DIT FFT（In-place）
    //
    // 標準蝴蝶運算實作，不需任何外部依賴。
    // 輸入長度必須是 2 的冪次（本專案固定為 8192）。
    // 時間複雜度：O(n log n)
    // ─────────────────────────────────────────
    private fun fftInPlace(real: FloatArray, imag: FloatArray, n: Int) {
        // 位元反轉排列（bit-reversal permutation）
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }

        // 蝴蝶運算（butterfly operations）
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angleStep = -2.0 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until halfLen) {
                    val angle = angleStep * k
                    val wr = cos(angle).toFloat()
                    val wi = sin(angle).toFloat()
                    val ur = real[i + k]
                    val ui = imag[i + k]
                    val vr = real[i + k + halfLen] * wr - imag[i + k + halfLen] * wi
                    val vi = real[i + k + halfLen] * wi + imag[i + k + halfLen] * wr
                    real[i + k] = ur + vr
                    imag[i + k] = ui + vi
                    real[i + k + halfLen] = ur - vr
                    imag[i + k + halfLen] = ui - vi
                }
            }
            len = len shl 1
        }
    }
}

// ─────────────────────────────────────────
// ViewModel
//
// 修正 #1（續）：
//   TunerViewModel 持有 AudioProcessor 實例，
//   所有錄音協程在 viewModelScope 內執行。
//   Activity 旋轉時 ViewModel 不被重建，錄音狀態得以保留。
//   Activity 真正結束時 ViewModel.onCleared() 自動停止錄音。
//
// 修正 #2：Permission race condition
//   原本 hasPermission 透過 LaunchedEffect(Unit) 非同步設定，
//   若使用者在 LaunchedEffect 執行前就快速點擊按鈕，
//   hasPermission 仍為 false，但可能已有權限，導致邏輯分支錯誤。
//   → hasPermission 改為在 ViewModel 初始化時由 Context 同步讀取，
//     並在 permissionLauncher 回呼後立即更新。
//     按鈕點擊時再做一次 checkSelfPermission 作為最終保障。
// ─────────────────────────────────────────

class TunerViewModel(private val hasPermissionCheck: () -> Boolean) : ViewModel() {

    private val audioProcessor = AudioProcessor()

    val pitchFlow: StateFlow<Float> = audioProcessor.pitchFlow

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    // 修正 #2：ViewModel 初始化時同步讀取權限狀態
    private val _hasPermission = MutableStateFlow(hasPermissionCheck())
    val hasPermission: StateFlow<Boolean> = _hasPermission

    fun onPermissionResult(granted: Boolean) {
        _hasPermission.value = granted
        if (granted && !_isRecording.value) {
            startRecording()
        }
    }

    fun startRecording() {
        if (_isRecording.value) return
        _isRecording.value = true
        audioProcessor.startRecording(viewModelScope) // 修正 #1：傳入 viewModelScope
    }

    fun stopRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false
        audioProcessor.stopRecording()
    }

    // 修正 #1：ViewModel 被清除時自動停止錄音，釋放 AudioRecord 資源
    override fun onCleared() {
        super.onCleared()
        audioProcessor.stopRecording()
    }
}

class TunerViewModelFactory(private val hasPermissionCheck: () -> Boolean) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return TunerViewModel(hasPermissionCheck) as T
    }
}

// ─────────────────────────────────────────
// 主 Activity
// ─────────────────────────────────────────

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

// ─────────────────────────────────────────
// UI 佈景主題
// ─────────────────────────────────────────

@Composable
fun GuitarTunerTheme(content: @Composable () -> Unit) {
    val eInkColorScheme = lightColorScheme(
        background = Color.White,
        surface = Color.White,
        primary = Color.Black,
        onPrimary = Color.White,
        onBackground = Color.Black,
        onSurface = Color.Black,
        error = Color.White,
        onError = Color.Black
    )
    MaterialTheme(
        colorScheme = eInkColorScheme,
        typography = Typography(),
        content = content
    )
}

// ─────────────────────────────────────────
// 主 Composable
// ─────────────────────────────────────────

@Composable
fun TunerApp() {
    val context = LocalContext.current

    // 修正 #1 + #2：透過 ViewModel 管理狀態，旋轉螢幕不重置
    val viewModel: TunerViewModel = viewModel(
        factory = TunerViewModelFactory {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }
    )

    val isRecording by viewModel.isRecording.collectAsState()
    val hasPermission by viewModel.hasPermission.collectAsState()
    val pitch by viewModel.pitchFlow.collectAsState()

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
    var tunerResult by remember { mutableStateOf<TunerResult?>(null) }
    var noSoundFrames by remember { mutableStateOf(0) }
    val NO_SOUND_THRESHOLD = 15

    // 修正 #2：permissionLauncher 回呼直接通知 ViewModel
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onPermissionResult(isGranted)
    }

    // 音高處理邏輯
    LaunchedEffect(pitch, isAutoMode, manualTargetNote, selectedTuning) {
        if (!isRecording) {
            tunerResult = null
            return@LaunchedEffect
        }
        if (pitch == -1f) {
            noSoundFrames++
            if (noSoundFrames > NO_SOUND_THRESHOLD) tunerResult = null
            return@LaunchedEffect
        }
        noSoundFrames = 0

        val notes = selectedTuning.notes
        val targetNote: String
        val targetFreq: Float

        if (isAutoMode) {
            val closest = notes.minByOrNull { abs(pitch - it.value) }!!
            targetNote = closest.key
            targetFreq = closest.value
        } else {
            if (manualTargetNote == null) { tunerResult = null; return@LaunchedEffect }
            targetNote = manualTargetNote!!
            targetFreq = notes[targetNote]!!
        }

        val centsOff = (1200 * log2(pitch / targetFreq)).toFloat()
        tunerResult = TunerResult(targetNote, targetFreq, centsOff, pitch)
    }

    // 主 UI
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

            TuningSelector(tunings, selectedTuning, onTuningSelected = { newTuning ->
                selectedTuning = newTuning
                manualTargetNote = null
            }) {}

            Spacer(Modifier.height(16.dp))

            AutoManualToggle(isAutoMode, onToggle = {
                isAutoMode = it
                manualTargetNote = null
            })

            if (!isAutoMode) {
                Spacer(Modifier.height(16.dp))
                StringButtons(selectedTuning.notes.keys.toList(), manualTargetNote) {
                    manualTargetNote = it
                }
            }

            Spacer(Modifier.height(32.dp))

            TunerDisplay(tunerResult, isAutoMode, manualTargetNote)
        }

        // 修正 #2：按鈕點擊時再做一次 checkSelfPermission 作為最終保障，
        //   防止 hasPermission StateFlow 尚未反映最新狀態的邊緣情況
        Button(
            onClick = {
                if (isRecording) {
                    viewModel.stopRecording()
                } else {
                    val currentPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (currentPermission) {
                        viewModel.startRecording()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentColor = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(if (isRecording) "停止" else "開始調音", fontSize = 18.sp)
        }
    }
}

// ─────────────────────────────────────────
// UI 元件（與原版相同，僅保留）
// ─────────────────────────────────────────

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
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                tunings.forEach { tuning ->
                    DropdownMenuItem(
                        text = { Text(tuning.name) },
                        onClick = { onTuningSelected(tuning); expanded = false }
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
        Text("手動", color = if (isAutoMode) Color.Gray else MaterialTheme.colorScheme.primary)
        Switch(
            checked = isAutoMode,
            onCheckedChange = onToggle,
            modifier = Modifier.padding(horizontal = 16.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.primary
            )
        )
        Text("自動", color = if (isAutoMode) MaterialTheme.colorScheme.primary else Color.Gray)
    }
}

@Composable
fun StringButtons(notes: List<String>, selectedNote: String?, onNoteSelected: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        notes.forEach { note ->
            val isSelected = note == selectedNote
            val simpleName = note.dropLast(1)
            Button(
                onClick = { onNoteSelected(note) },
                shape = CircleShape,
                modifier = Modifier.size(50.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
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
        abs(cents) < 2 -> { statusText = "$note - 準確 (Perfect)"; noteText = "✓" }
        cents < -2 -> { statusText = "太低 (Too Low)"; noteText = note }
        else -> { statusText = "太高 (Too High)"; noteText = note }
    }

    val displayColor = MaterialTheme.colorScheme.onBackground

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = noteText,
            fontSize = 80.sp,
            fontWeight = FontWeight.Bold,
            color = displayColor,
            modifier = Modifier.height(100.dp)
        )
        Spacer(Modifier.height(16.dp))
        TunerMeter(cents = cents ?: 0f)
        Spacer(Modifier.height(16.dp))
        Text(text = statusText, fontSize = 20.sp, color = displayColor, modifier = Modifier.height(30.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (freq != null) "%.2f Hz".format(freq) else " ",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.height(20.dp)
        )
    }
}

@Composable
fun TunerMeter(cents: Float) {
    val progress = ((cents.coerceIn(-50f, 50f) + 50f) / 100f)
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "MeterProgress")

    val indicatorColor by animateColorAsState(
        targetValue = when {
            abs(cents) < 2 -> MaterialTheme.colorScheme.primary
            else -> Color.Gray
        },
        label = "IndicatorColor"
    )

    val indicatorWidth = 8.dp

    // 中度修正 #2：改用 BoxWithConstraints 取得這個 Box 的實際可用寬度。
    //   原本 LocalConfiguration.current.screenWidthDp.dp 是螢幕總寬，
    //   不會反映 padding、摺疊機分欄、或平板多視窗的實際容器寬度，
    //   導致指示器在非標準螢幕上跑出容器邊界。
    //   maxWidth 是 BoxWithConstraints 在 layout 時量得的真實約束寬度。
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(20.dp))
    ) {
        val trackWidth = maxWidth - indicatorWidth // 指示器可移動的有效軌道長度

        // 中線
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(Color.Gray)
                .align(Alignment.Center)
        )
        // 指示器：offset 基於實際 trackWidth，任何螢幕尺寸都準確
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(indicatorWidth)
                .align(Alignment.CenterStart)
                .offset(x = trackWidth * animatedProgress)
                .background(indicatorColor, CircleShape)
        )
    }
}