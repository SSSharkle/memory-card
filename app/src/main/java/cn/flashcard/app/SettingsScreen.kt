package cn.flashcard.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.flashcard.core.*

@Composable
fun SettingsScreen(m: AppModel) {
    val settings by m.settings.collectAsState()
    val context = LocalContext.current
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var restore by remember { mutableStateOf<Backup?>(null) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var busy by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val importFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                m.run {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var n = input.read(chunk)
                        while (n != -1) {
                            require(out.size() + n <= 8 * 1024 * 1024) { "Markdown 文件超过 8 MB" }
                            out.write(chunk, 0, n)
                            n = input.read(chunk)
                        }
                        val result =
                            MarkdownImport.parse(
                                out.toString("UTF-8"),
                                m.repo.dao.signatures().toSet(),
                            )
                        preview = result
                        selected = result.candidates.indices.toSet()
                    }
                }
        }
    val exportFile =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            if (uri != null)
                m.run {
                    context.contentResolver.openOutputStream(uri)!!.use {
                        BackupCodec.write(m.repo.snapshot(), it)
                    }
                    m.message.value = "完整备份已导出"
                }
        }
    val restoreFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                m.run {
                    context.contentResolver.openInputStream(uri)!!.use {
                        restore = BackupCodec.read(it)
                    }
                }
        }
    val safetyFile =
        context.filesDir
            .listFiles()
            ?.filter { it.name.startsWith("safety-") && it.extension == "zip" }
            ?.maxByOrNull { it.lastModified() }
    val exportSafety =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            if (uri != null && safetyFile != null)
                m.run {
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        safetyFile.inputStream().use { it.copyTo(out) }
                    }
                    m.message.value = "恢复前安全备份已导出"
                }
        }
    Page {
        Panel("学习安排") {
            var daily by
                remember(settings.dailyNew) { mutableStateOf(settings.dailyNew.toString()) }
            OutlinedTextField(
                value = daily,
                onValueChange = { daily = it.filter(Char::isDigit).take(3) },
                label = { Text("每日新卡上限（0–999）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(
                        onClick = {
                            daily.toIntOrNull()?.let { n ->
                                m.run { m.repo.saveSettings(settings.copy(dailyNew = n)) }
                            }
                        }
                    ) {
                        Text("保存")
                    }
                },
            )
            Text("目标保留率 ${(settings.retention*100).toInt()}%")
            Slider(
                value = settings.retention.toFloat(),
                onValueChange = { v ->
                    m.run {
                        m.repo.saveSettings(settings.copy(retention = (v * 100).toInt() / 100.0))
                    }
                },
                valueRange = 0.7f..0.97f,
                steps = 26,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("输入答案辅助回忆")
                    Text("只在当前卡片临时显示，无自动评分", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = settings.typed,
                    onCheckedChange = { m.run { m.repo.saveSettings(settings.copy(typed = it)) } },
                )
            }
        }
        Panel("外观") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label)
                    ->
                    FilterChip(
                        selected = settings.theme == value,
                        onClick = { m.run { m.repo.saveSettings(settings.copy(theme = value)) } },
                        label = { Text(label) },
                    )
                }
            }
        }
        Panel("导入学习内容", "支持 YAML 元数据 + ## 标题 + ### 正面 / 背面；先预览再导入。") {
            OutlinedButton(
                onClick = { importFile.launch(arrayOf("text/*", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("选择 Markdown 文件")
            }
        }
        Panel("完整备份与恢复", "备份含笔记、牌组、卡片进度、评分历史和设置。恢复前自动保存当前数据。") {
            Button(
                onClick = { exportFile.launch("考研闪卡-${java.time.LocalDate.now()}.zip") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("导出 ZIP 备份")
            }
            OutlinedButton(
                onClick = {
                    restoreFile.launch(arrayOf("application/zip", "application/octet-stream"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("选择备份恢复")
            }
            if (safetyFile != null) {
                OutlinedButton(
                    onClick = { exportSafety.launch("考研闪卡-恢复前安全备份.zip") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("导出最近的恢复前安全备份")
                }
                TextButton(
                    onClick = {
                        m.run { safetyFile.inputStream().use { restore = BackupCodec.read(it) } }
                    }
                ) {
                    Text("回滚到最近恢复前的数据")
                }
            }
        }
        Panel("考研闪卡 1.0", "纯离线 · 无账号 · 无联网权限") {
            Text(
                "调度：FSRS-6 · py-fsrs 6.3.2 (MIT)\n学习 1 / 10 分钟，重学 10 分钟，不启用随机扰动。\n兄弟挖空卡自动埋到次日本地零点。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "第三方：AndroidX / Kotlin / SnakeYAML Engine (Apache-2.0), CommonMark Java (BSD-2-Clause), FSRS (MIT).",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { licenses = true }) { Text("查看完整开源许可") }
        }
    }
    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text("导入预览") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "有效 ${p.candidates.size} 条 · ${p.cardCount} 张卡\n重复跳过 ${p.duplicates} 条 · 错误 ${p.issues.size} 条"
                    )
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(p.issues) {
                            Text(
                                "第 ${it.line} 行：${it.message}",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        itemsIndexed(p.candidates) { index, c ->
                            Row {
                                Checkbox(
                                    checked = index in selected,
                                    onCheckedChange = {
                                        selected = if (it) selected + index else selected - index
                                    },
                                )
                                Column {
                                    Text(c.note.title)
                                    Text(
                                        "第 ${c.line} 行 · ${c.note.cardCount} 张 · ${c.note.subject}/${c.note.deck}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selected.isNotEmpty() && !busy,
                    onClick = {
                        busy = true
                        m.run {
                            try {
                                val count =
                                    m.repo.importNotes(
                                        p.candidates
                                            .filterIndexed { index, _ -> index in selected }
                                            .map { it.note },
                                        System.currentTimeMillis(),
                                    )
                                preview = null
                                m.message.value = "已导入 $count 条笔记"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text("导入所选 ${selected.size} 条")
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { preview = null }) { Text("取消") }
            },
        )
    }
    restore?.let { b ->
        AlertDialog(
            onDismissRequest = { if (!busy) restore = null },
            title = { Text("完整替换当前数据？") },
            text = {
                Text(
                    "备份包含 ${b.notes.size} 条笔记、${b.cards.size} 张卡、${b.reviews.size} 次评分。将替换当前数据和设置，恢复前自动在应用私有目录保存安全备份。"
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        m.replacing.value = true
                        m.run {
                            try {
                                val safety = m.repo.restore(b)
                                restore = null
                                m.message.value = "恢复成功，安全备份：${safety.name}"
                            } finally {
                                busy = false
                                m.replacing.value = false
                            }
                        }
                    },
                ) {
                    Text("备份当前数据并恢复")
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { restore = null }) { Text("取消") }
            },
        )
    }
    if (licenses)
        AlertDialog(
            onDismissRequest = { licenses = false },
            title = { Text("开源许可") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    item {
                        val text = remember {
                            context.assets.open("third-party-notices.txt").bufferedReader().use {
                                it.readText()
                            }
                        }
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text("关闭") } },
        )
}
