package com.nuvio.tv.data.requester

import android.util.Base64
import android.util.Log
import com.nuvio.tv.data.requester.model.MediaRequesterStatus
import com.nuvio.tv.data.requester.model.RequesterDot
import com.nuvio.tv.data.requester.model.RequesterDotColor
import com.nuvio.tv.data.requester.model.RequesterServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
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

    /**
     * Testa e resolve as credenciais do servidor Sonarr ou Radarr.
     * Suporta:
     * 1. Login por Usuário e Senha (Web UI Form Login + captura de cookie + extração de apiKey).
     * 2. HTTP Basic Auth com Usuário e Senha.
     * 3. Conexão direta sem autenticação (AuthenticationMethod: None).
     * 4. API Key direta previamente resolvida.
     *
     * Retorna a apiKey obtida ou confirmação de sucesso.
     */
    suspend fun testAndResolveCredentials(server: RequesterServerConfig): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeUrl(server.url)
        if (baseUrl.isBlank()) return@withContext Result.failure(IllegalArgumentException("Endereço do servidor inválido"))

        // 1. Se já tiver apiKey salva, testa diretamente
        if (server.apiKey.isNotBlank()) {
            val statusReq = Request.Builder()
                .url("$baseUrl/api/v3/system/status")
                .header("X-Api-Key", server.apiKey.trim())
                .get()
                .build()
            val resp = runCatching { okHttpClient.newCall(statusReq).execute() }.getOrNull()
            if (resp != null && resp.isSuccessful) {
                return@withContext Result.success(server.apiKey.trim())
            }
        }

        // 2. Se usuário e senha foram fornecidos: Tenta login via Form (/login)
        if (server.username.isNotBlank() && server.password.isNotBlank()) {
            try {
                val formBody = FormBody.Builder()
                    .add("username", server.username.trim())
                    .add("password", server.password)
                    .add("rememberMe", "true")
                    .build()

                val loginReq = Request.Builder()
                    .url("$baseUrl/login")
                    .post(formBody)
                    .build()

                val loginResp = okHttpClient.newCall(loginReq).execute()
                val cookies = loginResp.headers.values("Set-Cookie")
                val cookieHeader = cookies.joinToString("; ") { it.substringBefore(';') }

                // Se o login tiver sucesso (200 OK ou redirecionamento 302/303)
                if (loginResp.isSuccessful || loginResp.isRedirect) {
                    val hostReq = Request.Builder()
                        .url("$baseUrl/api/v3/config/host")
                        .apply {
                            if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader)
                        }
                        .get()
                        .build()

                    val hostResp = okHttpClient.newCall(hostReq).execute()
                    if (hostResp.isSuccessful) {
                        val body = hostResp.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val key = json.optString("apiKey")
                        if (key.isNotBlank()) {
                            return@withContext Result.success(key)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tentativa de login via /login falhou: ${e.message}")
            }

            // 3. Fallback: Tenta HTTP Basic Auth caso o servidor use Basic
            try {
                val credentials = Base64.encodeToString(
                    "${server.username.trim()}:${server.password}".toByteArray(),
                    Base64.NO_WRAP
                )
                val basicReq = Request.Builder()
                    .url("$baseUrl/api/v3/config/host")
                    .header("Authorization", "Basic $credentials")
                    .get()
                    .build()

                val basicResp = okHttpClient.newCall(basicReq).execute()
                if (basicResp.isSuccessful) {
                    val body = basicResp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val key = json.optString("apiKey")
                    if (key.isNotBlank()) {
                        return@withContext Result.success(key)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tentativa de Basic Auth falhou: ${e.message}")
            }
        }

        // 4. Sem autenticação (None): tenta obter apiKey de /api/v3/config/host diretamente
        try {
            val noneReq = Request.Builder()
                .url("$baseUrl/api/v3/config/host")
                .get()
                .build()
            val noneResp = okHttpClient.newCall(noneReq).execute()
            if (noneResp.isSuccessful) {
                val body = noneResp.body?.string().orEmpty()
                val json = JSONObject(body)
                val key = json.optString("apiKey")
                if (key.isNotBlank()) {
                    return@withContext Result.success(key)
                }
            }

            // Testa status sem auth
            val statusReq = Request.Builder()
                .url("$baseUrl/api/v3/system/status")
                .get()
                .build()
            val statusResp = okHttpClient.newCall(statusReq).execute()
            if (statusResp.isSuccessful) {
                return@withContext Result.success("")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tentativa sem autenticação falhou: ${e.message}")
        }

        Result.failure(Exception("Não foi possível conectar. Verifique o endereço, usuário e senha."))
    }

    suspend fun testConnection(server: RequesterServerConfig): Result<Boolean> =
        testAndResolveCredentials(server).map { true }

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

        val primaryServerName = enabledServers.firstOrNull()?.name?.ifBlank { "Radarr" } ?: "Radarr"
        MediaRequesterStatus.Level1NotRequested(serverName = primaryServerName)
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
                    return MediaRequesterStatus.Level2Requested(
                        serverName = serverName.ifBlank { "Radarr" },
                        phaseDescription = "Baixando no cliente...",
                        progressPercent = percent,
                        etaFormatted = timeleft,
                        sizeFormatted = formatBytes(size)
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

            val name = serverName.ifBlank { "Radarr" }
            if (hasFile) {
                MediaRequesterStatus.Level3Available(
                    serverName = name,
                    message = "Disponível na sua biblioteca local"
                )
            } else if (id > 0 && isMonitored) {
                MediaRequesterStatus.Level2Requested(
                    serverName = name,
                    phaseDescription = "Pesquisando releases nos indexadores..."
                )
            } else {
                MediaRequesterStatus.Level1NotRequested(serverName = name)
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

        val primaryServerName = enabledServers.firstOrNull()?.name?.ifBlank { "Sonarr" } ?: "Sonarr"
        MediaRequesterStatus.Level1NotRequested(serverName = primaryServerName)
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
                    return MediaRequesterStatus.Level2Requested(
                        serverName = serverName.ifBlank { "Sonarr" },
                        phaseDescription = "Baixando no cliente...",
                        progressPercent = percent,
                        etaFormatted = timeleft,
                        sizeFormatted = formatBytes(size)
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
                    val name = serverName.ifBlank { "Sonarr" }
                    return when {
                        hasFile -> MediaRequesterStatus.Level3Available(
                            serverName = name,
                            message = "Disponível na sua biblioteca local"
                        )
                        isMonitored -> MediaRequesterStatus.Level2Requested(
                            serverName = name,
                            phaseDescription = "Pesquisando releases nos indexadores..."
                        )
                        else -> MediaRequesterStatus.Level1NotRequested(serverName = name)
                    }
                }
            }
            MediaRequesterStatus.Level1NotRequested(serverName = serverName.ifBlank { "Sonarr" })
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao checar biblioteca Sonarr ($baseUrl): ${e.message}")
            null
        }
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
            var seriesId = resolveSonarrSeriesId(baseUrl, apiKey, imdbId, tvdbId, seriesTitle)

            if (seriesId == null) {
                val lookupTerm = when {
                    !tvdbId.isNullOrBlank() -> "tvdb:$tvdbId"
                    !imdbId.isNullOrBlank() -> "imdb:$imdbId"
                    !seriesTitle.isNullOrBlank() -> seriesTitle
                    else -> return@withContext Result.failure(IllegalArgumentException("Metadados insuficientes"))
                }
                val lookupUrl = "$baseUrl/api/v3/series/lookup?term=${URLEncoder.encode(lookupTerm, "UTF-8")}"
                val lookupReq = Request.Builder().url(lookupUrl).header("X-Api-Key", apiKey).get().build()
                val lookupResp = okHttpClient.newCall(lookupReq).execute()
                if (!lookupResp.isSuccessful) {
                    return@withContext Result.failure(Exception("Sonarr lookup falhou: HTTP ${lookupResp.code}"))
                }
                val lookupBody = lookupResp.body?.string().orEmpty()
                val array = JSONArray(lookupBody)
                if (array.length() == 0) return@withContext Result.failure(Exception("Série não encontrada no Sonarr"))
                val seriesPayload = array.getJSONObject(0)

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
                val addedObj = JSONObject(addResp.body?.string().orEmpty())
                seriesId = addedObj.optInt("id")
            }

            // Busca o ID específico do episódio
            val epReq = Request.Builder()
                .url("$baseUrl/api/v3/episode?seriesId=$seriesId&seasonNumber=$season")
                .header("X-Api-Key", apiKey)
                .get()
                .build()
            val epResp = okHttpClient.newCall(epReq).execute()
            if (!epResp.isSuccessful) return@withContext Result.failure(Exception("Erro ao listar episódios"))
            val epArray = JSONArray(epResp.body?.string().orEmpty())

            var targetEpisodeId: Int? = null
            for (i in 0 until epArray.length()) {
                val epObj = epArray.getJSONObject(i)
                if (epObj.optInt("episodeNumber") == episode) {
                    targetEpisodeId = epObj.optInt("id")
                    break
                }
            }

            if (targetEpisodeId != null) {
                val cmdBody = JSONObject().apply {
                    put("name", "EpisodeSearch")
                    put("episodeIds", JSONArray().put(targetEpisodeId))
                }
                val cmdReq = Request.Builder()
                    .url("$baseUrl/api/v3/command")
                    .header("X-Api-Key", apiKey)
                    .post(cmdBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val cmdResp = okHttpClient.newCall(cmdReq).execute()
                if (cmdResp.isSuccessful) {
                    Result.success("Busca do episódio iniciada no Sonarr!")
                } else {
                    Result.failure(Exception("Erro ao disparar busca do episódio: HTTP ${cmdResp.code}"))
                }
            } else {
                Result.failure(Exception("Episódio S${season}E${episode} não encontrado na série."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao requisitar episódio no Sonarr", e)
            Result.failure(e)
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
            val req = Request.Builder().url("$baseUrl/api/v3/series").header("X-Api-Key", apiKey).get().build()
            val resp = okHttpClient.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val array = JSONArray(resp.body?.string().orEmpty())

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val itemImdb = item.optString("imdbId")
                val itemTvdb = item.optInt("tvdbId", -1).toString()
                val itemTitle = item.optString("title")

                val matches = (imdbId != null && itemImdb.equals(imdbId, ignoreCase = true)) ||
                    (tvdbId != null && itemTvdb == tvdbId) ||
                    (seriesTitle != null && itemTitle.contains(seriesTitle, ignoreCase = true))

                if (matches) return item.optInt("id")
            }
            null
        } catch (_: Exception) { null }
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

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(java.util.Locale.US, "%.1f %s", value, units[digitGroups])
    }
}
