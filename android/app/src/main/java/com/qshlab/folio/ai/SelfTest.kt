package com.qshlab.folio.ai

import android.content.Context
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.jsonMapper
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.MessageCreateParams

/**
 * Checks, in the shrunk release build, that the Claude client still works without a real key:
 * a request with a fake key must fail as "unauthorized", and a sample response stream must parse.
 * Run with: adb shell am start -n com.qshlab.folio/.MainActivity --ez selftest true
 */
object SelfTest {
    fun run(ctx: Context): String {
        val out = StringBuilder()
        // 1. A request with a fake key: serialization, HTTP and error parsing.
        try {
            val c = AnthropicOkHttpClient.builder().apiKey("sk-ant-api03-folio-selftest-not-a-key").maxRetries(0).build()
            c.beta().messages().createStreaming(MessageCreateParams.builder().model("claude-opus-5-5").maxTokens(100)
                .system("x").addUserMessage("hi").outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                .addBeta("server-side-fallback-2026-07-01").fallbacks(BetaFallbacksParam.ofDefault()).build()).use { r -> r.stream().forEach { } }
            out.append("request: unexpected success; ")
        } catch (e: UnauthorizedException) {
            out.append("request: unauthorized as expected; ")
        } catch (e: Throwable) {
            out.append("request: FAILED ${e.javaClass.simpleName}: ${e.message?.take(120)}; ")
        }
        // 2. Parse a sample stream into a message.
        try {
            val events = listOf(
                """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}""",
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"rea"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"dy"}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}""",
                """{"type":"message_stop"}""")
            val acc = BetaMessageAccumulator.create()
            for (e in events) acc.accumulate(jsonMapper().readValue(e, BetaRawMessageStreamEvent::class.java))
            val m = acc.message()
            val text = m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            out.append("stream: text='$text' stop=${m.stopReason().orElse(null)} usage=${m.usage().inputTokens()}/${m.usage().outputTokens()}")
        } catch (e: Throwable) {
            out.append("stream: FAILED ${e.javaClass.simpleName}: ${e.message?.take(160)}")
        }
        return out.toString()
    }
}
