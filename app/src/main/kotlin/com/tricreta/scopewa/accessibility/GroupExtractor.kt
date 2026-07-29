package com.tricreta.scopewa.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.tricreta.scopewa.data.repository.extract.ExtractedMember
import com.tricreta.scopewa.data.repository.extract.GroupExtraction
import com.tricreta.scopewa.data.repository.extract.MemberRowParser
import kotlinx.coroutines.delay

/** Progress reported while a group is being read, so the UI can show movement. */
data class ExtractionProgress(
    val groupName: String,
    val membersFound: Int,
    val reportedMemberCount: Int?,
    val scrollPasses: Int
)

sealed class ExtractionOutcome {
    data class Success(val extraction: GroupExtraction) : ExtractionOutcome()

    data object ServiceNotConnected : ExtractionOutcome()

    /** The foreground app wasn't WhatsApp, or wasn't a group-info screen. */
    data class NotOnGroupInfoScreen(val foregroundPackage: String?) : ExtractionOutcome()

    /**
     * The participant list was located but produced no readable rows — the
     * signature of stale selectors. Carries a screen dump for the same reason
     * [ProbeResult.ComposeBoxNotFound] does.
     */
    data class NoMembersRead(val screenDump: String) : ExtractionOutcome()
}

/**
 * Reads a WhatsApp group's participant list through the Accessibility Service —
 * Phase 4 of `docs/BUILD-PLAN.md`, and the technique the client already saw
 * working in screenshot 14 ("open group info → scroll → come back → you have
 * all contacts").
 *
 * This is the **lowest-risk** WhatsApp feature by design (architecture doc
 * section 2): it only reads. Nothing is sent, nothing is tapped except the
 * scroll itself, so it is the right place to learn the accessibility
 * techniques that phases 5 and 7 depend on.
 *
 * ## Why scrolling is the hard part
 *
 * The participant list is a virtualised `RecyclerView`: rows that aren't on
 * screen don't exist in the node tree. Reading a 700-member group means
 * scrolling and re-reading repeatedly, and the loop has to know when it has
 * genuinely reached the end rather than merely failed to scroll. Getting that
 * wrong truncates the list silently — which is why [GroupExtraction] also
 * carries WhatsApp's own reported member count to check against.
 */
