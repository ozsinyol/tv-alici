package com.example.tvalici

/** Sarki bilgisi (AirPlay Muzik metadata'si). */
class TrackInfo(val title: String?, val artist: String?, val album: String?)

/** DMAP (application/x-dmap-tagged) cozucu: 4 B etiket + 4 B buyuk-endian uzunluk + deger. */
object DmapParser {
    private val containers = setOf("mlit", "mcon", "msrv", "mlcl", "cmst")

    /** Bozuk/kesik veride bile istisna atmaz; bulduklarini dondurur. */
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
            // bozuk paket: eldekiyle yetin
        }
        return TrackInfo(title?.takeIf { it.isNotBlank() }, artist?.takeIf { it.isNotBlank() }, album?.takeIf { it.isNotBlank() })
    }
}
