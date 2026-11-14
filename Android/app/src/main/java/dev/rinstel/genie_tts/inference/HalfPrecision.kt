package dev.rinstel.genie_tts.inference

object HalfPrecision {
    fun fp16BitsToFloat(bits: Int): Float {
        val sign = (bits ushr 15) and 0x1
        val exponent = (bits ushr 10) and 0x1F
        val fraction = bits and 0x3FF

        val floatBits = when (exponent) {
            0 -> {
                if (fraction == 0) {
                    sign shl 31
                } else {
                    var mantissa = fraction
                    var adjustedExponent = -14
                    while ((mantissa and 0x400) == 0) {
                        mantissa = mantissa shl 1
                        adjustedExponent -= 1
                    }
                    mantissa = mantissa and 0x3FF
                    (sign shl 31) or ((adjustedExponent + 127) shl 23) or (mantissa shl 13)
                }
            }
            0x1F -> {
                (sign shl 31) or 0x7F800000 or (fraction shl 13)
            }
            else -> {
                (sign shl 31) or ((exponent - 15 + 127) shl 23) or (fraction shl 13)
            }
        }

        return Float.fromBits(floatBits)
    }
}
