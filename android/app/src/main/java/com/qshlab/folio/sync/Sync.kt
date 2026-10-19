package com.qshlab.folio.sync

import android.content.Context
import android.os.Build
import com.qshlab.folio.FolioApp
import com.qshlab.folio.data.Changes
import com.qshlab.folio.data.MeetingMeta
import com.qshlab.folio.data.json
import com.qshlab.folio.data.readJson
import com.qshlab.folio.data.writeJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Sync between your devices through your own Cloudflare R2 bucket, end-to-end encrypted. Off unless set up
 * in Settings. The same protocol as folio/sync.py on the Mac (see there for the bucket layout): each device
 * adds batches of changes and content-addressed pieces; for each file the change with the highest
 * (clock, device) wins. Paths are the same on both: projects/<p>/…, people/<id>.json and .voices.json.
 */

@Serializable
data class SyncConfig(val endpoint: String, val bucket: String, val keyId: String, val secret: String, val key: String,
                      val device: String = "", val name: String = "")

@Serializable
data class Change(val p: String, val c: List<String> = emptyList(), val s: Long = 0, val t: Long, val d: String, val x: Boolean = false)

@Serializable
data class Batch(val v: Int = 1, val changes: List<Change>)

@Serializable
data class LocalFile(val s: Long = 0, val m: Long = 0, val c: List<String> = emptyList(), val t: Long, val d: String, val x: Boolean = false)

@Serializable
data class DeviceInfo(val name: String, val platform: String, val seen: Double, val me: Boolean = false)

@Serializable
data class SyncState(
    var seq: Long = -1, var clock: Long = 0,        // seq -1: not known yet (fresh start or rejoined)
    val cursors: MutableMap<String, String> = mutableMapOf(),
    val files: MutableMap<String, LocalFile> = mutableMapOf(),
    val known: MutableMap<String, Change> = mutableMapOf(),
    val pieces: MutableSet<String> = mutableSetOf(),
    var announced: Double = 0.0, var listed: Double = 0.0,
    var devices: List<DeviceInfo> = emptyList(),
)

@Serializable
private data class Marker(val format: Int = 1, val check: String)

@Serializable
private data class CodePayload(val endpoint: String, val bucket: String, val keyId: String, val secret: String, val key: String)

data class SyncStatus(val running: Boolean = false, val last: Double? = null, val error: String? = null,
                      val sent: Int = 0, val received: Int = 0)

class SyncError(message: String) : Exception(message)

object Sync {
    private const val CHUNK = 8 shl 20
    private const val INTERVAL_MS = 5 * 60_000L
    private val SYNCED = Regex("""^(projects/[^/]+/(project\.json|project\.md|glossary\.tsv|state\.md|todos\.json|state-history/[^/]+\.(md|json))""" +
        """|projects/[^/]+/meetings/[^/]+/(meeting\.json|transcript\.json|summary\.json|corrections\.json|audio\.m4a|voices\.json)""" +
        """|people/[^/]+\.json|anthropic-key)$""")
    private val META = setOf("meeting.json", "project.json")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status
    private val _config = MutableStateFlow<SyncConfig?>(null)
    val config: StateFlow<SyncConfig?> = _config

    private lateinit var ctx: Context
    private val home get() = FolioApp.instance.store.home
    private val stateFile get() = File(home, ".sync/state.json")
    private val prefs get() = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun start(context: Context) {
        ctx = context.applicationContext
        _config.value = prefs.getString("config", null)?.let { runCatching { json.decodeFromString<SyncConfig>(it) }.getOrNull() }
        // phones that joined before the id was kept: remember the current one
        _config.value?.let { c -> if (prefs.getString("device", null) == null) prefs.edit().putString("device", c.device).apply() }
        scope.launch {
            while (true) {
                if (_config.value != null) runOnce()
                withTimeoutOrNull(INTERVAL_MS) { wake.receive() }
            }
        }
    }

    /** Sync soon (app opened, a recording finished, "Sync now"). */
    fun kick() { wake.trySend(Unit) }

    // --- setup and pairing

