package com.nuvio.tv.data.requester

import android.content.Context
import android.util.Base64
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.data.requester.model.MediaRequesterStatus
import com.nuvio.tv.data.requester.model.RequesterServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private const val TAG = "ContentRequesterService"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

@Singleton
class ContentRequesterService @Inject constructor(
    @Named("addonPermissive") private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {

    private val requesterHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private val testOkHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun normalizeUrl(url: String): String =
        url.trim().trimEnd('/')

    private fun isUnreachableHost(e: Exception): Boolean {
        return e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException ||
            e is java.net.UnknownHostException ||
            e is java.net.NoRouteToHostException
    }

    private fun verifyApiKey(baseUrl: String, apiKey: String): Boolean {
        return try {
            val statusReq = Request.Builder()
                .url("$baseUrl/api/v3/system/status")
                .header("X-Api-Key", apiKey.trim())
                .get()
                .build()
            testOkHttpClient.newCall(statusReq).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Testa e resolve as credenciais do servidor Sonarr ou Radarr.
     * Suporta:
     * 1. Login por Usuário e Senha (Web UI Form Login + captura de cookie + extração de apiKey via /initialize.json).
     * 2. HTTP Basic Auth com Usuário e Senha.
     * 3. Conexão direta sem autenticação (AuthenticationMethod: None).
     * 4. API Key direta previamente resolvida.
     *
     * Retorna a apiKey obtida ou confirmação de sucesso.
     */
    suspend fun testAndResolveCredentials(server: RequesterServerConfig): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeUrl(server.url)
        if (baseUrl.isBlank()) return@withContext Result.failure(IllegalArgumentException(context.getString(R.string.requester_error_invalid_url)))

        // 1. Se já tiver apiKey salva, testa diretamente
        if (server.apiKey.isNotBlank()) {
            val statusReq = Request.Builder()
                .url("$baseUrl/api/v3/system/status")
                .header("X-Api-Key", server.apiKey.trim())
                .get()
                .build()
            try {
                testOkHttpClient.newCall(statusReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        return@withContext Result.success(server.apiKey.trim())
                    }
                }
            } catch (e: Exception) {
                if (isUnreachableHost(e)) {
                    return@withContext Result.failure(Exception(context.getString(R.string.requester_error_unreachable)))
                }
            }
        }

        // 2. Se usuário e senha foram fornecidos: Tenta login via Form (/login)
        if (server.username.isNotBlank() || server.password.isNotBlank()) {
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

                val (loginFailed, cookieHeader) = testOkHttpClient.newCall(loginReq).execute().use { loginResp ->
                    val location = loginResp.header("Location").orEmpty()
                    val failed = location.contains("loginFailed=true")
                    val cookies = loginResp.headers.values("Set-Cookie")
                    val header = cookies.joinToString("; ") { it.substringBefore(';') }
                    Pair(failed, header)
                }

                if (loginFailed) {
                    return@withContext Result.failure(Exception(context.getString(R.string.requester_error_invalid_credentials)))
                }

                // 2.1 Endpoint oficial da Web UI com o cookie de sessão: /initialize.json
                val initReq = Request.Builder()
                    .url("$baseUrl/initialize.json")
                    .apply {
                        if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader)
                    }
                    .get()
                    .build()

                val initKey = testOkHttpClient.newCall(initReq).execute().use { initResp ->
                    if (initResp.isSuccessful) {
                        val body = initResp.body?.string().orEmpty()
                        val json = JSONObject(body)
                        json.optString("apiKey")
                    } else null
                }
                if (!initKey.isNullOrBlank() && verifyApiKey(baseUrl, initKey)) {
                    return@withContext Result.success(initKey)
                }

                // 2.2 Fallback para /api/v3/config/host com cookie
                val hostReq = Request.Builder()
                    .url("$baseUrl/api/v3/config/host")
                    .apply {
                        if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader)
                    }
                    .get()
                    .build()

                val hostKey = testOkHttpClient.newCall(hostReq).execute().use { hostResp ->
                    if (hostResp.isSuccessful) {
                        val body = hostResp.body?.string().orEmpty()
                        val json = JSONObject(body)
                        json.optString("apiKey")
                    } else null
                }
                if (!hostKey.isNullOrBlank() && verifyApiKey(baseUrl, hostKey)) {
                    return@withContext Result.success(hostKey)
                }
            } catch (e: Exception) {
                if (isUnreachableHost(e)) {
                    return@withContext Result.failure(Exception(context.getString(R.string.requester_error_unreachable)))
                }
                Log.w(TAG, "Tentativa de login via /login falhou: ${e.message}")
            }

            // 3. Fallback: Tenta HTTP Basic Auth caso o servidor use Basic
            try {
                val credentials = Base64.encodeToString(
                    "${server.username.trim()}:${server.password}".toByteArray(),
                    Base64.NO_WRAP
                )
                val basicReq = Request.Builder()
                    .url("$baseUrl/initialize.json")
                    .header("Authorization", "Basic $credentials")
                    .get()
                    .build()

                val basicKey = testOkHttpClient.newCall(basicReq).execute().use { basicResp ->
                    if (basicResp.isSuccessful) {
                        val body = basicResp.body?.string().orEmpty()
                        val json = JSONObject(body)
                        json.optString("apiKey")
                    } else null
                }
                if (!basicKey.isNullOrBlank() && verifyApiKey(baseUrl, basicKey)) {
                    return@withContext Result.success(basicKey)
                }

                val basicHostReq = Request.Builder()
                    .url("$baseUrl/api/v3/config/host")
                    .header("Authorization", "Basic $credentials")
                    .get()
                    .build()

                val basicHostKey = testOkHttpClient.newCall(basicHostReq).execute().use { basicHostResp ->
                    if (basicHostResp.isSuccessful) {
                        val body = basicHostResp.body?.string().orEmpty()
                        val json = JSONObject(body)
                        json.optString("apiKey")
                    } else null
                }
                if (!basicHostKey.isNullOrBlank() && verifyApiKey(baseUrl, basicHostKey)) {
                    return@withContext Result.success(basicHostKey)
                }
            } catch (e: Exception) {
                if (isUnreachableHost(e)) {
                    return@withContext Result.failure(Exception(context.getString(R.string.requester_error_unreachable)))
                }
                Log.w(TAG, "Tentativa de Basic Auth falhou: ${e.message}")
            }
        }

        // 4. Sem autenticação (None): tenta obter apiKey de /initialize.json ou /config/host
        try {
            val initReq = Request.Builder()
                .url("$baseUrl/initialize.json")
                .get()
                .build()
            val initKey = testOkHttpClient.newCall(initReq).execute().use { initResp ->
                if (initResp.isSuccessful) {
                    val body = initResp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    json.optString("apiKey")
                } else null
            }
            if (!initKey.isNullOrBlank() && verifyApiKey(baseUrl, initKey)) {
                return@withContext Result.success(initKey)
            }

            val noneReq = Request.Builder()
                .url("$baseUrl/api/v3/config/host")
                .get()
                .build()
            val noneKey = testOkHttpClient.newCall(noneReq).execute().use { noneResp ->
                if (noneResp.isSuccessful) {
                    val body = noneResp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    json.optString("apiKey")
                } else null
            }
            if (!noneKey.isNullOrBlank() && verifyApiKey(baseUrl, noneKey)) {
                return@withContext Result.success(noneKey)
            }

            // Testa status sem auth
            val statusReq = Request.Builder()
                .url("$baseUrl/api/v3/system/status")
                .get()
                .build()
            val statusSuccess = testOkHttpClient.newCall(statusReq).execute().use { statusResp ->
                statusResp.isSuccessful
            }
            if (statusSuccess) {
                return@withContext Result.success("")
            }
        } catch (e: Exception) {
            if (isUnreachableHost(e)) {
                return@withContext Result.failure(Exception(context.getString(R.string.requester_error_unreachable)))
            }
            Log.w(TAG, "Tentativa sem autenticação falhou: ${e.message}")
        }

        Result.failure(Exception(context.getString(R.string.requester_error_auth_failed)))
    }

    suspend fun testConnection(server: RequesterServerConfig): Result<Boolean> =
        testAndResolveCredentials(server).map { true }

    // =========================================================================
    // RADARR (FILMES)
    // =========================================================================

    private fun resolveRadarrMovie(
        baseUrl: String,
        apiKey: String,
        imdbId: String?,
        tmdbId: String?,
        title: String?
    ): JSONObject? {
        return try {
            val req = Request.Builder().url("$baseUrl/api/v3/movie").header("X-Api-Key", apiKey).get().build()
            requesterHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val array = JSONArray(resp.body?.string().orEmpty())

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val itemImdb = item.optString("imdbId")
                    val itemTmdb = item.optInt("tmdbId", -1).toString()
                    val itemTitle = item.optString("title")

                    val matches = (imdbId != null && itemImdb.equals(imdbId, ignoreCase = true)) ||
                        (tmdbId != null && itemTmdb == tmdbId) ||
                        (title != null && itemTitle.equals(title, ignoreCase = true))

                    if (matches) return item
                }
                null
            }
        } catch (_: Exception) { null }
    }

    private fun isCommandActiveOrJustCompleted(cmd: JSONObject, graceWindowMs: Long = 15_000L): Boolean {
        val status = cmd.optString("status").lowercase(Locale.ROOT)
        if (status == "queued" || status == "started" || status == "processing") {
            return true
        }
        if (status == "completed") {
            val ended = cmd.optString("ended").ifBlank { cmd.optString("stateChangeTime") }
            if (ended.isNotBlank()) {
                val endedMs = runCatching { java.time.Instant.parse(ended).toEpochMilli() }.getOrNull()
                if (endedMs != null && (System.currentTimeMillis() - endedMs) in 0..graceWindowMs) {
                    return true
                }
            }
        }
        return false
    }

    private fun checkRadarrCommands(
        baseUrl: String,
        apiKey: String,
        serverName: String,
        movieId: Int?
    ): MediaRequesterStatus? {
        return try {
            val req = Request.Builder().url("$baseUrl/api/v3/command").header("X-Api-Key", apiKey).get().build()
            requesterHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val array = JSONArray(resp.body?.string().orEmpty())
                for (i in 0 until array.length()) {
                    val cmd = array.getJSONObject(i)
                    val name = cmd.optString("name")
                    val isSearchCmd = name.equals("MoviesSearch", ignoreCase = true) ||
                        name.equals("RefreshMovie", ignoreCase = true) ||
                        name.equals("DownloadedMoviesScan", ignoreCase = true)
                    if (!isSearchCmd) continue
                    if (!isCommandActiveOrJustCompleted(cmd)) continue

                    val body = cmd.optJSONObject("body")
                    val cmdMovieId = body?.optInt("movieId", -1) ?: -1
                    val cmdMovieIds = body?.optJSONArray("movieIds")

                    val matches = when {
                        movieId == null || movieId <= 0 -> true
                        cmdMovieId > 0 -> cmdMovieId == movieId
                        cmdMovieIds != null && cmdMovieIds.length() > 0 -> {
                            var found = false
                            for (j in 0 until cmdMovieIds.length()) {
                                if (cmdMovieIds.optInt(j) == movieId) {
                                    found = true
                                    break
                                }
                            }
                            found
                        }
                        else -> true
                    }

                    if (matches) {
                        return MediaRequesterStatus.Level2Requested(
                            serverName = serverName.ifBlank { "Radarr" },
                            phaseDescription = ""
                        )
                    }
                }
                null
            }
        } catch (_: Exception) { null }
    }

    suspend fun checkMovieStatus(
        servers: List<RequesterServerConfig>,
        imdbId: String?,
        tmdbId: String?,
        title: String?,
        isPollingActive: Boolean = false
    ): MediaRequesterStatus = withContext(Dispatchers.IO) {
        val enabledServers = servers.filter { it.enabled && it.url.isNotBlank() }
        if (enabledServers.isEmpty()) return@withContext MediaRequesterStatus.Disabled

        val results = coroutineScope {
            enabledServers.map { server ->
                async {
                    val baseUrl = normalizeUrl(server.url)
                    val apiKey = server.apiKey.trim()
                    val sName = server.name.ifBlank { "Radarr" }

                    val queueStatus = checkRadarrQueue(baseUrl, apiKey, sName, imdbId, tmdbId, title)
                    if (queueStatus != null) return@async queueStatus

                    val movieObj = resolveRadarrMovie(baseUrl, apiKey, imdbId, tmdbId, title)
                    val hasFile = movieObj?.optBoolean("hasFile", false) == true
                    if (hasFile) {
                        return@async MediaRequesterStatus.Level3Available(serverName = sName, message = "")
                    }

                    val cmdStatus = checkRadarrCommands(baseUrl, apiKey, sName, movieObj?.optInt("id", 0))
                    if (cmdStatus != null) return@async cmdStatus

                    MediaRequesterStatus.Level1NotRequested(serverName = sName)
                }
            }.awaitAll()
        }

        // 1. Se QUALQUER servidor estiver baixando/importando na fila, mantém Nível 2 com progresso
        val downloading = results.firstOrNull { it is MediaRequesterStatus.Level2Requested && it.progressPercent != null }
        if (downloading != null) return@withContext downloading

        // 2. Se QUALQUER servidor estiver com comando de busca em andamento ou recém-concluído, mantém Nível 2 buscando
        val searching = results.firstOrNull { it is MediaRequesterStatus.Level2Requested }
        if (searching != null) return@withContext searching

        // 3. Se TODOS os servidores ativos já têm o arquivo disponível, retorna Nível 3 Disponível
        if (results.isNotEmpty() && results.all { it is MediaRequesterStatus.Level3Available }) {
            return@withContext results.first()
        }

        // 4. Se pelo menos um servidor já tem o arquivo e nenhum está buscando/baixando:
        //    - Durante o polling (isPollingActive = true), todos os servidores já finalizaram suas buscas,
        //      então libera Level3Available.
        val available = results.firstOrNull { it is MediaRequesterStatus.Level3Available }
        if (available != null && isPollingActive) {
            return@withContext available
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
            requesterHttpClient.newCall(request).execute().use { response ->
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
                        (title != null && movieTitle.equals(title, ignoreCase = true))

                    if (matches) {
                        val size = record.optDouble("size", 0.0).toLong()
                        val sizeleft = record.optDouble("sizeleft", 0.0).toLong()
                        val percent = if (size > 0) (((size - sizeleft) * 100) / size).toInt().coerceIn(0, 100) else null
                        val timeleft = record.optString("timeleft").takeIf { it.isNotBlank() }
                        return MediaRequesterStatus.Level2Requested(
                            serverName = serverName.ifBlank { "Radarr" },
                            phaseDescription = "",
                            progressPercent = percent,
                            etaFormatted = timeleft,
                            sizeFormatted = formatBytes(size)
                        )
                    }
                }
                null
            }
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
        val movieObj = resolveRadarrMovie(baseUrl, apiKey, imdbId, tmdbId, title) ?: return null
        val hasFile = movieObj.optBoolean("hasFile", false)
        val name = serverName.ifBlank { "Radarr" }
        return if (hasFile) {
            MediaRequesterStatus.Level3Available(
                serverName = name,
                message = ""
            )
        } else {
            // Sem arquivo baixado e sem download ativo na fila -> retorna null para mostrar o botão de solicitar
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
        val activeServers = servers.filter { it.enabled && it.url.isNotBlank() }
        if (activeServers.isEmpty()) {
            return@withContext Result.failure(IllegalStateException(context.getString(R.string.requester_error_no_active_server, "Radarr")))
        }

        var atLeastOneSuccess = false
        val errors = mutableListOf<String>()

        for (server in activeServers) {
            val baseUrl = normalizeUrl(server.url)
            val apiKey = server.apiKey.trim()
            val serverLabel = server.name.ifBlank { "Radarr" }

            try {
                // Checa primeiro se já existe na biblioteca local do Radarr
                val existingMovie = resolveRadarrMovie(baseUrl, apiKey, imdbId, tmdbId, title)
                val existingId = existingMovie?.optInt("id", 0) ?: 0

                if (existingId > 0) {
                    if (existingMovie?.optBoolean("hasFile", false) == true) {
                        atLeastOneSuccess = true
                        continue
                    }
                    // Garante que o filme existente esteja marcado como monitorado no Radarr
                    monitorRadarrMovie(baseUrl, apiKey, existingId)

                    val cmdBody = JSONObject().apply {
                        put("name", "MoviesSearch")
                        put("movieIds", JSONArray().put(existingId))
                    }
                    val cmdReq = Request.Builder()
                        .url("$baseUrl/api/v3/command")
                        .header("X-Api-Key", apiKey)
                        .post(cmdBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    val cmdSuccess = requesterHttpClient.newCall(cmdReq).execute().use { cmdResp ->
                        if (cmdResp.isSuccessful) {
                            true
                        } else {
                            errors += "$serverLabel: ${context.getString(R.string.requester_error_search_failed)} (${cmdResp.code})"
                            false
                        }
                    }
                    if (cmdSuccess) {
                        atLeastOneSuccess = true
                    }
                    continue
                }

                // Não existe no Radarr: faz lookup e adiciona
                val lookupUrl = when {
                    !tmdbId.isNullOrBlank() -> "$baseUrl/api/v3/movie/lookup/tmdb?tmdbId=$tmdbId"
                    !imdbId.isNullOrBlank() -> "$baseUrl/api/v3/movie/lookup/imdb?imdbId=$imdbId"
                    !title.isNullOrBlank() -> "$baseUrl/api/v3/movie/lookup?term=${URLEncoder.encode(title, "UTF-8")}"
                    else -> {
                        errors += "$serverLabel: ${context.getString(R.string.requester_error_insufficient_metadata)}"
                        continue
                    }
                }

                val lookupReq = Request.Builder().url(lookupUrl).header("X-Api-Key", apiKey).get().build()
                val (lookupOk, lookupCode, lookupBody) = requesterHttpClient.newCall(lookupReq).execute().use { lookupResp ->
                    Triple(lookupResp.isSuccessful, lookupResp.code, lookupResp.body?.string().orEmpty())
                }
                if (!lookupOk) {
                    errors += "$serverLabel: ${context.getString(R.string.requester_error_lookup_failed)} ($lookupCode)"
                    continue
                }

                val moviePayload = if (lookupBody.trimStart().startsWith("[")) {
                    val array = JSONArray(lookupBody)
                    if (array.length() > 0) array.getJSONObject(0) else null
                } else {
                    JSONObject(lookupBody)
                } ?: run {
                    errors += "$serverLabel: ${context.getString(R.string.requester_error_not_found)}"
                    continue
                }

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
                val addSuccess = requesterHttpClient.newCall(addReq).execute().use { addResp ->
                    if (addResp.isSuccessful) {
                        true
                    } else {
                        val errBody = addResp.body?.string().orEmpty()
                        val msg = if (errBody.contains("already been added", ignoreCase = true) ||
                            errBody.contains("MovieExistsValidator", ignoreCase = true)
                        ) {
                            context.getString(R.string.requester_error_already_added)
                        } else {
                            "${context.getString(R.string.requester_error_add_failed)} (${addResp.code})"
                        }
                        errors += "$serverLabel: $msg"
                        false
                    }
                }
                if (addSuccess) {
                    atLeastOneSuccess = true
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to request movie on $serverLabel ($baseUrl)", e)
                val errMsg = if (e is java.io.IOException || isUnreachableHost(e)) {
                    context.getString(R.string.requester_error_connection)
                } else {
                    context.getString(R.string.requester_error_general_failure)
                }
                errors += "$serverLabel: $errMsg"
            }
        }

        if (atLeastOneSuccess) {
            Result.success(context.getString(R.string.requester_success_search_started))
        } else {
            Result.failure(Exception(errors.joinToString("; ").ifBlank { context.getString(R.string.requester_error_general_failure) }))
        }
    }

    private fun getRadarrRootFolder(baseUrl: String, apiKey: String): String? {
        return try {
            val req = Request.Builder().url("$baseUrl/api/v3/rootfolder").header("X-Api-Key", apiKey).get().build()
            requesterHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val array = JSONArray(resp.body?.string().orEmpty())
                if (array.length() > 0) array.getJSONObject(0).optString("path") else null
            }
        } catch (_: Exception) { null }
    }

    private fun getRadarrQualityProfile(baseUrl: String, apiKey: String): Int? {
        return try {
            val req = Request.Builder().url("$baseUrl/api/v3/qualityprofile").header("X-Api-Key", apiKey).get().build()
            requesterHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val array = JSONArray(resp.body?.string().orEmpty())
                if (array.length() > 0) array.getJSONObject(0).optInt("id") else null
            }
        } catch (_: Exception) { null }
    }

    private fun monitorRadarrMovie(baseUrl: String, apiKey: String, movieId: Int) {
        try {
            val payload = JSONObject().apply {
                put("movieIds", JSONArray().put(movieId))
                put("monitored", true)
            }
            val req = Request.Builder()
                .url("$baseUrl/api/v3/movie/editor")
                .header("X-Api-Key", apiKey)
                .put(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            requesterHttpClient.newCall(req).execute().close()
            Log.d(TAG, "Filme $movieId marcado como monitorado no Radarr ($baseUrl)")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Falha ao garantir monitoramento do filme $movieId no Radarr: ${e.message}")
        }
    }

    // =========================================================================
    // SONARR (SÉRIES / EPISÓDIOS)
    // =========================================================================

    private fun checkSonarrCommands(
        baseUrl: String,
        apiKey: String,
        serverName: String,
        seriesId: Int?
    ): MediaRequesterStatus? {
        return try {
            val req = Request.Builder().url("$baseUrl/api/v3/command").header("X-Api-Key", apiKey).get().build()
            requesterHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val array = JSONArray(resp.body?.string().orEmpty())
                for (i in 0 until array.length()) {
                    val cmd = array.getJSONObject(i)
                    val name = cmd.optString("name")
                    val isSearchCmd = name.equals("EpisodeSearch", ignoreCase = true) ||
                        name.equals("SeasonSearch", ignoreCase = true) ||
                        name.equals("SeriesSearch", ignoreCase = true) ||
                        name.equals("RefreshSeries", ignoreCase = true) ||
                        name.equals("DownloadedEpisodesScan", ignoreCase = true)
                    if (!isSearchCmd) continue
                    if (!isCommandActiveOrJustCompleted(cmd)) continue

                    val body = cmd.optJSONObject("body")
                    val cmdSeriesId = body?.optInt("seriesId", -1) ?: -1

                    val matches = when {
                        seriesId == null || seriesId <= 0 -> true
                        cmdSeriesId > 0 -> cmdSeriesId == seriesId
                        else -> true
                    }

                    if (matches) {
                        return MediaRequesterStatus.Level2Requested(
                            serverName = serverName.ifBlank { "Sonarr" },
                            phaseDescription = ""
                        )
                    }
                }
                null
            }
        } catch (_: Exception) { null }
    }

    private fun checkEpisodeHasFile(
        baseUrl: String,
        apiKey: String,
        seriesId: Int,
        season: Int,
        episode: Int
    ): Boolean {
        return try {
            val epReq = Request.Builder()
                .url("$baseUrl/api/v3/episode?seriesId=$seriesId&seasonNumber=$season")
                .header("X-Api-Key", apiKey)
                .get()
                .build()
            requesterHttpClient.newCall(epReq).execute().use { epResp ->
                if (!epResp.isSuccessful) return false
                val epArray = JSONArray(epResp.body?.string().orEmpty())
                for (i in 0 until epArray.length()) {
                    val epObj = epArray.getJSONObject(i)
                    if (epObj.optInt("episodeNumber") == episode) {
                        return epObj.optBoolean("hasFile", false)
                    }
                }
                false
            }
        } catch (_: Exception) { false }
    }

    suspend fun checkEpisodeStatus(
        servers: List<RequesterServerConfig>,
        imdbId: String?,
        tvdbId: String?,
        seriesTitle: String?,
        season: Int,
        episode: Int,
        isPollingActive: Boolean = false
    ): MediaRequesterStatus = withContext(Dispatchers.IO) {
        val enabledServers = servers.filter { it.enabled && it.url.isNotBlank() }
        if (enabledServers.isEmpty()) return@withContext MediaRequesterStatus.Disabled

        val results = coroutineScope {
            enabledServers.map { server ->
                async {
                    val baseUrl = normalizeUrl(server.url)
                    val apiKey = server.apiKey.trim()
                    val sName = server.name.ifBlank { "Sonarr" }

                    val queueStatus = checkSonarrQueue(baseUrl, apiKey, sName, imdbId, tvdbId, seriesTitle, season, episode)
                    if (queueStatus != null) return@async queueStatus

                    val seriesId = resolveSonarrSeriesId(baseUrl, apiKey, imdbId, tvdbId, seriesTitle)
                    val hasFile = if (seriesId != null) {
                        checkEpisodeHasFile(baseUrl, apiKey, seriesId, season, episode)
                    } else false

                    if (hasFile) {
                        return@async MediaRequesterStatus.Level3Available(serverName = sName, message = "")
                    }

                    val cmdStatus = checkSonarrCommands(baseUrl, apiKey, sName, seriesId)
                    if (cmdStatus != null) return@async cmdStatus

                    MediaRequesterStatus.Level1NotRequested(serverName = sName)
                }
            }.awaitAll()
        }

        // 1. Se QUALQUER servidor estiver baixando/importando na fila, mantém Nível 2 com progresso
        val downloading = results.firstOrNull { it is MediaRequesterStatus.Level2Requested && it.progressPercent != null }
        if (downloading != null) return@withContext downloading

        // 2. Se QUALQUER servidor estiver com comando de busca em andamento ou recém-concluído, mantém Nível 2 buscando
        val searching = results.firstOrNull { it is MediaRequesterStatus.Level2Requested }
        if (searching != null) return@withContext searching

        // 3. Se TODOS os servidores ativos já têm o arquivo disponível, retorna Nível 3 Disponível
        if (results.isNotEmpty() && results.all { it is MediaRequesterStatus.Level3Available }) {
            return@withContext results.first()
        }

        // 4. Se pelo menos um servidor já tem o arquivo e nenhum está buscando/baixando:
        //    - Durante o polling (isPollingActive = true), todos os servidores já finalizaram suas buscas,
        //      então libera Level3Available.
        val available = results.firstOrNull { it is MediaRequesterStatus.Level3Available }
        if (available != null && isPollingActive) {
            return@withContext available
        }

        val primaryServerName = enabledServers.firstOrNull()?.name?.ifBlank { "Sonarr" } ?: "Sonarr"
        MediaRequesterStatus.Level1NotRequested(serverName = primaryServerName)
    }

    private fun checkSonarrQueue(
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
            val request = Request.Builder()
                .url("$baseUrl/api/v3/queue?page=1&pageSize=50&includeEpisode=true&includeSeries=true")
                .header("X-Api-Key", apiKey)
                .get()
                .build()
            requesterHttpClient.newCall(request).execute().use { response ->
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
                        val seriesObj = record.optJSONObject("series")
                        val sImdb = seriesObj?.optString("imdbId")
                        val sTvdb = seriesObj?.optInt("tvdbId", -1)?.toString()
                        val sTitle = seriesObj?.optString("title")

                        val seriesMatches = when {
                            imdbId != null && sImdb?.equals(imdbId, ignoreCase = true) == true -> true
                            tvdbId != null && sTvdb == tvdbId -> true
                            seriesTitle != null && sTitle?.equals(seriesTitle, ignoreCase = true) == true -> true
                            else -> false
                        }

                        if (!seriesMatches) continue

                        val size = record.optDouble("size", 0.0).toLong()
                        val sizeleft = record.optDouble("sizeleft", 0.0).toLong()
                        val percent = if (size > 0) (((size - sizeleft) * 100) / size).toInt().coerceIn(0, 100) else null
                        val timeleft = record.optString("timeleft").takeIf { it.isNotBlank() }
                        return MediaRequesterStatus.Level2Requested(
                            serverName = serverName.ifBlank { "Sonarr" },
                            phaseDescription = "",
                            progressPercent = percent,
                            etaFormatted = timeleft,
                            sizeFormatted = formatBytes(size)
                        )
                    }
                }
                null
            }
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
            requesterHttpClient.newCall(epReq).execute().use { epResp ->
                if (!epResp.isSuccessful) return null
                val epArray = JSONArray(epResp.body?.string().orEmpty())

                for (i in 0 until epArray.length()) {
                    val epObj = epArray.getJSONObject(i)
                    if (epObj.optInt("episodeNumber") == episode) {
                        val hasFile = epObj.optBoolean("hasFile", false)
                        val name = serverName.ifBlank { "Sonarr" }
                        return if (hasFile) {
                            MediaRequesterStatus.Level3Available(
                                serverName = name,
                                message = ""
                            )
                        } else {
                            // Sem arquivo local e sem download na fila -> retorna null para checar outros servidores
                            null
                        }
                    }
                }
                null
            }
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
        val activeServers = servers.filter { it.enabled && it.url.isNotBlank() }
        if (activeServers.isEmpty()) {
            return@withContext Result.failure(IllegalStateException(context.getString(R.string.requester_error_no_active_server, "Sonarr")))
        }

        val epNotation = String.format(Locale.ROOT, "S%02dE%02d", season, episode)
        var atLeastOneSuccess = false
        val errors = mutableListOf<String>()

        for (server in activeServers) {
            val baseUrl = normalizeUrl(server.url)
            val apiKey = server.apiKey.trim()
            val serverLabel = server.name.ifBlank { "Sonarr" }

            try {
                var seriesId = resolveSonarrSeriesId(baseUrl, apiKey, imdbId, tvdbId, seriesTitle)
                var wasJustAdded = false

                if (seriesId == null) {
                    val lookupTerm = when {
                        !tvdbId.isNullOrBlank() -> "tvdb:$tvdbId"
                        !imdbId.isNullOrBlank() -> "imdb:$imdbId"
                        !seriesTitle.isNullOrBlank() -> seriesTitle
                        else -> {
                            errors += "$serverLabel: ${context.getString(R.string.requester_error_insufficient_metadata)}"
                            continue
                        }
                    }
                    val lookupUrl = "$baseUrl/api/v3/series/lookup?term=${URLEncoder.encode(lookupTerm, "UTF-8")}"
                    val lookupReq = Request.Builder().url(lookupUrl).header("X-Api-Key", apiKey).get().build()
                    val (lookupOk, lookupCode, lookupBody) = requesterHttpClient.newCall(lookupReq).execute().use { lookupResp ->
                        Triple(lookupResp.isSuccessful, lookupResp.code, lookupResp.body?.string().orEmpty())
                    }
                    if (!lookupOk) {
                        errors += "$serverLabel: ${context.getString(R.string.requester_error_lookup_failed)} ($lookupCode)"
                        continue
                    }
                    val array = JSONArray(lookupBody)
                    if (array.length() == 0) {
                        errors += "$serverLabel: ${context.getString(R.string.requester_error_not_found)}"
                        continue
                    }
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
                    val (addOk, addCode, addBody) = requesterHttpClient.newCall(addReq).execute().use { addResp ->
                        Triple(addResp.isSuccessful, addResp.code, addResp.body?.string().orEmpty())
                    }
                    if (!addOk) {
                        val msg = if (addBody.contains("already been added", ignoreCase = true) ||
                            addBody.contains("SeriesExistsValidator", ignoreCase = true)
                        ) {
                            context.getString(R.string.requester_error_already_added)
                        } else {
                            "${context.getString(R.string.requester_error_add_failed)} ($addCode)"
                        }
                        errors += "$serverLabel: $msg"
                        continue
                    }
                    val addedObj = JSONObject(addBody)
                    seriesId = addedObj.optInt("id")
                    wasJustAdded = true
                }

                // Busca o ID específico do episódio com retentativa se a série acabou de ser adicionada
                var targetEpisodeId: Int? = null
                val maxAttempts = if (wasJustAdded) 8 else 1

                for (attempt in 1..maxAttempts) {
                    if (attempt > 1) {
                        delay(1200L)
                    }

                    val epReq = Request.Builder()
                        .url("$baseUrl/api/v3/episode?seriesId=$seriesId&seasonNumber=$season")
                        .header("X-Api-Key", apiKey)
                        .get()
                        .build()
                    val epArray = requesterHttpClient.newCall(epReq).execute().use { epResp ->
                        if (epResp.isSuccessful) JSONArray(epResp.body?.string().orEmpty()) else null
                    } ?: continue

                    for (i in 0 until epArray.length()) {
                        val epObj = epArray.getJSONObject(i)
                        if (epObj.optInt("episodeNumber") == episode) {
                            val epHasFile = epObj.optBoolean("hasFile", false)
                            if (!wasJustAdded && epHasFile) {
                                targetEpisodeId = -1
                                atLeastOneSuccess = true
                                break
                            }
                            targetEpisodeId = epObj.optInt("id")
                            break
                        }
                    }

                    if (targetEpisodeId != null) break
                }

                if (targetEpisodeId == -1) {
                    // Arquivo já existe neste servidor, não precisa disparar EpisodeSearch
                    continue
                }

                if (targetEpisodeId == null) {
                    errors += "$serverLabel: ${context.getString(R.string.requester_error_episode_not_found)} ($epNotation)"
                    continue
                }

                // Garante que o episódio solicitado e a série estejam monitorados no Sonarr
                monitorSonarrEpisode(baseUrl, apiKey, seriesId, targetEpisodeId)

                // Dispara busca específica do episódio
                val cmdBody = JSONObject().apply {
                    put("name", "EpisodeSearch")
                    put("episodeIds", JSONArray().put(targetEpisodeId))
                }
                val cmdReq = Request.Builder()
                    .url("$baseUrl/api/v3/command")
                    .header("X-Api-Key", apiKey)
                    .post(cmdBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val cmdSuccess = requesterHttpClient.newCall(cmdReq).execute().use { cmdResp ->
                    if (cmdResp.isSuccessful) {
                        true
                    } else {
                        errors += "$serverLabel: ${context.getString(R.string.requester_error_search_failed)} (${cmdResp.code})"
                        false
                    }
                }
                if (cmdSuccess) {
                    atLeastOneSuccess = true
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to request episode on $serverLabel ($baseUrl)", e)
                val errMsg = if (e is java.io.IOException || isUnreachableHost(e)) {
                    context.getString(R.string.requester_error_connection)
                } else {
                    context.getString(R.string.requester_error_general_failure)
                }
                errors += "$serverLabel: $errMsg"
            }
        }

        if (atLeastOneSuccess) {
            Result.success(context.getString(R.string.requester_success_search_started))
        } else {
            Result.failure(Exception(errors.joinToString("; ").ifBlank { context.getString(R.string.requester_error_general_failure) }))
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
            requesterHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val array = JSONArray(resp.body?.string().orEmpty())

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val itemImdb = item.optString("imdbId")
                    val itemTvdb = item.optInt("tvdbId", -1).toString()
                    val itemTitle = item.optString("title")

                    val matches = (imdbId != null && itemImdb.equals(imdbId, ignoreCase = true)) ||
                        (tvdbId != null && itemTvdb == tvdbId) ||
                        (seriesTitle != null && itemTitle.equals(seriesTitle, ignoreCase = true))

                    if (matches) return item.optInt("id")
                }
                null
            }
        } catch (_: Exception) { null }
    }

    private fun getSonarrRootFolder(baseUrl: String, apiKey: String): String? {
        return try {
            val req = Request.Builder().url("$baseUrl/api/v3/rootfolder").header("X-Api-Key", apiKey).get().build()
            requesterHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val array = JSONArray(resp.body?.string().orEmpty())
                if (array.length() > 0) array.getJSONObject(0).optString("path") else null
            }
        } catch (_: Exception) { null }
    }

    private fun getSonarrQualityProfile(baseUrl: String, apiKey: String): Int? {
        return try {
            val req = Request.Builder().url("$baseUrl/api/v3/qualityprofile").header("X-Api-Key", apiKey).get().build()
            requesterHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val array = JSONArray(resp.body?.string().orEmpty())
                if (array.length() > 0) array.getJSONObject(0).optInt("id") else null
            }
        } catch (_: Exception) { null }
    }

    private fun monitorSonarrEpisode(baseUrl: String, apiKey: String, seriesId: Int, episodeId: Int) {
        try {
            // Marca o episódio solicitado como monitorado no Sonarr
            val epPayload = JSONObject().apply {
                put("episodeIds", JSONArray().put(episodeId))
                put("monitored", true)
            }
            val epReq = Request.Builder()
                .url("$baseUrl/api/v3/episode/monitor")
                .header("X-Api-Key", apiKey)
                .put(epPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            requesterHttpClient.newCall(epReq).execute().close()
            Log.d(TAG, "Episódio $episodeId marcado como monitorado no Sonarr ($baseUrl)")

            // Garante que a série também esteja ativa/monitorada no Sonarr
            val seriesPayload = JSONObject().apply {
                put("seriesIds", JSONArray().put(seriesId))
                put("monitored", true)
            }
            val seriesReq = Request.Builder()
                .url("$baseUrl/api/v3/series/editor")
                .header("X-Api-Key", apiKey)
                .put(seriesPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            requesterHttpClient.newCall(seriesReq).execute().close()
            Log.d(TAG, "Série $seriesId garantida como monitorada no Sonarr ($baseUrl)")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Falha ao garantir monitoramento no Sonarr ($baseUrl): ${e.message}")
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(java.util.Locale.US, "%.1f %s", value, units[digitGroups])
    }
}
