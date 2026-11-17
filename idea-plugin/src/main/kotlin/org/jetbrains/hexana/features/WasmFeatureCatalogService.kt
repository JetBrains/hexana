package org.jetbrains.hexana.features

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.shared.features.CachedCatalog
import org.jetbrains.hexana.shared.features.WASM_FEATURES_URL
import org.jetbrains.hexana.shared.features.WasmFeatureCatalog
import org.jetbrains.hexana.shared.features.resolveCatalog
import org.jetbrains.hexana.shared.features.shouldRefresh
import java.net.HttpURLConnection
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Supplies the WebAssembly feature catalog that the runtime compatibility table is built
 * from, keeping a disk cache of the upstream `features.json` up to date.
 *
 * Reads never block on the network: [catalog] answers immediately from the cache (or, on a
 * first run or a failed download, from the snapshot committed in `plugins-shared`), and
 * kicks off a background refresh when the cache is past its TTL. That is enough for a file
 * the upstream project changes a few times a month, and it means opening a `.wasm` file
 * never waits on GitHub.
 *
 * Revalidation uses a conditional GET rather than a HEAD probe: replaying the stored `ETag`
 * as `If-None-Match` costs one round-trip whether or not the file changed, where HEAD + GET
 * costs two when it did.
 */
@Service(Service.Level.APP)
class WasmFeatureCatalogService {

    private val store = WasmFeatureCatalogStore(defaultCacheRoot())
    private val refreshInFlight = AtomicBoolean(false)

    @Volatile
    private var cached: CachedCatalog? = null

    @Volatile
    private var resolved: WasmFeatureCatalog? = null

    private val _changes = MutableStateFlow(0)

    /**
     * Increments whenever a refresh replaces the catalog, so a badge or a Compatibility tab
     * that is already open re-evaluates against the new support data instead of waiting for
     * a target change or a reopen. A background download that lands silently would otherwise
     * leave the verdicts on screen stale.
     */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /**
     * The catalog to render right now. Never `null` and never throws: the committed snapshot
     * is the floor, so the compatibility table works offline and on a first run.
     */
    val catalog: WasmFeatureCatalog
        get() {
            val current = resolved ?: loadFromDisk()
            scheduleRefreshIfStale()
            return current
        }

    /** Refreshes regardless of TTL; used by the settings page's explicit action. */
    fun refreshNow() {
        launchRefresh()
    }

    private fun loadFromDisk(): WasmFeatureCatalog {
        synchronized(this) {
            resolved?.let { return it }
            val fromDisk = try {
                store.read()
            } catch (e: Exception) {
                LOG.warn("Failed to read the cached WebAssembly feature catalog", e)
                null
            }
            cached = fromDisk
            return resolveCatalog(fromDisk).also { resolved = it }
        }
    }

    private fun scheduleRefreshIfStale() {
        if (!shouldRefresh(cached, System.currentTimeMillis())) return
        launchRefresh()
    }

    private fun launchRefresh() {
        val application = ApplicationManager.getApplication()
        // A unit test or a headless run must not reach the network; both fall back to the
        // committed snapshot, which is also what the tests assert against.
        if (application == null || application.isUnitTestMode || application.isHeadlessEnvironment) return
        // Collapses concurrent callers - several editors opening at once, or a user clicking
        // the settings button repeatedly - onto one request.
        if (!refreshInFlight.compareAndSet(false, true)) return

        HexanaService.getInstance().coroutineScope.launch(Dispatchers.IO) {
            try {
                refresh()
            } catch (e: Exception) {
                // A missing or stale catalog degrades the table, it does not break it, so a
                // failed refresh is logged rather than surfaced to the user.
                LOG.info("WebAssembly feature catalog refresh failed: ${e.message}")
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    private fun refresh() {
        val etag = cached?.etag
        val now = System.currentTimeMillis()

        HttpRequests.request(WASM_FEATURES_URL)
            .accept("application/json")
            .tuner { connection ->
                if (etag != null) connection.setRequestProperty("If-None-Match", etag)
            }
            .connect { request ->
                val connection = request.connection as? HttpURLConnection
                if (connection?.responseCode == HTTP_NOT_MODIFIED) {
                    // The bytes we hold are still current; only their freshness moves.
                    store.touch(now)
                    cached = cached?.let { CachedCatalog(it.json, it.etag, now) }
                    return@connect
                }
                val json = request.readString(null)
                if (json.isBlank() || WasmFeatureCatalog.parse(json) == null) {
                    LOG.info("Discarding an unparseable WebAssembly feature catalog download")
                    return@connect
                }
                val newEtag = connection?.getHeaderField("ETag")
                store.write(json, newEtag, now)
                val fresh = CachedCatalog(json, newEtag, now)
                cached = fresh
                resolved = resolveCatalog(fresh)
                // Announced only here: a 304 and a discarded download both leave the bytes we
                // already render, and neither is worth a recomposition.
                _changes.value++
            }
    }

    companion object {
        private val LOG = Logger.getInstance(WasmFeatureCatalogService::class.java)

        private const val HTTP_NOT_MODIFIED = 304

        fun getInstance(): WasmFeatureCatalogService = service()

        /**
         * Sits under the same `hexana/` cache root as the OSV database and the Wasm binary
         * cache, so all of Hexana's derived data is in one place.
         */
        fun defaultCacheRoot(): Path = Path.of(PathManager.getSystemPath(), "hexana", "wasm-features")
    }
}
