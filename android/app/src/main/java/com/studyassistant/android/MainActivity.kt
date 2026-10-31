package com.studyassistant.android

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val apiClient = ApiClient(applicationContext)
        setContent {
            IOSMaterialYouTheme { StudyAssistantApp(apiClient) }
        }
    }
}

@Composable
fun IOSMaterialYouTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyAssistantApp(api: ApiClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isAuthenticated by remember { mutableStateOf(!api.apiToken.isNullOrEmpty()) }
    var snapshot by remember { mutableStateOf(api.getCachedSync()) }
    var isLoading by remember { mutableStateOf(false) }
    var currentTab by remember { mutableIntStateOf(0) }

    var quickHwLesson by remember { mutableStateOf<LessonItem?>(null) }
    var quickReminderLesson by remember { mutableStateOf<LessonItem?>(null) }

    fun refreshData() {
        scope.launch {
            isLoading = true
            api.syncAll().onSuccess { snapshot = it }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
            isLoading = false
        }
    }

    LaunchedEffect(isAuthenticated) { if (isAuthenticated) refreshData() }

    if (!isAuthenticated) {
        AuthPairScreen(api = api, onSuccess = { isAuthenticated = true; refreshData() })
        return
    }

    val isAdmin = snapshot?.user?.role == "admin"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(when (currentTab) { 0 -> "Расписание"; 1 -> "Домашка"; 2 -> "Таймеры"; else -> "Админ" }, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                        snapshot?.user?.let { Text("${it.fullName} • ${if (isAdmin) "Админ" else "Студент"}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                actions = {
                    IconButton(onClick = { refreshData() }) { Icon(Icons.Rounded.Refresh, null) }
                    IconButton(onClick = { api.logout(); isAuthenticated = false }) { Icon(Icons.Rounded.Logout, null) }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 8.dp) {
                NavigationBarItem(selected = currentTab == 0, onClick = { currentTab = 0 }, icon = { Icon(Icons.Rounded.CalendarMonth, null) }, label = { Text("Пары") })
                NavigationBarItem(selected = currentTab == 1, onClick = { currentTab = 1 }, icon = { Icon(Icons.Rounded.Assignment, null) }, label = { Text("Домашка") })
                NavigationBarItem(selected = currentTab == 2, onClick = { currentTab = 2 }, icon = { Icon(Icons.Rounded.NotificationsActive, null) }, label = { Text("Таймеры") })
                if (isAdmin) NavigationBarItem(selected = currentTab == 3, onClick = { currentTab = 3 }, icon = { Icon(Icons.Rounded.AdminPanelSettings, null) }, label = { Text("Админ") })
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            val data = snapshot
            if (data == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                when (currentTab) {
                    0 -> ScheduleScreen(data.lessons, data.homework, { quickHwLesson = it }, { quickReminderLesson = it })
                    1 -> HomeworkScreen(data.homework, data.lessons, { d, s, desc -> scope.launch { api.createHomework(d, s, desc).onSuccess { refreshData() } } }, { id -> scope.launch { api.deleteHomework(id).onSuccess { refreshData() } } }, { hw, d, t -> scope.launch { api.createReminder("ДЗ по ${hw.subject}: ${hw.description}", d, t).onSuccess { refreshData() } } })
                    2 -> RemindersScreen(data.reminders, { t, d, time -> scope.launch { api.createReminder(t, d, time).onSuccess { refreshData() } } }, { id -> scope.launch { api.deleteReminder(id).onSuccess { refreshData() } } })
                    3 -> if (isAdmin) AdminScreen(api) { refreshData() }
                }
            }
        }
    }

    quickHwLesson?.let { lesson ->
        QuickHomeworkDialog(lesson, { quickHwLesson = null }) { desc ->
            scope.launch { api.createHomework(lesson.dateStr, lesson.subject, desc).onSuccess { quickHwLesson = null; refreshData() } }
        }
    }

    quickReminderLesson?.let { lesson ->
        QuickLessonReminderDialog(lesson, { quickReminderLesson = null }) { t, d, time ->
            scope.launch { api.createReminder(t, d, time, lesson.id).onSuccess { quickReminderLesson = null; refreshData() } }
        }
    }
}

@Composable
fun AuthPairScreen(api: ApiClient, onSuccess: () -> Unit) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(api.baseUrl) }
    var pairCode by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(84.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.School, null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            Spacer(Modifier.height(24.dp))
            Text("Учебный Ассистент", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Введите 6-значный код из Telegram-бота", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text("Адрес сервера (URL)") }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = pairCode, onValueChange = { pairCode = it }, label = { Text("Код из бота") }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            errorText?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Spacer(Modifier.height(20.dp))
            Button(onClick = {
                api.baseUrl = serverUrl
                loading = true
                errorText = null
                scope.launch {
                    api.pairWithCode(pairCode).onSuccess { loading = false; onSuccess() }.onFailure { loading = false; errorText = it.message }
                }
            }, enabled = !loading && pairCode.length >= 4, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(54.dp)) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Войти", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ScheduleScreen(lessons: List<LessonItem>, homework: List<HomeworkItem>, onAttachHw: (LessonItem) -> Unit, onAttachRem: (LessonItem) -> Unit) {
    val dates = remember(lessons) { lessons.map { it.dateStr }.distinct() }
    var selectedDate by remember(dates) { mutableStateOf(dates.firstOrNull() ?: "") }
    val dayLessons = remember(lessons, selectedDate) { lessons.filter { it.dateStr == selectedDate } }

    Column(modifier = Modifier.fillMaxSize()) {
        if (dates.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Расписание пусто", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(dates) { dateStr ->
                val isSelected = dateStr == selectedDate
                val dayTitle = lessons.firstOrNull { it.dateStr == dateStr }?.dayLabel?.substringBefore(",")?.uppercase() ?: ""
                Surface(shape = RoundedCornerShape(18.dp), color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.clickable { selectedDate = dateStr }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(dayTitle, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text(dateStr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        dayLessons.firstOrNull()?.let {
            Text(it.dayLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            items(dayLessons, key = { it.id }) { lesson ->
                val hwCount = homework.count { it.dateStr == lesson.dateStr && it.subject.equals(lesson.subject, true) }
                IOSLessonCard(lesson, hwCount, { onAttachHw(lesson) }, { onAttachRem(lesson) })
            }
        }
    }
}

@Composable
fun IOSLessonCard(lesson: LessonItem, hwCount: Int, onHw: () -> Unit, onRem: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                    Text(lesson.startTime, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(lesson.endTime, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(modifier = Modifier.padding(horizontal = 12.dp).width(4.dp).height(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(lesson.lessonType, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        if (lesson.room.isNotEmpty()) Text(lesson.room, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        if (hwCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                                Text("ДЗ: $hwCount", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(lesson.subject, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (lesson.teacher.isNotEmpty()) Text(lesson.teacher, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onHw, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Icon(Icons.Rounded.EditNote, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("+ ДЗ", fontSize = 13.sp)
                }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onRem, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Icon(Icons.Rounded.AlarmAdd, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("В ТГ", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun HomeworkScreen(homework: List<HomeworkItem>, lessons: List<LessonItem>, onAdd: (String, String, String) -> Unit, onDelete: (Int) -> Unit, onRem: (HomeworkItem, String, String) -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    var remHw by remember { mutableStateOf<HomeworkItem?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
        if (homework.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Нет домашних заданий", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(homework, key = { it.id }) { hw ->
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(hw.dateStr, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                                }
                                Row {
                                    IconButton(onClick = { remHw = hw }) { Icon(Icons.Rounded.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary) }
                                    IconButton(onClick = { onDelete(hw.id) }) { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }
                                }
                            }
                            Text(hw.subject, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(hw.description, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { showAdd = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Добавить ДЗ") }, shape = RoundedCornerShape(20.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
    if (showAdd) {
        var d by remember { mutableStateOf(lessons.firstOrNull()?.dateStr ?: "26.10.2026") }
        var s by remember { mutableStateOf(lessons.firstOrNull()?.subject ?: "") }
        var desc by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Новое ДЗ") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = d, onValueChange = { d = it }, label = { Text("Дата (ДД.ММ.ГГГГ)") })
                OutlinedTextField(value = s, onValueChange = { s = it }, label = { Text("Предмет") })
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Описание") }, minLines = 3)
            }
        }, confirmButton = { Button(onClick = { if (d.isNotBlank() && s.isNotBlank() && desc.isNotBlank()) { onAdd(d, s, desc); showAdd = false } }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Отмена") } })
    }
    remHw?.let { hw ->
        var rDate by remember { mutableStateOf(hw.dateStr) }
        var rTime by remember { mutableStateOf("18:00") }
        AlertDialog(onDismissRequest = { remHw = null }, title = { Text("Напомнить о ДЗ в ТГ") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(hw.subject, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = rDate, onValueChange = { rDate = it }, label = { Text("Дата (ДД.ММ.ГГГГ)") })
                OutlinedTextField(value = rTime, onValueChange = { rTime = it }, label = { Text("Время (ЧЧ:ММ)") })
            }
        }, confirmButton = { Button(onClick = { onRem(hw, rDate, rTime); remHw = null }) { Text("Создать") } }, dismissButton = { TextButton(onClick = { remHw = null }) { Text("Отмена") } })
    }
}

@Composable
fun RemindersScreen(reminders: List<ReminderItem>, onAdd: (String, String, String) -> Unit, onDelete: (Int) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        if (reminders.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Нет активных таймеров", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(reminders, key = { it.id }) { rem ->
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${rem.remindDate} в ${rem.remindTime} ${if (rem.isSent) "• Отправлено" else "• Ожидает"}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (rem.isSent) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(4.dp))
                                Text(rem.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                            IconButton(onClick = { onDelete(rem.id) }) { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { showDialog = true }, icon = { Icon(Icons.Rounded.AlarmAdd, null) }, text = { Text("Новый таймер") }, shape = RoundedCornerShape(20.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
    if (showDialog) {
        var title by remember { mutableStateOf("") }
        var dateStr by remember { mutableStateOf("27.10.2026") }
        var timeStr by remember { mutableStateOf("07:30") }
        AlertDialog(onDismissRequest = { showDialog = false }, title = { Text("Таймер в Telegram") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Текст напоминания") })
                OutlinedTextField(value = dateStr, onValueChange = { dateStr = it }, label = { Text("Дата (ДД.ММ.ГГГГ)") })
                OutlinedTextField(value = timeStr, onValueChange = { timeStr = it }, label = { Text("Время (ЧЧ:ММ)") })
            }
        }, confirmButton = { Button(onClick = { if (title.isNotBlank()) { onAdd(title, dateStr, timeStr); showDialog = false } }) { Text("Создать") } }, dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Отмена") } })
    }
}

@Composable
fun AdminScreen(api: ApiClient, onUpdated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<AdminUserItem>>(emptyList()) }
    var broadcastText by remember { mutableStateOf("") }
    var showLesson by remember { mutableStateOf(false) }

    fun load() { scope.launch { api.getAdminUsers().onSuccess { users = it } } }
    LaunchedEffect(Unit) { load() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                api.uploadExcelUri(uri).onSuccess { count -> Toast.makeText(context, "Загружено пар: $count", Toast.LENGTH_LONG).show(); onUpdated() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Расписание", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { picker.launch("*/*") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.UploadFile, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Загрузить .xlsx")
                        }
                        FilledTonalButton(onClick = { showLesson = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.EditCalendar, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Править день")
                        }
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Рассылка в Telegram", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = broadcastText, onValueChange = { broadcastText = it }, label = { Text("Текст объявления") }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { if (broadcastText.isNotBlank()) { scope.launch { api.sendBroadcast(broadcastText).onSuccess { Toast.makeText(context, "Отправлено: $it чел.", Toast.LENGTH_SHORT).show(); broadcastText = "" } } } }, shape = RoundedCornerShape(14.dp), modifier = Modifier.align(Alignment.End)) {
                        Icon(Icons.Rounded.Send, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Отправить")
                    }
                }
            }
        }
        item { Text("Пользователи (${users.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        items(users, key = { it.tgId }) { u ->
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(u.fullName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("ID: ${u.tgId} • Статус: ${u.status}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (u.status != "approved") TextButton(onClick = { scope.launch { api.setUserStatus(u.tgId, "approved").onSuccess { load() } } }) { Text("Одобрить") }
                    if (u.status != "blocked" && u.role != "admin") TextButton(onClick = { scope.launch { api.setUserStatus(u.tgId, "blocked").onSuccess { load() } } }) { Text("Блок", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (showLesson) {
        var dateStr by remember { mutableStateOf("26.10.2026") }
        var dayLabel by remember { mutableStateOf("понедельник, 26 октября 2026 г.") }
        var start by remember { mutableStateOf("08:05") }
        var end by remember { mutableStateOf("09:35") }
        var type by remember { mutableStateOf("П") }
        var subj by remember { mutableStateOf("") }
        var teacher by remember { mutableStateOf("") }
        var room by remember { mutableStateOf("") }

        AlertDialog(onDismissRequest = { showLesson = false }, title = { Text("Добавить / Править пару") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = dateStr, onValueChange = { dateStr = it }, label = { Text("Дата (ДД.ММ.ГГГГ)") })
                OutlinedTextField(value = dayLabel, onValueChange = { dayLabel = it }, label = { Text("Подпись дня") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("Начало") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("Конец") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = type, onValueChange = { type = it }, label = { Text("Тип (Л/П/ЛР)") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = room, onValueChange = { room = it }, label = { Text("Аудитория") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = subj, onValueChange = { subj = it }, label = { Text("Предмет") })
                OutlinedTextField(value = teacher, onValueChange = { teacher = it }, label = { Text("Преподаватель") })
            }
        }, confirmButton = { Button(onClick = { scope.launch { api.upsertLesson(dateStr, dayLabel, start, end, type, subj, teacher, room).onSuccess { showLesson = false; onUpdated() } } }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { showLesson = false }) { Text("Отмена") } })
    }
}

@Composable
fun QuickHomeworkDialog(lesson: LessonItem, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var desc by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("ДЗ к паре") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${lesson.subject} (${lesson.dateStr})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Задание") }, minLines = 3)
        }
    }, confirmButton = { Button(onClick = { if (desc.isNotBlank()) onConfirm(desc) }) { Text("Добавить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
fun QuickLessonReminderDialog(lesson: LessonItem, onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf("Пара: ${lesson.subject} (${lesson.room})") }
    var remindDate by remember { mutableStateOf(lesson.dateStr) }
    var remindTime by remember { mutableStateOf(lesson.startTime) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Напомнить о паре в ТГ") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Текст") })
            OutlinedTextField(value = remindDate, onValueChange = { remindDate = it }, label = { Text("Дата (ДД.ММ.ГГГГ)") })
            OutlinedTextField(value = remindTime, onValueChange = { remindTime = it }, label = { Text("Время (ЧЧ:ММ)") })
        }
    }, confirmButton = { Button(onClick = { onConfirm(title, remindDate, remindTime) }) { Text("Создать") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
