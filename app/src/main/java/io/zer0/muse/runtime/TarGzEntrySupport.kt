package io.zer0.muse.runtime

import java.io.File
import java.io.IOException

internal fun applyTarMode(file: File, mode: Int) {
    val exec = (mode and 0b001_000_000) != 0 || (mode and 0b000_001_000) != 0
    file.setReadable(true, false)
    file.setWritable(true, true)
    file.setExecutable(exec, false)
}

internal fun removeTarTarget(target: File) {
    val exists = target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())
    if (exists && !target.delete()) throw IOException("无法替换归档目标: ${target.name}")
}

internal fun readTarString(bytes: ByteArray, offset: Int, length: Int): String {
    var end = offset
    while (end < offset + length && bytes[end] != 0.toByte()) end++
    return String(bytes, offset, end - offset, Charsets.UTF_8)
}

internal fun readTarOctal(bytes: ByteArray, offset: Int, length: Int): Int {
    var value = 0
    var finished = false
    for (i in offset until offset + length) {
        val b = bytes[i].toInt() and 0xff
        if (!finished) {
            if (b == 0 || b == ' '.code) {
                finished = value != 0
            } else if (b in '0'.code..'7'.code) {
                value = value * 8 + (b - '0'.code)
            }
        }
    }
    return value
}
