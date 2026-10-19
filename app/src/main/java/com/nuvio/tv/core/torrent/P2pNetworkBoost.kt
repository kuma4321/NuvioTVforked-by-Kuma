package com.nuvio.tv.core.torrent

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optimisations réseau autour du moteur P2P (bibliothèque fermée) :
 *  1. Port d'écoute STABLE par appareil : la table DHT et les pairs déjà connus restent valides
 *     d'une session à l'autre et les connexions entrantes (UPnP / NAT déjà ouvert) fonctionnent,
 *     contrairement à un port aléatoire à chaque démarrage.
 *  2. Liste de trackers FRAÎCHE : téléchargée en arrière-plan (cache 24 h) depuis la liste
 *     communautaire « trackers_best », fusionnée avec la liste intégrée. Plus de trackers
 *     vivants = plus de pairs trouvés dans les premières secondes.
 *  3. Verrous réseau pendant le streaming : WifiLock haute performance / faible latence
 *     (le mode économie d'énergie Wi-Fi bride fortement le trafic P2P fait de nombreux petits
 *     paquets) + WakeLock partiel pour que le CPU continue de servir les pièces écran éteint.
 */
internal object P2pNetworkBoost {
    private const val TAG = "P2pNetworkBoost"
    private const val TRACKERS_URL =
        "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
    private const val TRACKERS_CACHE_FILE = "p2p-trackers-best.txt"
    private const val TRACKERS_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private const val MAX_DYNAMIC_TRACKERS = 30

    private val refreshing = AtomicBoolean(false)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Port déterministe par installation, dans la plage dynamique (49160–65000). */
    @SuppressLint("HardwareIds")
    fun stableListenPort(context: Context): Int {
        val seed = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: context.packageName
        val span = 65_000 - 49_160
        return 49_160 + Math.floorMod(seed.hashCode(), span)
    }

    /** Trackers dynamiques en cache ; déclenche un rafraîchissement en arrière-plan si périmé. */
    fun cachedDynamicTrackers(context: Context): List<String> {
        val file = File(context.cacheDir, TRACKERS_CACHE_FILE)
        val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > TRACKERS_MAX_AGE_MS
        if (stale) refreshTrackersAsync(file)
        return runCatching { parseTrackers(file.readText()) }.getOrDefault(emptyList())
    }

    private fun refreshTrackersAsync(target: File) {
        if (!refreshing.compareAndSet(false, true)) return
        Thread({
            try {
                val connection = (URL(TRACKERS_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4_000
                    readTimeout = 4_000
                }
                try {
                    if (connection.responseCode == 200) {
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        if (parseTrackers(body).isNotEmpty()) {
                            val tmp = File(target.parentFile, target.name + ".tmp")
                            tmp.writeText(body)
                            tmp.renameTo(target)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                Log.w(TAG, "Tracker list refresh failed: ${error.message}")
            } finally {
                refreshing.set(false)
            }
        }, "p2p-trackers-refresh").apply { isDaemon = true }.start()
    }

    private fun parseTrackers(body: String): List<String> =
        body.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("udp://") || it.startsWith("http://") || it.startsWith("https://") }
            .filter { it.length < 200 && it.endsWith("/announce") }
            .distinct()
            .take(MAX_DYNAMIC_TRACKERS)
            .toList()

    @Synchronized
    fun acquireStreamingLocks(context: Context) {
        val app = context.applicationContext
        runCatching {
            if (wifiLock == null) {
                val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifi?.createWifiLock(mode, "nuviotv:p2p")?.apply { setReferenceCounted(false) }
            }
            wifiLock?.takeIf { !it.isHeld }?.acquire()
        }.onFailure { Log.w(TAG, "WifiLock unavailable: ${it.message}") }
        runCatching {
            if (wakeLock == null) {
                val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nuviotv:p2p")
                    ?.apply { setReferenceCounted(false) }
            }
            // Filet de sécurité : relâché automatiquement après 4 h si jamais oublié.
            wakeLock?.takeIf { !it.isHeld }?.acquire(4L * 60L * 60L * 1000L)
        }.onFailure { Log.w(TAG, "WakeLock unavailable: ${it.message}") }
    }

    @Synchronized
    fun releaseStreamingLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }
}
