package com.nuvio.tv.core.torrent

import com.nuvio.engine.NuvioEngineConfig
import com.nuvio.engine.NuvioTorrentProfile
import com.nuvio.engine.NuvioUploadMode
import java.io.File

internal fun buildTorrentEngineConfig(
    stateDirectory: File,
    cacheDirectory: File,
    uploadEnabled: Boolean,
    torrentProfile: TorrentProfile,
    diskCacheCapacityBytes: Long,
    memoryCacheCapacityBytes: Long = 64L * 1024L * 1024L,
    listenPort: Int = 0
): NuvioEngineConfig = NuvioEngineConfig(
    listenPort = listenPort,
    dataDirectory = stateDirectory,
    cacheDirectory = cacheDirectory,
    memoryCacheCapacityBytes = memoryCacheCapacityBytes,
    diskCacheCapacityBytes = diskCacheCapacityBytes,
    torrentProfile = when (torrentProfile) {
        TorrentProfile.SOFT -> NuvioTorrentProfile.Soft
        TorrentProfile.BALANCED -> NuvioTorrentProfile.Balanced
        TorrentProfile.FAST -> NuvioTorrentProfile.Fast
    },
    // Upload plafonné : réciprocité conservée sans saturer la voie montante (qui étranglerait
    // les ACK TCP et donc le débit descendant).
    uploadMode = if (uploadEnabled) NuvioUploadMode.Limited else NuvioUploadMode.Disabled,
    uploadLimitBytesPerSecond = if (uploadEnabled) 512L * 1024L else 0L,
    streamInactivityTimeoutMilliseconds = 0,
    // Torrent gardé « chaud » 10 min : changement d'épisode / reprise sans redécouverte des pairs.
    warmTorrentTimeoutMilliseconds = 10 * 60 * 1000
)

/** Cache mémoire du moteur proportionnel à la RAM (64 → 256 Mo). */
internal fun torrentMemoryCacheBytesFor(context: android.content.Context): Long {
    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
    val total = am?.let { android.app.ActivityManager.MemoryInfo().also(it::getMemoryInfo).totalMem } ?: 0L
    val gb = 1024L * 1024L * 1024L
    val mb = 1024L * 1024L
    return when {
        total < 3L * gb -> 64L * mb
        total < 5L * gb -> 128L * mb
        else -> 256L * mb
    }
}

internal fun migrateNestedPayloadDirectory(cacheDirectory: File) {
    val payloadDirectory = File(cacheDirectory, "payload")
    val nestedDirectory = File(payloadDirectory, "payload")
    val entries = nestedDirectory.listFiles() ?: return
    entries.forEach { entry ->
        val target = File(payloadDirectory, entry.name)
        if (target.exists() || !entry.renameTo(target)) {
            entry.deleteRecursively()
        }
    }
    nestedDirectory.deleteRecursively()
}

internal fun unexpectedStreamStopError(
    requestId: Long,
    eventStreamId: String?,
    currentStreamId: String?,
    message: String?,
    fallbackMessage: String
): TorrentState.Error? {
    if (requestId != 0L || currentStreamId == null || eventStreamId != currentStreamId) {
        return null
    }
    return TorrentState.Error(message?.trim()?.takeIf(String::isNotEmpty) ?: fallbackMessage)
}

internal fun unexpectedTorrentError(
    requestId: Long,
    eventTorrentId: String?,
    currentTorrentId: String?,
    message: String?,
    fallbackMessage: String
): TorrentState.Error? {
    if (requestId != 0L ||
        eventTorrentId == null ||
        currentTorrentId == null ||
        eventTorrentId != currentTorrentId
    ) {
        return null
    }
    return TorrentState.Error(message?.trim()?.takeIf(String::isNotEmpty) ?: fallbackMessage)
}
