package com.example.telegramproxychecker

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class ProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProxyRepository()
    private val cache = ProxyCache(application.applicationContext)

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

        val cached = cache.loadProxies()

        if (cached.isNotEmpty()) {
            proxies = repository.sortProxies(cached)
        }

        refresh(force = false)
    }

    fun refresh(force: Boolean = false) {
        if (isLoading) return

        viewModelScope.launch {
            isLoading = true
            error = null
            checkedCount = 0
            totalCount = 0

            val cached = cache.loadProxies()

            if (proxies.isEmpty() && cached.isNotEmpty()) {
                proxies = repository.sortProxies(cached)
            }

            val currentBeforeRefresh = proxies

            val baseForCache = if (currentBeforeRefresh.isNotEmpty()) {
                currentBeforeRefresh
            } else {
                cached
            }

            try {
                val result = repository.loadAndCheckProxies(
                    cachedProxies = baseForCache,
                    force = force
                ) { checked, total ->
                    checkedCount = checked
                    totalCount = total
                }

                val resultWithFavorites = applyFavorites(
                    freshList = result,
                    oldList = baseForCache
                )

                proxies = repository.sortProxies(resultWithFavorites)
                cache.saveProxies(proxies)

            } catch (e: Exception) {
                error = if (proxies.isNotEmpty()) {
                    "Не удалось обновить. Показан сохранённый список."
                } else {
                    e.message ?: "Ошибка загрузки"
                }
            }

            isLoading = false
        }
    }

    fun recheckProxy(proxy: MtProxy) {
        if (checkingProxyKeys.contains(proxy.cacheKey)) return

        checkingProxyKeys = checkingProxyKeys + proxy.cacheKey

        viewModelScope.launch {
            try {
                val checked = repository.recheckOneProxy(proxy)

                val checkedWithFavorite = checked.copy(
                    isFavorite = proxy.isFavorite
                )

                val updated = proxies.map {
                    if (it.cacheKey == proxy.cacheKey) {
                        checkedWithFavorite
                    } else {
                        it
                    }
                }

                proxies = repository.sortProxies(updated)
                cache.saveProxies(proxies)

            } catch (e: Exception) {
                error = e.message ?: "Ошибка точечной проверки"
            }

            checkingProxyKeys = checkingProxyKeys - proxy.cacheKey
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
        cache.saveProxies(proxies)
    }

    private fun applyFavorites(
        freshList: List<MtProxy>,
        oldList: List<MtProxy>
    ): List<MtProxy> {
        val favoriteKeys = oldList
            .filter { it.isFavorite }
            .map { it.cacheKey }
            .toSet()

        return freshList.map { proxy ->
            proxy.copy(
                isFavorite = proxy.isFavorite || proxy.cacheKey in favoriteKeys
            )
        }
    }
}