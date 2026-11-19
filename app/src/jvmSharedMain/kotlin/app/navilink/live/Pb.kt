package app.navilink.live

import java.io.ByteArrayOutputStream

/** Just enough protobuf to read and write Garmin Live Services (OBN) messages without generated classes. */
class PbField(val num: Int, val wire: Int, val varint: Long = 0, val bytes: ByteArray = ByteArray(0)) {
    val int get() = varint.toInt()
    val sint get() = ((varint ushr 1) xor -(varint and 1)).toInt()
    val text get() = String(bytes, Charsets.UTF_8)
    fun msg() = Pb.parse(bytes)
}

object Pb {
    fun parse(b: ByteArray): List<PbField> {
        val out = ArrayList<PbField>()
        var i = 0
        fun varint(): Long {
            var r = 0L; var s = 0
            while (true) {
                require(i < b.size) { "truncated varint" }
                val c = b[i++].toInt() and 0xff
                r = r or ((c and 0x7f).toLong() shl s)
                if (c < 0x80) return r
                s += 7
                require(s < 70) { "bad varint" }
            }
        }
        while (i < b.size) {
            val key = varint()
            val num = (key ushr 3).toInt(); val wire = (key and 7).toInt()
            require(num > 0) { "bad field number" }
            out += when (wire) {
                0 -> PbField(num, 0, varint())
                1 -> { require(i + 8 <= b.size); PbField(num, 1, bytes = b.copyOfRange(i, i + 8)).also { i += 8 } }
                2 -> { val n = varint().toInt(); require(n >= 0 && i + n <= b.size) { "truncated field" }; PbField(num, 2, bytes = b.copyOfRange(i, i + n)).also { i += n } }
                5 -> { require(i + 4 <= b.size); PbField(num, 5, bytes = b.copyOfRange(i, i + 4)).also { i += 4 } }
                else -> error("wire type $wire")
            }
        }
        return out
    }

    /** True when [b] parses cleanly as a message (used to tell protobuf from Garmin's text protocol). */
    fun looksLikeMessage(b: ByteArray) = b.isNotEmpty() && runCatching { parse(b).isNotEmpty() }.getOrDefault(false)
}

fun List<PbField>.first(num: Int) = firstOrNull { it.num == num }
fun List<PbField>.all(num: Int) = filter { it.num == num }

class PbWriter {
    private val o = ByteArrayOutputStream()
    private fun raw(v: Long) { var x = v; while (true) { if (x and 0x7fL.inv() == 0L) { o.write(x.toInt()); return }; o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 } }
    fun varint(n: Int, v: Long) = apply { raw((n shl 3).toLong()); raw(v) }
    fun int32(n: Int, v: Int) = varint(n, v.toLong())            // negative int32 is sign-extended, as protobuf does
    fun sint32(n: Int, v: Int) = varint(n, ((v shl 1) xor (v shr 31)).toLong() and 0xffffffffL)
    fun bool(n: Int, v: Boolean) = varint(n, if (v) 1 else 0)
    fun bytes(n: Int, v: ByteArray) = apply { raw(((n shl 3) or 2).toLong()); raw(v.size.toLong()); o.write(v) }
    fun string(n: Int, v: String) = bytes(n, v.toByteArray(Charsets.UTF_8))
    fun msg(n: Int, w: PbWriter) = bytes(n, w.toByteArray())
    fun field(f: PbField) = apply {
        when (f.wire) {
            0 -> varint(f.num, f.varint)
            2 -> bytes(f.num, f.bytes)
            else -> { raw(((f.num shl 3) or f.wire).toLong()); o.write(f.bytes) }
        }
    }
    fun toByteArray(): ByteArray = o.toByteArray()
}
