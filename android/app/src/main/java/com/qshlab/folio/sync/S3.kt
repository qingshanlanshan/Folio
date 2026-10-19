package com.qshlab.folio.sync

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A small S3 client (AWS Signature V4) for Cloudflare R2: put, get, list. Same as folio/s3.py on the Mac. */
class S3(endpoint: String, private val bucket: String, private val keyId: String, private val secret: String,
         private val region: String = "auto") {
    class Error(val status: Int, message: String) : Exception(message)

    private val base = endpoint.trimEnd('/')
    private val host = URI(base).let { if (it.port > 0) "${it.host}:${it.port}" else it.host }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hmac(key: ByteArray, msg: String) =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(msg.toByteArray()) }

    private fun enc(s: String, keepSlash: Boolean = false) = buildString {
        for (b in s.toByteArray()) {
            val c = b.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.~" || (keepSlash && c == '/')) append(c)
            else append("%%%02X".format(b.toInt() and 0xff))
        }
    }

    fun request(method: String, key: String = "", query: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)): Pair<Int, ByteArray> {
        val now = Date()
        val amzDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val day = amzDate.take(8)
        val path = "/" + enc(bucket) + (if (key.isNotEmpty()) "/" + enc(key, keepSlash = true) else "")
        val qs = query.toSortedMap().entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val payload = hex(sha256(body))
        val headers = sortedMapOf("host" to host, "x-amz-content-sha256" to payload, "x-amz-date" to amzDate)
        val signed = headers.keys.joinToString(";")
        val canonical = listOf(method, path, qs, headers.entries.joinToString("") { "${it.key}:${it.value}\n" }, signed, payload).joinToString("\n")
        val scope = "$day/$region/s3/aws4_request"
        val toSign = listOf("AWS4-HMAC-SHA256", amzDate, scope, hex(sha256(canonical.toByteArray()))).joinToString("\n")
        var k = hmac("AWS4$secret".toByteArray(), day)
        for (part in listOf(region, "s3", "aws4_request")) k = hmac(k, part)
        val sig = hex(Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); doFinal(toSign.toByteArray()) })

        val conn = URL(base + path + (if (qs.isNotEmpty()) "?$qs" else "")).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("x-amz-content-sha256", payload)
        conn.setRequestProperty("x-amz-date", amzDate)
        conn.setRequestProperty("Authorization", "AWS4-HMAC-SHA256 Credential=$keyId/$scope, SignedHeaders=$signed, Signature=$sig")
        try {
            if (method == "PUT") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val data = (if (code >= 400) conn.errorStream else conn.inputStream)?.use(InputStream::readBytes) ?: ByteArray(0)
            if (code == 404 && (method == "HEAD" || String(data).contains("NoSuchKey"))) return 404 to ByteArray(0)
            if (code >= 400) {
                val msg = Regex("<Message>(.*?)</Message>").find(String(data))?.groupValues?.get(1) ?: "HTTP $code"
                throw Error(code, msg.take(300))
            }
            return code to data
        } finally { conn.disconnect() }
    }

    fun put(key: String, body: ByteArray) { request("PUT", key, body = body) }
    fun get(key: String): ByteArray? = request("GET", key).let { (code, data) -> if (code == 404) null else data }

    /** → (keys, common prefixes), all pages. */
    fun list(prefix: String, startAfter: String = "", delimiter: String = ""): Pair<List<String>, List<String>> {
        val keys = mutableListOf<String>(); val prefixes = mutableListOf<String>()
        var token: String? = null
        while (true) {
            val q = mutableMapOf("list-type" to "2", "prefix" to prefix)
            if (startAfter.isNotEmpty()) q["start-after"] = startAfter
            if (delimiter.isNotEmpty()) q["delimiter"] = delimiter
            token?.let { q["continuation-token"] = it }
            val p = XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(request("GET", query = q).second.inputStream(), "UTF-8")
            var truncated = false; token = null
            val stack = ArrayDeque<String>()
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> stack.addLast(p.name)
                    XmlPullParser.END_TAG -> stack.removeLastOrNull()
                    XmlPullParser.TEXT -> {
                        val tag = stack.lastOrNull(); val parent = stack.getOrNull(stack.size - 2)
                        when {
                            tag == "Key" && parent == "Contents" -> keys += p.text
                            tag == "Prefix" && parent == "CommonPrefixes" -> prefixes += p.text
                            tag == "IsTruncated" -> truncated = p.text == "true"
                            tag == "NextContinuationToken" -> token = p.text
                        }
                    }
                }
            }
            if (!truncated || token == null) return keys to prefixes
        }
    }
}
