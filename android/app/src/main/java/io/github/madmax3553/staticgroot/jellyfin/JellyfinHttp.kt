package io.github.madmax3553.staticgroot.jellyfin

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class JellyfinException(message: String, val status: Int? = null) : Exception(message)

/**
 * The network seam. The client talks to this interface, so unit tests can feed it recorded
 * JSON and the app uses [HttpUrlJellyfinHttp].
 */
interface JellyfinHttp {
    @Throws(JellyfinException::class)
    fun get(url: String, headers: Map<String, String>): String

    @Throws(JellyfinException::class)
    fun post(url: String, headers: Map<String, String>, body: String): String
}

/** Plain HttpURLConnection. Blocking, so call it off the main thread. */
class HttpUrlJellyfinHttp(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : JellyfinHttp {

    override fun get(url: String, headers: Map<String, String>): String = send("GET", url, headers, null)

    override fun post(url: String, headers: Map<String, String>, body: String): String =
        send("POST", url, headers, body)

    private fun send(method: String, url: String, headers: Map<String, String>, body: String?): String {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection)
        } catch (e: IOException) {
            throw JellyfinException("Cannot reach the server")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw JellyfinException("Server answered HTTP $code", code)
            return text
        } catch (e: IOException) {
            throw JellyfinException("Request failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }
}
