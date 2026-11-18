package com.libretv.tv.domain.usecase

import com.libretv.tv.domain.model.FeedCard

/**
 * Append-only merge for the home feed.
 *
 * As collectors answer one by one, a naive "re-aggregate + re-sort everything"
 * moves already visible cards around (a title that gains a second source jumps
 * to the front under the 热门 sort). On a TV that reads as constant flicker.
 *
 * This keeps [previous] in exactly its current order — first loaded stays in
 * front — and appends only keys that have never been shown, in the order given
 * by [incoming] (already sorted for the selected genre). When
 * [refreshExisting] is true the offer metadata of an existing card is updated
 * once, in place, and only if it actually changed.
 */
fun mergeStableCards(
    previous: List<FeedCard>,
    incoming: List<FeedCard>,
    refreshExisting: Boolean
): List<FeedCard> {
    val byKey = incoming.associateBy { it.key }
    val used = HashSet<String>(previous.size + incoming.size)
    return buildList(previous.size + incoming.size) {
        previous.forEach { card ->
            if (!used.add(card.key)) return@forEach
            val fresh = if (refreshExisting) byKey[card.key] else null
            add(if (fresh != null && fresh != card) fresh else card)
        }
        incoming.forEach { card ->
            if (used.add(card.key)) add(card)
        }
    }
}
