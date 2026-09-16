package com.battlesbudz.jarvis.v2.ai.storage

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelStorageTest {
    @Test fun resumableTransferAndFullResponseFallbackRetainExactBytes() = runBlocking {
        val bytes = ByteArray(32001) { (it % 251).toByte() }
        for (rangeSupported in listOf(true, false)) {
            val root = Files.createTempDirectory("model-transfer").toFile()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/model") { exchange ->
                val range = exchange.requestHeaders.getFirst("Range")
                val start = if (rangeSupported && range != null) range.substringAfter("bytes=").substringBefore('-').toInt() else 0
                val end = if (rangeSupported && range == "bytes=0-0") 0 else bytes.lastIndex
                val partial = rangeSupported && range != null
                if (partial) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                exchange.sendResponseHeaders(if (partial) 206 else 200, (end - start + 1).toLong())
                exchange.responseBody.use { it.write(bytes, start, end - start + 1) }
                exchange.close()
            }
            server.start()
            try {
                val target = root.resolve("model.part").apply { writeBytes(bytes.copyOfRange(0, 3456)) }
                var progress = 0L
                ModelDownloader().download("http://127.0.0.1:${server.address.port}/model", target,
                    onProgress = { count, _ -> progress = count }, onStatus = {})
                assertArrayEquals(bytes, target.readBytes())
                assertEquals(bytes.size.toLong(), progress)
            } finally {
                server.stop(0)
                root.deleteRecursively()
            }
        }
    }

    @Test fun hashesModelBytesAndReportsCompletion() {
        val file = Files.createTempFile("model-hash", ".bin").toFile()
        try {
            file.writeText("abc")
            var finished = false
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                file.sha256 { count, total -> finished = count == 3L && total == 3L })
            assertTrue(finished)
            file.writeText("abd")
            assertNotEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", file.sha256())
        } finally { file.delete() }
    }
}
