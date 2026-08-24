package com.dmrandevu.gallery.data

import kotlinx.serialization.Serializable

/** One customer conversation carrying at least one video, as returned by /admin/media-gallery-page. */
@Serializable
data class Conversation(
    val salonId: String,
    val clientId: String,
    val clientName: String = "",
    val urls: List<String> = emptyList(),
    /** Index-aligned with [urls]: when each video arrived on Instagram. */
    val mediaTs: List<String?> = emptyList(),
    val lastMessageDate: String? = null
) {
    /** Stable identity — the delete flow tracks pages by this, never by list index. */
    val key: String get() = "$salonId:$clientId"

    /** Send time of one video, falling back to the conversation's own last-message date. */
    fun sentAt(index: Int): String? = mediaTs.getOrNull(index) ?: lastMessageDate
}

@Serializable
data class GalleryPage(
    val items: List<Conversation> = emptyList(),
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    /** Video-carrying conversations this account has in total, not just on this page. */
    val total: Int = 0
)

@Serializable
data class ResolveResponse(
    val igId: String,
    val username: String = ""
)

@Serializable
data class CaptionResponse(
    val caption: String = ""
)

/** Thrown when the server rejects the session; the UI drops back to the login screen. */
class UnauthorizedException : Exception("Not authenticated")
