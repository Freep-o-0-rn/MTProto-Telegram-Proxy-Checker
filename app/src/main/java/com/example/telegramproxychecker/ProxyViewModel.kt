package com.example.telegramproxychecker

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProxyRepository()
    private val cache = ProxyCache(application.applicationContext)
    private val cacheWriteMutex = Mutex()

    var proxies by mutableStateOf<List<MtProxy>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var showOnlyAvailable by mutableStateOf(false)
        private set

    var showOnlyFavorites by mutableStateOf(false)
        private set

    var checkingProxyKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    var checkedCount by mutableStateOf(0)
        private set

    var totalCount by mutableStateOf(0)
        private set

    private var wasLoadedOnce = false

    fun loadOnce() {
        if (wasLoadedOnce) return

        wasLoadedOnce = true

        refresh(force = false)
    }

    fun refresh(force: Boolean = false) {
        if (isLoading || checkingProxyKeys.isNotEmpty()) return

        isLoading = true
        error = null
        checkedCount = 0
        totalCount = 0

        viewModelScope.launch {
            try {
                if (proxies.isEmpty()) {
                    val cached = withContext(Dispatchers.IO) { cache.loadProxies() }
                    proxies = repository.sortProxies(cached)
                }

                repository.loadAndCheckProxies(
                    cachedProxies = proxies,
                    force = force,
                    onUpdate = { snapshot ->
                        // Favorites may change while the scan is running.
                        proxies = repository.sortProxies(snapshot.withFavoritesFrom(proxies))
                    }
                ) { checked, total ->
                    checkedCount = checked
                    totalCount = total
                }

                saveCache()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = if (proxies.isNotEmpty()) {
                    "Не удалось обновить. Показан сохранённый список."
                } else {
                    e.message ?: "Ошибка загрузки"
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun recheckProxy(proxy: MtProxy) {
        if (isLoading || checkingProxyKeys.contains(proxy.cacheKey)) return

        checkingProxyKeys = checkingProxyKeys + proxy.cacheKey

        viewModelScope.launch {
            try {
                val checked = repository.recheckOneProxy(proxy)

                val checkedWithFavorite = checked.copy(
                    isFavorite = proxies.firstOrNull { it.cacheKey == proxy.cacheKey }?.isFavorite
                        ?: proxy.isFavorite
                )

                val updated = proxies.map {
                    if (it.cacheKey == proxy.cacheKey) {
                        checkedWithFavorite
                    } else {
                        it
                    }
                }

                proxies = repository.sortProxies(updated)
                saveCache()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Ошибка точечной проверки"
            } finally {
                checkingProxyKeys = checkingProxyKeys - proxy.cacheKey
            }
        }
    }

    fun toggleOnlyAvailable() {
        showOnlyAvailable = !showOnlyAvailable
    }

    fun toggleOnlyFavorites() {
        showOnlyFavorites = !showOnlyFavorites
    }

    fun toggleFavorite(proxy: MtProxy) {
        val updated = proxies.map {
            if (it.cacheKey == proxy.cacheKey) {
                it.copy(isFavorite = !it.isFavorite)
            } else {
                it
            }
        }

        proxies = repository.sortProxies(updated)
        viewModelScope.launch { saveCache() }
    }

    private suspend fun saveCache() {
        // Serialize writes and take the newest state only after acquiring the lock.
        // JSON encoding must not block Compose or let an older save win a race.
        cacheWriteMutex.withLock {
            val snapshot = proxies
            withContext(Dispatchers.IO) { cache.saveProxies(snapshot) }
        }
    }
}
