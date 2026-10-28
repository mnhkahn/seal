package com.cyeam.seal.ocr

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.ServerSocket
import java.net.SocketException
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

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
        val request = AtomicReference<JSONObject>()
        val headers = AtomicReference<String>()
        val calls = AtomicInteger()
        val server = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try {
                repeat(4) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().buffered()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val byte = input.read()
                            check(byte >= 0)
                            header.append(byte.toChar())
                        }
                        headers.set(header.toString())
                        val length = header.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                        val payload = ByteArray(length)
                        DataInputStream(input).readFully(payload)
                        request.set(JSONObject(String(payload, Charsets.UTF_8)))
                        val call = calls.incrementAndGet()
                        val status = if (call == 3) "409 Conflict" else "200 OK"
                        val response = if (call == 4) """{"code":502}""" else """{"code":0}"""
                        val bytes = response.toByteArray()
                        socket.getOutputStream().use { output ->
                            output.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(bytes)
                        }
                    }
                }
            } catch (_: SocketException) { /* Closed by test cleanup. */ }
        }.apply { isDaemon = true; start() }
        try {
            val endpoint = "http://127.0.0.1:${server.localPort}/feedback"
            OcrClient.feedback("image-token", true, 2, "𢇷", endpoint)
            assertTrue(headers.get().startsWith("POST /feedback HTTP/1.1"))
            assertEquals("image-token", request.get().getString("token"))
            assertEquals("accepted", request.get().getString("decision"))
            assertEquals(2, request.get().getInt("candidate_rank"))
            assertEquals("𢇷", request.get().getString("character"))
            OcrClient.feedback("image-token", false, 2, "𢇷", endpoint)
            assertEquals("rejected", request.get().getString("decision"))
            assertEquals(0, request.get().getInt("candidate_rank"))
            assertEquals("", request.get().getString("character"))
            try {
                OcrClient.feedback("image-token", false, 0, "", endpoint)
                fail("pending upload must not be treated as a saved decision")
            } catch (e: OcrException) { assertEquals(409, e.status) }
            assertEquals(3, calls.get()) // Upload waiting is owned by the server.
            try {
                OcrClient.feedback("image-token", true, 1, "女", endpoint)
                fail("unsuccessful response must not be accepted")
            } catch (e: OcrException) { assertEquals(502, e.status) }
        } finally { server.close(); worker.join(1000) }
    }
}
