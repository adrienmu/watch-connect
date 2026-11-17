package com.boucle.app.ui.map

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/*
 * Fond de carte en tuiles raster OpenStreetMap, dessinées par Compose : le tracé, les repères et toutes les
 * animations (dessin du tracé, transformation vers le plein écran, relecture) restent ainsi dans la même toile.
 * Pas de clé d'API ; les tuiles sont gardées en mémoire et sur le disque, comme le demande la politique d'usage
 * d'OpenStreetMap.
 */

/** Style de fond de carte. */
enum class MapStyle(val label: String, val urlPattern: String, val maxZoom: Int, val attribution: String) {
    Plan("Plan", "https://tile.openstreetmap.org/%d/%d/%d.png", 19, "© OpenStreetMap"),
    Relief("Relief", "https://tile.opentopomap.org/%d/%d/%d.png", 17, "© OpenStreetMap · OpenTopoMap"),
}

/** Coordonnées Web Mercator normalisées : x et y entre 0 et 1, y vers le sud. */
object Mercator {
    fun x(longitude: Double): Double = (longitude + 180.0) / 360.0

    fun y(latitude: Double): Double {
        val lat = latitude.coerceIn(-85.05, 85.05) * PI / 180
        return (1 - ln(tan(lat) + 1 / kotlin.math.cos(lat)) / PI) / 2
    }
}

data class TileKey(val style: MapStyle, val zoom: Int, val x: Int, val y: Int)

/**
 * Tuiles chargées, observables par Compose : une tuile qui arrive redessine la carte. Les téléchargements sont
 * limités à 4 en parallèle ; une tuile en échec sera redemandée au prochain affichage.
 */
@Stable
class TileCache(context: Context) {
    private val dir = File(context.cacheDir, "map-tiles")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(4)
    private val loading = HashMap<TileKey, Job>()
    private val wantedBy = HashMap<Any, Set<TileKey>>()
    private val memory = LruCache<TileKey, ImageBitmap>(MEMORY_TILES)

    /** Tuiles prêtes à dessiner (miroir observable du cache mémoire). */
    val ready = mutableStateMapOf<TileKey, ImageBitmap>()

    fun get(key: TileKey): ImageBitmap? = ready[key]

    /**
     * Tuiles dont [owner] (une carte affichée) a besoin : charge celles qui manquent et abandonne celles que plus
     * aucune carte ne demande. À appeler depuis le fil principal.
     */
    fun request(owner: Any, keys: Collection<TileKey>) {
        wantedBy[owner] = keys.toSet()
        val wanted = wantedBy.values.flatten().toSet()
        val iterator = loading.entries.iterator()
        while (iterator.hasNext()) {
            val (key, job) = iterator.next()
            if (!job.isActive) {
                iterator.remove()
            } else if (key !in wanted) {
                job.cancel()
                iterator.remove()
            }
        }
        keys.forEach { key ->
            if (ready.containsKey(key) || loading.containsKey(key)) return@forEach
            memory.get(key)?.let {
                ready[key] = it
                return@forEach
            }
            loading[key] = scope.launch {
                val bitmap = permits.withPermit { load(key) } ?: return@launch
                memory.put(key, bitmap)
                launch(Dispatchers.Main) {
                    ready[key] = bitmap
                    // Le miroir observable reste de la taille du cache mémoire.
                    if (ready.size > MEMORY_TILES) ready.keys.filter { memory.get(it) == null }.forEach(ready::remove)
                }
            }
        }
    }

    private fun load(key: TileKey): ImageBitmap? {
        val file = File(dir, "${key.style.name}/${key.zoom}/${key.x}/${key.y}.png")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_MAX_AGE_MS
        if (fresh) decode(file)?.let { return it }
        return try {
            val connection = URL(key.style.urlPattern.format(key.zoom, key.x, key.y)).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return if (file.exists()) decode(file) else null
                val bytes = connection.inputStream.use { it.readBytes() }
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } finally {
                connection.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Hors ligne : une tuile périmée vaut mieux que rien.
            if (file.exists()) decode(file) else null
        }
    }

    private fun decode(file: File): ImageBitmap? = runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()

    /** La carte [owner] n'est plus affichée. */
    fun release(owner: Any) {
        wantedBy.remove(owner)
    }

    fun close() = scope.cancel()

    private companion object {
        const val MEMORY_TILES = 96
        const val CACHE_MAX_AGE_MS = 7L * 24 * 3600 * 1000
        /** OpenStreetMap demande d'identifier l'app qui télécharge les tuiles. */
        const val USER_AGENT = "Boucle/0.1 (Android; https://github.com/adrienmu/watch-connect)"
    }
}

@Composable
fun rememberTileCache(): TileCache {
    val context = LocalContext.current.applicationContext
    val cache = remember(context) { TileCache(context) }
    DisposableEffect(cache) { onDispose { cache.close() } }
    return cache
}