    private fun saveConfig(c: SyncConfig?) {
        prefs.edit().apply { if (c == null) remove("config") else putString("config", json.encodeToString(c)) }.apply()
        _config.value = c
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun master(c: SyncConfig) = Base64.getUrlDecoder().decode(c.key)
    private fun encKey(c: SyncConfig) = hmac(master(c), "folio-sync enc".toByteArray())
    private fun macKey(c: SyncConfig) = hmac(master(c), "folio-sync mac".toByteArray())
    private fun check(c: SyncConfig) = hex(hmac(macKey(c), "folio-sync".toByteArray())).take(16)
    private fun client(c: SyncConfig) = S3(c.endpoint, c.bucket, c.keyId, c.secret)
    private fun randomHex(n: Int) = hex(ByteArray(n).also { SecureRandom().nextBytes(it) })

    /** Kept when sync is turned off, so rejoining isn't a new device. */
    private fun deviceId(): String = prefs.getString("device", null)?.takeIf { Regex("[0-9a-f]{12}").matches(it) }
        ?: randomHex(6).also { prefs.edit().putString("device", it).apply() }
    private fun deviceName() = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
        .distinct().joinToString(" ").let { if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else it }

    fun endpoint(account: String): String {
        val a = account.trim()
        if (a.startsWith("https://") || a.startsWith("http://")) return java.net.URI(a).let { "${it.scheme}://${it.authority}" }
        if (!Regex("[0-9a-f]{32}").matches(a)) throw SyncError("The account ID is 32 letters and digits (Cloudflare dashboard → R2 → Account details).")
        return "https://$a.r2.cloudflarestorage.com"
    }

    private fun marker(c: SyncConfig): Marker? = try {
        client(c).get("folio-sync.json")?.let { json.decodeFromString<Marker>(String(it)) }
    } catch (e: S3.Error) {
        throw when (e.status) {
            401, 403 -> SyncError("Cloudflare refused the key. Check the access key ID and secret, and that the token can read and write this bucket.")
            404 -> SyncError("There's no bucket named “${c.bucket}” in this account.")
            else -> SyncError("Cloudflare said: ${e.message}")
        }
    } catch (e: IOException) {
        throw SyncError("Couldn't reach Cloudflare (${e.javaClass.simpleName}). Check the account ID and your connection.")
    }

    /** First device: check the bucket and start a new sync key. Call off the main thread. */
    fun connect(account: String, bucket: String, keyId: String, secret: String) {
        val c = SyncConfig(endpoint(account), bucket.trim(), keyId.trim(), secret.trim(),
            Base64.getUrlEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }), deviceId(), deviceName())
        if (listOf(c.bucket, c.keyId, c.secret).any { it.isEmpty() }) throw SyncError("Fill in all four fields.")
        if (marker(c) != null) throw SyncError("This bucket already holds Folio data. On the device that set it up, open Settings → Sync → “Code for another device”, and join with that code instead.")
        client(c).put("folio-sync.json", json.encodeToString(Marker(1, check(c))).toByteArray())
        begin(c)
    }

    fun code(): String {
        val c = _config.value ?: throw SyncError("Sync is off.")
        val p = kotlinx.serialization.json.Json.encodeToString(CodePayload(c.endpoint, c.bucket, c.keyId, c.secret, c.key))
        return "folio1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(p.toByteArray())
    }

    /** Another device: use the code from a device that already syncs. Call off the main thread. */
    fun join(text: String) {
        val t = text.trim()
        if (!t.startsWith("folio1:")) throw SyncError("That isn't a Folio sync code. It starts with “folio1:”.")
        val p = runCatching { json.decodeFromString<CodePayload>(String(Base64.getUrlDecoder().decode(t.substring(7)))) }
            .getOrElse { throw SyncError("The code is incomplete. Copy all of it.") }
        val c = SyncConfig(p.endpoint, p.bucket, p.keyId, p.secret, p.key, deviceId(), deviceName())
        val m = marker(c) ?: throw SyncError("The bucket in this code is empty. Set up sync on the other device first.")
        if (m.check != check(c)) throw SyncError("This code doesn't match the data in the bucket. Get a fresh code from the other device.")
        begin(c)
    }

    private fun begin(c: SyncConfig) {
        synchronized(lock) { stateFile.delete(); saveConfig(c) }
        _status.value = SyncStatus()
        kick()
    }

    /** Stop syncing on this phone. Nothing is deleted, here or in the bucket. */
    fun disconnect() {
        synchronized(lock) { saveConfig(null); stateFile.delete() }
        _status.value = SyncStatus()
    }

    fun devices(): List<DeviceInfo> = stateFile.readJson<SyncState>()?.devices ?: emptyList()

    // --- encryption

    private fun seal(c: SyncConfig, data: ByteArray, aad: String): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey(c), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.toByteArray())
        return nonce + cipher.doFinal(data)
    }

    private fun open(c: SyncConfig, blob: ByteArray, aad: String): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey(c), "AES"), GCMParameterSpec(128, blob, 0, 12))
        cipher.updateAAD(aad.toByteArray())
        cipher.doFinal(blob, 12, blob.size - 12)
    } catch (e: AEADBadTagException) {
        throw SyncError("Some synced data couldn't be decrypted (wrong key or damaged).")
    } catch (e: IllegalArgumentException) {
        throw SyncError("Some synced data couldn't be decrypted (damaged).")
    }

    private fun pieceId(c: SyncConfig, data: ByteArray, len: Int = data.size) =
        hex(Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(macKey(c), "HmacSHA256")); update(data, 0, len); doFinal() })

    // --- local files

    private fun busy(): Set<String> {
        val app = FolioApp.instance
        val out = app.engine.tasks.value.filter { it.mid != "_state" }.map { "projects/${it.pid}/meetings/${it.mid}/" }.toMutableSet()
        app.recorder.state.value?.let { out += "projects/${it.pid}/meetings/${it.mid}/" }
        return out
    }

    /** → (synced path → file, meeting folders to leave alone this time). */
    private fun scan(): Pair<Map<String, File>, Set<String>> {
        val skip = busy().toMutableSet()
        val projects = File(home, "projects").listFiles().orEmpty().filter { it.isDirectory }
        val candidates = mutableListOf<File>()
        for (p in projects) {
            candidates += p.listFiles().orEmpty()
            candidates += File(p, "state-history").listFiles().orEmpty()
            for (m in File(p, "meetings").listFiles().orEmpty()) {
                if (File(m, "meeting.json").readJson<MeetingMeta>()?.status != "ready") skip += "projects/${p.name}/meetings/${m.name}/"
                candidates += m.listFiles().orEmpty()
            }
        }
        candidates += File(home, "people").listFiles().orEmpty()
        candidates += File(home, "anthropic-key")
        val files = mutableMapOf<String, File>()
        for (f in candidates) {
            if (!f.isFile) continue
            val rel = f.relativeTo(home).invariantSeparatorsPath
            if (SYNCED.matches(rel) && skip.none { rel.startsWith(it) }) files[rel] = f
        }
        return files to skip
    }

    private fun beats(e: Change, cur: Change?) = cur == null || e.t > cur.t || (e.t == cur.t && e.d > cur.d)

    // --- one round

    fun runOnce() {
        val c = _config.value ?: return
        synchronized(lock) {
            _status.value = _status.value.copy(running = true)
            try {
                val (sent, received) = round(c)
                _status.value = SyncStatus(last = System.currentTimeMillis() / 1000.0, sent = sent, received = received)
                if (received > 0) Changes.bump()
            } catch (e: SyncError) {
                _status.value = _status.value.copy(running = false, error = e.message)
            } catch (e: S3.Error) {
                _status.value = _status.value.copy(running = false, error = if (e.status == 401 || e.status == 403) "Cloudflare refused the key." else "Cloudflare said: ${e.message}")
            } catch (e: IOException) {
                _status.value = _status.value.copy(running = false, error = "Couldn't reach Cloudflare (${e.javaClass.simpleName}). Will retry.")
            }
        }
    }

    private fun round(c: SyncConfig): Pair<Int, Int> {
        val s3 = client(c); val me = c.device
        val st = stateFile.readJson<SyncState>() ?: SyncState()
        fun save() = stateFile.writeJson(st)
        if (st.seq < 0)     // after rejoining: carry on after this device's earlier batches
            st.seq = s3.list("l/$me/").first.maxOfOrNull { it.substringAfterLast('/').toLong() } ?: 0

        // 1. local changes (files are read and sent in 8 MiB pieces, so long recordings fit in memory)
        val (local, skip) = scan()
        val changes = mutableListOf<Change>()
        val buf = ByteArray(CHUNK)
        for ((path, f) in local) {
            val cur = st.files[path]
            if (cur != null && !cur.x && cur.s == f.length() && cur.m == f.lastModified()) continue
            val ids = mutableListOf<String>()
            f.inputStream().use { input ->
                while (true) {
                    var n = 0
                    while (n < CHUNK) { val r = input.read(buf, n, CHUNK - n); if (r < 0) break; n += r }
                    if (n == 0 && ids.isNotEmpty()) break
                    val id = pieceId(c, buf, n)
                    ids += id
                    if (id !in st.pieces) { s3.put("b/$id", seal(c, buf.copyOf(n), id)); st.pieces += id }
                    if (n < CHUNK) break
                }
            }
            if (cur != null && !cur.x && cur.c == ids) { st.files[path] = cur.copy(s = f.length(), m = f.lastModified()); continue }
            st.clock += 1
            val e = Change(path, ids, f.length(), st.clock, me)
            changes += e
            st.files[path] = LocalFile(f.length(), f.lastModified(), ids, e.t, me)
            st.known[path] = e
        }
        for ((path, cur) in st.files.toMap()) {
            if (!cur.x && path !in local && skip.none { path.startsWith(it) }) {
                st.clock += 1
                val e = Change(path, t = st.clock, d = me, x = true)
                changes += e
                st.files[path] = LocalFile(t = e.t, d = me, x = true)
                st.known[path] = e
            }
        }
        if (changes.isNotEmpty()) {
            val key = "l/$me/%010d".format(st.seq + 1)
            s3.put(key, seal(c, json.encodeToString(Batch(1, changes)).toByteArray(), key))
            st.seq += 1
        }
        save()

        // 2. other devices' changes
        for (prefix in s3.list("l/", delimiter = "/").second) {
            val dev = prefix.removePrefix("l/").removeSuffix("/")
            if (dev == me) continue
            for (key in s3.list(prefix, startAfter = st.cursors[dev] ?: "").first) {
                val raw = s3.get(key) ?: continue
                for (e in json.decodeFromString<Batch>(String(open(c, raw, key))).changes) {
                    if (!SYNCED.matches(e.p) || e.p.contains("/../")) continue
                    st.clock = maxOf(st.clock, e.t)
                    if (beats(e, st.known[e.p])) st.known[e.p] = e
                    st.pieces += e.c
                }
                st.cursors[dev] = key
            }
        }
        save()

        // 3. apply what other devices won: deletions first (folders go whole to the trash), files before
        //    the meeting.json / project.json that makes them show up
        val todo = st.known.values.filter { e -> e.d != me && st.files[e.p].let { it?.t != e.t || it.d != e.d } }
            .sortedWith(compareBy({ !it.x }, { (File(it.p).name in META) != it.x }))
        val busy = busy()
        var received = 0
        for (e in todo) {
            if (busy.any { e.p.startsWith(it) }) continue
            val f = File(home, e.p)
            if (e.x) {
                if (File(e.p).name in META && f.parentFile?.exists() == true) FolioApp.instance.store.moveToTrash(f.parentFile!!)
                else f.delete()
                st.files[e.p] = LocalFile(t = e.t, d = e.d, x = true)
            } else {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".sync-tmp")
                tmp.outputStream().use { out ->
                    for (pid in e.c) {
                        val part = open(c, s3.get("b/$pid") ?: throw SyncError("A synced file is missing a piece. Will retry."), pid)
                        if (pieceId(c, part) != pid) throw SyncError("A synced file didn't match its checksum. Will retry.")
                        out.write(part)
                    }
                }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                st.files[e.p] = LocalFile(f.length(), f.lastModified(), e.c, e.t, e.d)
            }
            received++
            if (received % 20 == 0) save()
        }

        // 4. this device (said once a day), and the list of devices for Settings
        val now = System.currentTimeMillis() / 1000.0
        if (now - st.announced > 86400) {
            s3.put("d/$me", seal(c, json.encodeToString(DeviceInfo(c.name, "android", now)).toByteArray(), "d/$me"))
            st.announced = now
        }
        if (now - st.listed > 3600 || received > 0) {
            st.listed = now
            st.devices = s3.list("d/").first.mapNotNull { key ->
                val d = s3.get(key)?.let { json.decodeFromString<DeviceInfo>(String(open(c, it, key))).copy(me = key == "d/$me") } ?: return@mapNotNull null
                // this phone from before it rejoined (older versions got a new id each time)
                if (!d.me && d.name == c.name && d.platform == "android") { s3.delete(key); null } else d
            }.sortedWith(compareBy({ !it.me }, { it.name }))
        }
        save()
        return changes.size to received
    }
}
