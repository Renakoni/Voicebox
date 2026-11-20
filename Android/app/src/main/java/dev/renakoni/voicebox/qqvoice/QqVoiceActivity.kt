package dev.renakoni.voicebox.qqvoice

import dev.renakoni.voicebox.R
import dev.renakoni.voicebox.core.i18n.UiText
import dev.renakoni.voicebox.i18n.UiLanguageContext
import dev.renakoni.voicebox.core.i18n.userText
import dev.renakoni.voicebox.core.i18n.errorUi
import androidx.compose.ui.platform.LocalResources

import android.app.Application
import android.content.Context
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
import dev.renakoni.voicebox.AppSettingsRepository
import dev.renakoni.voicebox.studio.StudioTheme
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
    var message by mutableStateOf<UiText?>(null); private set
    var connected by mutableStateOf(false); private set
    var connectedPort by mutableIntStateOf(0); private set
    var needsPairing by mutableStateOf(false); private set
    private var initialized = false
    val hasAudio get() = durationMs > 0
    val canAutomate get() = durationMs in 1..59499

    private fun adb(): LocalAdb = connection ?: LocalAdb(getApplication()).also { connection = it }
    fun action(work: () -> UiText?, reportInQq: Boolean = false) {
        if (busy) return
        busy = true
        message = UiText(R.string.processing)
        viewModelScope.launch {
            try {
                message = withContext(Dispatchers.IO) { work() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                message = e.userText() ?: UiText(R.string.qq_operation_failed)
            }
            finally {
                connected = connection?.isConnected == true
                busy = false
                if (reportInQq) message?.let {
                    Toast.makeText(getApplication(), it.resolve(getApplication<Application>().resources), Toast.LENGTH_LONG).show()
                }
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
            null
        })
    }
    fun pair(code: String) = action({
        adb().pairLocal(code)
        needsPairing = false
        connectedPort = adb().connectLocal()
        null
    })
    fun connect() = action({
        try {
            connectedPort = adb().connectLocal()
            needsPairing = false
            null
        } catch (e: Exception) {
            if (e !is AdbAuthenticationFailedException && e !is AdbPairingRequiredException) throw e
            needsPairing = true
            UiText(R.string.adb_complete_pairing)
        }
    })
    fun automate(openQq: () -> Unit) {
        if (busy || !canAutomate || !connected) return
        openQq()
        action({
            try {
                val app = getApplication<Application>()
                if (session.status() == null) session.prepare(requireNotNull(source))
                val status: (UiText) -> Unit = { text ->
                    viewModelScope.launch {
                        message = text
                        Toast.makeText(app, text.resolve(app.resources), Toast.LENGTH_LONG).show()
                    }
                }
                val automation = QqDraftAutomation(adb())
                val recipient = automation.chooseRecipient(session, status)
                automation.prepareAndSend(session, durationMs, recipient, status)
                UiText(R.string.qq_audio_submitted, recipient)
            } finally {
                session.finish()
            }
        }, reportInQq = true)
    }
    override fun onCleared() {
        if (!busy) session.finish()
        connection?.let { runCatching { it.disconnect() } }
    }
}

class QqVoiceActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiLanguageContext(base))

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
        else model.action({ errorUi(UiText(R.string.qq_install_required)) })
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun QqVoicePage(vm: QqVoiceModel, settings: () -> Unit, qq: () -> Unit) {
    val resources = LocalResources.current
    var code by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .widthIn(max = 640.dp).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(resources.getString(R.string.qq_send_title), style = MaterialTheme.typography.headlineMedium)
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.message?.let { Text(it.resolve(resources), color = MaterialTheme.colorScheme.primary) }

            FlowRow(Modifier.fillMaxWidth(), itemVerticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(resources.getString(R.string.qq_connect_device), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = settings, enabled = !vm.busy) { Text(resources.getString(R.string.qq_debug_settings)) }
            }
            Button(onClick = vm::connect, enabled = !vm.busy && !vm.connected) {
                Text(if (vm.connected) resources.getString(R.string.qq_connected_port, vm.connectedPort) else resources.getString(R.string.connect))
            }
            if (!vm.connected) {
                if (vm.needsPairing) {
                    Text(resources.getString(R.string.qq_pairing_instructions), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text(resources.getString(R.string.qq_pairing_code)) },
                        singleLine = true, enabled = !vm.busy, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation())
                    Button(onClick = { val submitted = code; code = ""; vm.pair(submitted) },
                        enabled = !vm.busy && code.length == 6) { Text(resources.getString(R.string.qq_pair_connect)) }
                }
            }
            HorizontalDivider()
            Text(resources.getString(R.string.qq_automation_instructions))
            Button(onClick = { vm.automate(qq) }, enabled = vm.connected && !vm.busy && vm.canAutomate) { Text(resources.getString(R.string.qq_select_send)) }
            if (vm.hasAudio && !vm.canAutomate) Text(resources.getString(R.string.qq_audio_too_long), style = MaterialTheme.typography.bodySmall)
        }
    }
}
