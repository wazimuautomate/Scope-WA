package com.tricreta.scopewa.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaPackageTest {

    @Test
    fun `both variants the client uses are represented`() {
        // Architecture doc section 10, Q1: "Both business and normal whatsapp."
        assertEquals(2, WaPackage.entries.size)
        assertEquals("com.whatsapp", WaPackage.Consumer.packageName)
        assertEquals("com.whatsapp.w4b", WaPackage.Business.packageName)
    }

    @Test
    fun `packages resolve back to their variant`() {
        assertEquals(WaPackage.Consumer, WaPackage.fromPackageName("com.whatsapp"))
        assertEquals(WaPackage.Business, WaPackage.fromPackageName("com.whatsapp.w4b"))
    }

    @Test
    fun `an unknown or absent package resolves to nothing`() {
        assertNull(WaPackage.fromPackageName("com.android.launcher"))
        assertNull(WaPackage.fromPackageName(null))
    }

    @Test
    fun `package names agree with the selector constants and the manifest queries block`() {
        assertEquals(WaSelectors.PACKAGE_WHATSAPP, WaPackage.Consumer.packageName)
        assertEquals(WaSelectors.PACKAGE_WHATSAPP_BUSINESS, WaPackage.Business.packageName)
        assertEquals(
            WaSelectors.SUPPORTED_PACKAGES.toSet(),
            WaPackage.entries.map { it.packageName }.toSet()
        )
    }
}
