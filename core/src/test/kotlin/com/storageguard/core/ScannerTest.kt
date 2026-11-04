package com.storageguard.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ScannerTest {
    private val bytes = mutableMapOf<String, ByteArray>()
    private val metadata = mutableMapOf<String, Asset>()
    private val storage = object : StorageAccess {
        override fun open(asset: Asset): InputStream = ByteArrayInputStream(bytes[asset.uri] ?: error("Denied"))
        override fun refresh(asset: Asset) = metadata[asset.uri]
    }
    private fun file(id: String, content: ByteArray, identity: String? = id, removable: Boolean = true): Asset {
        val asset = Asset(id, identity, "$id.bin", "application/octet-stream", content.size.toLong(), 1,
            "Documents", Source.DOCUMENT, removable)
        bytes[id] = content
        metadata[id] = asset
        return asset
    }
    private suspend fun scan(vararg files: Asset) = DuplicateScanner(storage).scan({ emit -> files.forEach { emit(it) } })

    @Test fun `same size different bytes are not duplicates`() = runTest {
        assertTrue(scan(file("a", byteArrayOf(1)), file("b", byteArrayOf(2))).groups.isEmpty())
    }
    @Test fun `renamed identical files group with conservative savings`() = runTest {
        val result = scan(file("a", byteArrayOf(1, 2)), file("renamed", byteArrayOf(1, 2)), file("c", byteArrayOf(1, 2)))
        assertEquals(1, result.groups.size)
        assertEquals(4L, result.groups.single().possibleBytes)
    }
    @Test fun `aliases are counted once`() = runTest {
        assertTrue(scan(file("a", byteArrayOf(1)), file("alias", byteArrayOf(1), "a")).groups.isEmpty())
    }
    @Test fun `uncertain provider references never count as removable copies`() = runTest {
        val group = scan(file("a", byteArrayOf(1)), file("b", byteArrayOf(1), null)).groups.single()
        assertEquals(0, group.possibleBytes)
        assertFailsWith<IllegalArgumentException> { KeepPolicy.plan(listOf(group), setOf("b")) }
        assertFailsWith<IllegalStateException> { KeepPolicy.plan(listOf(group), setOf("a")) }
    }
    @Test fun `keep policy refuses all copies and accepts explicit selection`() = runTest {
        val group = scan(file("a", byteArrayOf(1)), file("b", byteArrayOf(1))).groups.single()
        assertFailsWith<IllegalStateException> { KeepPolicy.plan(listOf(group), setOf("a", "b")) }
        assertEquals("a", KeepPolicy.plan(listOf(group), setOf("b")).single().keeper.asset.uri)
    }
    @Test fun `non deletable provider is blocked`() = runTest {
        val group = scan(file("a", byteArrayOf(1)), file("b", byteArrayOf(1), removable = false)).groups.single()
        assertFailsWith<IllegalArgumentException> { KeepPolicy.plan(listOf(group), setOf("b")) }
    }
    @Test fun `changed bytes with unchanged metadata block preflight`() = runTest {
        val group = scan(file("a", byteArrayOf(1)), file("b", byteArrayOf(1))).groups.single()
        bytes["b"] = byteArrayOf(2)
        assertFailsWith<IllegalStateException> { Preflight(storage).verify(KeepPolicy.plan(listOf(group), setOf("b")).single()) }
    }
    @Test fun `missing keeper blocks preflight`() = runTest {
        val group = scan(file("a", byteArrayOf(1)), file("b", byteArrayOf(1))).groups.single()
        metadata.remove("a")
        assertFailsWith<IllegalStateException> { Preflight(storage).verify(KeepPolicy.plan(listOf(group), setOf("b")).single()) }
    }
    @Test fun `metadata change blocks preflight`() = runTest {
        val group = scan(file("a", byteArrayOf(1)), file("b", byteArrayOf(1))).groups.single()
        metadata["b"] = metadata.getValue("b").copy(modified = 2)
        assertFailsWith<IllegalStateException> { Preflight(storage).verify(KeepPolicy.plan(listOf(group), setOf("b")).single()) }
    }
    @Test fun `empty files are valid duplicates but save zero bytes`() = runTest {
        assertEquals(0L, scan(file("a", byteArrayOf()), file("b", byteArrayOf())).groups.single().possibleBytes)
    }
    @Test fun `inaccessible candidate is skipped`() = runTest {
        val a = file("a", byteArrayOf(1)); val b = file("b", byteArrayOf(1)); bytes.remove("b")
        val result = scan(a, b)
        assertTrue(result.groups.isEmpty())
        assertEquals(1, result.progress.issues)
    }
    @Test fun `cancellation propagates`() = runTest {
        file("a", byteArrayOf(1)); file("b", byteArrayOf(1))
        assertFailsWith<CancellationException> {
            DuplicateScanner(storage).scan({ emit -> metadata.values.forEach { emit(it) } }, { throw CancellationException() })
        }
    }
    @Test fun `large streams and differing read sizes compare correctly`() = runTest {
        val content = ByteArray(2 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val group = scan(file("a", content), file("b", content)).groups.single()
        val shortReadStorage = object : StorageAccess by storage {
            override fun open(asset: Asset): InputStream = object : ByteArrayInputStream(content) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, if (asset.uri == "a") 7 else 19))
            }
        }
        Preflight(shortReadStorage).verify(KeepPolicy.plan(listOf(group), setOf("b")).single())
    }
}
