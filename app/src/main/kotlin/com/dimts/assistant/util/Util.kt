package com.dimts.assistant.util

import android.content.Context
import com.dimts.core.audio.FeatureConfig
import com.dimts.core.audio.SoundKey
import com.dimts.core.script.Clock
import com.dimts.core.script.Level
import com.dimts.core.session.KeyRole
import java.io.File
import java.util.TimeZone

class AndroidClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun offsetMillis(): Long = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
}

/** Small persistent settings (SharedPreferences). */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("dimts", Context.MODE_PRIVATE)

    var sensitivity: Double
        get() = p.getFloat("sensitivity", 1.0f).toDouble()
        set(v) = p.edit().putFloat("sensitivity", v.toFloat()).apply()

    /** "en" or "am". */
    var language: String
        get() = p.getString("language", "en") ?: "en"
        set(v) = p.edit().putString("language", v).apply()

    var idleSeconds: Int
        get() = p.getInt("idleSeconds", 60)
        set(v) = p.edit().putInt("idleSeconds", v).apply()

    /** The user asked for key listening and has not stopped it; resumed when the app restarts. */
    var listeningWanted: Boolean
        get() = p.getBoolean("listeningWanted", false)
        set(v) = p.edit().putBoolean("listeningWanted", v).apply()

    var firstRunDone: Boolean
        get() = p.getBoolean("firstRunDone", false)
        set(v) = p.edit().putBoolean("firstRunDone", v).apply()

    fun role(r: KeyRole): String? = p.getString("role_" + r.name, null)

    fun setRole(r: KeyRole, name: String?) {
        p.edit().apply { if (name == null) remove("role_" + r.name) else putString("role_" + r.name, name) }.apply()
    }
}

/** Recorded key sounds, one small file per key in the app's private storage. */
class KeyStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "keys").apply { mkdirs() }

    fun validName(name: String) = Regex("[A-Za-z0-9_-]{1,32}").matches(name)

    private fun file(name: String) = File(dir, "$name.dtk")

    fun exists(name: String) = validName(name) && file(name).exists()

    fun names(): List<String> = dir.listFiles { f -> f.name.endsWith(".dtk") }?.map { it.name.removeSuffix(".dtk") }?.sorted() ?: emptyList()

    fun save(key: SoundKey) {
        require(validName(key.name)) { "invalid key name" }
        val tmp = File(dir, key.name + ".tmp")
        tmp.writeBytes(key.toByteArray())
        if (!tmp.renameTo(file(key.name))) {
            file(key.name).writeBytes(tmp.readBytes())
            tmp.delete()
        }
    }

    /** Returns null if missing or unreadable (e.g. recorded with older audio settings). */
    fun load(name: String, features: FeatureConfig = FeatureConfig()): SoundKey? {
        if (!exists(name)) return null
        return try {
            file(name).inputStream().use { SoundKey.read(it, features) }
        } catch (e: Exception) {
            null
        }
    }

    fun delete(name: String): Boolean = validName(name) && file(name).delete()
}

data class LogLine(val level: Level, val text: String, val time: Long)

/** Console history shared by the UI and the background parts. Main thread only. */
class LogBus(private val capacity: Int = 1500) {
    private val lines = ArrayDeque<LogLine>()
    var listener: ((LogLine) -> Unit)? = null

    fun add(level: Level, text: String) {
        val line = LogLine(level, text, System.currentTimeMillis())
        lines.addLast(line)
        while (lines.size > capacity) lines.removeFirst()
        listener?.invoke(line)
    }

    fun snapshot(): List<LogLine> = lines.toList()

    fun clear() = lines.clear()
}
