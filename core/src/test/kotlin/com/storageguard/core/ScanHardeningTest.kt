package com.storageguard.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ScanHardeningTest {
    private fun asset(id: String, size: Long? = 1) = Asset(id, id, "$id.bin", "application/octet-stream", size,
        1, "Documents", Source.DOCUMENT, true)
    private fun storage(content: ByteArray = byteArrayOf(1)) = object : StorageAccess {
        override fun open(asset: Asset): InputStream = ByteArrayInputStream(content)
        override fun refresh(asset: Asset) = asset
    }

    @Test fun `inventory limit stops discovery and preserves verified partial findings`() = runTest {
        var emitted = 0
        val result = DuplicateScanner(storage(), ScanLimits(maxFiles = 2)).scan({ emit ->
            repeat(10_000) { emitted++; emit(asset("file$it")) }
        })
        assertEquals(3, emitted)
        assertEquals(2, result.inventory.size)
        assertEquals(1, result.groups.size)
        assertEquals(1, result.progress.issues)
        assertNotNull(result.coverageLimit)
    }

    @Test fun `unknown size entries also consume the inventory budget`() = runTest {
        val result = DuplicateScanner(storage(), ScanLimits(maxFiles = 2)).scan({ emit ->
            repeat(100) { emit(asset("unknown$it", null)) }
        })
        assertEquals(2, result.inventory.size)
        assertTrue(result.groups.isEmpty())
        assertNotNull(result.coverageLimit)
    }

    @Test fun `aggregate metadata is bounded before the next asset is retained`() = runTest {
        val result = DuplicateScanner(storage(), ScanLimits(maxMetadataChars = 64)).scan({ emit ->
            repeat(100) { emit(asset("$it")) }
        })
        assertEquals(1, result.inventory.size)
        assertNotNull(result.coverageLimit)
    }

    @Test fun `one oversized field cannot enter the index`() = runTest {
        val result = DuplicateScanner(storage()).scan({ emit -> emit(asset("x").copy(name = "x".repeat(4_097))) })
        assertTrue(result.inventory.isEmpty())
        assertNotNull(result.coverageLimit)
    }

    @Test fun `known aliases do not exhaust the file budget`() = runTest {
        val result = DuplicateScanner(storage(), ScanLimits(maxFiles = 2)).scan({ emit ->
            repeat(100) { emit(asset("alias$it").copy(identity = "same")) }
            emit(asset("independent"))
        })
        assertEquals(2, result.inventory.size)
        assertNull(result.coverageLimit)
        assertEquals(1, result.groups.size)
    }

    @Test fun `directory cycles and repeated rows are deduplicated before queueing`() {
        val walk = DirectoryTraversal("root", ScanLimits(maxDirectories = 2))
        repeat(10_000) { walk.enqueue("root"); walk.enqueue("child") }
        assertEquals("root", walk.next())
        assertEquals("child", walk.next())
        walk.enqueue("root")
        assertNull(walk.next())
    }

    @Test fun `unique directory and traversal character budgets fail closed`() {
        val count = DirectoryTraversal("root", ScanLimits(maxDirectories = 2))
        count.enqueue("a")
        assertFailsWith<ScanLimitExceeded> { count.enqueue("b") }
        val chars = DirectoryTraversal("root", ScanLimits(maxTraversalChars = 5))
        chars.enqueue("a")
        assertFailsWith<ScanLimitExceeded> { chars.enqueue("b") }
        assertFailsWith<ScanLimitExceeded> { DirectoryTraversal("x".repeat(4_097)) }
    }

    @Test fun `repeated provider rows are bounded even when they add no new files`() {
        val walk = DirectoryTraversal("root", ScanLimits(maxProviderRows = 3))
        repeat(3) { walk.recordRow() }
        assertFailsWith<ScanLimitExceeded> { walk.recordRow() }
    }

    @Test fun `overlong streams are rejected after at most reported length plus one byte`() = runTest {
        var read = 0
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(1_000_000)) {
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int = super.read(buffer, offset, count).also { if (it > 0) read += it }
            override fun close() { closed = true; super.close() }
        }
        assertFailsWith<IOException> { sha256(input, expectedBytes = 4) }
        assertEquals(5, read)
        assertTrue(closed)
    }

    @Test fun `zero progress streams fail instead of spinning`() = runTest {
        var calls = 0
        var closed = false
        val input = object : InputStream() {
            override fun read() = 0
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int { calls++; return 0 }
            override fun close() { closed = true }
        }
        assertFailsWith<IOException> { sha256(input, expectedBytes = 1) }
        assertEquals(1, calls)
        assertTrue(closed)
    }

    @Test fun `cancellation during an active hash closes the stream`() = runTest {
        var readStarted = false
        var closed = false
        val access = object : StorageAccess {
            override fun refresh(asset: Asset) = asset
            override fun open(asset: Asset): InputStream = object : ByteArrayInputStream(ByteArray(300_000)) {
                override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also { readStarted = true }
                override fun close() { closed = true; super.close() }
            }
        }
        assertFailsWith<CancellationException> {
            DuplicateScanner(access).scan({ emit -> emit(asset("a", 300_000)); emit(asset("b", 300_000)) },
                checkpoint = { if (readStarted) throw CancellationException("Test cancellation") })
        }
        assertTrue(readStarted)
        assertTrue(closed)
    }

    @Test fun `contents changing between hash and byte comparison block preflight`() = runTest {
        val ordinary = storage()
        val group = DuplicateScanner(ordinary).scan({ emit -> emit(asset("a")); emit(asset("b")) }).groups.single()
        var opens = 0
        val changing = object : StorageAccess by ordinary {
            override fun open(asset: Asset): InputStream {
                opens++
                return ByteArrayInputStream(byteArrayOf(if (opens == 4) 2 else 1))
            }
        }
        assertFailsWith<IllegalStateException> { Preflight(changing).verify(KeepPolicy.plan(listOf(group), setOf("b")).single()) }
    }

    @Test fun `revoked removal capability blocks even readable duplicate content`() = runTest {
        val access = storage()
        val group = DuplicateScanner(access).scan({ emit -> emit(asset("a")); emit(asset("b")) }).groups.single()
        val revoked = object : StorageAccess by access {
            override fun refresh(asset: Asset) = asset.copy(removable = false)
        }
        assertFailsWith<IllegalStateException> { Preflight(revoked).verify(KeepPolicy.plan(listOf(group), setOf("b")).single()) }
    }

    @Test fun `stale selected URIs and batches over fifty are refused`() = runTest {
        val group = DuplicateScanner(storage()).scan({ emit -> repeat(52) { emit(asset("$it")) } }).groups.single()
        assertFailsWith<IllegalArgumentException> { KeepPolicy.plan(listOf(group), setOf("not-in-findings")) }
        assertFailsWith<IllegalArgumentException> { KeepPolicy.plan(listOf(group), (1..51).map { "$it" }.toSet()) }
        assertEquals(50, KeepPolicy.plan(listOf(group), (1..50).map { "$it" }.toSet()).size)
    }

    @Test fun `unknown lengths never qualify for destructive preflight`() = runTest {
        val a = HashedAsset(asset("a", null), "same")
        val b = HashedAsset(asset("b", null), "same")
        assertFailsWith<IllegalArgumentException> { Preflight(storage()).verify(Removal(a, b)) }
    }
}
