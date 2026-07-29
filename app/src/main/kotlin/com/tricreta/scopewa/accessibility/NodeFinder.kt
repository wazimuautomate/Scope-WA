package com.tricreta.scopewa.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * How a node was located. Recorded so diagnostics can say *why* a match
 * worked — "found via view-id `com.whatsapp:id/entry`" is actionable when
 * WhatsApp changes its UI; "found it" is not.
 */
sealed class MatchStrategy {
    data class ViewId(val qualifiedId: String) : MatchStrategy()
    data class ContentDescription(val value: String) : MatchStrategy()
    data class Text(val value: String) : MatchStrategy()

    /** Short human-readable form for the diagnostics screen and activity log. */
    val label: String
        get() = when (this) {
            is ViewId -> "view-id $qualifiedId"
            is ContentDescription -> "content-description \"$value\""
            is Text -> "text \"$value\""
        }
}

/** A located node plus the fallback rung that found it. */
data class NodeMatch(
    val node: AccessibilityNodeInfo,
    val strategy: MatchStrategy
)

/**
 * Walks `AccessibilityNodeInfo` trees to find WhatsApp's UI elements, trying
 * each [WaSelectors.Selector]'s candidates in order and reporting which one
 * won — architecture doc section 5.1.
 *
 * ## On recycling
 *
 * This code deliberately does not call `AccessibilityNodeInfo.recycle()`.
 * That method has been a no-op and deprecated since API 33, and on the older
 * releases where it did something, an over-eager recycle throws
 * `IllegalStateException` on the *next* access of a node someone else still
 * holds. A bounded amount of garbage that the collector reclaims is a better
 * failure mode than a crash mid-campaign, so we let GC handle it.
 */
object NodeFinder {

    /** Hard ceiling on tree traversal depth — WhatsApp's trees are nowhere near this deep. */
    private const val MAX_DEPTH = 60

    /**
     * Finds the first node matching [selector], trying view-ids first, then
     * content-descriptions, then visible text — see the ordering rules on
     * [WaSelectors].
     *
     * @param packageName the WhatsApp variant in use, needed to qualify view-ids.
     */
    fun find(
        root: AccessibilityNodeInfo?,
        selector: WaSelectors.Selector,
        packageName: String
    ): NodeMatch? {
        if (root == null) return null

        for (qualifiedId in selector.qualifiedViewIds(packageName)) {
            val node = root.findAccessibilityNodeInfosByViewId(qualifiedId)
                ?.firstOrNull { it.isVisibleToUser }
            if (node != null) return NodeMatch(node, MatchStrategy.ViewId(qualifiedId))
        }

        for (description in selector.contentDescriptions) {
            val node = firstMatching(root) { candidate ->
                candidate.contentDescription?.toString()?.equals(description, ignoreCase = true) == true
            }
            if (node != null) return NodeMatch(node, MatchStrategy.ContentDescription(description))
        }

        for (text in selector.texts) {
            val node = firstMatching(root) { candidate ->
                candidate.text?.toString()?.equals(text, ignoreCase = true) == true
            }
            if (node != null) return NodeMatch(node, MatchStrategy.Text(text))
        }

        return null
    }

    /**
     * Every visible text and content-description on screen, flattened.
     * Used to scan for WhatsApp's restriction dialogs, which carry no
     * distinguishing view-id — see [WaSelectors.RESTRICTION_TEXT_FRAGMENTS].
     */
    fun collectVisibleText(root: AccessibilityNodeInfo?): List<String> {
        val collected = mutableListOf<String>()
        walk(root) { node ->
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(collected::add)
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(collected::add)
        }
        return collected
    }

    /**
     * WhatsApp often puts the clickable behaviour on a parent `ViewGroup`
     * rather than the `TextView` that carries the label, so a click on the
     * matched node itself silently does nothing. Walks up to find the nearest
     * ancestor that will actually accept `ACTION_CLICK`.
     */
    fun clickableSelfOrAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < MAX_DEPTH) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            depth++
        }
        return null
    }

    /** Depth-first search for the first node satisfying [predicate]. */
    fun firstMatching(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (found == null && node.isVisibleToUser && predicate(node)) {
                found = node
            }
        }
        return found
    }

    /**
     * Depth-first walk over the whole tree, calling [visit] on every node.
     *
     * [visit] runs on every node even after a caller has found what it wants;
     * callers that only need the first hit guard with their own flag (see
     * [firstMatching]). Trees here are small enough that the simplicity is
     * worth more than the early exit.
     */
    fun walk(root: AccessibilityNodeInfo?, visit: (AccessibilityNodeInfo) -> Unit) {
        if (root == null) return
        walkInternal(root, 0, visit)
    }

    private fun walkInternal(
        node: AccessibilityNodeInfo,
        depth: Int,
        visit: (AccessibilityNodeInfo) -> Unit
    ) {
        if (depth > MAX_DEPTH) return
        visit(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            walkInternal(child, depth + 1, visit)
        }
    }
}
