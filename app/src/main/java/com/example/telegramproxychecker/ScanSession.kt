package com.example.telegramproxychecker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ScanSnapshot(
    val proxies: List<MtProxy> = emptyList(),
    val running: Boolean = false,
    val paused: Boolean = false,
    val checked: Int = 0,
    val total: Int = 0,
    val error: String? = null
)

/** One progress value shared by the Compose UI and foreground notification. */
internal data class ScanProgress(
    val checked: Int,
    val total: Int,
    val paused: Boolean
) {
    fun label(): String = if (total <= 0) "..." else "$checked/$total"
}

internal val ScanSnapshot.progress: ScanProgress
    get() = ScanProgress(checked = checked, total = total, paused = paused)

/** Shared scan state; the service owns the bulk check, the ViewModel only observes. */
object ScanSession {
    val repository = ProxyRepository()
    private val mutableState = MutableStateFlow(ScanSnapshot())
    val state: StateFlow<ScanSnapshot> = mutableState.asStateFlow()
    private val resumeGate = MutableStateFlow(true)
    private val diskMutex = Mutex()
    private var cacheLoaded = false
    // Only changed rows are committed; the shared SQLite store persists each
    // result transactionally instead of rewriting thousands of old records.
    private var lastPersisted = emptyMap<String, MtProxy>()

    suspend fun loadCache(context: Context) {
        diskMutex.withLock {
            if (cacheLoaded) return
            val saved = withContext(Dispatchers.IO) {
                ProxyCache(context.applicationContext).loadProxies()
            }
            mutableState.update { old ->
                if (old.proxies.isEmpty()) old.copy(proxies = repository.sortProxies(saved)) else old
            }
            lastPersisted = saved.associateBy { it.cacheKey }
            cacheLoaded = true
        }
    }

    suspend fun saveCache(context: Context) {
        diskMutex.withLock {
            val changed = mutableState.value.proxies.filter {
                lastPersisted[it.cacheKey] != it
            }
            if (changed.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    ProxyCache(context.applicationContext).saveProxies(changed)
                }
                lastPersisted = lastPersisted + changed.associateBy { it.cacheKey }
            }
        }
    }

    fun start() {
        resumeGate.value = true
        mutableState.update { it.copy(running = true, paused = false, checked = 0, total = 0, error = null) }
    }

    fun pause() {
        if (!mutableState.value.running) return
        resumeGate.value = false
        mutableState.update { it.copy(paused = true) }
    }

    fun resume() {
        resumeGate.value = true
        mutableState.update { it.copy(paused = false) }
    }

    suspend fun awaitResume() { resumeGate.first { it } }

    fun progress(checked: Int, total: Int) {
        mutableState.update { it.copy(checked = checked, total = total) }
    }

    fun updateProxies(snapshot: List<MtProxy>) {
        mutableState.update { old ->
            old.copy(proxies = repository.sortProxies(snapshot.withFavoritesFrom(old.proxies)))
        }
    }

    fun updateOne(proxy: MtProxy) {
        mutableState.update { old ->
            val favorite = old.proxies.firstOrNull { it.cacheKey == proxy.cacheKey }?.isFavorite
                ?: proxy.isFavorite
            old.copy(proxies = repository.sortProxies(old.proxies.map {
                if (it.cacheKey == proxy.cacheKey) proxy.copy(isFavorite = favorite) else it
            }))
        }
    }

    fun toggleFavorite(proxy: MtProxy) {
        mutableState.update { old ->
            old.copy(proxies = repository.sortProxies(old.proxies.map {
                if (it.cacheKey == proxy.cacheKey) it.copy(isFavorite = !it.isFavorite) else it
            }))
        }
    }

    fun setError(message: String) { mutableState.update { it.copy(error = message) } }
    fun finish() {
        resumeGate.value = true
        mutableState.update { it.copy(running = false, paused = false) }
    }
}
