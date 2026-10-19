package com.qshlab.folio.ai

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.qshlab.folio.data.readJson
import com.qshlab.folio.data.writeJson
import kotlinx.serialization.Serializable
import com.qshlab.folio.data.writeAtomic
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Claude (Anthropic API) for summaries, transcript fixes and questions — the only AI engine on Android.
 * Only text is sent; audio and speech recognition stay on the phone. The key is kept in the app's
 * private storage.
 */
class ClaudeError(message: String) : Exception(message)

data class ClaudeModel(val id: String, val name: String, val inPrice: Double, val outPrice: Double)

val CLAUDE_MODELS = listOf(
    ClaudeModel("claude-opus-5-5", "Claude Opus 5.5", 4.00, 20.00),
    ClaudeModel("claude-sonnet-5-5", "Claude Sonnet 5.5", 2.00, 10.00),
    ClaudeModel("claude-haiku-5-5", "Claude Haiku 5.5", 0.10, 0.50),
)

@Serializable
data class Month(val requests: Int = 0, val input: Long = 0, val output: Long = 0, val usd: Double = 0.0)

@Serializable
data class Usage(val months: Map<String, Month> = emptyMap())

class Claude(ctx: Context, private val home: File) {
    private val prefs = ctx.getSharedPreferences("claude", Context.MODE_PRIVATE)
    private var client: AnthropicClient? = null
    private var clientKey: String? = null
    private val usageFile get() = File(home, "claude-usage.json")

    // In Folio/anthropic-key (private to the app), the same file as on the Mac, so sync can share it.
    private val keyFile get() = File(home, "anthropic-key")

    init {   // keys saved by 0.3.0 and earlier lived in the app's preferences
        prefs.getString("key", null)?.takeIf { it.isNotBlank() }?.let { if (!keyFile.exists()) keyFile.writeAtomic(it.trim()) }
        prefs.edit().remove("key").apply()
    }

    var key: String?
        get() = keyFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        set(v) {
            if (v.isNullOrBlank()) keyFile.delete() else keyFile.writeAtomic(v.trim())
            client = null
            com.qshlab.folio.sync.Sync.kick()
        }

    var modelId: String
        get() = prefs.getString("model", null)?.takeIf { id -> CLAUDE_MODELS.any { it.id == id } } ?: CLAUDE_MODELS[0].id
        set(v) = prefs.edit().putString("model", v).apply()

    val model get() = CLAUDE_MODELS.first { it.id == modelId }
    val keyHint get() = key?.let { "…" + it.takeLast(4) }

    @Synchronized
    private fun client(): AnthropicClient {
        val k = key ?: throw ClaudeError("Add your Claude API key in Settings first.")
        if (client == null || clientKey != k) {
            client = AnthropicOkHttpClient.builder().apiKey(k).maxRetries(2).build()
            clientKey = k
        }
        return client!!
    }

    fun usage(): Usage = usageFile.readJson() ?: Usage()

    fun thisMonth(): Month = usage().months[SimpleDateFormat("yyyy-MM", Locale.US).format(Date())] ?: Month()

    @Synchronized
    private fun record(modelId: String, inp: Long, out: Long) {
        val m = CLAUDE_MODELS.firstOrNull { it.id == modelId } ?: model
        val cost = inp / 1e6 * m.inPrice + out / 1e6 * m.outPrice
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val u = usage()
        val cur = u.months[month] ?: Month()
        usageFile.writeJson(u.copy(months = u.months + (month to Month(cur.requests + 1, cur.input + inp, cur.output + out,
            Math.round((cur.usd + cost) * 10000) / 10000.0))))
    }

    /**
     * One request, streamed so long answers don't time out. [check] is called as text arrives so a
     * stopped task ends promptly (throw from it to stop).
     */
    fun chat(system: String, user: String, maxTokens: Long = 16000, effort: String = "medium", check: () -> Unit = {}): String {
        val m = model
        val b = MessageCreateParams.builder()
            .model(m.id)
            .maxTokens(maxTokens)
            .system(system)
            .addUserMessage(user)
            .outputConfig(BetaOutputConfig.builder().effort(
                when (effort) { "low" -> BetaOutputConfig.Effort.LOW; "high" -> BetaOutputConfig.Effort.HIGH; else -> BetaOutputConfig.Effort.MEDIUM }
            ).build())
        if (m.id != "claude-haiku-5-5") {
            // If a safety check declines the request, the API retries it on a suitable model.
            b.addBeta("server-side-fallback-2026-07-01").fallbacks(BetaFallbacksParam.ofDefault())
        }
        val acc = BetaMessageAccumulator.create()
        try {
            client().beta().messages().createStreaming(b.build()).use { resp ->
                val it = resp.stream().iterator()
                while (it.hasNext()) { acc.accumulate(it.next()); check() }
            }
        } catch (e: UnauthorizedException) {
            throw ClaudeError("Claude rejected the API key. Check it in Settings.")
        } catch (e: PermissionDeniedException) {
            throw ClaudeError("This API key isn't allowed to use ${m.name}.")
        } catch (e: RateLimitException) {
            throw ClaudeError("Claude is rate-limiting this key right now. Try again in a minute.")
        } catch (e: AnthropicIoException) {
            throw ClaudeError("Can't reach Claude (offline?). Try again when connected.")
        } catch (e: AnthropicServiceException) {
            val body = e.body().toString()
            if (e.statusCode() == 400 && body.contains("credit", ignoreCase = true)) throw ClaudeError("Your Anthropic account is out of credits.")
            throw ClaudeError("Claude API error ${e.statusCode()}.")
        }
        val msg = acc.message()
        record(msg.model().asString(), msg.usage().inputTokens(), msg.usage().outputTokens())
        if (msg.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw ClaudeError("Claude declined this request.")
        return msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
    }

    /** A tiny request to check the key and model. */
    fun test(): String = chat("Reply with the single word: ready", "Are you there?", 2000, "low").trim()
}
