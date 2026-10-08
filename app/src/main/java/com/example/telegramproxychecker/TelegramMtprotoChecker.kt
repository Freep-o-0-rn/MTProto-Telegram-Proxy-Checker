package com.example.telegramproxychecker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

private const val DC_START_DELAY_MS = 200L
private const val TD_REQUEST_TIMEOUT_SECONDS = 5.0
private const val CALLBACK_TIMEOUT_MS = 7000L

// A small boundary lets JVM tests exercise scheduling without loading native TDLib.
internal interface TelegramProbeClient {
    fun send(proxy: MtProxy, dcId: Int, timeoutSeconds: Double, onResult: (TelegramCheckResult) -> Unit)
    fun close()
}

class TelegramMtprotoChecker internal constructor(
    private val createClient: () -> TelegramProbeClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor() : this({ TdlibProbeClient() })

    private val dcIds = listOf(1, 2, 3, 4, 5)

    suspend fun check(proxy: MtProxy): MtProxy {
        // TDLib may use a different network resolution/connection path than java.net.Socket.
        // The caller decides whether to perform this expensive check after TCP FAIL.
        val result = try {
            withContext(dispatcher) { testProxy(proxy) }
        } catch (e: LinkageError) {
            // A broken or missing tdjni binary is not a proxy failure. Report it explicitly.
            TelegramCheckResult(false, null, "TDLib JNI недоступна: " + (e.message ?: e.javaClass.simpleName))
        }
        return proxy.copy(
            telegramOk = result.ok,
            telegramPingMs = result.pingMs,
            telegramError = result.error
        )
    }

    private suspend fun testProxy(proxy: MtProxy): TelegramCheckResult = coroutineScope {
        val client = createClient()
        val results = Channel<Pair<Int, TelegramCheckResult>>(dcIds.size)
        // Give a fast first DC a chance to finish without opening extra connections.
        // A slow/blocked DC must not hold up the remaining DCs for 5–7 seconds each.
        val probes = dcIds.mapIndexed { index, dcId ->
            launch {
                delay(index * DC_START_DELAY_MS)
                results.send(dcId to testProxyOnDc(client, proxy, dcId))
            }
        }

        try {
            val errors = mutableMapOf<Int, String>()
            repeat(dcIds.size) {
                val (dcId, result) = results.receive()
                if (result.ok) return@coroutineScope result
                result.error?.takeIf { it.isNotBlank() }?.let { errors[dcId] = it }
            }
            TelegramCheckResult(
                ok = false,
                pingMs = null,
                error = dcIds.firstNotNullOfOrNull { dcId ->
                    errors[dcId]?.let { "DC$dcId: $it" }
                } ?: "Все DC Telegram недоступны"
            )
        } finally {
            probes.forEach { it.cancel() }
            // Cancelling a coroutine alone does not cancel a native TestProxy request.
            // Each proxy owns its client, so Close also stops its unfinished probes.
            client.close()
        }
    }

    private suspend fun testProxyOnDc(
        client: TelegramProbeClient,
        proxy: MtProxy,
        dcId: Int
    ): TelegramCheckResult {
        return try {
            withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    client.send(proxy, dcId, TD_REQUEST_TIMEOUT_SECONDS) { result ->
                        // TDLib may deliver a result after timeout, success on another DC,
                        // or cancellation of the entire scan.
                        if (cont.isActive) cont.resume(result)
                    }
                }
            } ?: TelegramCheckResult(false, null, "TDLib timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TelegramCheckResult(false, null, e.message ?: "TDLib error")
        }
    }
}

private class TdlibProbeClient : TelegramProbeClient {
    private val client = Client.create(Client.ResultHandler { }, null, null)

    override fun send(
        proxy: MtProxy,
        dcId: Int,
        timeoutSeconds: Double,
        onResult: (TelegramCheckResult) -> Unit
    ) {
        val start = System.nanoTime()
        val type = when (proxy.protocol) {
            ProxySourceProtocol.MTPROTO -> TdApi.ProxyTypeMtproto(proxy.secret)
            ProxySourceProtocol.SOCKS5 -> TdApi.ProxyTypeSocks5(
                proxy.username.orEmpty(), proxy.password.orEmpty()
            )
            else -> throw IllegalArgumentException("Unsupported proxy protocol: ${proxy.protocol}")
        }
        val request = TdApi.TestProxy(
            TdApi.Proxy(proxy.server, proxy.port, type),
            dcId,
            timeoutSeconds
        )
        client.send(request, Client.ResultHandler { response ->
            onResult(when (response) {
                is TdApi.Ok -> TelegramCheckResult(true, (System.nanoTime() - start) / 1_000_000, null)
                is TdApi.Error -> TelegramCheckResult(
                    false, null, "TDLib " + response.code + ": " + response.message
                )
                else -> TelegramCheckResult(false, null, "Неожиданный ответ TDLib")
            })
        })
    }

    override fun close() {
        try {
            client.send(TdApi.Close(), Client.ResultHandler { })
        } catch (_: Exception) {
            // The client may already be closed.
        }
    }
}

data class TelegramCheckResult(
    val ok: Boolean,
    val pingMs: Long?,
    val error: String?
)
