package com.tricreta.scopewa.brain.campaign

import com.tricreta.scopewa.brain.pacing.PacingProfile
import com.tricreta.scopewa.brain.pacing.PacingProfiles

/**
 * Names the three pacing profiles from architecture doc section 7 so a campaign
 * row can persist a choice, and hands back the profile object.
 *
 * This is a lookup table, nothing more. **The numbers themselves live in
 * `brain/pacing/PacingProfiles` and are not touched here** — per `CLAUDE.md`
 * they're the product, not tuning knobs, and changing one needs a reason tied
 * back to section 6.
 */
object PacingProfileCatalog {

    /** Slowest, tiny batches — a new number, or anything after a warning. */
    const val SAFE = "Safe"

    /** The section 6 defaults. */
    const val NORMAL = "Normal"

    /** Shows a red warning before the user is allowed to pick it. */
    const val FAST = "Fast"

    val names: List<String> = listOf(SAFE, NORMAL, FAST)

    /** Unknown names fall back to Normal rather than throwing — a corrupt
     *  settings row must not make a campaign unopenable. */
    fun byName(name: String?): PacingProfile = when (name) {
        SAFE -> PacingProfiles.Safe
        FAST -> PacingProfiles.Fast
        else -> PacingProfiles.Normal
    }

    /** One line the composer can show under each choice. */
    fun describe(name: String): String {
        val profile = byName(name)
        return "${profile.minDelaySeconds}–${profile.maxDelaySeconds}s between messages · " +
            "break every ${profile.pauseEveryMessages} · " +
            "${profile.longPauseMinMinutes}–${profile.longPauseMaxMinutes} min break"
    }

    /** Fast is the only profile the UI must warn about before enabling. */
    fun needsWarning(name: String): Boolean = name == FAST
}
