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

            try {
                val result = repository.loadAndCheckProxies(
                    cachedProxies = cached,
                    force = force
                ) { checked, total ->
                    checkedCount = checked
                    totalCount = total
                }

                proxies = result
                cache.saveProxies(result)
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
}