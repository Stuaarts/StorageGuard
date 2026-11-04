package com.storageguard.app

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.storageguard.app.data.saveResult
import com.storageguard.app.data.CleanupAttempt
import com.storageguard.app.storage.AndroidStorage
import com.storageguard.core.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageGuardInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as StorageGuardApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private val created = mutableListOf<Uri>()
    private val createdDocuments = mutableListOf<Uri>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val prefix = "sg-test-${UUID.randomUUID().toString().take(8)}"

    @Before fun reset() = runBlocking {
        val dao = app.database.dao()
        dao.clearFiles(); dao.clearGroups(); dao.clearAttempts(); dao.clearSessions()
        app.preferences.clear()
        grantMedia()
    }
    @After fun clean() {
        scenario?.close()
        for (uri in createdDocuments) try { DocumentsContract.deleteDocument(app.contentResolver, uri) } catch (_: Exception) { }
        for (uri in created) try {
            app.contentResolver.delete(uri, Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) })
        } catch (_: Exception) { }
    }

    @Test fun realMediaStoreScanningPersistsResultsAndRejectsChangedBytes() = runBlocking {
        val a = image("a", byteArrayOf(1, 2, 3, 4))
        val b = image("b", byteArrayOf(1, 2, 3, 4))
        image("different", byteArrayOf(4, 3, 2, 1))
        val storage = AndroidStorage(app)
        val result = DuplicateScanner(storage).scan({ emit ->
            storage.discover(emptySet(), { error(it) }) { if (it.uri in created.map(Uri::toString)) emit(it) }
        })
        assertEquals(3, result.inventory.size)
        assertEquals(1, result.groups.size)
        assertEquals(4L, result.groups.single().possibleBytes)
        app.database.saveResult("test", result)
        assertEquals(3, app.database.dao().files().size)
        val removal = KeepPolicy.plan(result.groups, setOf(b.toString())).single()
        assertEquals(a.toString(), removal.keeper.asset.uri)
        Preflight(storage).verify(removal)
        app.contentResolver.openOutputStream(b, "wt")!!.use { it.write(byteArrayOf(9, 9, 9, 9)) }
        var blocked = false
        try { Preflight(storage).verify(removal) } catch (_: IllegalStateException) { blocked = true }
        assertTrue("Changed bytes must block removal", blocked)
    }

    @Test fun onboardingCoverageAndActivityRecreation() {
        launch()
        compose.onNodeWithText("Continue without media access").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Know your storage.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Settings", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Your scan, your scope.").assertIsDisplayed()
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Your scan, your scope.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Allow permanent document deletion").performScrollTo().assertIsOff()
    }

    @Test fun approvedMediaTrashKeepsOneCopyAndReportsVerifiedOutcome() {
        val (a, b) = prepareMediaTrash()
        // This is Android's own consent dialog, outside the Compose hierarchy.
        val allow = device.wait(Until.findObject(By.res("android", "button1")), 15_000)
        assertNotNull("Android must present its trash approval dialog", allow)
        allow.click()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Trashed · media trash").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(false, app.storage.isTrashed(a.toString()))
        assertEquals(true, app.storage.isTrashed(b.toString()))
        val attempts = runBlocking { app.database.dao().pending() }
        assertTrue(attempts.isEmpty())
    }

    @Test fun deniedMediaTrashRetainsBothCopiesAndReportsSkipped() {
        val (a, b) = prepareMediaTrash()
        val cancel = device.wait(Until.findObject(By.res("android", "button2")), 15_000)
        assertNotNull("Android must present a cancel/deny action", cancel)
        cancel.click()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Skipped · media trash").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(false, app.storage.isTrashed(a.toString()))
        assertEquals(false, app.storage.isTrashed(b.toString()))
    }

    @Test fun interruptedTrashRequestIsReconciledOnLaunch() {
        val uri = image("interrupted", byteArrayOf(5, 6, 7, 8))
        app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_TRASHED, 1) }, null, null)
        runBlocking {
            app.preferences.welcome()
            app.database.dao().attempts(listOf(CleanupAttempt("interrupted", "test", uri.toString(), "TRASH", 4, System.currentTimeMillis())))
        }
        launch()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Trashed · media trash").fetchSemanticsNodes().isNotEmpty() }
        val saved = runBlocking { app.database.dao().attempts().first().single() }
        assertEquals("TRASHED", saved.status)
        assertNull("Terminal history must omit the URI", saved.uri)
        assertNotNull(saved.completedAt)
    }

    @Test fun selectedLocalFolderDeduplicatesAliasesAndSupportsReviewedDocumentDeletion() {
        image("folder-a", byteArrayOf(1, 2, 3, 4))
        image("folder-b", byteArrayOf(1, 2, 3, 4))
        runBlocking { app.preferences.welcome() }
        launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Add a folder").performScrollTo().performClick()
        // The disposable emulator's picker opens local storage. Both names are actual provider rows.
        device.wait(Until.findObject(By.text("Pictures")), 10_000)!!.click()
        device.wait(Until.findObject(By.text("StorageGuardTests")), 10_000)!!.click()
        device.wait(Until.findObject(By.res("android", "button1").enabled(true)), 10_000)!!.click()
        device.wait(Until.findObject(By.res("android", "button1").enabled(true)), 10_000)!!.click()
        compose.waitUntil(10_000) { runBlocking { app.preferences.flow.first().sources.isNotEmpty() } }
        val sources = runBlocking { app.preferences.flow.first().sources }
        val root = Uri.parse(sources.single().substringAfter('|'))
        assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == root && it.isReadPermission && it.isWritePermission })
        val storage = AndroidStorage(app)
        val aliases = runBlocking {
            DuplicateScanner(storage).scan({ emit -> storage.discover(sources, { error(it) }) { if (it.name.startsWith(prefix)) emit(it) } })
        }
        assertEquals("Media and SAF references must be counted once", 2, aliases.inventory.size)
        assertEquals(4L, aliases.groups.single().possibleBytes)
        val parent = DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
        fun document(suffix: String): Uri {
            val uri = DocumentsContract.createDocument(app.contentResolver, parent, "application/octet-stream", "$prefix-doc-$suffix.bin")!!
            createdDocuments += uri
            app.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(7, 8, 9, 0, 1)) }
            return uri
        }
        document("a"); document("b")
        runBlocking {
            storage.discover(sources, { error(it) }) { asset ->
                if (asset.name.startsWith("$prefix-doc-") && asset.source == Source.DOCUMENT) {
                    assertTrue("Local documents must support deletion: $asset", asset.removable)
                }
            }
        }
        compose.onAllNodesWithContentDescription("Dismiss").fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
            compose.onNodeWithContentDescription("Dismiss").performClick()
        }
        compose.onNodeWithContentDescription("Allow permanent document deletion").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { app.preferences.flow.first().permanentDelete } }
        compose.onNodeWithContentDescription("Allow permanent document deletion").assertIsOn()
        compose.onNodeWithText("Home", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Scan accessible files").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("$prefix-doc-", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("$prefix-doc-", substring = true)).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Additional copy").performScrollTo()
        val checkbox = compose.onAllNodes(hasContentDescription("Remove $prefix-doc-", substring = true)).onFirst()
        checkbox.performScrollTo().performClick()
        checkbox.assertIsOn()
        compose.onNodeWithText("Review 1 selected · 5 B").performClick()
        compose.onNodeWithText("Verify & proceed with deletion").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { app.database.dao().attempts().first().any { it.status != "REQUESTED" } } }
        val outcome = runBlocking { app.database.dao().attempts().first().single() }
        assertEquals("Provider outcome: $outcome", "DELETED", outcome.status)
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Deleted · document deletion").fetchSemanticsNodes().isNotEmpty() }
        val stillPresent = createdDocuments.count { storage.documentExists(it.toString()) == true }
        assertEquals("One verified document must remain", 1, stillPresent)
    }

    private fun prepareMediaTrash(): Pair<Uri, Uri> {
        val a = image("a", byteArrayOf(1, 2, 3, 4))
        val b = image("b", byteArrayOf(1, 2, 3, 4))
        runBlocking { app.preferences.welcome() }
        launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Scan accessible files").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Scan accessible files").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("$prefix-a.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("$prefix-a.png").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Remove $prefix-b.png").performScrollTo().performClick()
        compose.onNodeWithText("Review 1 selected · 4 B").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Check before cleanup.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Verify & request Android approval").performScrollTo().performClick()
        return a to b
    }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    private fun grantMedia() {
        val permissions = if (Build.VERSION.SDK_INT >= 33)
            listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")
        else listOf("android.permission.READ_EXTERNAL_STORAGE")
        permissions.forEach { device.executeShellCommand("pm grant ${app.packageName} $it") }
    }
    private fun image(suffix: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$prefix-$suffix.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StorageGuardTests/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = app.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        created += uri
        app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
