package com.cyeam.seal.ocr

import org.junit.Assert.*
import org.junit.Test

class OcrClientTest {
    @Test fun readsWrappedJsonStringAndRanksWholeMatchesFirst() {
        val body="""{"code":0,"result":"{\"matches\":[{\"char\":\"女\",\"match_type\":\"component\",\"score\":0.99},{\"char\":\"䝿\",\"match_type\":\"whole\",\"prob\":\"85%\",\"unicode_glyph_image\":\"https://glyphwiki.org/glyph/u477f.svg\"}]}"}"""
        val parsed=OcrClient.parse(body)
        assertEquals("䝿",parsed[0].text)
        assertEquals(0.85,parsed[0].score!!,0.001)
        assertTrue(parsed[0].unicodeImage.endsWith(".svg"))
    }
    @Test fun supportsLegacyArraysAndSupplementaryCharacters() {
        val result=OcrClient.parse("""[{"character":"𢇷","probability":0.8},"女"]""")
        assertEquals(2,result.size)
        assertEquals(1,result[0].text.codePointCount(0,result[0].text.length))
    }
    @Test fun ignoresEmptyCandidatesAndNonHttpsImages() {
        val result=OcrClient.parse("""{"code":0,"result":[{}, {"char":"女","glyph_image":"http://example.com/a.png"}]}""")
        assertEquals(1,result.size); assertEquals("",result[0].glyphImage)
    }
    @Test fun rejectsServerErrorsRatherThanTreatingThemAsText() {
        assertThrows(OcrException::class.java) { OcrClient.parse("""{"code":1,"message":"unavailable"}""") }
    }
    @Test fun emptyResultsAreEmpty() { assertTrue(OcrClient.parse("""{"matches":[]}""").isEmpty()) }
}
