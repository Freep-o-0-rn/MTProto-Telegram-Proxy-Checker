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
                            !isMtproto && enabled -> "Выбран · проверка позже"
                            !isMtproto -> "Проверка позже"
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
                if (inventoryErrors.isNotEmpty()) {
                    Text(
                        "Не удалось обновить часть списков. Показаны последние сохранённые значения.",
                        color = gray, fontSize = 12.sp
                    )
                }
                if (!sourceSwitchEnabled) {
                    Text("Останови сканирование, чтобы изменить источники", color = gray, fontSize = 12.sp)
                }
                Text(
                    "SOCKS5 пока только загружается в каталог. Проверку Telegram добавим отдельным обновлением.",
                    color = gray, fontSize = 12.sp
                )
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
                    color = if (checked && source.protocol == ProxySourceProtocol.MTPROTO) green else gray,
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
