package com.tricreta.scopewa.data.repository.extract

/**
 * Why a member's phone number is or isn't available.
 *
 * ## Read this — it is the central constraint of Phase 4
 *
 * The Chrome extension in `docs/reference/whatsapp-contact-extractor` reads
 * WhatsApp Web's **internal store**, so it gets a dialable number for every
 * member from their `@c.us` WID, and only true LID (`@lid`) members come back
 * hidden.
 *
 * The Android app has no such store. The Accessibility Service can only read
 * **what is drawn on screen**, and WhatsApp draws:
 *
 * | Member | Row title shows |
 * | --- | --- |
 * | Not in your phonebook | their **phone number** ✅ |
 * | Saved in your phonebook | their **saved name** ❌ |
 * | LID / privacy-hidden | a **push name** ❌ |
 *
 * So extraction recovers numbers for the people the user does *not* already
 * have, and cannot for the ones they do. That is usually the useful direction —
 * the point is harvesting unknown numbers — but it means **member count and
 * extracted count will not match**, and the UI must say so rather than quietly
 * exporting a 700-member group as 400 rows.
 *
 * Note also that a rendered row gives no way to tell a saved contact apart from
 * a LID-hidden one: both show text where a number would be. Rather than guess,
 * this models the honest distinction — the number is either readable or it
 * isn't — and the reasons are explained to the user in aggregate.
 */
enum class MemberNumberStatus {
    /** A phone number was readable from the row. */
    Visible,

    /**
     * No number on screen. Either the member is saved in the phone's contacts
     * (WhatsApp shows the saved name) or WhatsApp is hiding it behind a LID.
     * Indistinguishable from rendered text — see the enum doc.
     */
    NotShown
}

/**
 * One participant, as read off a group-info row.
 *
 * Raw title and subtitle are retained because when a WhatsApp layout change
 * breaks parsing, the recorded raw text is what makes it diagnosable —
 * architecture doc section 8.
 */
data class ExtractedMember(
    val rawTitle: String,
    val rawSubtitle: String? = null,
    /** Best available human label: a saved/push name, or the number if that's all there is. */
    val displayName: String = "",
    /** Normalised number, or null when [numberStatus] is [MemberNumberStatus.NotShown]. */
    val phoneE164: String? = null,
    val numberStatus: MemberNumberStatus = MemberNumberStatus.NotShown,
    val isAdmin: Boolean = false,
    /** The account running the extraction — never exported. */
    val isSelf: Boolean = false
) {
    val hasNumber: Boolean get() = phoneE164 != null
}

/**
 * Everything pulled from one group, with counts that stay honest about what
 * could not be read.
 */
data class GroupExtraction(
    val groupName: String,
    val members: List<ExtractedMember>,
    /**
     * Member total WhatsApp itself claimed (from the "N participants" header),
     * when it was readable. Compared against [members] size to detect a scroll
     * that stopped early — the failure mode that silently loses people.
     */
    val reportedMemberCount: Int? = null
) {
    val withNumbers: List<ExtractedMember> get() = members.filter { it.hasNumber }
    val withoutNumbers: List<ExtractedMember> get() = members.filter { !it.hasNumber }
    val admins: List<ExtractedMember> get() = members.filter { it.isAdmin }

    /**
     * True when WhatsApp said there were more participants than we managed to
     * read. Surfaced loudly: a partial extraction that looks complete is worse
     * than an obvious failure, because the user acts on it.
     */
    val looksIncomplete: Boolean
        get() = reportedMemberCount != null && members.size < reportedMemberCount
}
