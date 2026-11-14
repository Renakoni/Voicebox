@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.rinstel.genie_tts.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rinstel.genie_tts.AppSettingsRepository.ThemeMode
import dev.rinstel.genie_tts.BuildConfig
import dev.rinstel.genie_tts.inference.GenerationStage
import dev.rinstel.genie_tts.inference.ReferenceLanguage
import dev.rinstel.genie_tts.inference.LanguageNormalizer

@Composable
fun StudioScreen(vm: StudioViewModel, pickReference: () -> Unit, pickRuntimePackage: () -> Unit,
    saveAudio: (StudioOutput) -> Unit, shareAudio: (StudioOutput) -> Unit,
    sendQqAudio: (StudioOutput) -> Unit, pickCheckpoint: (Boolean) -> Unit = {}, recordReference: () -> Unit = {},
    pickAvatar: () -> Unit = {}, takeAvatarPhoto: () -> Unit = {}) {
    val snackbar = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current
    LaunchedEffect(vm.notice) { vm.notice?.let { snackbar.showSnackbar(it); vm.clearNotice() } }
    BackHandler(vm.draft != null || vm.checkpointDraft != null || vm.choosingVoice || vm.tab != 0) {
        if (vm.recordingMs != null) vm.cancelReferenceRecording()
        else if (vm.checkpointDraft != null) vm.closeCheckpointImport()
        else if (vm.draft != null) vm.closeEditor()
        else if (vm.choosingVoice) vm.closeVoicePicker() else vm.tab = 0
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (vm.draft == null && vm.checkpointDraft == null && !vm.choosingVoice) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        val labels = listOf("创作", "音色", "设置")
                        val icons = listOf(Icons.Outlined.Edit, Icons.Outlined.Face, Icons.Outlined.Settings)
                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(selected = vm.tab == index,
                                onClick = { focus.clearFocus(); vm.tab = index },
                                icon = { Icon(icons[index], contentDescription = null, modifier = Modifier.size(22.dp)) }, label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent,
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.testTag("tab_$index"))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            val width = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            if (vm.checkpointDraft != null) CheckpointImportPage(vm, pickCheckpoint, pickReference, recordReference,
                pickAvatar, takeAvatarPhoto, width)
            else if (vm.draft != null) VoiceEditor(vm, pickReference, recordReference, pickAvatar, takeAvatarPhoto, width)
            else if (vm.choosingVoice) VoicePickerPage(vm, width)
            else when (vm.tab) {
                0 -> CreatePage(vm, saveAudio, shareAudio, sendQqAudio, pickRuntimePackage, width)
                1 -> VoicesPage(vm, width)
                else -> SettingsPage(vm, width)
            }
        }
    }
}

@Composable
private fun PageHeader(title: String) {
    TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() }) }, windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

