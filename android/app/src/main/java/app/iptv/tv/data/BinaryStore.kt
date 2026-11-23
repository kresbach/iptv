package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.EpgGuide
import app.iptv.tv.model.Episode
import app.iptv.tv.model.PlaylistData
import app.iptv.tv.model.Programme
import app.iptv.tv.model.SeriesShow
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.IdentityHashMap

/**
 * Kompaktes Binärformat für Playlist und TV-Guide – um ein Vielfaches schneller zu lesen als JSON
 * (wichtig für den App-Start auf dem Fernseher). Wiederkehrende Teile (Kategorien, Link-Anfänge wie
 * `http://server/movie/user/pass/`) werden nur einmal gespeichert.
 */
object BinaryStore {
    private const val MAGIC_DATA = 0x49505444 // "IPTD"
    private const val MAGIC_EPG = 0x49505445 // "IPTE"
    private const val VERSION = 2

    // region Playlist

    fun writeData(data: PlaylistData, output: OutputStream) {
        val out = DataOutputStream(BufferedOutputStream(output, 256 * 1024))
        val table = StringTable()
        // Tabelle vorab füllen (Kategorien, Link-Anfänge)
        fun prepare(c: Channel) {
            c.group?.let(table::add); table.add(prefix(c.url)); c.logo?.let { table.add(prefix(it)) }
        }
        data.channels.forEach(::prepare)
        data.shows.forEach { s ->
            table.add(s.category); s.poster?.let { table.add(prefix(it)) }
            s.episodes.forEach { prepare(it.channel) }
        }
        out.writeInt(MAGIC_DATA)
        out.writeInt(VERSION)
        table.write(out)
        out.writeInt(data.channels.size)
        data.channels.forEach { writeChannel(out, it, table) }
        out.writeInt(data.shows.size)
        for (s in data.shows) {
            out.str(s.title)
            out.writeInt(table.index(s.category))
            out.link(s.poster, table)
            out.str(s.plot)
            out.writeInt(s.xtreamSeriesId ?: -1)
            out.writeLong(s.added ?: Long.MIN_VALUE)
            out.writeInt(s.episodes.size)
            for (e in s.episodes) {
                writeChannel(out, e.channel, table)
                out.writeInt(e.season ?: Int.MIN_VALUE)
                out.writeInt(e.number ?: Int.MIN_VALUE)
                out.str(e.title)
                out.str(e.plot)
            }
        }
        out.str(data.serverTimeZone)
        out.flush()
    }

    fun readData(input: InputStream): PlaylistData {
        val inp = Reader(input.readBytes())
        if (inp.readInt() != MAGIC_DATA) throw IOException("Kein Playlist-Speicher")
        val version = inp.readInt()
        if (version !in 1..VERSION) throw IOException("Unbekannte Version")
        val table = StringTable.read(inp)
        val channelCount = inp.readInt()
        val channels = ArrayList<Channel>(channelCount)
        repeat(channelCount) { channels += readChannel(inp, table, version) }
        val showCount = inp.readInt()
        val shows = ArrayList<SeriesShow>(showCount)
        repeat(showCount) {
            val title = inp.str()!!
            val category = table[inp.readInt()]
            val poster = inp.link(table)
            val plot = inp.str()
            val seriesId = inp.readInt().takeIf { it != -1 }
            val added = inp.readLong().takeIf { it != Long.MIN_VALUE }
            val episodeCount = inp.readInt()
            val episodes = ArrayList<Episode>(episodeCount)
            repeat(episodeCount) {
                val ch = readChannel(inp, table, version)
                val season = inp.readInt().takeIf { it != Int.MIN_VALUE }
                val number = inp.readInt().takeIf { it != Int.MIN_VALUE }
                episodes += Episode(ch, season, number, inp.str(), inp.str())
            }
            shows += SeriesShow(title, category, poster, plot, episodes, seriesId, added)
        }
        val timeZone = if (version >= 2) inp.str() else null
        return PlaylistData(channels, shows, timeZone)
    }

    // endregion

    // region TV-Guide

    fun writeEpg(guide: EpgGuide, output: OutputStream) {
        val out = DataOutputStream(BufferedOutputStream(output, 256 * 1024))
        out.writeInt(MAGIC_EPG)
        out.writeInt(VERSION)
        out.writeLong(guide.fetchedAt)
        // Mehrere Schlüssel (tvg-id, Sendername) teilen sich oft dieselbe Liste → nur einmal speichern.
        val lists = IdentityHashMap<List<Programme>, Int>()
        val ordered = ArrayList<List<Programme>>()
        for (list in guide.programmes.values) if (lists[list] == null) { lists[list] = ordered.size; ordered += list }
        out.writeInt(ordered.size)
        for (list in ordered) {
            out.writeInt(list.size)
            for (p in list) {
                out.str(p.title)
                out.str(p.description)
                out.writeLong(p.start)
                out.writeLong(p.end)
            }
        }
        out.writeInt(guide.programmes.size)
        for ((key, list) in guide.programmes) {
            out.str(key)
            out.writeInt(lists.getValue(list))
        }
        out.flush()
    }

