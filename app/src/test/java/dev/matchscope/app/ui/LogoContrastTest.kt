package dev.matchscope.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LogoContrastTest {
    private fun transparentLogo(vararg colors: Int) = IntArray(100) { if (it < 20) 0 else colors[(it - 20) % colors.size] }

    @Test fun lightVariantsStayBoundToTheSameTeamAndKeepSignedFallbacks() {
        val original = "https://img-cdn.hltv.org/teamlogo/source.png?s=source-signature"
        val light = "https://img-cdn.hltv.org/teamlogo/light.png?s=light-signature"
        val known = mapOf("9565" to light)
        assertEquals(listOf(light, original), teamLogoSources("9565", original, known, dark = false))
        assertEquals(listOf(original, light), teamLogoSources("9565", original, known, dark = true))
        assertEquals(listOf(original), teamLogoSources("another-team", original, known, dark = false))
        assertEquals(listOf(light), teamLogoSources("9565", light, known, dark = false))
        assertEquals(emptyList<String>(), teamLogoSources("unknown", null, known, dark = false))
    }

    @Test fun transparentMonochromeMarksCanAdaptWithoutAddingABackground() {
        assertEquals(LogoTone.DARK, logoTone(transparentLogo(0xFF000000.toInt(), 0xFF333333.toInt())))
        assertEquals(LogoTone.LIGHT, logoTone(transparentLogo(0xFFFFFFFF.toInt(), 0xFFEEEEEE.toInt())))
    }

    @Test fun coloredAndTwoToneBrandsKeepTheirSourceColors() {
        assertEquals(LogoTone.ORIGINAL, logoTone(transparentLogo(0xFF00BC79.toInt())))
        assertEquals(LogoTone.ORIGINAL, logoTone(transparentLogo(0xFFFFFF00.toInt())))
        assertEquals(LogoTone.ORIGINAL, logoTone(transparentLogo(0xFF000000.toInt(), 0xFFFFFFFF.toInt())))
        assertEquals(LogoTone.ORIGINAL, logoTone(transparentLogo(0xFF000000.toInt(), 0xFFFF0000.toInt())))
    }

    @Test fun opaqueImagesAndEmptyPlaceholdersAreNeverRecolored() {
        assertEquals(LogoTone.ORIGINAL, logoTone(IntArray(100) { 0xFFFFFFFF.toInt() }))
        assertEquals(LogoTone.ORIGINAL, logoTone(IntArray(100)))
        assertEquals(LogoTone.ORIGINAL, logoTone(intArrayOf(0, 0xFF000000.toInt())))
    }
}
