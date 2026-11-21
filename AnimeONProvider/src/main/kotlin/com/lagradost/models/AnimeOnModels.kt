package com.lagradost.models

data class SafeResult(
    val id: Int,
    val titleUa: String,
    val description: String? = null,
    val image: Image,
    val malId: Int? = null,
    val rating: Double? = 0.0,
    val status: String? = null,
    val type: String? = null,
    val genres: List<Genres>? = null,
    val episodes: Int? = null
)

data class SafeNewAnimeModel(
    val results: List<SafeResult>,
    val totalCount: Int? = 0
)

data class SafeSearchApiResponse(
    val results: List<SafeResult>,
    val totalCount: Int? = 0
)

data class SafeAnimeInfoModel(
    val id: Int,
    val titleUa: String,
    val titleEn: String? = null,
    val description: String? = null,
    val image: Image? = null,
    val backgroundImage: String? = null,
    val trailer: String? = null,
    val rating: Double? = 0.0,
    val status: String? = "completed",
    val type: String? = "tv",
    val genres: List<Genres>? = null,
    val episodes: Int? = 0,
    val episodeTime: String? = "",
    val releaseDate: String? = null,
    val malId: Int? = 0
)

data class PopularAnime(
    val id: Int,
    val slug: String? = null,
    val titleUa: String,
    val titleEn: String? = null,
    val type: String? = null,
    val image: Image? = null
)

data class PopularAnimeItem(
    val views: Int? = null,
    val anime: PopularAnime
)

data class PopularAnimeResponse(
    val results: List<PopularAnimeItem>
)

data class SafeTranslationsResponse(
    val translations: List<TranslationItem>
)

data class SafePlayerEpisodes(
    val episodes: List<FundubEpisode>
)

data class LocalResult(
    val id: Int,
    val titleUa: String,
    val slug: String?,
    val episodesAired: Int?,
    val rating: String?,
    val image: Image,
    val description: String? = null
)

data class RedirectResponse(
    val moved: Boolean? = null,
    val redirectTo: String? = null,
    val slug: String? = null,
)

data class FranchiseItem(
    val id: Int,
    val slug: String?,
    val titleUa: String,
    val type: String?,
    val image: Image?,
    val releaseDate: String?,
)

data class EpisodeInfo(
    val id: Int? = null,
    val episode: Int,
    val title: String? = null,
    val titleUa: String? = null,
    val aired: String? = null,
    val filler: Boolean? = null,
    val recap: Boolean? = null,
)

data class EpisodeSource(
    val translationName: String,
    val playerName: String,
    val episodeId: Int,
    val episodeNum: Int? = null,
    val apiPoster: String? = null
)

data class DirectPlayerResponse(
    val videoUrl: String? = null,
    val fileUrl: String? = null,
    val selection: FenixSelection? = null
)

data class EpisodeVideoResponse(
    val videoUrl: String? = null,
    val fileUrl: String? = null,
    val selection: FenixSelection? = null
)