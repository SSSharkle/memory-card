package cn.flashcard.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.flashcard.core.*
import java.time.*

@Composable
fun DeckScreen(m: AppModel) {
    val decks by m.decks.collectAsState()
    val cards by m.cards.collectAsState()
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    var editor by remember { mutableStateOf<Note?>(null) }
    var editing by remember { mutableStateOf(false) }
    var deckDialog by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Deck?>(null) }
    var delete by remember { mutableStateOf<Note?>(null) }
    val deckById = remember(decks) { decks.associateBy { it.id } }
    val cardCounts = remember(cards) { cards.groupingBy { it.noteId }.eachCount() }
    val current = decks.firstOrNull { it.id == selected }
    val children = decks.filter { it.parentId == selected }
    fun descendant(id: Long): Boolean {
        var d = deckById[id]
        while (d != null) {
            if (d.id == selected) return true
            d = d.parentId?.let { deckById[it] }
        }
        return selected == null
    }
    val deckIds = remember(decks, selected) { decks.filter { descendant(it.id) }.map { it.id } }
    var loadedLimit by remember(selected, search, tag) { mutableIntStateOf(50) }
    val pageFlow =
        remember(deckIds, search, tag, loadedLimit) {
            m.repo.dao.notesPageFlow(deckIds, search, tag, loadedLimit, 0)
        }
    val countFlow =
        remember(deckIds, search, tag) { m.repo.dao.notesCountFlow(deckIds, search, tag) }
    val visible by pageFlow.collectAsState(initial = emptyList())
    val total by countFlow.collectAsState(initial = 0)
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { selected = current?.parentId }) {
                Text(
                    if (current == null) "全部牌组"
                    else "‹ ${m.repo.path(current.id,decks).joinToString(" / ")}"
                )
            }
            TextButton(onClick = { deckDialog = true }) { Text("新建牌组") }
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("搜索标题、内容、标签") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = tag,
                onValueChange = { tag = it.trim() },
                label = { Text("按标签筛选") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    editor = null
                    editing = true
                },
                enabled = decks.isNotEmpty(),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("新建笔记")
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(children, key = { "deck${it.id}" }) { d ->
                Card(onClick = { selected = d.id }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontWeight = FontWeight.Bold)
                            Text("牌组 / 章节", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { rename = d }) { Text("编辑") }
                    }
                }
            }
            item {
                Text(
                    "共 $total 条笔记 · 已加载 ${visible.size} 条",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(visible, key = { it.id }) { n ->
                Card(
                    onClick = {
                        editor = n
                        editing = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            n.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            m.repo.path(n.deckId, decks).joinToString(" / "),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(n.front.take(120), maxLines = 2)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${cardCounts[n.id] ?: 0} 张卡 · ${n.tags.joinToString(" · ")}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { delete = n }) { Text("删除") }
                        }
                    }
                }
            }
            if (visible.size < total)
                item {
                    OutlinedButton(
                        onClick = { loadedLimit += 50 },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("加载更多（50 条）")
                    }
                }
            if (visible.isEmpty() && children.isEmpty())
                item { Panel("这里还没有笔记", "点击新建笔记，或在设置中导入你的 Markdown。") }
        }
    }
    if (editing) NoteEditor(m, editor, selected ?: decks.first().id) { editing = false }
    if (deckDialog || rename != null) {
        var name by remember(rename) { mutableStateOf(rename?.name ?: "") }
        AlertDialog(
            onDismissRequest = {
                deckDialog = false
                rename = null
            },
            title = { Text(if (rename == null) "新建牌组 / 章节" else "编辑牌组") },
            text = {
                Column {
                    Text("父级：${current?.name?:"根目录"}")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val d = rename
                        m.run {
                            if (d == null) m.repo.createDeck(name, selected)
                            else m.repo.renameDeck(d, name)
                        }
                        deckDialog = false
                        rename = null
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                Row {
                    if (rename != null)
                        TextButton(
                            onClick = {
                                val d = rename!!
                                m.run { m.repo.deleteDeck(d) }
                                rename = null
                            }
                        ) {
                            Text("删除空牌组")
                        }
                    TextButton(
                        onClick = {
                            deckDialog = false
                            rename = null
                        }
                    ) {
                        Text("取消")
                    }
                }
            },
        )
    }
    delete?.let { n ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("删除这条笔记？") },
            text = { Text("将删除“${n.title}”的卡片及复习记录。此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        m.run { m.repo.deleteNote(n.id) }
                        delete = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditor(m: AppModel, note: Note?, initialDeck: Long, dismiss: () -> Unit) {
    val decks by m.decks.collectAsState()
    var deck by remember { mutableLongStateOf(note?.deckId ?: initialDeck) }
    var title by remember { mutableStateOf(note?.title ?: "") }
    var front by remember { mutableStateOf(note?.front ?: "") }
    var back by remember { mutableStateOf(note?.back ?: "") }
    var tags by remember { mutableStateOf(note?.tags?.joinToString("，") ?: "") }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = dismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (note == null) "新建笔记" else "编辑笔记") },
                        navigationIcon = { TextButton(onClick = dismiss) { Text("取消") } },
                        actions = {
                            TextButton(
                                enabled = !saving,
                                onClick = {
                                    saving = true
                                    m.run {
                                        try {
                                            m.repo.saveNote(
                                                note,
                                                deck,
                                                title,
                                                front,
                                                back,
                                                tags
                                                    .split('，', ',')
                                                    .map { it.trim() }
                                                    .filter { it.isNotBlank() },
                                                System.currentTimeMillis(),
                                            )
                                            dismiss()
                                        } catch (e: Exception) {
                                            error = e.message ?: "保存失败"
                                        } finally {
                                            saving = false
                                        }
                                    }
                                },
                            ) {
                                Text("保存")
                            }
                        },
                    )
                }
            ) { padding ->
                Column(
                    Modifier.padding(padding).fillMaxSize().padding(20.dp).then(Modifier),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(m.repo.path(deck, decks).joinToString(" / "))
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            decks.forEach { d ->
                                DropdownMenuItem(
                                    text = { Text(m.repo.path(d.id, decks).joinToString(" / ")) },
                                    onClick = {
                                        deck = d.id
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("标题") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = front,
                        onValueChange = { front = it },
                        label = { Text("正面 · 支持 Markdown / 挖空") },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        minLines = 3,
                    )
                    Text(
                        "挖空示例：进程有 {{c1::独立的地址空间}}；相同编号合成一张卡。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    OutlinedTextField(
                        value = back,
                        onValueChange = { back = it },
                        label = { Text("背面 / 补充说明") },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        minLines = 3,
                    )
                    OutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = { Text("标签，用逗号分隔") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun StatsScreen(m: AppModel) {
    val cards by m.cards.collectAsState()
    val reviews by m.reviews.collectAsState()
    val notes by m.notes.collectAsState()
    val decks by m.decks.collectAsState()
    val settings by m.settings.collectAsState()
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val bounds = m.repo.dayBounds(now)
    val current = reviews.filter { it.timestamp in bounds.first until bounds.second }
    Page {
        Panel("今日学习") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("评分次数", current.size.toString())
                Metric(
                    "新学卡片",
                    current.filter { it.wasNew }.map { it.cardId }.distinct().size.toString(),
                )
                Metric("复习评分", current.count { !it.wasNew }.toString())
            }
        }
        for (days in listOf(7, 30)) {
            val first = today.minusDays((days - 1).toLong())
            val logs =
                reviews.filter {
                    Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() in first..today
                }
            val active =
                logs
                    .map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
                    .distinct()
                    .size
            Panel("近 $days 天", "${logs.size} 次评分 · $active 天学习") {
                val byDate =
                    logs
                        .groupingBy {
                            Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate()
                        }
                        .eachCount()
                val recent = (0L until days.toLong()).map { today.minusDays(it) }.reversed()
                recent.forEach { d ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.toString())
                        Text("${byDate[d]?:0} 次")
                    }
                }
            }
        }
        val learned = cards.filter { it.schedule.lastReview != null }
        Panel("预计回忆概率", "FSRS 模型估计，不能代表考试正确率") {
            Text(
                if (learned.isEmpty()) "学习后会显示估计值"
                else
                    "${(learned.map{Fsrs(settings.retention).retrievability(it.schedule,now)}.average()*100).toInt()}%",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Text("章节掌握情况", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        val noteDecks = notes.associate { it.id to it.deckId }
        val grouped = cards.groupBy { noteDecks[it.noteId] }
        decks
            .filter { grouped[it.id]?.isNotEmpty() == true }
            .forEach { d ->
                val group = grouped.getValue(d.id)
                val unseen = group.count { it.schedule.lastReview == null }
                val learning =
                    group.count {
                        it.schedule.lastReview != null && it.schedule.state != CardState.REVIEW
                    }
                val review = group.size - unseen - learning
                Panel(m.repo.path(d.id, decks).joinToString(" / ")) {
                    Text(
                        "未学 $unseen (${unseen*100/group.size}%) · 学习 $learning (${learning*100/group.size}%) · 复习 $review (${review*100/group.size}%)"
                    )
                    LinearProgressIndicator(
                        progress = { review.toFloat() / group.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
    }
}
