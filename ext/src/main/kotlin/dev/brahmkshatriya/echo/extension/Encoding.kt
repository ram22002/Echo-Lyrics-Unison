package dev.brahmkshatriya.echo.extension

/** Minimal, dependency free codecs used by providers whose payloads are base64 or hex. */
internal object Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() || it == '=' || it == '"' || it == '\\' }
        val out = ArrayList<Byte>(clean.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (char in clean) {
            val value = when (char) {
                in ALPHABET -> ALPHABET.indexOf(char)
                '-' -> 62
                '_' -> 63
                else -> continue
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    fun encode(data: ByteArray): String {
        val builder = StringBuilder()
        var index = 0
        while (index < data.size) {
            val b0 = data[index].toInt() and 0xFF
            val b1 = if (index + 1 < data.size) data[index + 1].toInt() and 0xFF else -1
            val b2 = if (index + 2 < data.size) data[index + 2].toInt() and 0xFF else -1
            builder.append(ALPHABET[b0 shr 2])
            builder.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) builder.append(ALPHABET[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)]) else builder.append('=')
            if (b2 >= 0) builder.append(ALPHABET[b2 and 0x3F]) else builder.append('=')
            index += 3
        }
        return builder.toString()
    }

    fun hex(text: String): ByteArray {
        val clean = text.filter { it.isLetterOrDigit() }
        val out = ByteArray(clean.length / 2)
        for (index in out.indices) {
            val high = clean[index * 2].digitToIntOrNull(16) ?: return ByteArray(0)
            val low = clean[index * 2 + 1].digitToIntOrNull(16) ?: return ByteArray(0)
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }
}
