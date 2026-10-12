package cn.flashcard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.flashcard.core.*
import java.time.*
import kotlinx.coroutines.delay
import org.commonmark.node.*
import org.commonmark.node.Paragraph as MarkdownParagraph
import org.commonmark.node.Text as MarkdownTextNode
import org.commonmark.parser.Parser

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: AppModel = viewModel()
            FlashApp(model)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashApp(m: AppModel) {
    val settings by m.settings.collectAsState()
    val message by m.message.collectAsState()
    val ready by m.ready.collectAsState()
    val replacing by m.replacing.collectAsState()
    val dark =
        when (settings.theme) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    val colors =
        if (dark)
            darkColorScheme(
                primary = Color(0xFFAFC6FF),
                background = Color(0xFF11151E),
                surface = Color(0xFF1B2230),
            )
        else
            lightColorScheme(
                primary = Color(0xFF245DD8),
                background = Color(0xFFF6F8FC),
                surface = Color.White,
                secondary = Color(0xFF1D796E),
            )
    MaterialTheme(colorScheme = colors) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var reviewing by rememberSaveable { mutableStateOf(false) }
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(message) {
            if (message.isNotBlank()) {
                snack.showSnackbar(message)
                m.message.value = ""
            }
        }
        val activity = LocalActivity.current
        val view = LocalView.current
        SideEffect {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                if (android.os.Build.VERSION.SDK_INT < 35) {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = colors.surface.toArgb()
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = colors.surface.toArgb()
                }
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (reviewing) "专注复习" else listOf("今日", "牌组", "学习统计", "设置")[tab])
                    },
                    actions = {
                        if (reviewing) TextButton(onClick = { reviewing = false }) { Text("结束") }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                if (!reviewing)
                    NavigationBar {
                        listOf("今日", "牌组", "统计", "设置").forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = {
                                    Icon(
                                        listOf(
                                            Icons.Outlined.Today,
                                            Icons.Outlined.Style,
                                            Icons.Outlined.BarChart,
                                            Icons.Outlined.Settings,
                                        )[index],
                                        label,
                                    )
                                },
                                label = { Text(label) },
                            )
                        }
                    }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (!ready) CircularProgressIndicator(Modifier.padding(24.dp))
                else if (reviewing) ReviewScreen(m) { reviewing = false }
                else
                    when (tab) {
                        0 -> TodayScreen(m) { reviewing = true }
                        1 -> DeckScreen(m)
                        2 -> StatsScreen(m)
                        3 -> SettingsScreen(m)
                    }
            }
        }
        if (replacing)
            androidx.compose.ui.window.Dialog(
                onDismissRequest = {},
                properties =
                    androidx.compose.ui.window.DialogProperties(
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                    ),
            ) {
                Surface(shape = MaterialTheme.shapes.large) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("正在备份当前数据并恢复…")
                    }
                }
            }
    }
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
fun Panel(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
fun TodayScreen(m: AppModel, start: () -> Unit) {
    val cards by m.cards.collectAsState()
    val reviews by m.reviews.collectAsState()
    val settings by m.settings.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15000)
            now = System.currentTimeMillis()
        }
    }
    val queue = m.repo.queue(cards, reviews, settings, now)
    val bounds = m.repo.dayBounds(now)
    val today = reviews.filter { it.timestamp in bounds.first until bounds.second }
    val newCount = queue.count { it.schedule.lastReview == null }
    val dueCount = queue.size - newCount
    Page {
        Text(
            LocalDate.now().toString() + " · 稳稳前进",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Panel("把知识留在记忆里", "先回忆，再揭晓；按实际记忆情况评分。") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("待复习", dueCount.toString())
                Metric("可学新卡", newCount.toString())
                Metric("今日评分", today.size.toString())
            }
            Button(
                onClick = start,
                enabled = queue.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (queue.isEmpty()) "当前任务已完成" else "开始复习 · ${queue.size} 张")
            }
        }
        Panel("今日节奏", "每日新卡上限 ${settings.dailyNew} · 目标保留率 ${(settings.retention*100).toInt()}%") {
            Text(
                "已学新卡 ${today.filter{it.wasNew}.map{it.cardId}.distinct().size} 张。到期卡优先，短期学习卡到时会重新出现。"
            )
        }
        if (cards.isEmpty()) Panel("从你的知识开始", "前往牌组创建笔记，或在设置中导入 Markdown。没有预装题库，所有学习内容由你掌握。")
        val future =
            cards
                .filter { it.schedule.lastReview != null && it.due > now && it.buriedUntil <= now }
                .minOfOrNull { it.due }
        future?.let {
            Text(
                "下一张到期：${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime().toString().replace('T',' ')}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun Metric(label: String, value: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun ReviewScreen(m: AppModel, finish: () -> Unit) {
    val cards by m.cards.collectAsState()
    val reviews by m.reviews.collectAsState()
    val notes by m.notes.collectAsState()
    val settings by m.settings.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    var activeId by rememberSaveable { mutableStateOf<Long?>(null) }
    val generation by m.repo.generation.collectAsState()
    val sessionGeneration = generation
    LaunchedEffect(generation) { activeId = null }
    val card =
        cards.firstOrNull { it.id == activeId }
            ?: m.repo.queue(cards, reviews, settings, now).firstOrNull()
    val note = notes.firstOrNull { it.id == card?.noteId }
    LaunchedEffect(card?.id) { activeId = card?.id }
    var revealed by remember(card?.id, card?.revision, generation) { mutableStateOf(false) }
    var typed by remember(card?.id, card?.revision, generation) { mutableStateOf("") }
    if (card == null || note == null) {
        Page {
            Panel("这一轮完成了", "短期学习卡到期后可继续。你的评分和进度已保存。") { Button(onClick = finish) { Text("返回今日") } }
        }
        return
    }
    Page {
        Text(
            "${note.title} · ${if(card.schedule.lastReview==null)"新卡" else if(card.schedule.state==CardState.REVIEW)"复习" else "学习中"}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Panel("先试着回忆") {
            MarkdownText(
                if (card.cloze == 0) note.front else Cloze.render(note.front, card.cloze, false)
            )
            if (settings.typed)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("写下你的回忆（不保存）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
        }
        if (!revealed)
            Button(
                onClick = { revealed = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("揭晓答案")
            }
        else {
            Panel("参考答案") {
                if (card.cloze != 0) MarkdownText(Cloze.render(note.front, card.cloze, true))
                if (note.back.isNotBlank()) MarkdownText(note.back)
                if (settings.typed)
                    Text("请自己对照答案评分，输入内容仅临时保留。", style = MaterialTheme.typography.bodySmall)
            }
            Text("按实际记忆情况选择", style = MaterialTheme.typography.titleMedium)
            for (pair in Rating.entries.chunked(2)) Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (rating in pair) {
                    val preview = Fsrs(settings.retention).review(card.schedule, rating, now)
                    OutlinedButton(
                        onClick = {
                            if (!busy) {
                                busy = true
                                m.run {
                                    try {
                                        if (
                                            m.repo.rate(
                                                card.id,
                                                card.revision,
                                                rating,
                                                System.currentTimeMillis(),
                                                sessionGeneration,
                                            )
                                        )
                                            activeId = null
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                    ) {
                        Column {
                            Text(
                                listOf("忘记", "困难", "记得", "轻松")[rating.ordinal],
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                intervalLabel(preview.due - now),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

fun intervalLabel(ms: Long): String =
    when {
        ms < 3600000 -> "${ms/60000} 分钟"
        ms < 86400000 -> "${ms/3600000} 小时"
        else -> "${ms/86400000} 天"
    }

@Composable
fun MarkdownText(source: String) {
    val text =
        remember(source) {
            buildAnnotatedString {
                val root = Parser.builder().build().parse(source)
                fun visit(n: Node) {
                    when (n) {
                        is MarkdownTextNode -> append(n.literal)
                        is Code ->
                            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                                append(n.literal)
                            }
                        is FencedCodeBlock ->
                            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                                append(n.literal)
                                append('\n')
                            }
                        is IndentedCodeBlock -> append(n.literal)
                        is SoftLineBreak,
                        is HardLineBreak -> append('\n')
                        is HtmlInline,
                        is HtmlBlock -> {}
                        is Image -> append("[图片已忽略]")
                        else -> {
                            val style =
                                when (n) {
                                    is StrongEmphasis -> SpanStyle(fontWeight = FontWeight.Bold)
                                    is Emphasis -> SpanStyle(fontStyle = FontStyle.Italic)
                                    is Heading ->
                                        SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                    else -> SpanStyle()
                                }
                            withStyle(style) {
                                if (n is ListItem) append("• ")
                                var c = n.firstChild
                                while (c != null) {
                                    visit(c)
                                    c = c.next
                                }
                                if (n is MarkdownParagraph || n is Heading || n is ListItem)
                                    append('\n')
                            }
                        }
                    }
                }
                visit(root)
            }
        }
    Text(text, style = MaterialTheme.typography.bodyLarge, lineHeight = 28.sp)
}
