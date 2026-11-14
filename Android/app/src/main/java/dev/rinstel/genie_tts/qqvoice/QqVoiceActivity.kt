package dev.rinstel.genie_tts.qqvoice

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rinstel.genie_tts.AppSettingsRepository
import dev.rinstel.genie_tts.studio.StudioTheme
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class QqVoiceModel(app: Application) : AndroidViewModel(app) {
    private val session = QqDraftSession(app)
    private var connection: LocalAdb? = null
    private var source: File? = null
    private var durationMs by mutableIntStateOf(0)
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var connected by mutableStateOf(false); private set
    var connectedPort by mutableIntStateOf(0); private set
    var needsPairing by mutableStateOf(false); private set
    private var initialized = false
    val hasAudio get() = durationMs > 0
    val canAutomate get() = durationMs in 1..59499

    private fun adb(): LocalAdb = connection ?: LocalAdb(getApplication()).also { connection = it }
    fun action(work: () -> String, reportInQq: Boolean = false) {
        if (busy) return
        busy = true
        message = "正在处理…"
        viewModelScope.launch {
            try {
                message = withContext(Dispatchers.IO) { work() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "操作失败，请检查无线调试连接。" }
            finally {
                connected = connection?.isConnected == true
                busy = false
                if (reportInQq) Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
            }
        }
    }
    fun initialize(path: String?) {
        if (initialized) return
        initialized = true
        source = path?.let(::File)
        action({
            session.finish()
            source?.let {
                session.prepare(it)
                durationMs = requireNotNull(session.status()).getInt("durationMs")
            }
            ""
        })
    }
    fun pair(code: String) = action({
        adb().pairLocal(code)
        needsPairing = false
        connectedPort = adb().connectLocal()
        ""
    })
    fun connect() = action({
        try {
            connectedPort = adb().connectLocal()
            needsPairing = false
            ""
        } catch (e: Exception) {
            if (e !is AdbAuthenticationFailedException && e !is AdbPairingRequiredException) throw e
            needsPairing = true
            "请完成系统配对。"
        }
    })
    fun automate(openQq: () -> Unit) {
        if (busy || !canAutomate || !connected) return
        openQq()
        action({
            try {
                val app = getApplication<Application>()
                check(app.packageManager.getPackageInfo("com.tencent.mobileqq", 0).longVersionCode == 16580L) {
                    "自动发送目前适配 QQ 9.3.75。"
                }
                if (session.status() == null) session.prepare(requireNotNull(source))
                val status: (String) -> Unit = { text ->
                    viewModelScope.launch {
                        message = text
                        Toast.makeText(app, text, Toast.LENGTH_LONG).show()
                    }
                }
                val automation = QqDraftAutomation(adb())
                val recipient = automation.chooseRecipient(session, status)
                automation.prepareAndSend(session, durationMs, recipient, status)
                "语音已提交到 $recipient，请在 QQ 查看发送状态。"
            } finally {
                session.finish()
            }
        }, reportInQq = true)
    }
    override fun onCleared() {
        if (!busy) session.finish()
        connection?.let { runCatching { it.disconnect() } }
        super.onCleared()
    }
}

class QqVoiceActivity : ComponentActivity() {
    private val model by viewModels<QqVoiceModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.initialize(intent.getStringExtra("wav_path"))
        setContent {
            StudioTheme(AppSettingsRepository(this).themeMode) {
                QqVoicePage(model, ::openSettings, ::openQq)
            }
        }
    }
    private fun openSettings() {
        runCatching { startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }
            .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
    }
    private fun openQq() {
        val intent = packageManager.getLaunchIntentForPackage("com.tencent.mobileqq")
        if (intent != null) startActivity(intent)
        else model.action({ error("请先安装官方 QQ。") })
    }
}

@Composable
private fun QqVoicePage(vm: QqVoiceModel, settings: () -> Unit, qq: () -> Unit) {
    var code by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .widthIn(max = 640.dp).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("发送 QQ 语音", style = MaterialTheme.typography.headlineMedium)
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.message.isNotBlank()) Text(vm.message, color = MaterialTheme.colorScheme.primary)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("连接本机", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = settings, enabled = !vm.busy) { Text("打开无线调试设置") }
            }
            Button(onClick = vm::connect, enabled = !vm.busy && !vm.connected) {
                Text(if (vm.connected) "已连接 · ${vm.connectedPort}" else "连接")
            }
            if (!vm.connected) {
                if (vm.needsPairing) {
                    Text("保持系统配对码弹窗打开，输入六位配对码。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("六位配对码") },
                        singleLine = true, enabled = !vm.busy, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation())
                    Button(onClick = { val submitted = code; code = ""; vm.pair(submitted) },
                        enabled = !vm.busy && code.length == 6) { Text("配对并连接") }
                }
            }
            HorizontalDivider()
            Text("进入 QQ 消息列表，点选联系人，打开目标会话后会自动录制占位、替换并发送，请勿操作 QQ。")
            Button(onClick = { vm.automate(qq) }, enabled = vm.connected && !vm.busy && vm.canAutomate) { Text("选择联系人并发送") }
            if (vm.hasAudio && !vm.canAutomate) Text("这段语音过长，暂不支持 QQ 自动发送。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
