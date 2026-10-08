package com.example.telegramproxychecker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val bgTop = Color(0xFF07111F)
private val bgBottom = Color(0xFF0B2742)
private val surface = Color(0xFF192B3D)
private val surfaceBorder = Color(0xFF2A4155)
private val blue = Color(0xFF229ED9)
private val green = Color(0xFF4ADE80)
private val red = Color(0xFFFF7272)
private val yellow = Color(0xFFFFC857)
private val mainText = Color(0xFFF1F5F9)
private val mutedText = Color(0xFFA8BACB)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ProxyDashboardScreen(
    viewModel: ProxyViewModel,
    listState: LazyListState,
    selectedTab: ProxyTab,
    onTabSelected: (ProxyTab) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchVisible: Boolean,
    onSearchVisibleChange: (Boolean) -> Unit,
    expandedProxyKey: String?,
    onExpandProxy: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onConnect: (MtProxy) -> Unit
) {
    val proxies = viewModel.proxies
    val isScanning = viewModel.isLoading
    val progress = viewModel.scanProgress
    val isSmallScreen = LocalConfiguration.current.screenWidthDp < 380
    val pendingSingle = viewModel.checkingProxyKeys

    val sorted = remember(proxies, selectedTab, searchQuery) {
        dashboardProxies(proxies, selectedTab, searchQuery)
    }
    val sortedKeys = remember(sorted) { sorted.map { it.cacheKey } }
    val freezeOrder = listState.isScrollInProgress || listState.firstVisibleItemIndex > 1
    var stableKeys by remember(selectedTab, searchQuery) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(selectedTab, searchQuery, sortedKeys, freezeOrder) {
        stableKeys = stableDashboardKeys(stableKeys, sortedKeys, freezeOrder)
    }
    val byKey = remember(proxies) { proxies.associateBy { it.cacheKey } }
    val visible = remember(stableKeys, byKey) { stableKeys.mapNotNull { byKey[it] } }

    var moreOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = bgTop,
        topBar = {
            TopAppBar(
                title = {
                    Text("MTProxy Checker", color = mainText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh(tcpOkOnly = true) },
                        enabled = !isScanning && pendingSingle.isEmpty() && (viewModel.mtprotoSourceEnabled || viewModel.socks5SourceEnabled) && proxies.any { it.tcpOk == true },
                        modifier = Modifier.semantics { contentDescription = "Проверка доступных по TCP прокси" }
                    ) {
                        Text("↻", fontSize = 26.sp, color = if (isScanning) mutedText else blue)
                    }
                    Box {
                        IconButton(
                            onClick = { moreOpen = true },
                            modifier = Modifier.semantics { contentDescription = "Дополнительные действия" }
                        ) { Text("⋮", fontSize = 25.sp, color = mainText) }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Проверка") },
                                enabled = !isScanning && pendingSingle.isEmpty() && (viewModel.mtprotoSourceEnabled || viewModel.socks5SourceEnabled) && proxies.any { it.tcpOk == true },
                                onClick = {
                                    moreOpen = false
                                    viewModel.refresh(tcpOkOnly = true)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Полная проверка") },
                                enabled = !isScanning && pendingSingle.isEmpty() && (viewModel.mtprotoSourceEnabled || viewModel.socks5SourceEnabled),
                                onClick = {
                                    moreOpen = false
                                    viewModel.refresh(force = true)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Скопировать диагностику") },
                                onClick = {
                                    moreOpen = false
                                    onCopyDiagnostics()
                                }
                            )
                        }
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.semantics { contentDescription = "Открыть настройки" }
                    ) {
                        Text("⚙", color = mainText, fontSize = 22.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgTop)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item(key = "dashboard") {
                    DashboardSummary(
                        proxies = proxies,
                        selectedTab = selectedTab,
                        onTabSelected = onTabSelected
                    )
                }

                // The large statistics scroll away; filters and progress stay visible.
                stickyHeader(key = "controls") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgTop)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ScanProgressStrip(
                            running = isScanning,
                            paused = progress.paused,
                            checked = progress.checked,
                            total = progress.total,
                            telegramOk = proxies.count { it.telegramOk == true },
                            onPause = {
                                if (progress.paused) viewModel.resumeScan() else viewModel.pauseScan()
                            },
                            onStop = viewModel::stopScan
                        )
                        FilterBar(
                            tab = selectedTab,
                            onSelect = onTabSelected,
                            total = proxies.size,
                            ok = proxies.count { it.telegramOk == true },
                            favorites = proxies.count { it.isFavorite },
                            isSmallScreen = isSmallScreen,
                            searchVisible = searchVisible,
                            onToggleSearch = { onSearchVisibleChange(!searchVisible) }
                        )
                        if (selectedTab == ProxyTab.TCP_OK) {
                            // The dashboard itself scrolls away; keep the active
                            // TCP-only filter discoverable in the sticky controls.
                            TextButton(
                                onClick = { onTabSelected(ProxyTab.ALL) },
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    "TCP OK · ${proxies.count { it.tcpOk == true }}  × Сбросить",
                                    color = blue,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        if (searchVisible) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                singleLine = true,
                                label = { Text("Поиск по серверу или порту") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                if (viewModel.error != null) {
                    item(key = "scan_error") {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF3B202C))
                        ) {
                            Text(
                                "Ошибка: ${viewModel.error}",
                                color = red,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                if (visible.isEmpty()) {
                    item(key = "empty") {
                        EmptyProxyState(
                            tab = selectedTab,
                            hasAny = proxies.isNotEmpty(),
                            isScanning = isScanning,
                            searching = searchQuery.isNotBlank(),
                            onShowAll = { onTabSelected(ProxyTab.ALL) }
                        )
                    }
                }

                items(visible, key = { "proxy_${it.cacheKey}" }) { proxy ->
                    Box(modifier = Modifier.padding(horizontal = 10.dp)) {
                        CompactProxyCard(
                            proxy = proxy,
                            expanded = expandedProxyKey == proxy.cacheKey,
                            isChecking = proxy.cacheKey in pendingSingle,
                            canRecheck = !isScanning,
                            onToggleDetails = {
                                onExpandProxy(if (expandedProxyKey == proxy.cacheKey) null else proxy.cacheKey)
                            },
                            onConnect = { onConnect(proxy) },
                            onFavorite = { viewModel.toggleFavorite(proxy) },
                            onRecheck = { viewModel.recheckProxy(proxy) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardSummary(
    proxies: List<MtProxy>,
    selectedTab: ProxyTab,
    onTabSelected: (ProxyTab) -> Unit
) {
    val ok = proxies.count { it.telegramOk == true }
    val tcp = proxies.count { it.tcpOk == true }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        StatTile(
            label = "Telegram OK", value = "$ok", color = green,
            selected = selectedTab == ProxyTab.WORKING,
            onClick = { onTabSelected(ProxyTab.WORKING) },
            modifier = Modifier.weight(1f)
        )
        StatTile(
            label = "Всего", value = "${proxies.size}", color = mainText,
            selected = selectedTab == ProxyTab.ALL,
            onClick = { onTabSelected(ProxyTab.ALL) },
            modifier = Modifier.weight(1f)
        )
        StatTile(
            label = "TCP OK", value = "$tcp", color = blue,
            selected = selectedTab == ProxyTab.TCP_OK,
            onClick = { onTabSelected(ProxyTab.TCP_OK) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Показать: $label, $value прокси" },
        shape = RoundedCornerShape(12.dp),
        border = if (selected) BorderStroke(1.dp, color.copy(alpha = 0.8f)) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF213A50) else surface
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 23.sp)
            Text(label, color = if (selected) mainText else mutedText, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ScanProgressStrip(
    running: Boolean,
    paused: Boolean,
    checked: Int,
    total: Int,
    telegramOk: Int,
    onPause: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val label = when {
                running && paused -> "Пауза"
                running -> "Сканирование"
                total > 0 && checked == total -> "Проверка завершена"
                total > 0 -> "Последний проход"
                else -> "Готов к проверке"
            }
            Text(label, color = if (running) blue else mutedText, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                if (total > 0) "$checked/$total" else "$telegramOk рабочих",
                fontSize = 12.sp,
                color = mainText
            )
            if (running) {
                Spacer(Modifier.width(5.dp))
                IconButton(
                    onClick = onPause,
                    modifier = Modifier.size(36.dp).semantics {
                        contentDescription = if (paused) "Продолжить сканирование" else "Пауза сканирования"
                    }
                ) {
                    Text(if (paused) "▶" else "Ⅱ", fontSize = 17.sp, color = mainText)
                }
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(36.dp).semantics { contentDescription = "Остановить сканирование" }
                ) {
                    Text("■", fontSize = 15.sp, color = mutedText)
                }
            }
        }
        if (running) {
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (checked.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = blue,
                    trackColor = surfaceBorder
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = blue,
                    trackColor = surfaceBorder
                )
            }
        }
    }
}

@Composable
private fun FilterBar(
    tab: ProxyTab,
    onSelect: (ProxyTab) -> Unit,
    total: Int,
    ok: Int,
    favorites: Int,
    isSmallScreen: Boolean,
    searchVisible: Boolean,
    onToggleSearch: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DashboardFilter(
            label = if (isSmallScreen) "OK $ok" else "Рабочие $ok",
            selected = tab == ProxyTab.WORKING,
            onClick = { onSelect(ProxyTab.WORKING) }
        )
        DashboardFilter(
            label = "Все $total",
            selected = tab == ProxyTab.ALL,
            onClick = { onSelect(ProxyTab.ALL) }
        )
        DashboardFilter(
            label = if (isSmallScreen) "★ $favorites" else "Избранное $favorites",
            selected = tab == ProxyTab.FAVORITES,
            onClick = { onSelect(ProxyTab.FAVORITES) }
        )
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onToggleSearch,
            modifier = Modifier.size(38.dp).semantics { contentDescription = if (searchVisible) "Скрыть поиск" else "Найти прокси" }
        ) {
            Text(if (searchVisible) "×" else "⌕", fontSize = 25.sp, color = if (searchVisible) blue else mainText)
        }
    }
}

@Composable
private fun DashboardFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp, maxLines = 1) },
        modifier = Modifier.height(34.dp),
        shape = RoundedCornerShape(18.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = surface,
            labelColor = mutedText,
            selectedContainerColor = Color(0xFF245475),
            selectedLabelColor = mainText
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = surfaceBorder,
            selectedBorderColor = Color(0xFF3576A1)
        )
    )
}

@Composable
private fun EmptyProxyState(
    tab: ProxyTab,
    hasAny: Boolean,
    isScanning: Boolean,
    searching: Boolean,
    onShowAll: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(containerColor = surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                when {
                    searching -> "Совпадений не найдено"
                    !hasAny && isScanning -> "Загружаем и проверяем прокси…"
                    !hasAny -> "Список прокси пока пуст"
                    tab == ProxyTab.WORKING -> "Рабочие прокси пока не найдены"
                    tab == ProxyTab.TCP_OK -> "Прокси с TCP OK пока не найдены"
                    tab == ProxyTab.FAVORITES -> "В избранном пока ничего нет"
                    else -> "Прокси не найдены"
                },
                color = mainText,
                fontWeight = FontWeight.Medium
            )
            if (tab != ProxyTab.ALL) {
                TextButton(onClick = onShowAll) { Text("Показать все прокси") }
            }
        }
    }
}

@Composable
private fun CompactProxyCard(
    proxy: MtProxy,
    expanded: Boolean,
    isChecking: Boolean,
    canRecheck: Boolean,
    onToggleDetails: () -> Unit,
    onConnect: () -> Unit,
    onFavorite: () -> Unit,
    onRecheck: () -> Unit
) {
    val isWorking = proxy.telegramOk == true
    val skippedTelegram = proxy.tcpOk == false && proxy.telegramError == "TCP недоступен"
    val status = when {
        isWorking -> "Telegram OK"
        skippedTelegram -> "Не проверен · TCP FAIL"
        proxy.telegramError?.startsWith("SOCKS5:") == true -> "SOCKS5 FAIL"
        proxy.telegramOk == false -> "Telegram FAIL"
        else -> "Не проверен"
    }
    val statusColor = when {
        isWorking -> green
        skippedTelegram -> yellow
        proxy.telegramOk == false -> red
        else -> mutedText
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(start = 8.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggleDetails).padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("●", color = statusColor, fontSize = 14.sp)
                Spacer(Modifier.width(7.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        proxy.server,
                        color = mainText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        ":${proxy.port} · ${proxy.protocol.label} · $status" +
                            (if (isWorking) " · ${formatDashboardPing(proxy.telegramPingMs)}" else ""),
                        color = if (isWorking) green else mutedText,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(3.dp))
                Text(if (expanded) "⌃" else "⌄", color = mutedText, fontSize = 17.sp)
            }
            IconButton(
                onClick = onFavorite,
                modifier = Modifier.size(34.dp).semantics { contentDescription = if (proxy.isFavorite) "Удалить из избранного" else "Добавить в избранное" }
            ) {
                Text(if (proxy.isFavorite) "★" else "☆", color = if (proxy.isFavorite) yellow else mutedText, fontSize = 22.sp)
            }
            Spacer(Modifier.width(3.dp))
            if (isWorking) {
                Button(
                    onClick = onConnect,
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.height(33.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = blue, contentColor = Color.White)
                ) { Text("Подключиться", fontSize = 10.sp, maxLines = 1) }
            } else {
                OutlinedButton(
                    onClick = onConnect,
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.height(33.dp),
                    border = BorderStroke(1.dp, surfaceBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = mutedText)
                ) { Text("Подключиться", fontSize = 10.sp, maxLines = 1) }
            }
        }
        if (expanded) {
            HorizontalDivider(color = surfaceBorder)
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailValue("Протокол", proxy.protocol.label)
                DetailValue("Telegram", if (isWorking) "OK · ${formatDashboardPing(proxy.telegramPingMs)}" else status)
                DetailValue(
                    "TCP",
                    when (proxy.tcpOk) {
                        true -> "OK · ${formatDashboardPing(proxy.tcpPingMs)}"
                        false -> "FAIL"
                        null -> "Не проверен"
                    }
                )
                DetailValue("Адрес", "${proxy.server}:${proxy.port}")
                DetailValue("Проверено", formatDashboardCheckedAt(proxy.checkedAt))
                if (!proxy.telegramError.isNullOrBlank() && proxy.telegramOk == false) {
                    Text(proxy.telegramError.orEmpty(), color = red, fontSize = 11.sp)
                }
                if (proxy.protocol == ProxySourceProtocol.MTPROTO) {
                    var secretVisible by remember(proxy.cacheKey) { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Секрет", color = mutedText, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(
                            if (secretVisible) proxy.secret else "••••••••",
                            color = mainText, fontSize = 10.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f)
                        )
                        TextButton(onClick = { secretVisible = !secretVisible }) {
                            Text(if (secretVisible) "Скрыть" else "Показать", fontSize = 11.sp)
                        }
                    }
                } else if (!proxy.username.isNullOrEmpty()) {
                    DetailValue("Авторизация", "Требуется")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onRecheck,
                        enabled = canRecheck && !isChecking,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 5.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                        } else {
                            Text("↻ Проверить", fontSize = 12.sp)
                        }
                    }
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1.7f),
                        contentPadding = PaddingValues(horizontal = 5.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isWorking) blue else Color(0xFF30536B),
                            contentColor = mainText
                        )
                    ) {
                        Text("Подключиться через Telegram", fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = mutedText, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = mainText, fontSize = 11.sp, modifier = Modifier.weight(2f))
    }
}

internal fun formatDashboardPing(value: Long?): String = when {
    value == null -> "—"
    value >= 1000 -> "${"%.1f".format(java.util.Locale.US, value / 1000.0)} с"
    else -> "$value мс"
}

internal fun formatDashboardCheckedAt(time: Long?): String {
    if (time == null) return "Не проверялось"
    val elapsed = (System.currentTimeMillis() - time).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    val hours = minutes / 60L
    return when {
        minutes < 1 -> "Только что"
        minutes < 60 -> "$minutes мин. назад"
        hours < 24 -> "$hours ч. назад"
        else -> "${hours / 24L} дн. назад"
    }
}
