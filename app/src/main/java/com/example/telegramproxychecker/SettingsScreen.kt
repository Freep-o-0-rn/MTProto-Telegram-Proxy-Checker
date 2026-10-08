package com.example.telegramproxychecker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val top = Color(0xFF07111F)
private val bottom = Color(0xFF0B2742)
private val panel = Color(0xFF192B3D)
private val white = Color(0xFFF2F7FF)
private val gray = Color(0xFFB8C7D9)
private val green = Color(0xFF35D07F)
private val blue = Color(0xFF229ED9)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    mtprotoEnabled: Boolean,
    socks5Enabled: Boolean,
    sourceSwitchEnabled: Boolean,
    inventoryCounts: Map<String, SourceInventoryCount>,
    inventoryErrors: Map<String, String>,
    inventoryRefreshing: Boolean,
    scanLimit: Int,
    scanAll: Boolean,
    parallelChecks: Int,
    autoConcurrency: Boolean,
    autoSuggestedWorkers: Int,
    onParallelChecksChange: (Int) -> Unit,
    onAutoConcurrencyChange: (Boolean) -> Unit,
    onScanLimitChange: (Int) -> Unit,
    onScanAllChange: (Boolean) -> Unit,
    onRefreshInventory: () -> Unit,
    onMtprotoEnabledChange: (Boolean) -> Unit,
    onSocks5EnabledChange: (Boolean) -> Unit
) {
    val selected = ProxySourceCatalogue.entries.filter { source ->
        if (source.protocol == ProxySourceProtocol.MTPROTO) mtprotoEnabled else
            if (source.protocol == ProxySourceProtocol.SOCKS5) socks5Enabled else false
    }
    val allLoaded = selected.all { inventoryCounts.containsKey(it.id) }
    val selectedTotal = selected.sumOf { inventoryCounts[it.id]?.count ?: 0 }

    Scaffold(
        containerColor = top,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", color = white, fontSize = 24.sp) }
                },
                title = { Text("Настройки", color = white, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = top)
            )
        }
    ) { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets)
                .background(Brush.verticalGradient(listOf(top, bottom)))
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Источники прокси",
                        color = white, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefreshInventory, enabled = !inventoryRefreshing) {
                        Text(if (inventoryRefreshing) "Загрузка…" else "Обновить", color = blue)
                    }
                }
                ProxySourceCatalogue.entries.forEach { source ->
                    val isMtproto = source.protocol == ProxySourceProtocol.MTPROTO
                    val enabled = if (isMtproto) mtprotoEnabled else socks5Enabled
                    val count = inventoryCounts[source.id]
                    SourceToggleRow(
                        source = source,
                        checked = enabled,
                        enabled = sourceSwitchEnabled,
                        status = when {
                            !isMtproto && enabled -> "Включён"
                            !isMtproto -> "Выключен"
                            enabled -> "Включён"
                            else -> "Выключен"
                        },
                        count = count?.count,
                        stale = source.id in inventoryErrors,
                        onCheckedChange = if (isMtproto) onMtprotoEnabledChange else onSocks5EnabledChange
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("В выбранных источниках", color = gray, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(
                        if (allLoaded) "%,d".format(java.util.Locale.US, selectedTotal).replace(',', ' ')
                        else "—",
                        color = white, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                    )
                }
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = panel),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val maximum = selectedTotal.coerceAtLeast(1)
                        val selectedLimit = scanLimit.coerceIn(1, maximum)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Лимит проверки", color = white, fontSize = 14.sp,
                                modifier = Modifier.weight(1f))
                            Text(if (scanAll && allLoaded) "Все: $selectedTotal" else "$selectedLimit",
                                color = blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Slider(
                            value = selectedLimit.toFloat(),
                            onValueChange = { onScanLimitChange(it.roundToInt()) },
                            valueRange = 1f..maximum.toFloat(),
                            enabled = sourceSwitchEnabled && !scanAll && allLoaded &&
                                selectedTotal > 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("1", color = gray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(if (allLoaded) "$selectedTotal" else "Загрузка…",
                                color = gray, fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Без ограничения · Все", color = white, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            Checkbox(
                                checked = scanAll,
                                onCheckedChange = onScanAllChange,
                                enabled = sourceSwitchEnabled
                            )
                        }
                    }
                }
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = panel),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val effectiveWorkers = if (autoConcurrency) autoSuggestedWorkers else parallelChecks
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Параллельные проверки", color = white, fontSize = 14.sp,
                                modifier = Modifier.weight(1f))
                            Text("$effectiveWorkers", color = blue,
                                fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Slider(
                            value = parallelChecks.toFloat(),
                            onValueChange = { onParallelChecksChange(it.roundToInt()) },
                            valueRange = ScanConcurrencyPolicy.MIN_WORKERS.toFloat()..
                                ScanConcurrencyPolicy.MAX_WORKERS.toFloat(),
                            steps = ScanConcurrencyPolicy.MAX_WORKERS -
                                ScanConcurrencyPolicy.MIN_WORKERS - 1,
                            enabled = sourceSwitchEnabled && !autoConcurrency
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("1 · минимальная нагрузка", color = gray,
                                fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("30 · максимум", color = gray, fontSize = 11.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Автоматически", color = white, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            Switch(
                                checked = autoConcurrency,
                                onCheckedChange = onAutoConcurrencyChange,
                                enabled = sourceSwitchEnabled
                            )
                        }
                        Text(
                            if (autoConcurrency) "По устройству: $autoSuggestedWorkers. " +
                                "TDLib — максимум 6 одновременно."
                            else "TDLib — максимум 6 проверок одновременно, " +
                                "даже при более высокой скорости TCP/SOCKS5.",
                            color = gray, fontSize = 11.sp
                        )
                    }
                }
                if (inventoryErrors.isNotEmpty()) {
                    Text(
                        "Не удалось обновить часть списков. Показаны последние сохранённые значения.",
                        color = gray, fontSize = 12.sp
                    )
                }
                if (!sourceSwitchEnabled) {
                    Text("Останови сканирование, чтобы изменить источники", color = gray, fontSize = 12.sp)
                }
                Text("MTProto и SOCKS5 используют общую очередь; " +
                    "режим скорости применяется при следующем запуске.", color = gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SourceToggleRow(
    source: ProxySourcePresentation,
    checked: Boolean,
    enabled: Boolean,
    status: String,
    count: Int?,
    stale: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = panel),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(source.name, color = white, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(source.protocol.label + " · " + status,
                    color = if (checked) green else gray,
                    fontSize = 12.sp)
                Text(source.repository, color = gray, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        count == null -> "Адресов: загрузка…"
                        stale -> "Адресов: $count · из кэша"
                        else -> "Адресов: $count"
                    },
                    color = if (stale) gray else blue, fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}
