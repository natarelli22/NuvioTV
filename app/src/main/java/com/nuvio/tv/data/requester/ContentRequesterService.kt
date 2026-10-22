package com.nuvio.tv.data.requester

import android.util.Log
import com.nuvio.tv.data.requester.model.MediaRequesterStatus
import com.nuvio.tv.data.requester.model.RequesterServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private const val TAG = "ContentRequesterService"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

@Singleton
class ContentRequesterService @Inject constructor(
    @Named("addonPermissive") private val okHttpClient: OkHttpClient
) {

    private fun normalizeUrl(url: String): String =
        url.trim().trimEnd('/')

    suspend fun testConnection(server: RequesterServerConfig): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeUrl(server.url)
        if (baseUrl.isBlank()) return@withContext Result.failure(IllegalArgumentException("URL inválida"))
        val endpoint = "$baseUrl/api/v3/system/status"
        try {
            val request = Request.Builder()
                .url(endpoint)
                .header("X-Api-Key", server.apiKey.trim())
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao testar conexão com $baseUrl", e)
            Result.failure(e)
        }
    }

    // =========================================================================
    // RADARR (FILMES)
    // =========================================================================

    suspend fun checkMovieStatus(
        servers: List<RequesterServerConfig>,
        imdbId: String?,
        tmdbId: String?,
        title: String?
    ): MediaRequesterStatus = withContext(Dispatchers.IO) {
        val enabledServers = servers.filter { it.enabled && it.url.isNotBlank() }
        if (enabledServers.isEmpty()) return@withContext MediaRequesterStatus.Disabled

        for (server in enabledServers) {
            val baseUrl = normalizeUrl(server.url)
            val apiKey = server.apiKey.trim()

            // 1. Checar Fila de Downloads
            val queueStatus = checkRadarrQueue(baseUrl, apiKey, server.name, imdbId, tmdbId, title)
            if (queueStatus != null) return@withContext queueStatus

            // 2. Checar Biblioteca do Radarr
            val libraryStatus = checkRadarrLibrary(baseUrl, apiKey, server.name, imdbId, tmdbId, title)
            if (libraryStatus != null) return@withContext libraryStatus
        }

        MediaRequesterStatus.NotMonitored
    }

    private fun checkRadarrQueue(
        baseUrl: String,
        apiKey: String,
        serverName: String,
        imdbId: String?,
        tmdbId: String?,
        title: String?
    ): MediaRequesterStatus? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/v3/queue?page=1&pageSize=50&includeMovie=true")
                .header("X-Api-Key", apiKey)
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val records = json.optJSONArray("records") ?: return null

            for (i in 0 until records.length()) {
                val record = records.optJSONObject(i) ?: continue
                val movie = record.optJSONObject("movie")
                val movieImdb = movie?.optString("imdbId")
                val movieTmdb = movie?.optInt("tmdbId", -1)?.toString()
                val movieTitle = movie?.optString("title") ?: record.optString("title")

                val matches = (imdbId != null && movieImdb?.equals(imdbId, ignoreCase = true) == true) ||
                    (tmdbId != null && movieTmdb == tmdbId) ||
                    (title != null && movieTitle.contains(title, ignoreCase = true))

                if (matches) {
                    val size = record.optDouble("size", 0.0).toLong()
                    val sizeleft = record.optDouble("sizeleft", 0.0).toLong()
                    val percent = if (size > 0) (((size - sizeleft) * 100) / size).toInt().coerceIn(0, 100) else null
                    val timeleft = record.optString("timeleft").takeIf { it.isNotBlank() }
                    return MediaRequesterStatus.Downloading(
                        serverName = serverName,
                        progressPercent = percent,
                        sizeLeftBytes = sizeleft,
                        totalSizeBytes = size,
                        etaFormatted = timeleft
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao checar fila Radarr ($baseUrl): ${e.message}")
            null
        }
    }

    private fun checkRadarrLibrary(
        baseUrl: String,
        apiKey: String,
        serverName: String,
        imdbId: String?,
        tmdbId: String?,
        title: String?
    ): MediaRequesterStatus? {
        return try {
            val url = when {
                !tmdbId.isNullOrBlank() -> "$baseUrl/api/v3/movie?tmdbId=$tmdbId"
                !imdbId.isNullOrBlank() -> "$baseUrl/api/v3/movie/lookup/imdb?imdbId=$imdbId"
                else -> return null
            }
            val request = Request.Builder()
                .url(url)
                .header("X-Api-Key", apiKey)
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null

            val movieObj = if (body.trimStart().startsWith("[")) {
                val array = JSONArray(body)
                if (array.length() > 0) array.getJSONObject(0) else null
            } else {
                JSONObject(body)
            } ?: return null

            val hasFile = movieObj.optBoolean("hasFile", false)
            val isMonitored = movieObj.optBoolean("monitored", false)
            val id = movieObj.optInt("id", 0)

            if (hasFile) {
                MediaRequesterStatus.Available(serverName)
            } else if (id > 0 && isMonitored) {
                MediaRequesterStatus.MonitoredSearching(serverName)
            } else {
                MediaRequesterStatus.NotMonitored
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao checar biblioteca Radarr ($baseUrl): ${e.message}")
            null
        }
    }

    suspend fun requestMovie(
        servers: List<RequesterServerConfig>,
        imdbId: String?,
        tmdbId: String?,
        title: String?,
        year: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        val server = servers.firstOrNull { it.enabled && it.url.isNotBlank() }
            ?: return@withContext Result.failure(IllegalStateException("Nenhum servidor Radarr ativo configurado"))

        val baseUrl = normalizeUrl(server.url)
        val apiKey = server.apiKey.trim()

        try {
            // 1. Lookup do filme para obter metadados do Radarr
            val lookupUrl = when {
                !tmdbId.isNullOrBlank() -> "$baseUrl/api/v3/movie/lookup/tmdb?tmdbId=$tmdbId"
                !imdbId.isNullOrBlank() -> "$baseUrl/api/v3/movie/lookup/imdb?imdbId=$imdbId"
                !title.isNullOrBlank() -> "$baseUrl/api/v3/movie/lookup?term=${URLEncoder.encode(title, "UTF-8")}"
                else -> return@withContext Result.failure(IllegalArgumentException("Metadados insuficientes"))
            }

            val lookupReq = Request.Builder().url(lookupUrl).header("X-Api-Key", apiKey).get().build()
            val lookupResp = okHttpClient.newCall(lookupReq).execute()
            if (!lookupResp.isSuccessful) {
                return@withContext Result.failure(Exception("Radarr lookup falhou: HTTP ${lookupResp.code}"))
            }
            val lookupBody = lookupResp.body?.string() ?: return@withContext Result.failure(Exception("Resposta vazia"))

            val moviePayload = if (lookupBody.trimStart().startsWith("[")) {
                val array = JSONArray(lookupBody)
                if (array.length() > 0) array.getJSONObject(0) else null
            } else {
                JSONObject(lookupBody)
            } ?: return@withContext Result.failure(Exception("Filme não encontrado no Radarr"))

            val existingId = moviePayload.optInt("id", 0)
            if (existingId > 0) {
                // Já existe no Radarr: Dispara comando de busca
                val cmdBody = JSONObject().apply {
                    put("name", "MoviesSearch")
                    put("movieIds", JSONArray().put(existingId))
                }
                val cmdReq = Request.Builder()
                    .url("$baseUrl/api/v3/command")
                    .header("X-Api-Key", apiKey)
                    .post(cmdBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val cmdResp = okHttpClient.newCall(cmdReq).execute()
                if (cmdResp.isSuccessful) {
                    Result.success("Busca iniciada no Radarr!")
                } else {
                    Result.failure(Exception("Erro ao disparar busca no Radarr: HTTP ${cmdResp.code}"))
                }
            } else {
                // Não existe: Adiciona e inicia busca
                // Obtém rootFolderPath e qualityProfileId padrões do Radarr
                val rootFolder = getRadarrRootFolder(baseUrl, apiKey) ?: "/data/media/movies"
                val qualityProfileId = getRadarrQualityProfile(baseUrl, apiKey) ?: 1

                moviePayload.put("monitored", true)
                moviePayload.put("rootFolderPath", rootFolder)
                moviePayload.put("qualityProfileId", qualityProfileId)
                moviePayload.put("addOptions", JSONObject().apply {
                    put("searchForMovie", true)
                })

                val addReq = Request.Builder()
                    .url("$baseUrl/api/v3/movie")
                    .header("X-Api-Key", apiKey)
                    .post(moviePayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val addResp = okHttpClient.newCall(addReq).execute()
                if (addResp.isSuccessful) {
                    Result.success("Filme adicionado e buscando no Radarr!")
                } else {
                    Result.failure(Exception("Erro ao adicionar filme no Radarr: HTTP ${addResp.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao requisitar filme no Radarr", e)
            Result.failure(e)
        }
    }

    private fun getRadarrRootFolder(baseUrl: String, apiKey: String): String? {
        return try {
            val req = Request.Builder().url("$baseUrl/api/v3/rootfolder").header("X-Api-Key", apiKey).get().build()
            val resp = okHttpClient.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val array = JSONArray(resp.body?.string().orEmpty())
            if (array.length() > 0) array.getJSONObject(0).optString("path") else null
        } catch (_: Exception) { null }
    }

    private fun getRadarrQualityProfile(baseUrl: String, apiKey: String): Int? {
        return try {
            val req = Request.Builder().url("$baseUrl/api/v3/qualityprofile").header("X-Api-Key", apiKey).get().build()
            val resp = okHttpClient.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val array = JSONArray(resp.body?.string().orEmpty())
            if (array.length() > 0) array.getJSONObject(0).optInt("id") else null
        } catch (_: Exception) { null }
    }

    // =========================================================================
    // SONARR (SÉRIES / EPISÓDIOS)
    // =========================================================================

    suspend fun checkEpisodeStatus(
        servers: List<RequesterServerConfig>,
        imdbId: String?,
        tvdbId: String?,
        seriesTitle: String?,
        season: Int,
        episode: Int
    ): MediaRequesterStatus = withContext(Dispatchers.IO) {
        val enabledServers = servers.filter { it.enabled && it.url.isNotBlank() }
        if (enabledServers.isEmpty()) return@withContext MediaRequesterStatus.Disabled

        for (server in enabledServers) {
            val baseUrl = normalizeUrl(server.url)
            val apiKey = server.apiKey.trim()

            // 1. Checar Fila de Downloads
            val queueStatus = checkSonarrQueue(baseUrl, apiKey, server.name, season, episode)
            if (queueStatus != null) return@withContext queueStatus

            // 2. Checar Biblioteca do Sonarr
            val libraryStatus = checkSonarrLibrary(baseUrl, apiKey, server.name, imdbId, tvdbId, seriesTitle, season, episode)
            if (libraryStatus != null) return@withContext libraryStatus
        }

        MediaRequesterStatus.NotMonitored
    }

    private fun checkSonarrQueue(
        baseUrl: String,
        apiKey: String,
        serverName: String,
        season: Int,
        episode: Int
    ): MediaRequesterStatus? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/v3/queue?page=1&pageSize=50&includeEpisode=true&includeSeries=true")
                .header("X-Api-Key", apiKey)
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val records = json.optJSONArray("records") ?: return null

            for (i in 0 until records.length()) {
                val record = records.optJSONObject(i) ?: continue
                val ep = record.optJSONObject("episode")
                val epSeason = ep?.optInt("seasonNumber", -1)
                val epNumber = ep?.optInt("episodeNumber", -1)

                if (epSeason == season && epNumber == episode) {
                    val size = record.optDouble("size", 0.0).toLong()
                    val sizeleft = record.optDouble("sizeleft", 0.0).toLong()
                    val percent = if (size > 0) (((size - sizeleft) * 100) / size).toInt().coerceIn(0, 100) else null
                    val timeleft = record.optString("timeleft").takeIf { it.isNotBlank() }
                    return MediaRequesterStatus.Downloading(
                        serverName = serverName,
                        progressPercent = percent,
                        sizeLeftBytes = sizeleft,
                        totalSizeBytes = size,
                        etaFormatted = timeleft
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao checar fila Sonarr ($baseUrl): ${e.message}")
            null
        }
    }

    private fun checkSonarrLibrary(
        baseUrl: String,
        apiKey: String,
        serverName: String,
        imdbId: String?,
        tvdbId: String?,
        seriesTitle: String?,
        season: Int,
        episode: Int
    ): MediaRequesterStatus? {
        return try {
            val seriesId = resolveSonarrSeriesId(baseUrl, apiKey, imdbId, tvdbId, seriesTitle) ?: return null
            val epReq = Request.Builder()
                .url("$baseUrl/api/v3/episode?seriesId=$seriesId&seasonNumber=$season")
                .header("X-Api-Key", apiKey)
                .get()
                .build()
            val epResp = okHttpClient.newCall(epReq).execute()
            if (!epResp.isSuccessful) return null
            val epArray = JSONArray(epResp.body?.string().orEmpty())

            for (i in 0 until epArray.length()) {
                val epObj = epArray.getJSONObject(i)
                if (epObj.optInt("episodeNumber") == episode) {
                    val hasFile = epObj.optBoolean("hasFile", false)
                    val isMonitored = epObj.optBoolean("monitored", false)
                    return when {
                        hasFile -> MediaRequesterStatus.Available(serverName)
                        isMonitored -> MediaRequesterStatus.MonitoredSearching(serverName)
                        else -> MediaRequesterStatus.NotMonitored
                    }
                }
            }
            MediaRequesterStatus.NotMonitored
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao checar biblioteca Sonarr ($baseUrl): ${e.message}")
            null
        }
    }

    private fun resolveSonarrSeriesId(
        baseUrl: String,
        apiKey: String,
        imdbId: String?,
        tvdbId: String?,
        seriesTitle: String?
    ): Int? {
        return try {
            val lookupUrl = when {
                !tvdbId.isNullOrBlank() -> "$baseUrl/api/v3/series/lookup?term=tvdb:$tvdbId"
                !imdbId.isNullOrBlank() -> "$baseUrl/api/v3/series/lookup?term=imdb:$imdbId"
                !seriesTitle.isNullOrBlank() -> "$baseUrl/api/v3/series/lookup?term=${URLEncoder.encode(seriesTitle, "UTF-8")}"
                else -> return null
            }
            val req = Request.Builder().url(lookupUrl).header("X-Api-Key", apiKey).get().build()
            val resp = okHttpClient.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val array = JSONArray(resp.body?.string().orEmpty())
            if (array.length() == 0) return null
            val seriesObj = array.getJSONObject(0)
            val id = seriesObj.optInt("id", 0)
            if (id > 0) id else null
        } catch (_: Exception) { null }
    }

    suspend fun requestEpisode(
        servers: List<RequesterServerConfig>,
        imdbId: String?,
        tvdbId: String?,
        seriesTitle: String?,
        season: Int,
        episode: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        val server = servers.firstOrNull { it.enabled && it.url.isNotBlank() }
            ?: return@withContext Result.failure(IllegalStateException("Nenhum servidor Sonarr ativo configurado"))

        val baseUrl = normalizeUrl(server.url)
        val apiKey = server.apiKey.trim()

        try {
            // 1. Resolver Série
            val lookupUrl = when {
                !tvdbId.isNullOrBlank() -> "$baseUrl/api/v3/series/lookup?term=tvdb:$tvdbId"
                !imdbId.isNullOrBlank() -> "$baseUrl/api/v3/series/lookup?term=imdb:$imdbId"
                !seriesTitle.isNullOrBlank() -> "$baseUrl/api/v3/series/lookup?term=${URLEncoder.encode(seriesTitle, "UTF-8")}"
                else -> return@withContext Result.failure(IllegalArgumentException("Metadados insuficientes"))
            }

            val lookupReq = Request.Builder().url(lookupUrl).header("X-Api-Key", apiKey).get().build()
            val lookupResp = okHttpClient.newCall(lookupReq).execute()
            if (!lookupResp.isSuccessful) {
                return@withContext Result.failure(Exception("Sonarr lookup falhou: HTTP ${lookupResp.code}"))
            }
            val array = JSONArray(lookupResp.body?.string().orEmpty())
            if (array.length() == 0) {
                return@withContext Result.failure(Exception("Série não encontrada no Sonarr"))
            }
            val seriesPayload = array.getJSONObject(0)
            var seriesId = seriesPayload.optInt("id", 0)

            if (seriesId == 0) {
                // Adiciona série no Sonarr
                val rootFolder = getSonarrRootFolder(baseUrl, apiKey) ?: "/data/media/tv"
                val qualityProfileId = getSonarrQualityProfile(baseUrl, apiKey) ?: 1

                seriesPayload.put("monitored", true)
                seriesPayload.put("rootFolderPath", rootFolder)
                seriesPayload.put("qualityProfileId", qualityProfileId)
                seriesPayload.put("addOptions", JSONObject().apply {
                    put("searchForMissingEpisodes", false)
                })

                val addReq = Request.Builder()
                    .url("$baseUrl/api/v3/series")
                    .header("X-Api-Key", apiKey)
                    .post(seriesPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val addResp = okHttpClient.newCall(addReq).execute()
                if (!addResp.isSuccessful) {
                    return@withContext Result.failure(Exception("Erro ao adicionar série no Sonarr: HTTP ${addResp.code}"))
                }
                val createdSeries = JSONObject(addResp.body?.string().orEmpty())
                seriesId = createdSeries.optInt("id", 0)
            }

            // 2. Localizar o Episode ID
            val epReq = Request.Builder()
                .url("$baseUrl/api/v3/episode?seriesId=$seriesId&seasonNumber=$season")
                .header("X-Api-Key", apiKey)
                .get()
                .build()
            val epResp = okHttpClient.newCall(epReq).execute()
            if (!epResp.isSuccessful) {
                return@withContext Result.failure(Exception("Erro ao consultar episódios: HTTP ${epResp.code}"))
            }
            val epArray = JSONArray(epResp.body?.string().orEmpty())
            var episodeId: Int? = null
            for (i in 0 until epArray.length()) {
                val epObj = epArray.getJSONObject(i)
                if (epObj.optInt("episodeNumber") == episode) {
                    episodeId = epObj.optInt("id")
                    break
                }
            }

            if (episodeId == null) {
                return@withContext Result.failure(Exception("Episódio S${season}E${episode} não encontrado na série"))
            }

            // 3. Disparar Comando de Busca para o Episódio
            val cmdBody = JSONObject().apply {
                put("name", "EpisodeSearch")
                put("episodeIds", JSONArray().put(episodeId))
            }
            val cmdReq = Request.Builder()
                .url("$baseUrl/api/v3/command")
                .header("X-Api-Key", apiKey)
                .post(cmdBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val cmdResp = okHttpClient.newCall(cmdReq).execute()
            if (cmdResp.isSuccessful) {
                Result.success("Busca iniciada para S${season}E${episode} no Sonarr!")
            } else {
                Result.failure(Exception("Erro ao disparar busca no Sonarr: HTTP ${cmdResp.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao requisitar episódio no Sonarr", e)
            Result.failure(e)
        }
    }

    private fun getSonarrRootFolder(baseUrl: String, apiKey: String): String? {
        return try {
            val req = Request.Builder().url("$baseUrl/api/v3/rootfolder").header("X-Api-Key", apiKey).get().build()
            val resp = okHttpClient.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val array = JSONArray(resp.body?.string().orEmpty())
            if (array.length() > 0) array.getJSONObject(0).optString("path") else null
        } catch (_: Exception) { null }
    }

    private fun getSonarrQualityProfile(baseUrl: String, apiKey: String): Int? {
        return try {
            val req = Request.Builder().url("$baseUrl/api/v3/qualityprofile").header("X-Api-Key", apiKey).get().build()
            val resp = okHttpClient.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val array = JSONArray(resp.body?.string().orEmpty())
            if (array.length() > 0) array.getJSONObject(0).optInt("id") else null
        } catch (_: Exception) { null }
    }
}
