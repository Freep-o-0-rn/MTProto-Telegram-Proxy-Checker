package com.example.telegramproxychecker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

private val SettingsBackgroundTop = Color(0xFF07111F)
private val SettingsBackgroundBottom = Color(0xFF0B2742)
private val SettingsSurface = Color(0xFF192B3D)
private val SettingsText = Color(0xFFF2F7FF)
private val SettingsMuted = Color(0xFFB8C7D9)
private val SettingsAccent = Color(0xFF1EA7FF)
private val SettingsGreen = Color(0xFF35D07F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = SettingsBackgroundTop,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", color = SettingsText, fontSize = 25.sp)
                    }
                },
                title = {
                    Text(
                        "Настройки",
                        color = SettingsText,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SettingsBackgroundTop
                )
            )
        }
    ) { insets ->
        Box(
            modifier = Modifier
                .padding(insets)
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(SettingsBackgroundTop, SettingsBackgroundBottom)))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Источники прокси",
                        color = SettingsText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Сейчас приложение сканирует только MTProto. " +
                            "Дополнительные протоколы и источники появятся в следующих обновлениях.",
                        color = SettingsMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                ProxySourceCatalogue.entries.forEach { source ->
                    item(key = source.id) {
                        SettingsSourceCard(source)
                    }
                }

                item {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("＋ Добавить источник GitHub — скоро")
                    }
                }

                item {
                    SettingsSectionTitle("Поддерживаемые типы")
                }

                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SettingsSurface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SettingsProtocolRow("MTProto", "Используется для проверки Telegram", active = true)
                            SettingsProtocolRow("SOCKS5", "В планах · отдельная логика проверки", active = false)
                            SettingsProtocolRow("HTTP", "В планах", active = false)
                            SettingsProtocolRow("WEB", "В планах", active = false)
                        }
                    }
                }

                item {
                    SettingsSectionTitle("Сканирование")
                }

                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SettingsSurface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SettingsReadOnlyRow("Параллельных проверок", "До 6")
                            SettingsReadOnlyRow("Повторная проверка", "Через 30 минут")
                            SettingsReadOnlyRow("Фоновое сканирование", "Включено")
                            Text(
                                "Это текущие параметры приложения. Их редактирование пока недоступно.",
                                color = SettingsMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                item {
                    Text(
                        "Новые источники пока не загружаются и не участвуют в сканировании. " +
                            "Экран настроек подготовлен только как интерфейс.",
                        color = SettingsMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSourceCard(source: ProxySourcePresentation) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SettingsSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (source.status == ProxySourceStatus.ACTIVE) SettingsGreen else SettingsMuted,
                            RoundedCornerShape(50)
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    source.name,
                    color = SettingsText,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                SettingsBadge(
                    if (source.status == ProxySourceStatus.ACTIVE) "Активен" else "Не подключён",
                    source.status == ProxySourceStatus.ACTIVE
                )
            }
            Text(
                source.repository,
                color = SettingsMuted,
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsBadge(source.protocol.label, source.status == ProxySourceStatus.ACTIVE)
                if (source.status == ProxySourceStatus.PLANNED) {
                    Text("Запланирован", color = SettingsMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                source.sourceUrl,
                color = SettingsMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingsBadge(label: String, active: Boolean) {
    Text(
        label,
        color = if (active) SettingsGreen else SettingsMuted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(
                if (active) Color(0xFF173A35) else Color(0xFF293A4C),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun SettingsSectionTitle(label: String) {
    Text(
        label,
        color = SettingsText,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun SettingsProtocolRow(name: String, description: String, active: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = SettingsText, fontWeight = FontWeight.Medium)
            Text(description, color = SettingsMuted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.width(8.dp))
        SettingsBadge(if (active) "Активен" else "Позже", active)
    }
}

@Composable
private fun SettingsReadOnlyRow(name: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = SettingsMuted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = SettingsText, style = MaterialTheme.typography.bodySmall)
    }
}
