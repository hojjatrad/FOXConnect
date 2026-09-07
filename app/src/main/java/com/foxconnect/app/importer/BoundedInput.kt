package com.foxconnect.app.importer

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (total < maxBytes) {
        val read = read(buffer, 0, minOf(buffer.size, maxBytes - total))
        if (read < 0) break
        if (read == 0) continue
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}
