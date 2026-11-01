package com.example.tvalici

/** Song information (AirPlay Music metadata). */
class TrackInfo(val title: String?, val artist: String?, val album: String?)

/** DMAP (application/x-dmap-tagged) decoder: 4 B tag + 4 B big-endian length + value. */
object DmapParser {
    private val containers = setOf("mlit", "mcon", "msrv", "mlcl", "cmst")

    /** Does not throw even on corrupt/truncated data; returns what it found. */
    fun parse(data: ByteArray): TrackInfo {
        var title: String? = null
        var artist: String? = null
        var album: String? = null

        fun walk(from: Int, to: Int, depth: Int) {
            var p = from
            while (p + 8 <= to) {
                val tag = String(data, p, 4, Charsets.ISO_8859_1)
                val len = ((data[p + 4].toInt() and 0xFF) shl 24) or
                    ((data[p + 5].toInt() and 0xFF) shl 16) or
                    ((data[p + 6].toInt() and 0xFF) shl 8) or
                    (data[p + 7].toInt() and 0xFF)
                val start = p + 8
                if (len < 0 || len > to - start) return
                val end = start + len
                when {
                    tag in containers -> if (depth < 4) walk(start, end, depth + 1)
                    tag == "minm" -> title = String(data, start, len, Charsets.UTF_8)
                    tag == "asar" -> artist = String(data, start, len, Charsets.UTF_8)
                    tag == "asal" -> album = String(data, start, len, Charsets.UTF_8)
                }
                p = end
            }
        }

        try {
            walk(0, data.size, 0)
        } catch (e: Exception) {
            // corrupt packet: make do with what we have
        }
        return TrackInfo(title?.takeIf { it.isNotBlank() }, artist?.takeIf { it.isNotBlank() }, album?.takeIf { it.isNotBlank() })
    }
}
