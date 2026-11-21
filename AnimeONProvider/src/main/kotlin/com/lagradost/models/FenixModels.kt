package com.lagradost.models

data class FenixSelection(
    val episode: Int? = null,
    val edition: String? = null,
    val track: String? = null
)

data class FenixSubtitleRendition(
    val url: String? = null,
    val styled_url: String? = null,
    val fonts_url: String? = null,
)

data class FenixSubtitle(
    val label: String? = null,
    val language: String? = null,
    val url: String? = null,
    val styled_url: String? = null,
    val fonts_url: String? = null,
    val kind: String? = null,
    val voice_studio_id: String? = null,
    val voice_studio_name: String? = null,
    val is_default: Boolean? = null,
    val offset_ms: Int? = null,
    val renditions: Map<String, FenixSubtitleRendition>? = null,
)

data class FenixMediaAccess(
    val origin: String? = null,
    val token: String? = null
)

data class FenixPlayback(
    val version: Int? = 1,
    val token: String? = null,
)

data class FenixPlaybackTrack(
    val id: String? = null,
    val label: String? = null,
    val language: String? = null,
    val track_type: String? = null,
    val hls_url: String? = null,
    val cast_avc_url: String? = null,
    val is_default: Boolean? = null,
    val voice_studio_id: String? = null,
)

data class FenixPlaybackEpisode(
    val id: String? = null,
    val number: Int = 0,
    val edition: String? = null,
    val title: String? = null,
    val thumbnail_url: String? = null,
    val hls_master_url: String? = null,
    val cast_avc_url: String? = null,
    val video_segment_format: String? = null,
    val tracks: List<FenixPlaybackTrack>? = null,
    val subtitles: List<FenixSubtitle>? = null,
)

data class FenixPlaybackEpisodeResponse(
    val episode: FenixPlaybackEpisode? = null,
    val token: String? = null,
)

data class FenixPlaylist(
    val slug: String? = null,
    val poster_url: String? = null,
    val playback: FenixPlayback? = null,
)