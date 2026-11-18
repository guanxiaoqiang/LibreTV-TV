package com.libretv.tv.domain.usecase

/**
 * Suggestions shown in the strip directly below the search field.
 *
 * The strip reacts to whatever is in the search field:
 *
 *  - plain latin typed on the on-screen board (letters, digits, space) is
 *    resolved through the pinyin-initial index;
 *  - free text from the IME (usually Chinese) matches bundled titles and
 *    recent keywords as the viewer types.
 *
 * Keeping the matching here (instead of inside the view model) makes the
 * behaviour cheap to unit test and keeps recomposition-independent.
 */
object SearchSuggestions {

    /**
     * The pinyin-initials part of [text], or an empty string when the text is
     * not plain latin.
     *
     * The board types its characters straight into the input field, so the
     * field text is the only source of truth. Spaces are ignored here: the
     * board inserts them as word separators, while the pinyin index is keyed
     * by plain letter runs ("zhang jie" -> "zhangjie").
     */
    fun pinyinInitials(text: String): String {
        val letters = text.replace(" ", "").replace("\u3000", "")
        if (letters.isEmpty()) return ""
        if (!letters.all { it in 'a'..'z' || it in 'A'..'Z' }) return ""
        return letters.lowercase()
    }

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
