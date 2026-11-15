package com.libretv.tv.data

/**
 * Suggestions shown in the strip directly below the search field.
 *
 * Two input paths feed the same strip:
 *
 *  - the on-screen QWERTY board, which accumulates pinyin initials;
 *  - the focusable text field, where the IME produces free text (usually
 *    Chinese) one character at a time.
 *
 * Keeping the matching here (instead of inside the view model) makes the
 * behaviour cheap to unit test and keeps recomposition-independent.
 */
object SearchSuggestions {

    /**
     * @param titleIndex pinyin initials -> Chinese title, loaded from assets.
     * @param history recent keywords, newest first.
     * @param initials letters typed on the on-screen board (wins when present).
     * @param query current text field value (used for live IME suggestions).
     */
    fun suggest(
        titleIndex: Map<String, String>,
        history: List<String>,
        initials: String,
        query: String,
        limit: Int = 8
    ): List<String> {
        if (limit <= 0) return emptyList()
        val letters = initials.trim().lowercase()
        if (letters.isNotEmpty()) {
            return titleIndex.entries
                .filter { (key, _) -> key.startsWith(letters) }
                .sortedByDescending { it.key.length }
                .map { it.value }
                .distinct()
                .take(limit)
        }

        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        // Recent keywords come first: a half-typed word the viewer searched
        // yesterday is the most likely thing they are typing again. Prefix
        // matches outrank mid-string matches so the strip narrows predictably.
        val pool = (history + titleIndex.values).distinct()
        return pool
            .filter { it.isNotBlank() && it.lowercase().contains(q) }
            .sortedWith(
                compareBy<String> { if (it.lowercase().startsWith(q)) 0 else 1 }
                    .thenBy { it.length }
            )
            .take(limit)
    }
}
