package com.tricreta.scopewa.accessibility

/**
 * Which WhatsApp app a campaign drives. The client uses both consumer
 * WhatsApp and WhatsApp Business (architecture doc section 10, Q1), and the
 * app must support either.
 *
 * Deliberately free of Android imports so probe results and setup state that
 * reference it stay unit-testable. Anything needing a `Context` — is it
 * installed, what version — lives in `WaInstallations.kt`.
 */
enum class WaPackage(val packageName: String, val displayName: String) {
    Consumer(WaSelectors.PACKAGE_WHATSAPP, "WhatsApp"),
    Business(WaSelectors.PACKAGE_WHATSAPP_BUSINESS, "WhatsApp Business");

    companion object {
        fun fromPackageName(packageName: String?): WaPackage? =
            entries.firstOrNull { it.packageName == packageName }
    }
}