    fun readEpg(input: InputStream): EpgGuide {
        val inp = Reader(input.readBytes())
        if (inp.readInt() != MAGIC_EPG) throw IOException("Kein TV-Guide-Speicher")
        if (inp.readInt() !in 1..VERSION) throw IOException("Unbekannte Version")
        val fetchedAt = inp.readLong()
        val lists = Array(inp.readInt()) {
            val n = inp.readInt()
            val list = ArrayList<Programme>(n)
            repeat(n) { list += Programme(inp.str()!!, inp.str(), inp.readLong(), inp.readLong()) }
            list
        }
        val keys = inp.readInt()
        val map = HashMap<String, List<Programme>>(keys * 2)
        repeat(keys) { map[inp.str()!!] = lists[inp.readInt()] }
        return EpgGuide(map, fetchedAt)
    }

    // endregion

    // region Hilfen

    private fun prefix(link: String): String = link.substring(0, link.lastIndexOf('/') + 1)

    private fun writeChannel(out: DataOutputStream, c: Channel, table: StringTable) {
        out.str(c.name)
        out.link(c.url, table)
        out.writeInt(c.group?.let(table::index) ?: -1)
        out.str(c.tvgId)
        out.link(c.logo, table)
        out.writeByte(c.kind.ordinal)
        out.writeLong(c.added ?: Long.MIN_VALUE)
        out.writeInt(c.archiveDays ?: -1)
    }

    private fun readChannel(inp: Reader, table: Array<String>, version: Int): Channel {
        val name = inp.str()!!
        val url = inp.link(table)!!
        val group = inp.readInt().let { if (it < 0) null else table[it] }
        val tvgId = inp.str()
        val logo = inp.link(table)
        val kind = ContentKind.entries[inp.readByte().toInt()]
        val added = inp.readLong().takeIf { it != Long.MIN_VALUE }
        val archive = if (version >= 2) inp.readInt().takeIf { it > 0 } else null
        return Channel(name, url, group, tvgId, logo, kind, added, archive)
    }

    /** Link als (Anfang aus der Tabelle, Rest). */
    private fun DataOutputStream.link(link: String?, table: StringTable) {
        if (link == null) { writeInt(-1); return }
        val p = prefix(link)
        writeInt(table.index(p))
        str(link.substring(p.length))
    }

    private fun Reader.link(table: Array<String>): String? {
        val i = readInt()
        if (i < 0) return null
        return table[i] + str()!!
    }

    /** UTF-8-Text mit Länge (null = -1); ohne die 64-KB-Grenze von writeUTF. */
    private fun DataOutputStream.str(s: String?) {
        if (s == null) { writeInt(-1); return }
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    /** Liest direkt aus dem Byte-Array (deutlich schneller als DataInputStream auf dem Fernseher). */
    private class Reader(private val b: ByteArray) {
        private var pos = 0
        fun readInt(): Int {
            if (pos + 4 > b.size) throw IOException("Datei unvollständig")
            val v = (b[pos].toInt() and 0xFF shl 24) or (b[pos + 1].toInt() and 0xFF shl 16) or
                (b[pos + 2].toInt() and 0xFF shl 8) or (b[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }
        fun readLong(): Long = (readInt().toLong() shl 32) or (readInt().toLong() and 0xFFFFFFFFL)
        fun readByte(): Byte {
            if (pos >= b.size) throw IOException("Datei unvollständig")
            return b[pos++]
        }
        fun str(): String? {
            val n = readInt()
            if (n < 0) return null
            if (pos + n > b.size) throw IOException("Datei unvollständig")
            val s = String(b, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }
    }

    private class StringTable {
        private val map = HashMap<String, Int>()
        private val list = ArrayList<String>()
        fun add(s: String) { if (!map.containsKey(s)) { map[s] = list.size; list += s } }
        fun index(s: String): Int = map[s] ?: error("nicht in Tabelle: $s")
        fun write(out: DataOutputStream) {
            out.writeInt(list.size)
            list.forEach { out.str(it) }
        }

        companion object {
            fun read(inp: Reader): Array<String> = Array(inp.readInt()) { inp.str()!! }
        }
    }

    // endregion
}
