package com.example.telegramproxychecker

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

class TelegramMtprotoChecker {

    private val dcIds = listOf(1, 2, 3, 4, 5)

    suspend fun check(proxy: MtProxy): MtProxy {
        if (proxy.tcpOk != true) {
            return proxy.copy(
                telegramOk = false,
                telegramPingMs = null,
                telegramError = "TCP недоступен"
            )
        }

        val result = testProxy(proxy)

        return proxy.copy(
            telegramOk = result.ok,
            telegramPingMs = result.pingMs,
            telegramError = result.error
        )
    }

    private suspend fun testProxy(proxy: MtProxy): TelegramCheckResult {
        val client = Client.create(
            Client.ResultHandler { },
            null,
            null
        )

        return try {
            val errors = mutableListOf<String>()

            for (dcId in dcIds) {
                val result = testProxyOnDc(
                    client = client,
                    proxy = proxy,
                    dcId = dcId
                )

                if (result.ok) {
                    return result
                }

                if (!result.error.isNullOrBlank()) {
                    errors.add("DC$dcId: ${result.error}")
                }
            }

            TelegramCheckResult(
                ok = false,
                pingMs = null,
                error = errors.firstOrNull() ?: "Все DC Telegram недоступны"
            )
        } catch (e: Exception) {
            TelegramCheckResult(
                ok = false,
                pingMs = null,
                error = e.message ?: "TDLib error"
            )
        } finally {
            closeClient(client)
        }
    }

    private suspend fun testProxyOnDc(
        client: Client,
        proxy: MtProxy,
        dcId: Int
    ): TelegramCheckResult {
        val start = System.currentTimeMillis()

        return withTimeoutOrNull(7000L) {
            suspendCancellableCoroutine<TelegramCheckResult> { cont ->

                val proxyType = TdApi.ProxyTypeMtproto(proxy.secret)

                val tdProxy = TdApi.Proxy(
                    proxy.server,
                    proxy.port,
                    proxyType
                )

                val request = TdApi.TestProxy(
                    tdProxy,
                    dcId,
                    5.0
                )

                client.send(
                    request,
                    Client.ResultHandler { response ->
                        if (!cont.isActive) {
                            return@ResultHandler
                        }

                        when (response) {
                            is TdApi.Ok -> {
                                cont.resume(
                                    TelegramCheckResult(
                                        ok = true,
                                        pingMs = System.currentTimeMillis() - start,
                                        error = null
                                    )
                                )
                            }

                            is TdApi.Error -> {
                                cont.resume(
                                    TelegramCheckResult(
                                        ok = false,
                                        pingMs = null,
                                        error = response.message
                                    )
                                )
                            }

                            else -> {
                                cont.resume(
                                    TelegramCheckResult(
                                        ok = false,
                                        pingMs = null,
                                        error = "Неожиданный ответ TDLib"
                                    )
                                )
                            }
                        }
                    }
                )
            }
        } ?: TelegramCheckResult(
            ok = false,
            pingMs = null,
            error = "TDLib timeout"
        )
    }

    private fun closeClient(client: Client) {
        try {
            client.send(
                TdApi.Close(),
                Client.ResultHandler { }
            )
        } catch (_: Exception) {
            // клиент уже мог быть закрыт
        }
    }
}

data class TelegramCheckResult(
    val ok: Boolean,
    val pingMs: Long?,
    val error: String?
)