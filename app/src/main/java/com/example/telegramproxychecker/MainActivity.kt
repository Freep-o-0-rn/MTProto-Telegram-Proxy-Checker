package com.example.telegramproxychecker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider

private val BgTop = Color(0xFF07111F)
private val BgBottom = Color(0xFF0B2742)
private val CardColor = Color(0xE61A2A3A)
private val AccentBlue = Color(0xFF1EA7FF)
private val AccentGreen = Color(0xFF35D07F)
private val AccentRed = Color(0xFFFF5C5C)
private val AccentYellow = Color(0xFFFFC857)
private val TextMain = Color(0xFFF2F7FF)
private val TextMuted = Color(0xFFB8C7D9)

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: ProxyViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[ProxyViewModel::class.java]

        setContent {
            ProxyApp(viewModel)
        }
    }
}

fun openTelegramProxy(context: Context, proxy: MtProxy) {
    val telegramUri = Uri.parse(
        "tg://proxy" +
                "?server=${Uri.encode(proxy.server)}" +
                "&port=${proxy.port}" +
                "&secret=${Uri.encode(proxy.secret)}"
    )

    val webUri = Uri.parse(proxy.originalUrl)

    val telegramPackages = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.messenger.beta",
        "org.thunderdog.challegram"
    )

    for (packageName in telegramPackages) {
        val intent = Intent(Intent.ACTION_VIEW, telegramUri)
            .setPackage(packageName)

        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // пробуем следующий пакет Telegram
        }
    }

    try {
        val intent = Intent(Intent.ACTION_VIEW, telegramUri)
        context.startActivity(intent)
        return
    } catch (_: Exception) {
        // fallback ниже
    }

    try {
        val intent = Intent(Intent.ACTION_VIEW, webUri)
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(
            context,
            "Telegram не найден",
            Toast.LENGTH_LONG
        ).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyApp(viewModel: ProxyViewModel) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    val isSmallScreen = configuration.screenWidthDp < 380

    val pagePadding = if (isSmallScreen) 8.dp else 12.dp
    val cardPadding = if (isSmallScreen) 10.dp else 14.dp
    val itemSpacing = if (isSmallScreen) 8.dp else 10.dp

    val proxies = viewModel.proxies
    val isLoading = viewModel.isLoading
    val error = viewModel.error
    val showOnlyAvailable = viewModel.showOnlyAvailable
    val showOnlyFavorites = viewModel.showOnlyFavorites
    val checkedCount = viewModel.checkedCount
    val totalCount = viewModel.totalCount

    LaunchedEffect(Unit) {
        viewModel.loadOnce()
    }

    val tcpOkCount = proxies.count { it.tcpOk == true }
    val tcpFailCount = proxies.count { it.tcpOk == false }

    val telegramOkCount = proxies.count { it.telegramOk == true }
    val telegramFailCount = proxies.count { it.telegramOk == false && it.tcpOk == true }
    val favoriteCount = proxies.count { it.isFavorite }

    val visibleProxies = proxies
        .filter {
            if (showOnlyAvailable) {
                it.telegramOk == true
            } else {
                true
            }
        }
        .filter {
            if (showOnlyFavorites) {
                it.isFavorite
            } else {
                true
            }
        }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "MTProto Proxy",
                            color = TextMain,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Telegram proxy checker",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF07111F),
                    titleContentColor = TextMain
                )
            )
        }
    ) { padding ->

        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(BgTop, BgBottom)
                    )
                )
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(pagePadding),
                verticalArrangement = Arrangement.spacedBy(itemSpacing)
            ) {
                item {
                    Button(
                        onClick = {
                            viewModel.refresh()
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentBlue,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF24445F),
                            disabledContentColor = TextMuted
                        )
                    ) {
                        Text(
                            text = if (isLoading) {
                                "Проверка proxy..."
                            } else {
                                "Обновить и проверить"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                item {
                    if (isLoading) {
                        LoadingProgressCard(
                            checkedCount = checkedCount,
                            totalCount = totalCount
                        )
                    }
                }

                item {
                    if (error != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF3A1820)
                            )
                        ) {
                            Text(
                                text = "Ошибка: $error",
                                color = AccentRed,
                                modifier = Modifier.padding(cardPadding)
                            )
                        }
                    }
                }

                item {
                    StatsCard(
                        total = proxies.size,
                        tcpOk = tcpOkCount,
                        tcpFail = tcpFailCount,
                        telegramOk = telegramOkCount,
                        telegramFail = telegramFailCount,
                        favorites = favoriteCount,
                        visible = visibleProxies.size,
                        cardPadding = cardPadding
                    )
                }

                item {
                    Button(
                        onClick = {
                            viewModel.toggleOnlyAvailable()
                        },
                        enabled = proxies.isNotEmpty() && !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF19364F),
                            contentColor = TextMain,
                            disabledContainerColor = Color(0xFF162838),
                            disabledContentColor = TextMuted
                        )
                    ) {
                        Text(
                            text = if (showOnlyAvailable) {
                                "Показать все"
                            } else {
                                "Показать рабочие"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                item {
                    Button(
                        onClick = {
                            viewModel.toggleOnlyFavorites()
                        },
                        enabled = proxies.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showOnlyFavorites) Color(0xFF5A4318) else Color(0xFF19364F),
                            contentColor = TextMain,
                            disabledContainerColor = Color(0xFF162838),
                            disabledContentColor = TextMuted
                        )
                    ) {
                        Text(
                            text = if (showOnlyFavorites) {
                                "Показать все"
                            } else {
                                "Показать избранные"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                items(
                    items = visibleProxies,
                    key = { it.cacheKey }
                ) { proxy ->
                    ProxyItem(
                        proxy = proxy,
                        isSmallScreen = isSmallScreen,
                        onConnectClick = {
                            openTelegramProxy(context, proxy)
                        },
                        onFavoriteClick = {
                            viewModel.toggleFavorite(proxy)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun StatsCard(
    total: Int,
    tcpOk: Int,
    tcpFail: Int,
    telegramOk: Int,
    telegramFail: Int,
    favorites: Int,
    visible: Int,
    cardPadding: androidx.compose.ui.unit.Dp
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = CardColor
        )
    ) {
        Column(
            modifier = Modifier.padding(cardPadding)
        ) {
            Text(
                text = "Статистика",
                color = TextMain,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text("Всего прокси: $total", color = TextMuted)
            Text("TCP OK: $tcpOk", color = AccentGreen)
            Text("TCP FAIL: $tcpFail", color = AccentRed)
            Text("Telegram OK: $telegramOk", color = AccentGreen)
            Text("Telegram FAIL: $telegramFail", color = AccentRed)
            Text("Избранных: $favorites", color = AccentYellow)
            Text("Показано: $visible", color = TextMuted)
        }
    }
}

@Composable
fun ProxyItem(
    proxy: MtProxy,
    isSmallScreen: Boolean,
    onConnectClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    val cardPadding = if (isSmallScreen) 10.dp else 14.dp
    val secretLength = if (isSmallScreen) 8 else 12

    val tcpText = when (proxy.tcpOk) {
        true -> "TCP: OK, ${proxy.tcpPingMs} мс"
        false -> "TCP: FAIL"
        null -> "TCP: не проверен"
    }

    val tcpColor = when (proxy.tcpOk) {
        true -> AccentGreen
        false -> AccentRed
        null -> TextMuted
    }

    val telegramText = when (proxy.telegramOk) {
        true -> "Telegram: OK, ${formatMs(proxy.telegramPingMs)}"
        false -> "Telegram: FAIL"
        null -> "Telegram: не проверен"
    }

    val telegramColor = when (proxy.telegramOk) {
        true -> AccentGreen
        false -> AccentRed
        null -> AccentYellow
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = CardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(cardPadding)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${proxy.server}:${proxy.port}",
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onFavoriteClick,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (proxy.isFavorite) AccentYellow else Color(0xFF19364F),
                        contentColor = if (proxy.isFavorite) Color.Black else TextMain
                    )
                ) {
                    Text(
                        text = if (proxy.isFavorite) "★" else "☆",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = tcpText,
                color = tcpColor,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = telegramText,
                color = telegramColor,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Проверено: ${formatCheckedAt(proxy.checkedAt)}",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall
            )

            if (!proxy.telegramError.isNullOrBlank() && proxy.telegramOk == false) {
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Ошибка: ${proxy.telegramError.take(80)}",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "secret: ${proxy.secret.take(secretLength)}...",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onConnectClick,
                enabled = proxy.tcpOk == true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF24445F),
                    disabledContentColor = TextMuted
                )
            ) {
                Text(
                    text = "Подключиться",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun LoadingProgressCard(
    checkedCount: Int,
    totalCount: Int
) {
    val progressText = if (totalCount > 0) {
        "Проверено $checkedCount из $totalCount"
    } else {
        "Загрузка списка с GitHub..."
    }

    val progress = if (totalCount > 0) {
        checkedCount.toFloat() / totalCount.toFloat()
    } else {
        0f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = CardColor
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = AccentBlue
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = progressText,
                    color = TextMain,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (totalCount > 0) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentBlue,
                    trackColor = Color(0xFF1B354A)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentBlue,
                    trackColor = Color(0xFF1B354A)
                )
            }
        }
    }
}

fun formatMs(value: Long?): String {
    if (value == null) return "-"

    return if (value >= 1000) {
        String.format("%.1f сек", value / 1000.0)
    } else {
        "$value мс"
    }
}

fun formatCheckedAt(checkedAt: Long?): String {
    if (checkedAt == null) {
        return "не проверялось"
    }

    val diffMs = System.currentTimeMillis() - checkedAt
    val minutes = diffMs / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L

    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин. назад"
        hours < 24 -> "$hours ч. назад"
        else -> "$days дн. назад"
    }
}