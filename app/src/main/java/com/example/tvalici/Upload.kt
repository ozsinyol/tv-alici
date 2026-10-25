@file:Suppress("UnsafeOptInUsageError")

package com.example.tvalici

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Devam eden bir video yuklemesi. Sunucu dosyayi yazdikca [update] cagirir,
 * oynatici [awaitBytes] ile yeni veri bekler. Boylece yukleme bitmeden oynatma baslayabilir.
 */
class Upload(val file: File, val totalSize: Long) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    @Volatile
    var written: Long = 0L
        private set

    @Volatile
    var finished: Boolean = false
        private set

    @Volatile
    var failed: Boolean = false
        private set

    fun update(bytes: Long) {
        lock.withLock {
            written = bytes
            changed.signalAll()
        }
    }

    fun finish(ok: Boolean) {
        lock.withLock {
            if (ok) finished = true else failed = true
            changed.signalAll()
        }
    }

    /** [position] sonrasinda en az 1 bayt hazir olana (ya da akis bitene) kadar bekler. */
    fun awaitBytes(position: Long): Long {
        lock.withLock {
            while (written <= position && !finished && !failed) {
                changed.await(500, TimeUnit.MILLISECONDS)
            }
            return written - position
        }
    }
}

/**
 * Buyuyen dosyayi okuyan ExoPlayer veri kaynagi. Toplam boyut baslangicta bilindigi icin
 * oynatici gerekirse ileri sarabilir (ornegin dosyanin sonundaki moov bilgisi icin); henuz
 * yazilmamis bir konuma istek gelirse veri gelene kadar bekler.
 */
class GrowingFileDataSource(private val upload: Upload) : DataSource {
    private var raf: RandomAccessFile? = null
    private var currentUri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        position = dataSpec.position
        if (position > upload.totalSize) throw IOException("Konum dosya boyutunu aşıyor")
        val wanted =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) upload.totalSize - position
            else dataSpec.length
        remaining = minOf(wanted, upload.totalSize - position)
        val file = RandomAccessFile(upload.file, "r")
        file.seek(position)
        raf = file
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val available = upload.awaitBytes(position)
        if (available <= 0L) {
            if (upload.failed) throw IOException("Yükleme yarıda kesildi")
            return C.RESULT_END_OF_INPUT
        }
        val toRead = minOf(length.toLong(), available, remaining).toInt()
        val file = raf ?: return C.RESULT_END_OF_INPUT
        val n = file.read(buffer, offset, toRead)
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        return n
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            raf?.close()
        } catch (e: IOException) {
            // kapanirken hata onemsiz
        }
        raf = null
        currentUri = null
    }
}
