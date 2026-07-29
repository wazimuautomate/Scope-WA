package com.tricreta.scopewa.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Renders the live WhatsApp screen's accessibility tree as readable text.
 *
 * This is the tool that makes [WaSelectors] maintainable. Architecture doc
 * section 8 promises that a WhatsApp UI change is "a small patch + a release";
 * that promise only holds if capturing the *new* view-ids off a real phone is
 * quick. Without this, fixing a broken selector means attaching a laptop and
 * running `uiautomatorviewer`; with it, whoever holds the phone taps
 * **Diagnostics → Dump WhatsApp screen**, shares the text, and the fix is a
 * one-line edit.
 *
 * It is also how the unverified candidate ids in [WaSelectors] get confirmed
 * or corrected in the first place.
 */
object WaScreenDump {

    /**
     * @param root the WhatsApp window's root node.
     * @param interestingOnly when true (the default), skips nodes carrying no
     *   id, text, or content-description. WhatsApp's trees are mostly
     *   structural `ViewGroup`s; including them makes a dump too long to read
     *   on a phone and too long to paste into a bug report.
     */
    fun render(
        root: AccessibilityNodeInfo?,
        interestingOnly: Boolean = true
    ): String {
        if (root == null) return "No window available. Is WhatsApp in the foreground?"

        val builder = StringBuilder()
        builder.appendLine("package: ${root.packageName}")
        builder.appendLine("---")
        renderNode(root, depth = 0, interestingOnly = interestingOnly, out = builder)
        return builder.toString().trimEnd()
    }

    /**
     * Best-guess summary of the selectors that matter most, checked against
     * the live screen. Shown above the raw dump so a reader sees "compose box:
     * NOT FOUND" immediately rather than having to scan a hundred lines.
     */
    fun renderSelectorReport(root: AccessibilityNodeInfo?, packageName: String): String {
        if (root == null) return "No window available."

        val selectors = listOf(
            WaSelectors.ComposeBox,
            WaSelectors.SendButton,
            WaSelectors.ConversationTitle,
            WaSelectors.ParticipantList,
            WaSelectors.AddParticipantSearch
        )

        return buildString {
            appendLine("Selector check against the current screen:")
            for (selector in selectors) {
                val match = NodeFinder.find(root, selector, packageName)
                if (match == null) {
                    appendLine("  ✗ ${selector.name} — not found on this screen")
                } else {
                    appendLine("  ✓ ${selector.name} — ${match.strategy.label}")
                }
            }
            appendLine()
            appendLine(
                "A \"not found\" is only a problem if the element is actually visible " +
                    "on the screen you dumped. The send button, for example, only exists " +
                    "once the compose box has text in it."
            )
        }
    }

    private fun renderNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        interestingOnly: Boolean,
        out: StringBuilder
    ) {
        if (!interestingOnly || isInteresting(node)) {
            out.append("  ".repeat(depth))
            out.appendLine(describe(node))
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            renderNode(child, depth + 1, interestingOnly, out)
        }
    }

    private fun isInteresting(node: AccessibilityNodeInfo): Boolean =
        !node.viewIdResourceName.isNullOrBlank() ||
            !node.text.isNullOrBlank() ||
            !node.contentDescription.isNullOrBlank()

    private fun describe(node: AccessibilityNodeInfo): String = buildString {
        append(node.className?.toString()?.substringAfterLast('.') ?: "?")

        node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { append(" id=$it") }
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { append(" text=\"${truncate(it)}\"") }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?.let { append(" desc=\"${truncate(it)}\"") }

        val flags = buildList {
            if (node.isClickable) add("clickable")
            if (node.isEditable) add("editable")
            if (node.isScrollable) add("scrollable")
            if (!node.isVisibleToUser) add("hidden")
        }
        if (flags.isNotEmpty()) append(" [${flags.joinToString(",")}]")
    }

    /**
     * Message bodies and "about" texts can run long; a dump is for identifying
     * *which* node something is, not for reading its content.
     */
    private fun truncate(value: String, max: Int = 40): String =
        if (value.length <= max) value else value.take(max) + "…"
}
