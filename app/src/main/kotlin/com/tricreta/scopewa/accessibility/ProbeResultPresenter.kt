package com.tricreta.scopewa.accessibility

/**
 * Turns a [ProbeResult] into the three things the person holding the phone
 * needs: what happened, why, and what to do next.
 *
 * Kept free of Android and Compose imports so the wording is unit tested in
 * CI. That matters more than it sounds: this is the text the client reads
 * when setup fails, and a wrong or vague instruction here is the difference
 * between them fixing it themselves and the project stalling on a support
 * call.
 */
object ProbeResultPresenter {

    data class Presentation(
        val headline: String,
        val detail: String,
        val nextStep: String?,
        val isSuccess: Boolean,
        /** Long technical text worth sharing with a developer, if any. */
        val shareableDiagnostics: String? = null
    )

    fun present(result: ProbeResult): Presentation = when (result) {
        is ProbeResult.Success -> Presentation(
            headline = "WhatsApp automation is working",
            detail = buildString {
                append("Opened ${result.target.displayName}")
                result.whatsAppVersion?.let { append(" (version $it)") }
                append(" and found the message box in ${result.elapsedMillis / 1000.0}s ")
                append("via ${result.composeBoxStrategy}.")
            },
            nextStep = null,
            isSuccess = true
        )

        is ProbeResult.NotInstalled -> Presentation(
            headline = "${result.target.displayName} isn't installed",
            detail = "Scope WA drives the WhatsApp app already on this phone — it can't " +
                "message anyone on its own.",
            nextStep = "Install ${result.target.displayName} and sign in with the number " +
                "you'll run campaigns from, then run this test again.",
            isSuccess = false
        )

        ProbeResult.ServiceNotConnected -> Presentation(
            headline = "Accessibility permission isn't active",
            detail = "Scope WA can't read WhatsApp's screen yet, so it can't send anything.",
            nextStep = "Finish the setup steps above — turn on Scope WA under " +
                "Accessibility, and if the switch is greyed out, use \"Allow restricted " +
                "settings\" first.",
            isSuccess = false
        )

        is ProbeResult.CouldNotOpenWhatsApp -> Presentation(
            headline = "Couldn't open ${result.target.displayName}",
            detail = "${result.target.displayName} is installed but refused to open a chat " +
                "screen. This usually means it was disabled in Android's app settings.",
            nextStep = "Open ${result.target.displayName} manually once, make sure it's " +
                "signed in and not disabled, then run this test again.",
            isSuccess = false
        )

        is ProbeResult.WhatsAppNeverAppeared -> Presentation(
            headline = "${result.target.displayName} didn't come to the front in time",
            detail = buildString {
                append("Waited ${result.timeoutMillis / 1000}s. ")
                if (result.lastSeenPackage != null && result.lastSeenPackage != result.target.packageName) {
                    append("The screen in front was still \"${result.lastSeenPackage}\". ")
                }
                append(
                    "On a cold start this can just be slowness, but it's also what " +
                        "battery optimisation looks like when it blocks an app from launching."
                )
            },
            nextStep = "Exempt Scope WA from battery optimisation (step above), make sure " +
                "the screen stays on, and try again.",
            isSuccess = false
        )

        is ProbeResult.ComposeBoxNotFound -> Presentation(
            headline = "WhatsApp opened, but its message box wasn't recognised",
            detail = buildString {
                append("The permission is working — Scope WA could read the screen. ")
                append("What it couldn't do is find the message box, which means ")
                append("${result.target.displayName}")
                result.whatsAppVersion?.let { append(" $it") }
                append(" has changed its layout since Scope WA was last updated.")
            },
            nextStep = "Send the diagnostics below to the developer. This is a known kind of " +
                "breakage and the fix is a small app update, not a rebuild.",
            isSuccess = false,
            shareableDiagnostics = result.screenDump
        )
    }
}
