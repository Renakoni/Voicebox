package dev.rinstel.genie_tts

import android.content.ClipData
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import dev.rinstel.genie_tts.studio.StudioOutput
import dev.rinstel.genie_tts.studio.StudioScreen
import dev.rinstel.genie_tts.studio.StudioTheme
import dev.rinstel.genie_tts.studio.StudioViewModel
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var studio: StudioViewModel
    private var exportPath: String? = null
    private var avatarCameraPath: String? = null
    private val avatarPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { studio.pickAvatar(it) }
    }
    private val galleryPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let { studio.pickAvatar(it) }
    }
    private val avatarCamera = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = avatarCameraPath?.let(::File)
        avatarCameraPath = null
        if (saved && file != null) studio.pickAvatar(Uri.fromFile(file), file) else file?.delete()
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The permission dialog ends the hold gesture. Recording needs a new press after granting.
        studio.referenceRecordingError(if (granted) null else "需要麦克风权限才能录音，也可以直接选择音频。")
    }
    private val referencePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(studio::pickReference)
    }
    private val runtimePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(studio::importRuntimePackage)
    }
    private val gptPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { studio.pickCheckpoint(it, gpt = true) }
    }
    private val sovitsPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { studio.pickCheckpoint(it, gpt = false) }
    }
    private val outputPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val path = exportPath
        if (uri != null && path != null) studio.export(uri, path)
        exportPath = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        studio = ViewModelProvider(this)[StudioViewModel::class.java]
        studio.warmupEnabled = !(BuildConfig.DEBUG && intent.getBooleanExtra("benchmarkNoWarmup", false))
        exportPath = savedInstanceState?.getString("export_path")
        avatarCameraPath = savedInstanceState?.getString("avatar_camera_path")
        setContent {
            StudioTheme(studio.theme) {
                StudioScreen(studio,
                    pickReference = { referencePicker.launch(arrayOf("audio/*")) },
                    pickAvatar = ::chooseAvatarPhoto,
                    takeAvatarPhoto = ::takeAvatarPhoto,
                    recordReference = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            studio.startReferenceRecording()
                        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    pickCheckpoint = { gpt ->
                        (if (gpt) gptPicker else sovitsPicker).launch(arrayOf("*/*"))
                    },
                    pickRuntimePackage = {
                        runtimePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    },
                    saveAudio = { output ->
                        exportPath = output.path
                        outputPicker.launch("Voicebox-${output.voiceName}-${System.currentTimeMillis()}.wav")
                    },
                    shareAudio = ::share,
                    sendQqAudio = { output ->
                        startActivity(Intent(this, dev.rinstel.genie_tts.qqvoice.QqVoiceActivity::class.java)
                            .putExtra("wav_path", output.path))
                    },
                )
            }
        }
    }

    private fun chooseAvatarPhoto() {
        runCatching {
            if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)) {
                avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } else {
                // Older devices use their gallery app, not the document/file picker fallback.
                galleryPicker.launch(Intent(Intent.ACTION_PICK).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"))
            }
        }.onFailure { studio.referenceRecordingError("无法打开相册，请检查是否安装了相册应用。") }
    }

    private fun takeAvatarPhoto() {
        runCatching {
            val directory = File(cacheDir, "avatar_camera").apply { mkdirs() }
            val file = File.createTempFile("capture-", ".jpg", directory)
            avatarCameraPath = file.absolutePath
            avatarCamera.launch(FileProvider.getUriForFile(this, "$packageName.audio", file))
        }.onFailure {
            avatarCameraPath?.let { File(it).delete() }
            avatarCameraPath = null
            studio.referenceRecordingError("无法打开相机，请检查是否安装了相机应用。")
        }
    }

    private fun share(output: StudioOutput) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.audio", File(output.path))
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Voicebox 音频", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享语音"))
        }.onFailure { studio.notify("无法分享这段音频，请先保存到文件。") }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("export_path", exportPath)
        outState.putString("avatar_camera_path", avatarCameraPath)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        studio.cancelReferenceRecording()
        studio.audio.stop()
        super.onStop()
    }
}
