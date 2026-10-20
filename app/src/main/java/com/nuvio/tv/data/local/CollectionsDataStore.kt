package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionCatalogSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.domain.model.TraktListSort
import com.nuvio.tv.domain.model.TraktSortHow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ValidationResult(
    val valid: Boolean,
    val error: String? = null,
    val collectionCount: Int = 0,
    val folderCount: Int = 0
)

@Singleton
class CollectionsDataStore @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "collections"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()
    private val collectionsKey = stringPreferencesKey("collections_json")
    private fun string(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    val collections: Flow<List<Collection>> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs ->
                parseCollections(prefs[collectionsKey])
            }
        }

    suspend fun setCollections(collections: List<Collection>) {
        store().edit { prefs ->
            if (collections.isEmpty()) {
                prefs.remove(collectionsKey)
            } else {
                prefs[collectionsKey] = gson.toJson(collections.map { it.toSerializable() })
            }
        }
    }

    suspend fun addCollection(collection: Collection) {
        store().edit { prefs ->
            val current = parseCollections(prefs[collectionsKey]).toMutableList()
            current.add(collection)
            prefs[collectionsKey] = gson.toJson(current.map { it.toSerializable() })
        }
    }

    suspend fun updateCollection(collection: Collection) {
        store().edit { prefs ->
            val current = parseCollections(prefs[collectionsKey]).toMutableList()
            val index = current.indexOfFirst { it.id == collection.id }
            if (index >= 0) {
                current[index] = collection
            }
            prefs[collectionsKey] = gson.toJson(current.map { it.toSerializable() })
        }
    }

    suspend fun removeCollection(collectionId: String) {
        store().edit { prefs ->
            val current = parseCollections(prefs[collectionsKey]).toMutableList()
            current.removeAll { it.id == collectionId }
            if (current.isEmpty()) {
                prefs.remove(collectionsKey)
            } else {
                prefs[collectionsKey] = gson.toJson(current.map { it.toSerializable() })
            }
        }
    }

    fun generateId(): String = UUID.randomUUID().toString()

    fun exportToJson(collections: List<Collection>): String {
        return gson.toJson(collections.map { it.toSerializable() })
    }

    fun importFromJson(json: String): List<Collection> {
        return parseCollections(json)
    }

    suspend fun getCurrentCollections(): List<Collection> {
        val prefs = store().data.first()
        return parseCollections(prefs[collectionsKey])
    }

    suspend fun exportCurrentProfileJson(): String? {
        val prefs = store().data.first()
        return prefs[collectionsKey]
    }

    fun validateCollectionsJson(json: String): ValidationResult {
        if (json.isBlank()) return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_empty_input))
        return try {
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val parsed = gson.fromJson<List<Map<String, Any?>>>(json, type)
                ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_expected_array))
            if (parsed.isEmpty()) return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_empty_array))

            var folderCount = 0
            val validShapes = setOf("POSTER", "LANDSCAPE", "SQUARE", "poster", "wide", "square")

            for ((i, item) in parsed.withIndex()) {
                val id = item["id"] as? String
                if (id.isNullOrBlank()) return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_missing_id, i + 1))
                val title = item["title"] as? String
                    ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_missing_title, id))
                val folders = item["folders"] as? List<*>
                    ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_folders_array, title))

                for ((j, f) in folders.withIndex()) {
                    val folder = f as? Map<*, *>
                        ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_folder_invalid, title, j + 1))
                    val folderId = folder["id"] as? String
                    if (folderId.isNullOrBlank()) return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_folder_missing_id, title, j + 1))
                    val folderTitle = folder["title"] as? String
                        ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_folder_missing_title, title, folderId))
                    val sources = (folder["sources"] as? List<*>) ?: (folder["catalogSources"] as? List<*>)
                        ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_sources_array, title, folderTitle))
                    val shape = folder["tileShape"] as? String
                    if (shape != null && shape !in validShapes) {
                        return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_invalid_tile_shape, title, folderTitle, shape))
                    }
                    for ((k, s) in sources.withIndex()) {
                        val source = s as? Map<*, *>
                            ?: return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_source_invalid, title, folderTitle, k + 1))
                        val provider = (source["provider"] as? String)?.lowercase()
                        val isAddonSource = provider == null || provider == "addon"
                        val isTmdbSource = provider == "tmdb"
                        val isTraktSource = provider == "trakt"
                        if (isAddonSource && (source["addonId"] !is String || source["type"] !is String || source["catalogId"] !is String)) {
                            return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_source_required_fields, title, folderTitle, k + 1))
                        }
                        if (isTmdbSource && source["tmdbSourceType"] !is String) {
                            return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_missing_tmdb_type, title, folderTitle, k + 1))
                        }
                        if (isTraktSource && (source["traktListId"] as? Number)?.toLong() == null) {
                            return ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_missing_trakt_list_id, title, folderTitle, k + 1))
                        }
                    }
                    folderCount++
                }
            }
            ValidationResult(true, collectionCount = parsed.size, folderCount = folderCount)
        } catch (e: Exception) {
            ValidationResult(false, appContext.getString(com.nuvio.tv.R.string.collections_import_error_json_parse, e.message.orEmpty()))
        }
    }

    /**
     * Lecture tolérante (collections produites par des outils externes comme Kaptain) :
     * chaque collection, dossier et source est lu séparément. Un élément mal formé ou d'un
     * type inattendu est ignoré au lieu de faire disparaître TOUTE la configuration.
     */
    private fun parseCollections(json: String?): List<Collection> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { com.google.gson.JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.get("collections")?.isJsonArray == true ->
                root.asJsonObject.getAsJsonArray("collections")
            else -> return emptyList()
        }
        return array.mapNotNull { element -> runCatching { parseCollectionLenient(element) }.getOrNull() }
            // Dédoublonnage par ID (clés LazyColumn uniques).
            .associateBy { it.id }
            .values.toList()
    }

    private fun com.google.gson.JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun com.google.gson.JsonObject.bool(key: String): Boolean? {
        val v = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            v.isBoolean -> v.asBoolean
            v.isNumber -> v.asInt != 0
            else -> when (v.asString.trim().lowercase()) { "true", "1", "yes" -> true; "false", "0", "no" -> false; else -> null }
        }
    }

    private fun parseCollectionLenient(element: com.google.gson.JsonElement): Collection? {
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val folders = obj.get("folders")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { folder -> runCatching { parseFolderLenient(folder) }.getOrNull() }
            .orEmpty()
        val title = obj.str("title") ?: obj.str("name") ?: return null
        val collection = SerializableCollection(
            id = obj.str("id") ?: "c-" + title.hashCode().toUInt().toString(16),
            title = title,
            backdropImageUrl = obj.str("backdropImageUrl"),
            pinToTop = obj.bool("pinToTop") ?: false,
            focusGlowEnabled = obj.bool("focusGlowEnabled"),
            viewMode = obj.str("viewMode") ?: "TABBED_GRID",
            showAllTab = obj.bool("showAllTab") ?: true,
            folders = emptyList()
        )
        return collection.toDomain().copy(folders = folders)
    }

    private fun parseFolderLenient(element: com.google.gson.JsonElement): CollectionFolder? {
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val title = obj.str("title") ?: obj.str("name") ?: ""
        val sources = obj.get("sources")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { src -> runCatching { gson.fromJson(src, SerializableSource::class.java)?.toDomainSource() }.getOrNull() }
        val catalogSources = obj.get("catalogSources")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { src -> runCatching { gson.fromJson(src, SerializableCatalogSource::class.java) }.getOrNull() }
            ?.filter { it.addonId != null && it.type != null && it.catalogId != null }
            .orEmpty()
        return CollectionFolder(
            id = obj.str("id") ?: "f-" + (title + obj.toString().length).hashCode().toUInt().toString(16),
            title = title,
            coverImageUrl = obj.str("coverImageUrl"),
            focusGifUrl = obj.str("focusGifUrl"),
            focusGifEnabled = obj.bool("focusGifEnabled") ?: true,
            coverEmoji = obj.str("coverEmoji"),
            tileShape = PosterShape.fromString(obj.str("tileShape")),
            hideTitle = obj.bool("hideTitle") ?: false,
            heroBackdropUrl = obj.str("heroBackdropUrl"),
            heroVideoUrl = obj.str("heroVideoUrl"),
            titleLogoUrl = obj.str("titleLogoUrl"),
            sources = sources?.takeIf { it.isNotEmpty() || catalogSources.isEmpty() }
                ?: catalogSources.map { AddonCatalogCollectionSource(it.addonId, it.type, it.catalogId, it.genre) }
        )
    }

    @androidx.annotation.Keep
    private data class SerializableCollection(
        val id: String,
        val title: String,
        val backdropImageUrl: String? = null,
        val pinToTop: Boolean = false,
        val focusGlowEnabled: Boolean? = null,
        val viewMode: String = "TABBED_GRID",
        val showAllTab: Boolean = true,
        val folders: List<SerializableFolder> = emptyList()
    )

    @androidx.annotation.Keep
    private data class SerializableFolder(
        val id: String,
        val title: String,
        val coverImageUrl: String? = null,
        val focusGifUrl: String? = null,
        val focusGifEnabled: Boolean? = null,
        val coverEmoji: String? = null,
        val tileShape: String = "SQUARE",
        val hideTitle: Boolean = false,
        val sources: List<SerializableSource>? = null,
        val catalogSources: List<SerializableCatalogSource> = emptyList(),
        val heroBackdropUrl: String? = null,
        val heroVideoUrl: String? = null,
        val titleLogoUrl: String? = null
    )

    @androidx.annotation.Keep
    private data class SerializableSource(
        val provider: String = "addon",
        val addonId: String? = null,
        val type: String? = null,
        val catalogId: String? = null,
        val genre: String? = null,
        val tmdbSourceType: String? = null,
        val title: String? = null,
        val tmdbId: Int? = null,
        val traktListId: Long? = null,
        val mediaType: String? = null,
        val sortBy: String? = null,
        val sortHow: String? = null,
        val filters: SerializableTmdbFilters? = null
    )

    @androidx.annotation.Keep
    private data class SerializableTmdbFilters(
        val withGenres: String? = null,
        val withoutGenres: String? = null,
        val releaseDateGte: String? = null,
        val releaseDateLte: String? = null,
        val voteAverageGte: Double? = null,
        val voteAverageLte: Double? = null,
        val voteCountGte: Int? = null,
        val withOriginalLanguage: String? = null,
        val withOriginCountry: String? = null,
        val withKeywords: String? = null,
        val withoutKeywords: String? = null,
        val withCompanies: String? = null,
        val withoutCompanies: String? = null,
        val withNetworks: String? = null,
        val year: Int? = null,
        val watchRegion: String? = null,
        val withWatchProviders: String? = null,
        val withoutWatchProviders: String? = null
    )

    @androidx.annotation.Keep
    private data class SerializableCatalogSource(
        val addonId: String,
        val type: String,
        val catalogId: String,
        val genre: String? = null
    )

    private fun Collection.toSerializable() = SerializableCollection(
        id = id,
        title = title,
        backdropImageUrl = backdropImageUrl,
        pinToTop = pinToTop,
        focusGlowEnabled = focusGlowEnabled,
        viewMode = viewMode.name,
        showAllTab = showAllTab,
        folders = folders.map { folder ->
            SerializableFolder(
                id = folder.id,
                title = folder.title,
                coverImageUrl = folder.coverImageUrl,
                focusGifUrl = folder.focusGifUrl,
                focusGifEnabled = folder.focusGifEnabled,
                coverEmoji = folder.coverEmoji,
                tileShape = folder.tileShape.name,
                hideTitle = folder.hideTitle,
                heroBackdropUrl = folder.heroBackdropUrl,
                heroVideoUrl = folder.heroVideoUrl,
                titleLogoUrl = folder.titleLogoUrl,
                sources = folder.sources.map { it.toSerializableSource() },
                catalogSources = folder.catalogSources.map { source ->
                    SerializableCatalogSource(
                        addonId = source.addonId,
                        type = source.type,
                        catalogId = source.catalogId,
                        genre = source.genre
                    )
                }
            )
        }
    )

    private fun CollectionSource.toSerializableSource(): SerializableSource {
        return when (this) {
            is AddonCatalogCollectionSource -> SerializableSource(
                provider = "addon",
                addonId = addonId,
                type = type,
                catalogId = catalogId,
                genre = genre
            )
            is TmdbCollectionSource -> SerializableSource(
                provider = "tmdb",
                tmdbSourceType = sourceType.name,
                title = title,
                tmdbId = tmdbId,
                mediaType = mediaType.name,
                sortBy = sortBy,
                filters = filters.toSerializable()
            )
            is TraktCollectionSource -> SerializableSource(
                provider = "trakt",
                title = title,
                traktListId = traktListId,
                mediaType = mediaType.name,
                sortBy = sortBy,
                sortHow = sortHow
            )
        }
    }

    private fun TmdbCollectionFilters.toSerializable() = SerializableTmdbFilters(
        withGenres = withGenres,
        withoutGenres = withoutGenres,
        releaseDateGte = releaseDateGte,
        releaseDateLte = releaseDateLte,
        voteAverageGte = voteAverageGte,
        voteAverageLte = voteAverageLte,
        voteCountGte = voteCountGte,
        withOriginalLanguage = withOriginalLanguage,
        withOriginCountry = withOriginCountry,
        withKeywords = withKeywords,
        withoutKeywords = withoutKeywords,
        withCompanies = withCompanies,
        withoutCompanies = withoutCompanies,
        withNetworks = withNetworks,
        year = year,
        watchRegion = watchRegion,
        withWatchProviders = withWatchProviders,
        withoutWatchProviders = withoutWatchProviders
    )

    private fun SerializableCollection.toDomain() = Collection(
        id = id,
        title = title,
        backdropImageUrl = backdropImageUrl,
        pinToTop = pinToTop,
        focusGlowEnabled = focusGlowEnabled ?: true,
        viewMode = FolderViewMode.fromString(viewMode),
        showAllTab = showAllTab,
        folders = folders.map { folder ->
            CollectionFolder(
                id = folder.id,
                title = folder.title,
                coverImageUrl = folder.coverImageUrl,
                focusGifUrl = folder.focusGifUrl,
                focusGifEnabled = folder.focusGifEnabled ?: true,
                coverEmoji = folder.coverEmoji,
                tileShape = PosterShape.fromString(folder.tileShape),
                hideTitle = folder.hideTitle,
                heroBackdropUrl = folder.heroBackdropUrl,
                heroVideoUrl = folder.heroVideoUrl,
                titleLogoUrl = folder.titleLogoUrl,
                sources = folder.sources?.mapNotNull { it.toDomainSource() }
                    ?: folder.catalogSources.map { source ->
                        AddonCatalogCollectionSource(
                            addonId = source.addonId,
                            type = source.type,
                            catalogId = source.catalogId,
                            genre = source.genre
                        )
                    }
            )
        }
    )

    private fun SerializableSource.toDomainSource(): CollectionSource? {
        return when (provider.lowercase()) {
            "tmdb" -> {
                val type = tmdbSourceType?.let { raw ->
                    runCatching { TmdbCollectionSourceType.valueOf(raw.trim().uppercase()) }.getOrNull()
                } ?: return null
                val sourceSortBy = sortBy?.takeIf { it.isNotBlank() } ?: TmdbCollectionSort.POPULAR_DESC.value
                val normalizedSortBy = if (
                    type in setOf(TmdbCollectionSourceType.LIST, TmdbCollectionSourceType.COLLECTION) &&
                    sourceSortBy == TmdbCollectionSort.POPULAR_DESC.value
                ) {
                    TmdbCollectionSort.ORIGINAL.value
                } else {
                    sourceSortBy
                }
                TmdbCollectionSource(
                    sourceType = type,
                    title = title?.takeIf { it.isNotBlank() } ?: type.name.lowercase().replaceFirstChar { it.uppercase() },
                    tmdbId = tmdbId,
                    mediaType = parseMediaType(mediaType),
                    sortBy = normalizedSortBy,
                    filters = filters?.toDomain() ?: TmdbCollectionFilters()
                )
            }
            "trakt" -> {
                val id = traktListId?.takeIf { it > 0L } ?: return null
                TraktCollectionSource(
                    title = title?.takeIf { it.isNotBlank() }
                        ?: string(R.string.collections_editor_trakt_list_with_id, id),
                    traktListId = id,
                    mediaType = parseMediaType(mediaType),
                    sortBy = TraktListSort.normalize(sortBy),
                    sortHow = TraktSortHow.normalize(sortHow)
                )
            }
            else -> {
                val sourceAddonId = addonId?.takeIf { it.isNotBlank() } ?: return null
                val sourceType = type?.takeIf { it.isNotBlank() } ?: return null
                val sourceCatalogId = catalogId?.takeIf { it.isNotBlank() } ?: return null
                AddonCatalogCollectionSource(
                    addonId = sourceAddonId,
                    type = sourceType,
                    catalogId = sourceCatalogId,
                    genre = genre
                )
            }
        }
    }

    /** « series » est le type Stremio/Kaptain des séries : il doit donner TV, pas MOVIE. */
    private fun parseMediaType(raw: String?): TmdbCollectionMediaType =
        when (raw?.trim()?.lowercase()) {
            "tv", "series", "show", "shows" -> TmdbCollectionMediaType.TV
            else -> TmdbCollectionMediaType.MOVIE
        }

    private fun SerializableTmdbFilters.toDomain() = TmdbCollectionFilters(
        withGenres = withGenres,
        withoutGenres = withoutGenres,
        releaseDateGte = releaseDateGte,
        releaseDateLte = releaseDateLte,
        voteAverageGte = voteAverageGte,
        voteAverageLte = voteAverageLte,
        voteCountGte = voteCountGte,
        withOriginalLanguage = withOriginalLanguage,
        withOriginCountry = withOriginCountry,
        withKeywords = withKeywords,
        withoutKeywords = withoutKeywords,
        withCompanies = withCompanies,
        withoutCompanies = withoutCompanies,
        withNetworks = withNetworks,
        year = year,
        watchRegion = watchRegion,
        withWatchProviders = withWatchProviders,
        withoutWatchProviders = withoutWatchProviders
    )
}