class GroupExtractor(
    private val parser: MemberRowParser = MemberRowParser(),
    private val bridge: WaServiceBridgeContract = DefaultServiceBridge
) {

    /**
     * Reads the group-info screen currently in the foreground.
     *
     * The caller is responsible for having navigated there — this does not open
     * the group itself. Keeping navigation out means the routine works whether
     * the user got there by hand or a later phase drove them there.
     *
     * @param onProgress called after each scroll pass.
     */
    suspend fun extractCurrentGroup(
        packageName: String,
        maxScrollPasses: Int = DEFAULT_MAX_SCROLL_PASSES,
        onProgress: (ExtractionProgress) -> Unit = {}
    ): ExtractionOutcome {
        if (!bridge.isConnected()) return ExtractionOutcome.ServiceNotConnected

        val root = bridge.currentWindowRoot()
            ?: return ExtractionOutcome.NotOnGroupInfoScreen(null)

        val foreground = root.packageName?.toString()
        if (foreground != packageName) {
            return ExtractionOutcome.NotOnGroupInfoScreen(foreground)
        }

        val groupName = readGroupName(root, packageName)
        val reportedCount = readReportedMemberCount(root, packageName)

        // Keyed by raw title + subtitle: the only identity available before
        // parsing, and stable enough to tell a re-read row from a new one.
        val seen = LinkedHashMap<String, ExtractedMember>()
        var passesWithoutNewRows = 0
        var passes = 0

        while (passes < maxScrollPasses) {
            val current = bridge.currentWindowRoot() ?: break
            val before = seen.size

            readVisibleRows(current, packageName).forEach { member ->
                seen.putIfAbsent(rowKey(member), member)
            }

            passes++
            onProgress(
                ExtractionProgress(
                    groupName = groupName,
                    membersFound = seen.size,
                    reportedMemberCount = reportedCount,
                    scrollPasses = passes
                )
            )

            if (seen.size == before) {
                passesWithoutNewRows++
                // Require several barren passes before concluding the end has
                // been reached: one can just mean the scroll animation hadn't
                // settled when the tree was read.
                if (passesWithoutNewRows >= BARREN_PASSES_BEFORE_STOP) break
            } else {
                passesWithoutNewRows = 0
            }

            if (reportedCount != null && seen.size >= reportedCount) break

            if (!scrollParticipantList(current, packageName)) break
            delay(SCROLL_SETTLE_MS)
        }

        if (seen.isEmpty()) {
            val dump = bridge.currentWindowRoot()?.let { WaScreenDump.render(it) }
                ?: "No window available."
            return ExtractionOutcome.NoMembersRead(dump)
        }

        return ExtractionOutcome.Success(
            GroupExtraction(
                groupName = groupName,
                members = seen.values.toList(),
                reportedMemberCount = reportedCount
            )
        )
    }

    /**
     * Reads every participant row currently rendered.
     *
     * Rows are found by locating name nodes and walking up to the row
     * container, rather than by assuming a fixed tree shape — WhatsApp's row
     * layout has changed more than once, but "the name node has a subtitle
     * sibling" has held.
     */
    private fun readVisibleRows(
        root: AccessibilityNodeInfo,
        packageName: String
    ): List<ExtractedMember> {
        val nameIds = WaSelectors.ParticipantName.qualifiedViewIds(packageName)
        val subtitleIds = WaSelectors.ParticipantSubtitle.qualifiedViewIds(packageName)

        val nameNodes = nameIds.flatMap { id ->
            root.findAccessibilityNodeInfosByViewId(id).orEmpty()
        }.filter { it.isVisibleToUser }

        if (nameNodes.isEmpty()) return emptyList()

        return nameNodes.mapNotNull { nameNode ->
            val row = nameNode.parent ?: return@mapNotNull null
            val title = nameNode.text?.toString()

            val subtitle = subtitleIds
                .firstNotNullOfOrNull { id ->
                    row.findAccessibilityNodeInfosByViewId(id)
                        ?.firstOrNull { it.isVisibleToUser }
                        ?.text
                        ?.toString()
                }

            parser.parse(
                title = title,
                subtitle = subtitle,
                adminLabel = findAdminLabel(row)
            )
        }
    }

    /**
     * Looks for admin wording anywhere inside the row. The badge often has no
     * id of its own, so text matching is the reliable path here — and being
     * wrong only affects an optional filter, never who gets messaged.
     */
    private fun findAdminLabel(row: AccessibilityNodeInfo): String? {
        var found: String? = null
        NodeFinder.walk(row) { node ->
            if (found != null) return@walk
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if (text != null && WaSelectors.ADMIN_LABEL_TEXTS.any { it.equals(text.trim(), true) }) {
                found = text.trim()
            }
        }
        return found
    }

    private fun scrollParticipantList(root: AccessibilityNodeInfo, packageName: String): Boolean {
        val list = NodeFinder.find(root, WaSelectors.ParticipantList, packageName)?.node
            ?: NodeFinder.firstMatching(root) { it.isScrollable }
            ?: return false

        return list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun readGroupName(root: AccessibilityNodeInfo, packageName: String): String =
        NodeFinder.find(root, WaSelectors.GroupTitle, packageName)
            ?.node
            ?.text
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: UNKNOWN_GROUP_NAME

    private fun readReportedMemberCount(root: AccessibilityNodeInfo, packageName: String): Int? {
        NodeFinder.find(root, WaSelectors.ParticipantCountHeader, packageName)
            ?.node
            ?.text
            ?.toString()
            ?.let { WaSelectors.parseReportedMemberCount(it) }
            ?.let { return it }

        // The header often has no id; fall back to scanning visible text for
        // something shaped like "824 participants".
        return NodeFinder.collectVisibleText(root)
            .firstNotNullOfOrNull { WaSelectors.parseReportedMemberCount(it) }
    }

    private fun rowKey(member: ExtractedMember): String =
        member.phoneE164 ?: "${member.rawTitle}|${member.rawSubtitle.orEmpty()}"

    companion object {
        const val UNKNOWN_GROUP_NAME = "Unknown group"

        /**
         * At ~8 rows a screen, this covers groups well past the client's
         * 700-member case (architecture doc section 10, Q7) while still
         * terminating if scrolling silently stops working.
         */
        const val DEFAULT_MAX_SCROLL_PASSES = 400

        private const val BARREN_PASSES_BEFORE_STOP = 3
        private const val SCROLL_SETTLE_MS = 350L
    }
}

/**
 * Indirection over [WaServiceBridge] so the scroll/dedupe loop can be driven by
 * a fake in tests. The bridge itself is a process-wide singleton wrapping a
 * system-instantiated service, which is not something a unit test can stand up.
 */
interface WaServiceBridgeContract {
    fun isConnected(): Boolean
    fun currentWindowRoot(): AccessibilityNodeInfo?
}

internal object DefaultServiceBridge : WaServiceBridgeContract {
    override fun isConnected(): Boolean = WaServiceBridge.isConnected.value
    override fun currentWindowRoot(): AccessibilityNodeInfo? = WaServiceBridge.currentWindowRoot()
}
