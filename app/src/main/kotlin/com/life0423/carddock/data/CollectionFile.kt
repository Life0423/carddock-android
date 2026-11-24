package com.life0423.carddock.data

import androidx.core.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 沿用 AtomicFile 的 .bak 恢复和 .new 暂存。提交时原子替换旧集合，并把替换失败交给调用方。 */
internal open class CollectionFile(base: File) : AtomicFile(base) {
    override fun finishWrite(stream: FileOutputStream?) {
        requireNotNull(stream).close() // 调用方已同步文件，此后不会再写入。
        val path = baseFile.toPath()
        Files.move(path.resolveSibling("${path.fileName}.new"), path,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
