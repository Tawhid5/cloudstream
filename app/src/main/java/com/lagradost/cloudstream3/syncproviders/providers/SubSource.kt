package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities
import com.lagradost.cloudstream3.subtitles.SubtitleResource
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthLoginRequirement
import com.lagradost.cloudstream3.syncproviders.AuthLoginResponse
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.SubtitleAPI
import com.lagradost.cloudstream3.utils.SubtitleHelper
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SubSourceApi : SubtitleAPI() {
    override val name = "SubSource"
    override val idPrefix = "subsource"

    override val hasInApp = true
    override val inAppLoginRequirement = AuthLoginRequirement(password = true)
    override val requiresLogin = true
    override val createAccountUrl = "https://subsource.net/api-docs"

    companion object {
        const val APIURL = "https://api.subsource.net/api/v1"
    }

    override suspend fun login(form: AuthLoginResponse): AuthToken? {
        val apiKey = form.password?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val testRes = app.get(
            url = "$APIURL/movies/search?searchType=text&q=test",
            headers = mapOf("X-API-Key" to apiKey)
        )
        if (!testRes.isSuccessful) return null
        return AuthToken(accessToken = apiKey, payload = "SubSource API Key")
    }

    override suspend fun user(token: AuthToken?): AuthUser? {
        val key = token?.accessToken?.takeIf { it.isNotBlank() } ?: return null
        return AuthUser(id = key.hashCode(), name = token.payload ?: "SubSource")
    }

    override suspend fun search(
        auth: AuthData?,
        query: AbstractSubtitleEntities.SubtitleSearch
    ): List<AbstractSubtitleEntities.SubtitleEntity>? {
        val apiKey = auth?.token?.accessToken?.takeIf { it.isNotBlank() } ?: return null
        val headers = mapOf("X-API-Key" to apiKey)
        val queryLang = SubtitleHelper.fromTagToEnglishLanguageName(query.lang)
        val type = if ((query.seasonNumber ?: 0) > 0) TvType.TvSeries else TvType.Movie

        val searchUrl = if (!query.imdbId.isNullOrBlank()) {
            "$APIURL/movies/search?searchType=imdb&imdb=${URLEncoder.encode(query.imdbId, "UTF-8")}"
        } else {
            val q = query.query.trim()
            if (q.isBlank()) return null
            "$APIURL/movies/search?searchType=text&q=${URLEncoder.encode(q, "UTF-8")}"
        }

        val searchResponse = app.get(
            url = searchUrl,
            headers = headers,
            cacheTime = 120,
            cacheUnit = TimeUnit.MINUTES,
        ).parsedSafe<SearchRoot>() ?: return null

        val movies = searchResponse.data.ifEmpty { searchResponse.results }
        val firstResult = movies.firstOrNull() ?: return null
        val movieId = firstResult.movieId ?: firstResult.id ?: return null

        val langParam = queryLang?.takeIf { it.isNotBlank() }
            ?.let { "&language=${URLEncoder.encode(it.lowercase(), "UTF-8")}" }
            ?: ""

        val subsUrl = "$APIURL/subtitles?movieId=$movieId$langParam"
        val apiResponse = app.get(
            url = subsUrl,
            headers = headers,
            cacheTime = 120,
            cacheUnit = TimeUnit.MINUTES,
        ).parsedSafe<ItemRoot>() ?: return null

        val rawSubs = apiResponse.data.ifEmpty { apiResponse.subtitles }
        val filteredSubtitles = rawSubs.filter { sub ->
            sub.releaseType != "trailer" &&
                (queryLang.isNullOrBlank() || sub.language.equals(queryLang, true))
        }

        val subtitles = if (type == TvType.Movie || query.epNumber == null) {
            filteredSubtitles
        } else {
            val ep = query.epNumber!!
            val ePad = String.format(null, "E%02d", ep)
            val eShort = "E$ep"
            val matched = filteredSubtitles.filter { sub ->
                val info = sub.releaseInfo.ifBlank { sub.title.orEmpty() }
                info.contains(ePad, ignoreCase = true) || info.contains(eShort, ignoreCase = true)
            }
            matched.ifEmpty { filteredSubtitles }
        }

        return subtitles.mapNotNull { subtitle ->
            val subId = subtitle.id ?: return@mapNotNull null
            val subName = subtitle.releaseInfo.ifBlank { subtitle.title ?: "SubSource #$subId" }
            AbstractSubtitleEntities.SubtitleEntity(
                idPrefix = this.idPrefix,
                name = subName,
                lang = subtitle.language.ifBlank { queryLang ?: "en" },
                data = subId.toString(),
                type = type,
                source = this.name,
                epNumber = query.epNumber,
                seasonNumber = query.seasonNumber,
                isHearingImpaired = subtitle.hearingImpaired == 1,
                headers = headers,
            )
        }
    }

    override suspend fun SubtitleResource.getResources(
        auth: AuthData?,
        subtitle: AbstractSubtitleEntities.SubtitleEntity
    ) {
        val apiKey = auth?.token?.accessToken?.takeIf { it.isNotBlank() } ?: return
        val subId = subtitle.data.trim()
        if (subId.isBlank()) return

        this.addZipUrl(
            "$APIURL/subtitles/$subId/download?api_key=$apiKey"
        ) { name, _ ->
            name
        }
    }

    @Serializable
    data class SearchRoot(
        @JsonProperty("success") @SerialName("success") var success: Boolean? = null,
        @JsonProperty("data") @SerialName("data") var data: ArrayList<Results> = arrayListOf(),
        @JsonProperty("results") @SerialName("results") var results: ArrayList<Results> = arrayListOf()
    )

    @Serializable
    data class Results(
        @JsonProperty("id") @SerialName("id") var id: Int? = null,
        @JsonProperty("movieId") @SerialName("movieId") var movieId: Int? = null,
        @JsonProperty("title") @SerialName("title") var title: String? = null,
        @JsonProperty("type") @SerialName("type") var type: String? = null,
        @JsonProperty("link") @SerialName("link") var link: String? = null,
        @JsonProperty("releaseYear") @SerialName("releaseYear") var releaseYear: Int? = null
    )

    @Serializable
    data class ItemRoot(
        @JsonProperty("data") @SerialName("data") var data: ArrayList<Subtitles> = arrayListOf(),
        @JsonProperty("subtitles") @SerialName("subtitles") var subtitles: ArrayList<Subtitles> = arrayListOf()
    )

    @Serializable
    data class Subtitles(
        @JsonProperty("id") @SerialName("id") var id: Int? = null,
        @JsonProperty("language") @SerialName("language") var language: String = "",
        @JsonProperty("release_type") @SerialName("release_type") var releaseType: String? = null,
        @JsonProperty("release_info") @SerialName("release_info") var releaseInfo: String = "",
        @JsonProperty("title") @SerialName("title") var title: String? = null,
        @JsonProperty("hearing_impaired") @SerialName("hearing_impaired") var hearingImpaired: Int? = null,
        @JsonProperty("link") @SerialName("link") var link: String? = null
    )
}
