package com.qshlab.folio.speech

import com.qshlab.folio.audio.SR
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Who spoke when — the same method as Folio on the Mac: speech regions → TitaNet embeddings on 1.5 s
 * windows → spectral clustering with the speaker count chosen by the eigengap → clusters whose voices
 * match (cosine > 0.7) merged. On a phone, long recordings are first grouped into ≤ 300 micro-clusters
 * so the eigen decomposition stays small.
 */
data class Turn(val start: Double, var end: Double, val speaker: Int)

const val SAME_VOICE = 0.7f   // TitaNet centroids: same person ≥ 0.85, different people ≤ 0.56

object Diarizer {
    private const val WIN = 1.5
    private const val STEP = 0.75
    private const val MAX_POINTS = 300

    data class Result(val turns: List<Turn>, val centroids: Map<Int, FloatArray>)

    fun run(pcm: FloatArray, regions: List<Pair<Double, Double>>, emb: Embedder, numSpeakers: Int = -1,
            minShare: Double = 0.02, progress: (Float) -> Unit = {}): Result {
        val wins = windows(regions)
        if (wins.isEmpty()) return Result(emptyList(), emptyMap())
        val E = wins.mapIndexed { i, (a, b) ->
            if (i % 40 == 0) progress(i.toFloat() / wins.size)
            emb.embed(pcm.copyOfRange((a * SR).toInt(), min((b * SR).toInt(), pcm.size)))
        }
        val durs = wins.map { it.second - it.first }
        var lab = cluster(E, durs, numSpeakers)
        lab = foldSmall(E, durs, lab, minShare)
        if (numSpeakers <= 0) lab = mergeSameVoice(E, lab)
        // Relabel by first appearance.
        val order = LinkedHashMap<Int, Int>()
        for (c in lab) order.getOrPut(c) { order.size }
        lab = IntArray(lab.size) { order[lab[it]]!! }
        val cents = lab.distinct().associateWith { c -> unit(mean(E.indices.filter { lab[it] == c }.map { E[it] })) }
        // Each instant belongs to the window whose centre is nearest; then merge runs.
        val mids = wins.map { (it.first + it.second) / 2 }
        val turns = mutableListOf<Turn>()
        for ((a, b) in regions) {
            var t = a
            while (t < b) {
                var best = 0; var bd = Double.MAX_VALUE
                for (k in mids.indices) { val d = abs(mids[k] - t); if (d < bd) { bd = d; best = k } }
                val spk = lab[best]
                val last = turns.lastOrNull()
                if (last != null && last.speaker == spk && t - last.end < 0.11) last.end = min(t + 0.05, b)
                else turns += Turn(t, min(t + 0.05, b), spk)
                t += 0.05
            }
        }
        return Result(turns, cents)
    }

    /** Sets each word's speaker to the one whose turns overlap it most (else the nearest turn). */
    fun assign(words: List<W>, turns: List<Turn>, offset: Int = 0) {
        if (turns.isEmpty()) return
        var lo = 0
        for (w in words) {
            while (lo < turns.size && turns[lo].end < w.start - 30) lo++
            val totals = HashMap<Int, Double>()
            var k = lo
            while (k < turns.size && turns[k].start < w.end + 0.001) {
                val ov = min(turns[k].end, w.end) - max(turns[k].start, w.start)
                if (ov > 0) totals[turns[k].speaker] = (totals[turns[k].speaker] ?: 0.0) + ov
                k++
            }
            w.speaker = (totals.maxByOrNull { it.value }?.key ?: turns.minBy { abs((it.start + it.end) / 2 - (w.start + w.end) / 2) }.speaker) + offset
        }
    }

    private fun windows(regions: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        for ((a, b) in regions) {
            if (b - a < 0.4) continue
            if (b - a <= WIN) { out += a to b; continue }
            var t = a
            while (t + WIN < b) { out += t to t + WIN; t += STEP }
            out += max(a, b - WIN) to b
        }
        return out
    }

    private fun cluster(E: List<FloatArray>, durs: List<Double>, num: Int): IntArray {
        val n = E.size
        if (n < 4) return IntArray(n)
        if (n <= MAX_POINTS) return spectral(E, num)
        // Micro-clusters first, then spectral on their centroids.
        val k = MAX_POINTS
        val micro = kmeans(E, k, Random(0))
        val groups = (0 until k).map { c -> E.indices.filter { micro[it] == c } }.filter { it.isNotEmpty() }
        val cents = groups.map { g -> unit(mean(g.map { E[it] })) }
        val lab = spectral(cents, num)
        val out = IntArray(n)
        groups.forEachIndexed { gi, g -> for (i in g) out[i] = lab[gi] }
        return out
    }

