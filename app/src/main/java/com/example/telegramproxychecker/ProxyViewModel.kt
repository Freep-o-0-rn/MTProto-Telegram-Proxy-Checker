package com.example.telegramproxychecker

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** UI only: the service survives Activity/ViewModel destruction. */
class ProxyViewModel(application: Application) : AndroidViewModel(application) {
    var proxies by mutableStateOf(ScanSession.state.value.proxies)
        private set
    var isLoading by mutableStateOf(ScanSession.state.value.running)
        private set
    internal var scanProgress by mutableStateOf(ScanSession.state.value.progress)
        private set
    var error by mutableStateOf(ScanSession.state.value.error)
        private set

    var enabledSourceIds by mutableStateOf(ProxySourceSettings.enabledSourceIds(application))
        private set
    val mtprotoSourceEnabled: Boolean
        get() = ProxySourceCatalogue.entries.any {
            it.protocol == ProxySourceProtocol.MTPROTO && it.id in enabledSourceIds
        }
    val socks5SourceEnabled: Boolean
        get() = "hookzof-socks5" in enabledSourceIds
    var selectedUniqueCount by mutableStateOf<Int?>(null)
        private set
    var scanLimit by mutableStateOf(ProxySourceSettings.scanLimit(application))
        private set
    var scanAll by mutableStateOf(ProxySourceSettings.scanAll(application))
        private set
    var parallelChecks by mutableStateOf(ProxySourceSettings.parallelChecks(application))
        private set
    var autoConcurrency by mutableStateOf(ProxySourceSettings.autoConcurrency(application))
        private set
    val autoSuggestedWorkers: Int = ScanConcurrencyPolicy.recommended(application)
    internal var inventoryCounts by mutableStateOf<Map<String, SourceInventoryCount>>(emptyMap())
        private set
    var inventoryErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var inventoryRefreshing by mutableStateOf(false)
        private set
    var clearingProxyData by mutableStateOf(false)
        private set
    var proxyCleanupMessage by mutableStateOf<String?>(null)
        private set
    private val inventoryRepository = ProxySourceInventory(application)

    var showOnlyAvailable by mutableStateOf(false)
        private set
    var showOnlyFavorites by mutableStateOf(false)
        private set
    var checkingProxyKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    private var loadedOnce = false
    // Prevent an old asynchronous count query from repainting stale totals.
    private var inventoryGeneration = 0

    init {
        if (!ScanSession.state.value.running) {
            ScanSession.repository.configureParallelChecks(ScanConcurrencyPolicy.effective(application))
        }
        viewModelScope.launch {
            ScanSession.state.collect { current ->
                proxies = current.proxies
                isLoading = current.running
                scanProgress = current.progress
                error = current.error
            }
        }
    }

    fun loadOnce() {
        if (loadedOnce) return
        loadedOnce = true
        viewModelScope.launch {
            try {
                ScanSession.loadCache(getApplication())
            } catch (e: Exception) {
                ScanSession.setError(e.message ?: "Ошибка загрузки кэша")
            }
            // Opening the app again must not immediately repeat a completed background scan.
            // Results (including failures) have already been written to the persistent cache.
            val now = System.currentTimeMillis()
            val hasRecentChecks = ScanSession.state.value.proxies.any { proxy ->
                val sourceIsEnabled = when (proxy.protocol) {
                    ProxySourceProtocol.MTPROTO -> mtprotoSourceEnabled
                    ProxySourceProtocol.SOCKS5 -> socks5SourceEnabled
                    else -> false
                }
                sourceIsEnabled &&
                    (proxy.checkedAt?.let { now - it in 0 until 30L * 60L * 1000L } == true)
            }
            if (!ScanSession.state.value.running &&
                (mtprotoSourceEnabled || socks5SourceEnabled) && !hasRecentChecks) refresh()
        }
    }

    fun refresh(force: Boolean = false, tcpOkOnly: Boolean = false) {
        if (ScanSession.state.value.running || checkingProxyKeys.isNotEmpty() ||
            clearingProxyData) return
        if (!mtprotoSourceEnabled && !socks5SourceEnabled) {
            ScanSession.setError("Включи хотя бы один источник в настройках")
            return
        }
        ScanSession.start()
        try {
            ProxyScanService.start(getApplication(), force, tcpOkOnly)
        } catch (e: Exception) {
            ScanSession.setError(e.message ?: "Не удалось запустить фоновый сервис")
            ScanSession.finish()
        }
    }

    fun updateSourceEnabled(sourceId: String, enabled: Boolean) {
        if (ScanSession.state.value.running || clearingProxyData ||
            checkingProxyKeys.isNotEmpty()) return
        ProxySourceSettings.setSourceEnabled(getApplication(), sourceId, enabled)
        enabledSourceIds = ProxySourceSettings.enabledSourceIds(getApplication())
        refreshUniqueSelectedCount()
    }