@Composable
private fun CreatePage(vm: StudioViewModel, save: (StudioOutput) -> Unit, share: (StudioOutput) -> Unit,
    sendQq: (StudioOutput) -> Unit, pickRuntime: () -> Unit, modifier: Modifier) {
    val focus = LocalFocusManager.current
    Column(modifier.fillMaxHeight()) {
        PageHeader("声音创作")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 24.dp)) {
            if (vm.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在读取音色…", modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!vm.runtimeReady || vm.voices.isEmpty()) {
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!vm.runtimeReady) {
                        Text("准备语音引擎", style = MaterialTheme.typography.titleMedium)
                        Text("首次使用需导入公共模型资源。", style = MaterialTheme.typography.bodyMedium)
                        StudioButton(onClick = pickRuntime, enabled = vm.connected && !vm.busy) { Text("导入公共资源") }
                    } else {
                        Text("添加你的第一个音色", style = MaterialTheme.typography.titleMedium)
                        StudioButton(onClick = { vm.tab = 1 }) { Text("前往音色") }
                    }
                    vm.importMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val voice = vm.selectedVoice
                Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !vm.busy, onClick = vm::openVoicePicker)
                    .heightIn(min = 56.dp).testTag("selected_voice"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (voice != null) {
                        VoiceAvatar(voice.name, voice.avatarPath, Modifier.size(36.dp))
                        Text(voice.name, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    } else Text("选择音色", modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Default.ArrowDropDown, "更换音色", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                LanguageMenu("生成语言", vm.outputLanguage, !vm.busy, "output_language", onSelect = vm::changeOutputLanguage)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val writingStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 30.sp)
            BasicTextField(value = vm.text, onValueChange = vm::updateText, enabled = !vm.busy,
                textStyle = writingStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), minLines = 4, maxLines = 10,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp)
                    .testTag("synthesis_text").semantics { contentDescription = "语音文本" },
                decorationBox = { inner ->
                    Box {
                        if (vm.text.isEmpty()) Text("输入要生成语音的文字…", style = writingStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }
                })
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${vm.text.length} 字", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                StudioButton(onClick = { vm.updateText(when (vm.outputLanguage) {
                    "ja" -> "こんにちは。今日は日本語で話しています。よろしくお願いします。"
                    "en" -> "The wind is gentle tonight. We walk slowly along the river, and distant lights shine on the water."
                    else -> "今晚风很轻，我们沿着河边慢慢往前走。远处的灯亮了，像星星落在水面上。"
                }) }, enabled = !vm.busy, style = StudioButtonStyle.Quiet) {
                    Text("示例")
                }
                StudioButton(onClick = { focus.clearFocus(); vm.generate() }, enabled = vm.canGenerate,
                    modifier = Modifier.testTag("generate")) {
                    Text("生成语音")
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                }
            }
            if (vm.busy) {
                val progress = generationStatus(vm.state)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (vm.importing) "正在导入资源" else if (vm.cancelling) "正在停止…" else progress.title,
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("generation_status"))
                        if (!vm.importing) StudioButton(onClick = vm::cancel, enabled = !vm.cancelling,
                            style = StudioButtonStyle.Quiet) { Text("停止") }
                    }
                    if (!vm.importing && !vm.cancelling) progress.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("generation_detail"))
                    }
                    if (progress.percent != null && !vm.importing && !vm.cancelling) {
                        LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else if (vm.state.stage == GenerationStage.CANCELLED) {
                Text("已停止，可以修改文字后重新生成。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.generationError?.let { ErrorMessage(it) }
            vm.output?.let { output ->
                HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("生成结果", style = MaterialTheme.typography.titleMedium)
                    Text(output.voiceName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    if (output.text.isNotBlank() && output.text.trim() != vm.text.trim()) {
                        Text("文本已修改", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutputPlayer(output, vm, save, share, sendQq)
            }
        }
    }
}

private fun languageName(value: String) = when (LanguageNormalizer.normalize(value)) {
    "japanese" -> "日语"
    "english" -> "英语"
    else -> "中文"
}

@Composable
private fun LanguageChoice(title: String, value: String, enabled: Boolean, tag: String,
    autoText: String? = null, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LanguageMenu(title, value, enabled, tag, autoText, onSelect)
    }
}

@Composable
private fun LanguageMenu(title: String, value: String, enabled: Boolean, tag: String,
    autoText: String? = null, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (value == "auto") "按原文 · ${languageName(ReferenceLanguage.resolve(value, autoText.orEmpty()))}"
        else languageName(value)
    Box {
        StudioButton(onClick = { expanded = true }, enabled = enabled, style = StudioButtonStyle.Quiet,
            modifier = Modifier.testTag(tag).semantics { contentDescription = "$title：$label" }) {
            Text(label)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (autoText != null) DropdownMenuItem(text = { Text("按参考原文判断") },
                onClick = { onSelect("auto"); expanded = false }, modifier = Modifier.testTag("${tag}_auto"))
            listOf("zh", "ja", "en").forEach { language ->
                DropdownMenuItem(text = { Text(languageName(language)) },
                    onClick = { onSelect(language); expanded = false }, modifier = Modifier.testTag("${tag}_$language"))
            }
        }
    }
}

@Composable
private fun OutputPlayer(output: StudioOutput, vm: StudioViewModel, save: (StudioOutput) -> Unit,
    share: (StudioOutput) -> Unit, sendQq: (StudioOutput) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (output.text.isNotBlank() && output.text.trim() != vm.text.trim())
            Text(output.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutputPlayback(vm.preview.takeIf { it.path == output.path } ?: PreviewState(), output.durationMs, !vm.busy,
            onToggle = { vm.audio.toggle(output.path) }, onSeek = vm.audio::seek)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StudioButton(onClick = { save(output) }, style = StudioButtonStyle.Quiet) { Text("保存音频") }
            StudioButton(onClick = { share(output) }, style = StudioButtonStyle.Quiet) { Text("分享") }
            StudioButton(onClick = { sendQq(output) }, enabled = !vm.busy, style = StudioButtonStyle.Quiet,
                modifier = Modifier.testTag("send_qq_voice")) {
                Text("去 QQ 发送")
            }
        }
    }
}

@Composable
private fun VoicesPage(vm: StudioViewModel, modifier: Modifier) {
    var deleteTarget by remember { mutableStateOf<StudioVoice?>(null) }
    Column(modifier.fillMaxHeight()) {
        PageHeader("我的音色")
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StudioButton(onClick = { vm.beginVoice() }, enabled = vm.models.isNotEmpty() && !vm.busy && !vm.fileBusy,
                style = StudioButtonStyle.Secondary, modifier = Modifier.weight(1f).testTag("new_voice")) {
                Text("克隆音色")
            }
            StudioButton(onClick = vm::beginCheckpointImport, enabled = vm.connected && !vm.busy && !vm.fileBusy,
                style = StudioButtonStyle.Secondary, modifier = Modifier.weight(1f).testTag("import_voice")) {
                Text("导入音色")
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (vm.voices.isEmpty()) item {
                Text(if (vm.loading) "正在读取音色…" else "还没有音色", modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(vm.voices, key = { _, voice -> voice.id }) { index, voice ->
                var more by remember { mutableStateOf(false) }
                val available = vm.modelFor(voice) != null
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !vm.busy && available) { vm.select(voice); vm.tab = 0 }
                            .padding(vertical = 8.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            VoiceAvatar(voice.name, voice.avatarPath)
                            Column(Modifier.weight(1f)) {
                                Text(voice.name, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (!available) Text("模型不可用",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (vm.selectedId == voice.id) Icon(Icons.Default.Check, "已选用",
                                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { vm.beginEditVoice(voice) }, enabled = !vm.busy && !vm.fileBusy,
                            modifier = Modifier.testTag("edit_voice_${voice.id}")) {
                            Icon(Icons.Default.Edit, "编辑${voice.name}", modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { more = true }, enabled = !vm.busy && !vm.fileBusy) {
                                Icon(Icons.Default.MoreVert, "更多操作：${voice.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                if (voice.builtIn) DropdownMenuItem(text = { Text("用作模板") },
                                    onClick = { more = false; vm.beginVoice(voice) })
                                else DropdownMenuItem(text = { Text("删除") },
                                    onClick = { more = false; deleteTarget = voice })
                            }
                        }
                    }
                    if (index < vm.voices.lastIndex) HorizontalDivider(Modifier.padding(start = 68.dp),
                        color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    deleteTarget?.let { voice ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除“${voice.name}”？") },
            text = { Text("会移除保存的参考音频和原文，已生成的音频不受影响。") },
            confirmButton = { TextButton(onClick = { vm.deleteVoice(voice); deleteTarget = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("保留") } })
    }
}

@Composable
private fun VoicePickerPage(vm: StudioViewModel, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeVoicePicker) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回创作") }
            Text("选择音色", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("voice_picker"))
        }
        vm.voices.forEach { voice ->
            val available = vm.modelFor(voice) != null
            VoiceChoiceRow(voice.name, voice.avatarPath, if (available) "" else "模型不可用",
                voice.id == vm.selectedId, available && !vm.busy) { vm.chooseVoice(voice) }
        }
    }
}

@Composable
private fun VoiceChoiceRow(name: String, avatarPath: String, status: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            VoiceAvatar(name, avatarPath)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Default.Check, "已选用", tint = MaterialTheme.colorScheme.primary)
            else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CheckpointImportPage(vm: StudioViewModel, pickCheckpoint: (Boolean) -> Unit, pickReference: () -> Unit,
    recordReference: () -> Unit, pickAvatar: () -> Unit, takeAvatarPhoto: () -> Unit, modifier: Modifier) {
    val draft = vm.checkpointDraft ?: return
    val enabled = !vm.busy && !vm.fileBusy
    val editing = draft.editingCharacterId != null
    val focus = LocalFocusManager.current
    VoiceEditorLayout(if (editing) "编辑音色" else "导入音色", vm::closeCheckpointImport, enabled, modifier,
        actions = {
            StudioButton(onClick = { focus.clearFocus(); vm.importCheckpoints() }, enabled = enabled && vm.connected,
                style = StudioButtonStyle.Quiet,
                modifier = Modifier.heightIn(min = 48.dp).testTag("import_checkpoints")) {
                Text(if (editing) { if (vm.importing) "保存中" else "保存" }
                    else { if (vm.importing) "导入中" else "导入" })
            }
        }) {
        VoiceAvatarEditor(draft.name, draft.avatarPath, enabled, pickAvatar, takeAvatarPhoto)
        OutlinedTextField(draft.name, vm::editCheckpointName, label = { Text("音色名称") }, singleLine = true,
            enabled = enabled, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("checkpoint_name"))
        if (draft.modelVersion == "v2ProPlus") listOf(true, false).forEach { gpt ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (gpt) "GPT 权重 · ckpt" else "SoVITS 权重 · pth", style = MaterialTheme.typography.titleMedium)
                StudioButton(onClick = { pickCheckpoint(gpt) }, enabled = enabled, style = StudioButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(if (gpt) "pick_ckpt" else "pick_pth")) {
                    Text((if (gpt) draft.gptName else draft.sovitsName).ifBlank { if (editing) "未记录原始文件名" else "选择文件" },
                        maxLines = 1, softWrap = false, overflow = TextOverflow.MiddleEllipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                if (editing && (if (gpt) draft.gpt else draft.sovits) != null) {
                    StudioButton(onClick = { vm.clearCheckpointReplacement(gpt) }, enabled = enabled,
                        style = StudioButtonStyle.Quiet) { Text("撤销更换") }
                }
            }
        }
        ReferenceAudioInput(vm, draft.audioPath, draft.audioName, draft.durationMs,
            pickReference, recordReference, "checkpoint_reference") {
            Text("参考音频", style = MaterialTheme.typography.titleMedium)
        }
        LanguageChoice("参考语言", draft.referenceLanguage, enabled, "checkpoint_language",
            autoText = draft.transcript, onSelect = vm::editCheckpointLanguage)
        OutlinedTextField(draft.transcript, vm::editCheckpointTranscript, label = { Text("参考文字") },
            minLines = 3, maxLines = 6,
            enabled = enabled, shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().testTag("checkpoint_reference_text"))
        if (vm.importing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        vm.importMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("checkpoint_progress")) }
        vm.checkpointError?.let { ErrorMessage(it) }
    }
}

@Composable
private fun VoiceEditor(vm: StudioViewModel, pickReference: () -> Unit, recordReference: () -> Unit,
    pickAvatar: () -> Unit, takeAvatarPhoto: () -> Unit, modifier: Modifier) {
    val draft = vm.draft ?: return
    val focus = LocalFocusManager.current
    val editing = vm.voices.any { it.id == draft.id }
    VoiceEditorLayout(if (editing) "编辑音色" else "克隆音色", vm::closeEditor, !vm.fileBusy, modifier,
        actions = {
            StudioButton(onClick = { focus.clearFocus(); vm.saveVoice() }, enabled = !vm.fileBusy && !vm.busy,
                style = StudioButtonStyle.Quiet,
                modifier = Modifier.heightIn(min = 48.dp).testTag("save_voice")) { Text("保存") }
        }) {
        VoiceAvatarEditor(draft.name, draft.avatarPath, !vm.fileBusy && !vm.busy, pickAvatar, takeAvatarPhoto)
        OutlinedTextField(draft.name, { vm.edit(draft.copy(name = it)) }, label = { Text("音色名称") },
            placeholder = { Text("给这个声音起个名字") }, singleLine = true, enabled = !vm.fileBusy,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("voice_name"))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StepTitle("01", "选择基础音色")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                vm.models.forEach { model ->
                    FilterChip(selected = draft.modelDirectory == model.relativeModelDirectory,
                        onClick = { vm.edit(draft.copy(modelDirectory = model.relativeModelDirectory)) },
                        label = { Text(modelName(model)) }, enabled = !vm.fileBusy,
                        leadingIcon = if (draft.modelDirectory == model.relativeModelDirectory) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else null)
                }
            }
        }
        ReferenceAudioInput(vm, draft.audioPath, draft.audioName, draft.durationMs,
            pickReference, recordReference, "pick_reference") {
            StepTitle("02", "参考音频")
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StepTitle("03", "填写对应原文")
            LanguageChoice("参考语言", draft.referenceLanguage, !vm.fileBusy, "editor_language",
                autoText = draft.transcript, onSelect = { vm.edit(draft.copy(referenceLanguage = it)) })
            OutlinedTextField(draft.transcript, { vm.edit(draft.copy(transcript = it)) },
                minLines = 3, maxLines = 6,
                enabled = !vm.fileBusy, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("reference_text"))
        }
        vm.editorError?.let { ErrorMessage(it) }
    }
}

@Composable
private fun VoiceEditorLayout(title: String, onBack: () -> Unit, backEnabled: Boolean, modifier: Modifier,
    actions: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxHeight()) {
        TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = onBack, enabled = backEnabled) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回音色") }
            }, actions = actions, windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

@Composable
private fun SettingsPage(vm: StudioViewModel, modifier: Modifier) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxHeight()) {
        PageHeader("设置")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("GPU 加速", style = MaterialTheme.typography.titleMedium)
                    Text(if (vm.gpuAvailable) "加快语音生成；遇到兼容问题可关闭。" else "当前运行环境使用 CPU 生成。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = vm.gpuEnabled && vm.gpuAvailable, onCheckedChange = vm::setGpu, enabled = vm.gpuAvailable && !vm.busy)
            }
            Text("界面主题", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEach { (value, label) ->
                    FilterChip(selected = vm.theme == value, onClick = { vm.changeTheme(value) }, label = { Text(label) },
                        modifier = Modifier.testTag("theme_${value.name}"))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("本机应用接口", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (advanced) "收起" else "展开", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (advanced) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("允许其他本机应用调用", modifier = Modifier.weight(1f))
                    Switch(checked = vm.apiEnabled, onCheckedChange = vm::setApi)
                }
                Text("通过 127.0.0.1 上的 HTTP 接口使用当前音色。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(vm.apiPort, vm::editPort, label = { Text("端口") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = vm.apiPortError != null,
                    supportingText = { vm.apiPortError?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
                StudioButton(onClick = vm::applyPort, style = StudioButtonStyle.Secondary) { Text("应用端口") }
            }
            Text("Voicebox · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun StepTitle(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun ErrorMessage(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium)
    }
}
