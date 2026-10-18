package com.qshlab.folio.ai

import android.content.Context
import com.qshlab.folio.audio.SR
import com.qshlab.folio.data.Corrections
import com.qshlab.folio.data.Fix
import com.qshlab.folio.data.FixRun
import com.qshlab.folio.data.NotFound
import com.qshlab.folio.data.People
import com.qshlab.folio.data.Person
import com.qshlab.folio.data.Store
import com.qshlab.folio.data.Transcript
import com.qshlab.folio.data.diff
import com.qshlab.folio.data.now
import com.qshlab.folio.data.paragraphs
import com.qshlab.folio.data.readJson
import com.qshlab.folio.data.replaceSpan
import com.qshlab.folio.data.speakerName
import com.qshlab.folio.data.writeJson
import com.qshlab.folio.speech.W
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * After-recording transcript fixes, as on the Mac. Modes: text (Claude proofreads with the glossary,
 * people and project notes), audio (Whisper listens again; disagreements on glossary terms or unsure
 * words become fixes), both. Glossary "also heard as" entries always apply. Every fix must sound like
 * what was transcribed and use real words, and waits for your review in corrections.json.
 */
class Fixes(private val ctx: Context, private val store: Store, private val people: People, private val claude: Claude) {
    private val FILLERS = setOf("uh", "um", "hmm", "mm", "mhm", "ah", "er", "erm", "oh", "eh")
    private val LOW_CONF = 0.75
    private val WINDOW_WORDS = 1100

    private fun n(w: String) = w.lowercase().replace(Regex("[^\\w']"), "")
    private fun ntoks(t: String) = t.split(Regex("\\s+")).map(::n).filter { it.isNotEmpty() }
    private fun glue(t: String) = t.lowercase().replace(Regex("[^a-z0-9]"), "")

    private val words: Set<String> by lazy {
        GZIPInputStream(ctx.assets.open("words.txt.gz")).bufferedReader().useLines { it.toHashSet() }
    }

    private fun known(tok: String, vocab: Set<String>): Boolean {
        if (tok in words || tok in vocab || tok.length <= 2) return true
        for (suf in listOf("s", "es", "ed", "d", "ing", "ly", "er", "ers", "ics"))
            if (tok.endsWith(suf) && (tok.dropLast(suf.length) in words || tok.dropLast(suf.length) + "e" in words)) return true
        return false
    }

    /** Rough sound skeleton: consonants after common spelling merges, first letter kept. */
    private fun phon(text: String): String {
        var s = text.lowercase().replace(Regex("[^a-z]"), "")
        for ((a, b) in listOf("ph" to "f", "ck" to "k", "q" to "k", "x" to "ks", "z" to "s", "dg" to "j", "wh" to "w",
            "gh" to "g", "th" to "0", "sh" to "x", "ch" to "x")) s = s.replace(a, b)
        s = s.replace(Regex("c(?=[eiy])"), "s").replace("c", "k")
        if (s.isEmpty()) return ""
        return (s[0] + s.substring(1).replace(Regex("[aeiouyhw]"), "")).replace(Regex("(.)\\1+"), "$1")
    }

