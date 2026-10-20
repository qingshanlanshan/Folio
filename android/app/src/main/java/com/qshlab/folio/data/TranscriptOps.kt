package com.qshlab.folio.data

import kotlin.math.max

/** A run of words by one speaker, with word indices (inclusive). */
data class Paragraph(val speaker: Int?, val start: Double, val end: Double, val first: Int, val last: Int)

private val SENTENCE_END = Regex("""[.?!…]['")\]]*$""")

fun Transcript.paragraphs(maxLen: Double = 60.0, gap: Double = 2.0): List<Paragraph> {
    val out = mutableListOf<Paragraph>()
    words.forEachIndexed { i, w ->
        val p = out.lastOrNull()
        val new = p == null || w.speaker != p.speaker || w.start - words[i - 1].end > gap ||
            (w.start - p.start > maxLen && SENTENCE_END.containsMatchIn(words[i - 1].text))
        if (new) out += Paragraph(w.speaker, w.start, w.end, i, i)
        else out[out.lastIndex] = p!!.copy(end = w.end, last = i)
    }
    return out
}

fun Transcript.text(p: Paragraph) = words.subList(p.first, p.last + 1).joinToString(" ") { it.text }

fun Transcript.speakerName(spk: Int?): String {
    if (spk == null) return "Unknown"
    return speakers[spk.toString()]?.name ?: "Speaker ${if (spk < 26) ('A' + spk) else spk.toString()}"
}

/**
 * Replaces words[first..last] with edited text. A word-level diff keeps the timing of every unchanged
 * word; changed runs share the time of the words they replace.
 */
fun Transcript.replaceSpan(first: Int, last: Int, text: String): Transcript {
    val old = words.subList(first, last + 1)
    require(old.isNotEmpty())
    val newTokens = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (old.map { it.text } == newTokens) return this
    val spk = old[0].speaker
    val made = mutableListOf<Word>()
    for (op in diff(old.map { it.text }, newTokens)) {
        if (op.tag == 'e') { made += old.subList(op.i1, op.i2); continue }
        if (op.j1 == op.j2) continue
        val (a, b) = if (op.i1 < op.i2) old[op.i1].start to old[op.i2 - 1].end else {
            (if (op.i1 > 0) old[op.i1 - 1].end else old[0].start) to (if (op.i1 < old.size) old[op.i1].start else old.last().end)
        }
        val toks = newTokens.subList(op.j1, op.j2)
        val total = toks.sumOf { it.length + 1 }.toDouble()
        var cur = a
        for (tok in toks) {
            val dur = max(b - a, 0.0) * (tok.length + 1) / total
            made += Word(tok, round3(cur), round3(cur + dur), 1.0, spk, edited = true)
            cur += dur
        }
    }
    return copy(words = words.subList(0, first) + made + words.subList(last + 1, words.size), edited = true)
}

fun Transcript.setSpeaker(first: Int, last: Int, speaker: Int): Transcript {
    val ws = words.toMutableList()
    for (i in first..last) ws[i] = ws[i].copy(speaker = speaker)
    val spk = speakers.toMutableMap()
    spk.putIfAbsent(speaker.toString(), Speaker())
    return copy(words = ws, speakers = spk, edited = true)
}

fun round3(x: Double) = Math.round(x * 1000) / 1000.0

private fun clock(s: Double, srt: Boolean = false): String {
    val h = (s / 3600).toInt(); val m = ((s % 3600) / 60).toInt(); val sec = s % 60
    return if (srt) "%02d:%02d:%02d,%03d".format(h, m, sec.toInt(), ((sec % 1) * 1000).toInt())
    else if (h > 0) "%d:%02d:%02d".format(h, m, sec.toInt()) else "%d:%02d".format(m, sec.toInt())
}

fun Transcript.export(fmt: String, title: String): String = when (fmt) {
    "srt" -> paragraphs(6.0, 0.8).mapIndexed { n, p ->
        "${n + 1}\n${clock(p.start, true)} --> ${clock(p.end, true)}\n${speakerName(p.speaker)}: ${text(p)}\n"
    }.joinToString("\n")
    "md" -> (if (title.isNotEmpty()) "# $title\n\n" else "") + paragraphs().joinToString("\n\n") {
        "**${speakerName(it.speaker)}** [${clock(it.start)}]  \n${text(it)}"
    } + "\n"
    else -> paragraphs().joinToString("\n\n") { "[${clock(it.start)}] ${speakerName(it.speaker)}: ${text(it)}" } + "\n"
}

// --- a small difflib.SequenceMatcher equivalent (longest matching blocks, recursively)

data class DiffOp(val tag: Char, val i1: Int, val i2: Int, val j1: Int, val j2: Int)  // tag: e(qual) r(eplace) d(elete) i(nsert)

fun <T> diff(a: List<T>, b: List<T>): List<DiffOp> {
    val blocks = mutableListOf<Triple<Int, Int, Int>>()
    fun rec(alo: Int, ahi: Int, blo: Int, bhi: Int) {
        var bi = alo; var bj = blo; var bk = 0
        // Longest common run in a[alo:ahi], b[blo:bhi] (O(n*m), fine for paragraphs and windows).
        var prev = IntArray(bhi - blo + 1)
        for (i in alo until ahi) {
            val cur = IntArray(bhi - blo + 1)
            for (j in blo until bhi) {
                if (a[i] == b[j]) {
                    val k = prev[j - blo] + 1
                    cur[j - blo + 1] = k
                    if (k > bk) { bk = k; bi = i - k + 1; bj = j - k + 1 }
                }
            }
            prev = cur
        }
        if (bk == 0) return
        rec(alo, bi, blo, bj)
        blocks += Triple(bi, bj, bk)
        rec(bi + bk, ahi, bj + bk, bhi)
    }
    rec(0, a.size, 0, b.size)
    blocks += Triple(a.size, b.size, 0)
    val ops = mutableListOf<DiffOp>()
    var i = 0; var j = 0
    for ((ai, bj, size) in blocks) {
        val tag = when {
            i < ai && j < bj -> 'r'; i < ai -> 'd'; j < bj -> 'i'; else -> null
        }
        if (tag != null) ops += DiffOp(tag, i, ai, j, bj)
        if (size > 0) ops += DiffOp('e', ai, ai + size, bj, bj + size)
        i = ai + size; j = bj + size
    }
    return ops
}