    private fun spectral(E: List<FloatArray>, num: Int, maxK: Int = 10): IntArray {
        val n = E.size
        if (n < 4) return IntArray(n)
        val sim = Array(n) { i -> DoubleArray(n) { j -> dot(E[i], E[j]).toDouble() } }
        // Keep each row's strongest links (p-pruning), symmetrize.
        val keep = max(2, (n * 0.1).toInt())
        val A = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            val idx = (0 until n).sortedByDescending { sim[i][it] }.take(keep)
            for (j in idx) A[i][j] = sim[i][j]
        }
        for (i in 0 until n) for (j in i until n) {
            val v = max((A[i][j] + A[j][i]) / 2, 0.0); A[i][j] = v; A[j][i] = v
        }
        for (i in 0 until n) A[i][i] = 0.0
        val L = Array(n) { i -> DoubleArray(n) { j -> -A[i][j] } }
        for (i in 0 until n) L[i][i] = A[i].sum()
        val (vals, vecs) = jacobiEigen(L)
        val order = vals.indices.sortedBy { vals[it] }
        val k = if (num > 0) min(num, n) else {
            var best = 1; var gap = -1.0
            for (i in 0 until min(maxK, n - 1)) {
                val g = vals[order[i + 1]] - vals[order[i]]
                if (g > gap) { gap = g; best = i + 1 }
            }
            best
        }
        if (k <= 1) return IntArray(n)
        val X = List(n) { r -> FloatArray(k) { c -> vecs[r][order[c]].toFloat() }.let(::unit) }
        var bestLab = IntArray(n); var bestInertia = Double.MAX_VALUE
        repeat(5) { rep ->
            val lab = kmeans(X, k, Random(rep))
            val cents = (0 until k).map { c -> mean(X.indices.filter { lab[it] == c }.map { X[it] }.ifEmpty { listOf(X[0]) }) }
            val inertia = X.indices.sumOf { i -> dist2(X[i], cents[lab[i]]).toDouble() }
            if (inertia < bestInertia) { bestInertia = inertia; bestLab = lab }
        }
        return bestLab
    }

    private fun foldSmall(E: List<FloatArray>, durs: List<Double>, labIn: IntArray, minShare: Double): IntArray {
        val lab = labIn.copyOf()
        val total = durs.sum()
        while (true) {
            val ids = lab.distinct()
            if (ids.size <= 1) break
            val share = ids.associateWith { c -> lab.indices.filter { lab[it] == c }.sumOf { durs[it] } / total }
            val c = ids.filter { share[it]!! < minShare }.minByOrNull { share[it]!! } ?: break
            val mine = mean(lab.indices.filter { lab[it] == c }.map { E[it] })
            val target = ids.filter { it != c }.maxBy { o -> dot(mean(lab.indices.filter { lab[it] == o }.map { E[it] }), mine) }
            for (i in lab.indices) if (lab[i] == c) lab[i] = target
        }
        return lab
    }

    private fun mergeSameVoice(E: List<FloatArray>, labIn: IntArray): IntArray {
        val lab = labIn.copyOf()
        while (true) {
            val ids = lab.distinct()
            if (ids.size < 2) break
            val cents = ids.associateWith { c -> unit(mean(lab.indices.filter { lab[it] == c }.map { E[it] })) }
            var best = SAME_VOICE; var pair: Pair<Int, Int>? = null
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                val s = dot(cents[ids[i]]!!, cents[ids[j]]!!)
                if (s > best) { best = s; pair = ids[i] to ids[j] }
            }
            val (a, b) = pair ?: break
            for (i in lab.indices) if (lab[i] == b) lab[i] = a
        }
        return lab
    }

    // --- small linear algebra

    fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }
    private fun dist2(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) { val d = a[i] - b[i]; s += d * d }; return s }
    fun unit(v: FloatArray): FloatArray { val n = sqrt(dot(v, v)) + 1e-9f; return FloatArray(v.size) { v[it] / n } }
    fun mean(vs: List<FloatArray>): FloatArray {
        val m = FloatArray(vs[0].size); for (v in vs) for (i in v.indices) m[i] += v[i]
        for (i in m.indices) m[i] /= vs.size; return m
    }

    /** k-means++ seeding, a few Lloyd iterations. */
    private fun kmeans(X: List<FloatArray>, k: Int, rnd: Random, iters: Int = 25): IntArray {
        val n = X.size
        val cents = mutableListOf(X[rnd.nextInt(n)].copyOf())
        val d = FloatArray(n) { Float.MAX_VALUE }
        while (cents.size < min(k, n)) {
            for (i in 0 until n) d[i] = min(d[i], dist2(X[i], cents.last()))
            val total = d.sum()
            var r = rnd.nextFloat() * total; var pick = n - 1
            for (i in 0 until n) { r -= d[i]; if (r <= 0) { pick = i; break } }
            cents += X[pick].copyOf()
        }
        val lab = IntArray(n)
        repeat(iters) {
            var moved = false
            for (i in 0 until n) {
                var best = 0; var bd = Float.MAX_VALUE
                for (c in cents.indices) { val dd = dist2(X[i], cents[c]); if (dd < bd) { bd = dd; best = c } }
                if (lab[i] != best) { lab[i] = best; moved = true }
            }
            for (c in cents.indices) {
                val members = (0 until n).filter { lab[it] == c }
                if (members.isNotEmpty()) cents[c] = mean(members.map { X[it] })
            }
            if (!moved) return lab
        }
        return lab
    }

    /** Eigen decomposition of a symmetric matrix (cyclic Jacobi). Returns (values, vectors as columns). */
    private fun jacobiEigen(Ain: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val n = Ain.size
        val a = Array(n) { Ain[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        for (sweep in 0 until 60) {
            var off = 0.0
            for (p in 0 until n) for (q in p + 1 until n) off += a[p][q] * a[p][q]
            if (off < 1e-18) break
            for (p in 0 until n - 1) for (q in p + 1 until n) {
                val apq = a[p][q]
                if (abs(apq) < 1e-15) continue
                val theta = (a[q][q] - a[p][p]) / (2 * apq)
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1); val s = t * c
                for (k in 0 until n) {
                    val akp = a[k][p]; val akq = a[k][q]
                    a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq
                }
                for (k in 0 until n) {
                    val apk = a[p][k]; val aqk = a[q][k]
                    a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk
                }
                for (k in 0 until n) {
                    val vkp = v[k][p]; val vkq = v[k][q]
                    v[k][p] = c * vkp - s * vkq; v[k][q] = s * vkp + c * vkq
                }
            }
        }
        return DoubleArray(n) { a[it][it] } to v
    }
}