    private fun ratio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val m = diff(a.toList(), b.toList()).filter { it.tag == 'e' }.sumOf { it.i2 - it.i1 }
        return 2.0 * m / (a.length + b.length)
    }

    /** Does the fix sound like what was transcribed, with real words, and not just a rewrite? */
    fun plausible(old: String, new: String, keyterms: Set<String>, vocab: Set<String> = emptySet()): Boolean {
        var o = ntoks(old); var nw = ntoks(new)
        if (nw.isEmpty() || o == nw) return false
        val term = nw.any { it in keyterms && it !in o }
        if (o.size == 1 && FILLERS.any { o[0].startsWith(it) && o[0].substring(it.length) == glue(new) }) return true
        while (o.isNotEmpty() && nw.isNotEmpty() && o[0] == nw[0]) { o = o.drop(1); nw = nw.drop(1) }
        while (o.isNotEmpty() && nw.isNotEmpty() && o.last() == nw.last()) { o = o.dropLast(1); nw = nw.dropLast(1) }
        if (nw.isEmpty()) return false
        val oc = o.filter { it !in FILLERS }; val nc = nw.filter { it !in FILLERS }
        if (oc.joinToString("") == nc.joinToString("")) return '-' !in old && '-' !in new && oc.size > nc.size
        if (Regex("\\d").containsMatchIn(new) && !Regex("\\d").containsMatchIn(old)) return false
        if ((o + nw).any { '\'' in it }) return false
        if (!term && (nw.toSet() - o.toSet()).size > 2) return false
        val po = phon(o.joinToString(" ")); val pn = phon(nw.joinToString(" "))
        if (!term && o.size != nw.size && po != pn) return false
        if (!nw.all { known(it, vocab + keyterms) }) return false
        if (!term) {
            if (po == pn && o.size == nw.size) return false
            val (a, b) = listOf(o.joinToString(" "), nw.joinToString(" ")).sortedBy { it.length }
            if (a in b && b.length - a.length <= 2) return false
        }
        return ratio(po, pn) >= if (term) 0.5 else 0.67
    }

    private fun keyterms(entries: List<GlossaryEntry>, ppl: List<Person>) =
        (entries.flatMap { it.term.split(" ") } + ppl.flatMap { it.name.split(" ") }).map(::n).filter { it.isNotEmpty() }.toSet()

    private fun item(first: Int, last: Int, old: String, new: String, why: String, source: String) =
        Fix(UUID.randomUUID().toString().take(8), first, last, old, new.trim(), why, source)

    private fun glossaryFixes(t: Transcript, entries: List<GlossaryEntry>): List<Fix> {
        val toks = t.words.map { n(it.text) }
        val out = mutableListOf<Fix>()
        for (e in entries) for (v in e.heard) {
            val vt = ntoks(v); if (vt.isEmpty()) continue
            for (i in 0..toks.size - vt.size) {
                if (toks.subList(i, i + vt.size) != vt) continue
                val last = i + vt.size - 1
                val old = t.words.subList(i, last + 1).joinToString(" ") { it.text }
                val tail = Regex("[^\\w']*$").find(t.words[last].text)?.value ?: ""
                out += item(i, last, old, e.term + tail, "Glossary: “$v” is usually “${e.term}”", "glossary")
            }
        }
        return out
    }

    private fun audioFixes(t: Transcript, pcm: FloatArray, entries: List<GlossaryEntry>, ppl: List<Person>,
                           listen: (FloatArray, Double) -> List<W>, progress: (Float) -> Unit, check: () -> Unit): Pair<List<Fix>, Map<Int, List<String>>> {
        val keyterms = keyterms(entries, ppl)
        val vocab = t.words.map { n(it.text) }.toSet()
        val out = mutableListOf<Fix>(); val hints = HashMap<Int, MutableList<String>>()
        val paras = t.paragraphs()
        paras.forEachIndexed { pi, p ->
            check(); progress(pi.toFloat() / paras.size)
            val a = maxOf(0, ((p.start - 0.3) * SR).toInt()); val b = minOf(pcm.size, ((p.end + 0.3) * SR).toInt())
            if (b - a < SR / 2) return@forEachIndexed
            val seg = listen(pcm.copyOfRange(a, b), a.toDouble() / SR)
            val cur = t.words.subList(p.first, p.last + 1)
            val ca = cur.map { n(it.text) }; val sb = seg.map { n(it.text) }
            for (op in diff(ca, sb)) {
                if (op.tag != 'r') continue
                val oldT = ca.subList(op.i1, op.i2); val newT = sb.subList(op.j1, op.j2)
                if ((oldT + newT).all { it in FILLERS } || oldT == newT) continue
                val old = cur.subList(op.i1, op.i2).joinToString(" ") { it.text }
                val new = seg.subList(op.j1, op.j2).joinToString(" ") { it.text }
                hints.getOrPut(pi) { mutableListOf() } += "“$old” (second listen: “$new”)"
                val termHit = newT.any { it in keyterms && it !in oldT }
                val unsure = cur.subList(op.i1, op.i2).minOf { it.conf } < LOW_CONF
                if (termHit || (unsure && newT.size <= 3 && plausible(old, new, keyterms, vocab)))
                    out += item(p.first + op.i1, p.first + op.i2 - 1, old, new,
                        if (termHit) "Second listen heard a glossary term or name" else "Unsure word; a second listen heard it differently", "audio")
            }
        }
        return out to hints
    }

    private val SYSTEM = """You correct speech-recognition errors in meeting transcripts.
Fix ONLY words that were clearly misheard: the transcript word makes no sense in context AND the correct word
sounds almost the same (e.g. "the sis" → "thesis", "pie torch" → "PyTorch"). Fix misspelled names and terms
using the glossary and people list.
NEVER: rephrase, fix grammar, change numbers or how they are written, change British/American spelling,
singular/plural, hyphenation, or remove filler words. Most paragraphs need no fix. When unsure, leave it.
Reply as JSON: {"fixes": [{"p": <paragraph number>, "find": "<exact text from that paragraph, 1-6 words>",
"replace": "<corrected text>", "why": "<short reason>"}]}. Reply {"fixes": []} if nothing needs fixing."""

    private fun textFixes(pid: String, t: Transcript, entries: List<GlossaryEntry>, ppl: List<Person>, hints: Map<Int, List<String>>,
                          sums: Summaries, progress: (Float) -> Unit, check: () -> Unit): List<Fix> {
        val keyterms = keyterms(entries, ppl)
        val vocab = t.words.map { n(it.text) }.toSet()
        val paras = t.paragraphs()
        val gl = entries.take(80).joinToString("\n") { e -> "- ${e.term}" + (if (e.heard.isNotEmpty()) " (often misheard as: ${e.heard.joinToString(", ")})" else "") + if (e.note.isNotEmpty()) ": ${e.note}" else "" }.ifEmpty { "(none)" }
        val pl = ppl.take(30).joinToString("\n") { "- ${it.name}" + if (it.notes.isNotEmpty()) ": ${it.notes.take(160)}" else "" }.ifEmpty { "(none)" }
        val head = "Project notes:\n${store.knowledge(pid).take(1500).ifEmpty { "(none)" }}\n\nGlossary:\n$gl\n\nPeople:\n$pl\n"
        val windows = mutableListOf<List<Int>>(); var cur = mutableListOf<Int>(); var nw = 0
        paras.forEachIndexed { i, p -> cur += i; nw += p.last - p.first + 1; if (nw >= WINDOW_WORDS) { windows += cur; cur = mutableListOf(); nw = 0 } }
        if (cur.isNotEmpty()) windows += cur
        val out = mutableListOf<Fix>()
        windows.forEachIndexed { wi, idxs ->
            val lines = mutableListOf<String>(); val notes = mutableListOf<String>()
            for (i in idxs) {
                val p = paras[i]; val ws = t.words.subList(p.first, p.last + 1)
                lines += "[$i] ${t.speakerName(p.speaker)}: " + ws.joinToString(" ") { it.text }
                ws.filter { it.conf < LOW_CONF && n(it.text) !in FILLERS }.take(8).takeIf { it.isNotEmpty() }?.let { u -> notes += "[$i] unsure: " + u.joinToString(", ") { "“${it.text}”" } }
                hints[i]?.take(6)?.forEach { notes += "[$i] $it" }
            }
            var user = head + "\nTranscript:\n" + lines.joinToString("\n")
            if (notes.isNotEmpty()) user += "\n\nRecognizer notes (may help, may be wrong):\n" + notes.joinToString("\n")
            val r = runCatching { sums.chatJson(SYSTEM, user, "low", check) }.getOrElse { if (it is ClaudeError) throw it else null }
            r?.arr("fixes")?.forEach { f -> (f as? JsonObject)?.let { applyFix(t, paras, it) }?.takeIf { plausible(it.old, it.new, keyterms, vocab) }?.let(out::add) }
            progress((wi + 1f) / windows.size)
        }
        return out
    }

    private fun applyFix(t: Transcript, paras: List<com.qshlab.folio.data.Paragraph>, f: JsonObject): Fix? {
        val pi = (f["p"] as? JsonPrimitive)?.intOrNull ?: (f["p"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
        val find = f.str("find").trim(); val rep = f.str("replace").trim()
        if (pi !in paras.indices || find.isEmpty() || rep.isEmpty() || find == rep) return null
        if (find.split(" ").size > 8 || rep.split(" ").size > 2 * find.split(" ").size + 2) return null
        if (ntoks(find) == ntoks(rep)) return null
        val p = paras[pi]; val ws = t.words.subList(p.first, p.last + 1)
        val offs = mutableListOf<Pair<Int, Int>>(); var pos = 0
        for (w in ws) { offs += pos to pos + w.text.length; pos += w.text.length + 1 }
        val text = ws.joinToString(" ") { it.text }
        var at = text.indexOf(find); if (at < 0) at = text.lowercase().indexOf(find.lowercase()); if (at < 0) return null
        val end = at + find.length
        val i1 = offs.indexOfFirst { it.second > at }; val i2 = offs.indexOfLast { it.first < end }
        if (i1 < 0 || i2 < i1) return null
        val span = text.substring(offs[i1].first, offs[i2].second)
        val new = span.substring(0, at - offs[i1].first) + rep + span.substring(end - offs[i1].first)
        return item(p.first + i1, p.first + i2, span, new, f.str("why").take(160), "text")
    }

    private fun merge(items: List<Fix>): List<Fix> {
        val rank = mapOf("glossary" to 0, "audio" to 1, "text" to 2)
        val taken = HashSet<Int>(); val out = mutableListOf<Fix>()
        for (it in items.sortedWith(compareBy({ rank[it.source] }, { it.first }))) {
            val span = (it.first..it.last).toSet()
            if (span.any { s -> s in taken } || ntoks(it.old) == ntoks(it.new)) continue
            taken += span; out += it
        }
        return out.sortedBy { it.first }
    }

    fun file(pid: String, mid: String) = File(store.meetingDir(pid, mid), "corrections.json")
    fun load(pid: String, mid: String): Corrections = file(pid, mid).readJson() ?: Corrections()

    fun run(pid: String, mid: String, mode: String, pcm: () -> FloatArray, listen: ((FloatArray, Double) -> List<W>)?,
            sums: Summaries, step: (String, Float) -> Unit, check: () -> Unit) {
        val t = people.resolve(store.transcript(pid, mid) ?: throw NotFound(mid))
        val entries = parseGlossary(store.glossary(pid))
        val linked = t.speakers.values.mapNotNull { it.person }.toSet()
        val ppl = people.all().filter { pid in it.projects || it.id in linked }
        val items = glossaryFixes(t, entries).toMutableList()
        var hints: Map<Int, List<String>> = emptyMap()
        if (mode in setOf("audio", "both") && listen != null) {
            step("listening again", 0f)
            val (found, h) = audioFixes(t, pcm(), entries, ppl, listen, { step("listening again", it) }, check)
            items += found; hints = h
        }
        if (mode in setOf("text", "both")) {
            step("checking text", 0f)
            items += textFixes(pid, t, entries, ppl, hints, sums, { step("checking text", it) }, check)
        }
        val prev = load(pid, mid)
        val rejected = prev.items.filter { it.status == "rejected" }.map { it.old to it.new }.toSet()
        val fresh = merge(items).filter { (it.old to it.new) !in rejected }
        file(pid, mid).writeJson(Corrections(prev.runs + FixRun(mode, now(), fresh.size), prev.items.filter { it.status != "pending" } + fresh))
    }

    private fun locate(t: Transcript, f: Fix): IntRange? {
        val old = f.old.split(" ")
        fun at(i: Int) = i >= 0 && i + old.size <= t.words.size && t.words.subList(i, i + old.size).map { it.text } == old
        if (at(f.first)) return f.first until f.first + old.size
        for (d in 1 until 400) for (i in listOf(f.first - d, f.first + d)) if (at(i)) return i until i + old.size
        return null
    }

    fun decide(pid: String, mid: String, ids: Set<String>, accept: Boolean) {
        val c = load(pid, mid)
        var t = store.transcript(pid, mid) ?: throw NotFound(mid)
        val items = c.items.map { f ->
            if (f.id !in ids || f.status != "pending") return@map f
            if (!accept) return@map f.copy(status = "rejected")
            val span = locate(t, f) ?: return@map f.copy(status = "stale")
            t = t.replaceSpan(span.first, span.last, f.new)
            f.copy(status = "accepted")
        }
        store.saveTranscript(pid, mid, t)
        file(pid, mid).writeJson(c.copy(items = items))
    }
}