    private fun refreshUniqueSelectedCount() {
        val ids = enabledSourceIds
        val generation = ++inventoryGeneration
        selectedUniqueCount = null
        viewModelScope.launch {
            try {
                val count = inventoryRepository.uniqueSelectedCount(ids)
                if (generation == inventoryGeneration && ids == enabledSourceIds) {
                    selectedUniqueCount = count
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Inventory counts remain visible even if SQLite is unavailable.
            }
        }
    }

    fun updateScanLimit(value: Int) {
        if (isLoading) return
        scanLimit = value.coerceAtLeast(1)
        ProxySourceSettings.setScanLimit(getApplication(), scanLimit)
    }

    fun updateScanAll(value: Boolean) {
        if (isLoading) return
        scanAll = value
        ProxySourceSettings.setScanAll(getApplication(), value)
    }

    fun updateParallelChecks(value: Int) {
        if (isLoading || checkingProxyKeys.isNotEmpty()) return
        val clamped = ScanConcurrencyPolicy.clamp(value)
        ProxySourceSettings.setParallelChecks(getApplication(), clamped)
        parallelChecks = clamped
        if (!autoConcurrency) ScanSession.repository.configureParallelChecks(clamped)
    }

    fun updateAutoConcurrency(enabled: Boolean) {
        if (isLoading || checkingProxyKeys.isNotEmpty()) return
        ProxySourceSettings.setAutoConcurrency(getApplication(), enabled)
        autoConcurrency = enabled
        ScanSession.repository.configureParallelChecks(
            if (enabled) autoSuggestedWorkers else parallelChecks
        )
    }

    fun refreshSourceInventory() {
        if (inventoryRefreshing || clearingProxyData) return
        inventoryRefreshing = true
        viewModelScope.launch {
            try {
                inventoryCounts = inventoryRepository.cached()
                refreshUniqueSelectedCount()
                val result = inventoryRepository.refresh()
                inventoryCounts = result.counts
                inventoryErrors = result.errors
                refreshUniqueSelectedCount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                inventoryErrors = mapOf("sources" to (e.message ?: "Ошибка загрузки источников"))
            } finally {
                inventoryRefreshing = false
            }
        }
    }

    /** User-confirmed destructive maintenance, never interleaved with a scan or import. */
    fun clearProxyData() {
        if (clearingProxyData || inventoryRefreshing || isLoading ||
            ScanSession.state.value.running || checkingProxyKeys.isNotEmpty()) return
        clearingProxyData = true
        proxyCleanupMessage = null
        viewModelScope.launch {
            try {
                val app: Application = getApplication()
                val result: ProxyCleanupResult = ScanSession.clearProxyData(app)
                // Drop both the live list and metadata; no scheduled save may revive them.
                inventoryGeneration++
                inventoryCounts = emptyMap()
                inventoryErrors = emptyMap()
                selectedUniqueCount = null
                proxyCleanupMessage = "Удалено: ${result.checkedProxies} проверенных, " +
                    "${result.sourceEntries} записей источников." +
                    if (result.compacted) " SQLite сжата."
                    else " Не удалось сжать файл SQLite."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                proxyCleanupMessage = "Ошибка очистки: " +
                    (e.message ?: "не удалось удалить сохранённые прокси")
            } finally {
                clearingProxyData = false
            }
        }
    }

    fun pauseScan() {
        if (!ScanSession.state.value.running || ScanSession.state.value.paused) return
        try {
            ProxyScanService.pause(getApplication())
        } catch (e: Exception) {
            ScanSession.setError(e.message ?: "Не удалось приостановить проверку")
        }
    }

    fun resumeScan() {
        if (!ScanSession.state.value.running || !ScanSession.state.value.paused) return
        try {
            ProxyScanService.resume(getApplication())
        } catch (e: Exception) {
            ScanSession.setError(e.message ?: "Не удалось возобновить проверку")
        }
    }

    fun stopScan() {
        if (!ScanSession.state.value.running) return
        try {
            ProxyScanService.stop(getApplication())
        } catch (e: Exception) {
            ScanSession.setError(e.message ?: "Не удалось остановить проверку")
        }
    }

    fun recheckProxy(proxy: MtProxy) {
        if (ScanSession.state.value.running || clearingProxyData ||
            checkingProxyKeys.contains(proxy.cacheKey)) return
        checkingProxyKeys = checkingProxyKeys + proxy.cacheKey
        viewModelScope.launch {
            try {
                val checked = ScanSession.repository.recheckOneProxy(proxy)
                ScanSession.updateOne(checked)
                ScanSession.saveCache(getApplication())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ScanSession.setError(e.message ?: "Ошибка точечной проверки")
            } finally {
                checkingProxyKeys = checkingProxyKeys - proxy.cacheKey
            }
        }
    }

    fun toggleOnlyAvailable() { showOnlyAvailable = !showOnlyAvailable }
    fun toggleOnlyFavorites() { showOnlyFavorites = !showOnlyFavorites }

    fun toggleFavorite(proxy: MtProxy) {
        if (clearingProxyData) return
        ScanSession.toggleFavorite(proxy)
        viewModelScope.launch { ScanSession.saveCache(getApplication()) }
    }
}
