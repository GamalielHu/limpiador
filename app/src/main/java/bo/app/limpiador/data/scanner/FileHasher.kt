package bo.app.limpiador.data.scanner

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * SHA-256 por streaming: nunca carga el archivo entero en memoria, lee con un buffer fijo de 64 KB.
 */
class FileHasher(private val resolver: ContentResolver) {

    companion object {
        const val CHUNK = 64 * 1024

        /** Con este tamaño o menos, el pre-hash ya cubre el archivo entero. */
        const val FULL_COVERAGE = 2L * CHUNK

        private fun hex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            val digits = "0123456789abcdef"
            bytes.forEachIndexed { i, b ->
                val v = b.toInt() and 0xFF
                out[i * 2] = digits[v ushr 4]
                out[i * 2 + 1] = digits[v and 0x0F]
            }
            return String(out)
        }
    }

    /** Hash de tamaño + primeros 64 KB + últimos 64 KB. Lee como máximo 128 KB. */
    suspend fun preHash(uri: Uri, size: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val md = MessageDigest.getInstance("SHA-256")
                    md.update(longToBytes(size))
                    val buf = ByteArray(CHUNK)
                    readFully(input, buf, CHUNK, md)
                    if (size > FULL_COVERAGE) {
                        val tailStart = size - CHUNK
                        val seeked = runCatching { input.channel.position(tailStart); true }.getOrDefault(false)
                        if (!seeked) skipFully(input, tailStart - CHUNK)
                        readFully(input, buf, CHUNK, md)
                    } else if (size > CHUNK) {
                        readFully(input, buf, (size - CHUNK).toInt(), md)
                    }
                    hex(md.digest())
                }
            }
        }.getOrNull()
    }

    /** SHA-256 del contenido completo, en bloques de 64 KB, cancelable entre bloques. */
    suspend fun fullHash(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val md = MessageDigest.getInstance("SHA-256")
                    val buf = ByteArray(CHUNK)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                    hex(md.digest())
                }
            }
        }.getOrNull()
    }

    private fun readFully(input: InputStream, buf: ByteArray, max: Int, md: MessageDigest) {
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            md.update(buf, 0, n)
            total += n
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) break
            left -= n
        }
    }

    private fun longToBytes(v: Long) = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }
}
