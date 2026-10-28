package com.cyeam.seal.ocr

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

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
    @Test fun retainsFeedbackCapabilityWithoutBreakingLegacyResults() {
        val response = OcrClient.parseRecognition("""{"code":0,"feedback_token":"specific-image-token","result":[{"char":"女"}]}""")
        assertEquals("specific-image-token", response.feedbackToken)
        assertEquals("女", response.candidates.single().text)
        assertEquals("", OcrClient.parseRecognition("""["女"]""").feedbackToken)
    }
    @Test fun submitsChoicesToServerAndRequiresSuccessfulSave() = runBlocking {
        var request: JSONObject? = null
        var status = 200
        var body = """{"code":0}"""
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feedback") { exchange ->
            calls++
            assertEquals("POST", exchange.requestMethod)
            request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = body.toByteArray()
            exchange.sendResponseHeaders(status, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}/feedback"
            OcrClient.feedback("image-token", true, 2, "𢇷", endpoint)
            assertEquals("image-token", request!!.getString("token"))
            assertEquals("accepted", request!!.getString("decision"))
            assertEquals(2, request!!.getInt("candidate_rank"))
            assertEquals("𢇷", request!!.getString("character"))
            OcrClient.feedback("image-token", false, 2, "𢇷", endpoint)
            assertEquals("rejected", request!!.getString("decision"))
            assertEquals(0, request!!.getInt("candidate_rank"))
            assertEquals("", request!!.getString("character"))
            status = 409
            try {
                OcrClient.feedback("image-token", false, 0, "", endpoint)
                fail("pending upload must not be treated as a saved decision")
            } catch (e: OcrException) { assertEquals(409, e.status) }
            assertEquals(3, calls) // Upload waiting is owned by the server.
            status = 200
            body = """{"code":502}"""
            try {
                OcrClient.feedback("image-token", true, 1, "女", endpoint)
                fail("unsuccessful response must not be accepted")
            } catch (e: OcrException) { assertEquals(502, e.status) }
        } finally { server.stop(0) }
    }
}
