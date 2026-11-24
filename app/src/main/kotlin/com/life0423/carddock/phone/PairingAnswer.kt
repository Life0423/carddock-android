package com.life0423.carddock.phone

/** 通知和分屏共用输入边界。六位码保留前导零；仅手动回退时才带端口。 */
internal class PairingAnswer(val port: Int?, val code: String) {
    companion object {
        private val format = Regex("""(?:([0-9]{1,5})[\s:：,，]+)?([0-9]{6})""")

        fun parse(input: String): PairingAnswer? {
            val match = format.matchEntire(input.trim()) ?: return null
            val port = match.groups[1]?.value?.toInt()
            if (port != null && port !in 1..65535) return null
            return PairingAnswer(port, match.groupValues[2])
        }
    }
}
