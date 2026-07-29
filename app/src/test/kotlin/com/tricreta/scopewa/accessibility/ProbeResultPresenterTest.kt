package com.tricreta.scopewa.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe's wording is the client's only feedback when setup fails, so it is
 * tested like logic rather than treated as cosmetic copy. Every failure must
 * say what to do next; only success is allowed to say nothing.
 */
class ProbeResultPresenterTest {

    @Test
    fun `success reports the variant, timing, and which selector matched`() {
        val presentation = ProbeResultPresenter.present(
            ProbeResult.Success(
                target = WaPackage.Business,
                whatsAppVersion = "2.24.1.75",
                composeBoxStrategy = "view-id com.whatsapp.w4b:id/entry",
                elapsedMillis = 2_500
            )
        )

        assertTrue(presentation.isSuccess)
        assertTrue(presentation.detail.contains("WhatsApp Business"))
        assertTrue(presentation.detail.contains("2.24.1.75"))
        assertTrue(presentation.detail.contains("com.whatsapp.w4b:id/entry"))
        assertNull("Success needs no next step", presentation.nextStep)
    }

    @Test
    fun `every failure tells the user what to do next`() {
        val failures = listOf(
            ProbeResult.NotInstalled(WaPackage.Consumer),
            ProbeResult.ServiceNotConnected,
            ProbeResult.CouldNotOpenWhatsApp(WaPackage.Consumer),
            ProbeResult.WhatsAppNeverAppeared(WaPackage.Consumer, "com.android.launcher", 15_000),
            ProbeResult.ComposeBoxNotFound(WaPackage.Consumer, "2.24.1.75", "dump")
        )

        for (failure in failures) {
            val presentation = ProbeResultPresenter.present(failure)
            assertFalse("$failure should not read as success", presentation.isSuccess)
            assertNotNull("$failure must offer a next step", presentation.nextStep)
            assertTrue("$failure must have a headline", presentation.headline.isNotBlank())
        }
    }

    @Test
    fun `only the stale-selector case offers shareable diagnostics`() {
        val staleSelectors = ProbeResultPresenter.present(
            ProbeResult.ComposeBoxNotFound(WaPackage.Consumer, "2.24.1.75", "the screen dump")
        )
        assertEquals("the screen dump", staleSelectors.shareableDiagnostics)

        // Nothing else has a dump worth sending anyone.
        val others = listOf(
            ProbeResult.NotInstalled(WaPackage.Consumer),
            ProbeResult.ServiceNotConnected,
            ProbeResult.CouldNotOpenWhatsApp(WaPackage.Consumer),
            ProbeResult.WhatsAppNeverAppeared(WaPackage.Consumer, null, 15_000)
        )
        for (result in others) {
            assertNull(
                "$result should not offer diagnostics",
                ProbeResultPresenter.present(result).shareableDiagnostics
            )
        }
    }

    @Test
    fun `stale selectors are framed as a fixable app update, not a broken permission`() {
        // This distinction matters: the client should not go re-toggling the
        // Accessibility permission when the permission is working fine.
        val presentation = ProbeResultPresenter.present(
            ProbeResult.ComposeBoxNotFound(WaPackage.Consumer, "2.24.1.75", "dump")
        )
        assertTrue(presentation.detail.contains("permission is working"))
        assertTrue(presentation.nextStep!!.contains("update"))
    }

    @Test
    fun `a timeout mentions what was on screen instead of whatsapp`() {
        val presentation = ProbeResultPresenter.present(
            ProbeResult.WhatsAppNeverAppeared(WaPackage.Consumer, "com.android.launcher", 15_000)
        )
        assertTrue(presentation.detail.contains("com.android.launcher"))
        assertTrue(presentation.detail.contains("15s"))
    }

    @Test
    fun `a timeout with nothing readable does not invent a package name`() {
        val presentation = ProbeResultPresenter.present(
            ProbeResult.WhatsAppNeverAppeared(WaPackage.Consumer, null, 15_000)
        )
        assertFalse(presentation.detail.contains("null"))
    }

    @Test
    fun `the not-installed case names the specific variant that is missing`() {
        val business = ProbeResultPresenter.present(ProbeResult.NotInstalled(WaPackage.Business))
        assertTrue(business.headline.contains("WhatsApp Business"))

        val consumer = ProbeResultPresenter.present(ProbeResult.NotInstalled(WaPackage.Consumer))
        assertTrue(consumer.headline.contains("WhatsApp"))
        assertFalse(consumer.headline.contains("Business"))
    }
}
