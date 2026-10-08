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

    var showOnlyAvailable by mutableStateOf(false)
        private set
    var showOnlyFavorites by mutableStateOf(false)
        private set
    var checkingProxyKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    private var loadedOnce = false

    init {
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
                proxy.checkedAt?.let { now - it in 0 until 30L * 60L * 1000L } == true
            }
            if (!ScanSession.state.value.running && !hasRecentChecks) refresh()
        }
    }

    fun refresh(force: Boolean = false) {
        if (ScanSession.state.value.running || checkingProxyKeys.isNotEmpty()) return
        ScanSession.start()
        try {
            ProxyScanService.start(getApplication(), force)
        } catch (e: Exception) {
            ScanSession.setError(e.message ?: "Не удалось запустить фоновый сервис")
            ScanSession.finish()
        }
    }

    fun recheckProxy(proxy: MtProxy) {
        if (ScanSession.state.value.running || checkingProxyKeys.contains(proxy.cacheKey)) return
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
        ScanSession.toggleFavorite(proxy)
        viewModelScope.launch { ScanSession.saveCache(getApplication()) }
    }
}
