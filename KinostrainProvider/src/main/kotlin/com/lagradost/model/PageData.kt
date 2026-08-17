package com.lagradost.model

import org.jsoup.nodes.Document

data class PageData(
    val title: String = "",
    val poster: String = "",
    val description: String = "",
    val year: Int = 0,
    val tags: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val rating: String = "",
    val engTitle: String = "",
    val trailer: String = "",
    val source: Document = Document("")
)
