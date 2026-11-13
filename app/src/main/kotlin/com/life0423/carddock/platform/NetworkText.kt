package com.life0423.carddock.platform

import java.io.Reader

/** 只消费适配器需要的响应正文大小，兼容应用最低 Android 版本。 */
internal fun Reader.readBounded(limit: Int): String = buildString {
    val buffer = CharArray(8192)
    while (length < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - length))
        if (count < 0) break
        append(buffer, 0, count)
    }
}
