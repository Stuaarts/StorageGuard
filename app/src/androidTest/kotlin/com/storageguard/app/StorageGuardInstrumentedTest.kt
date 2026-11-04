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
import androidx.lifecycle.ViewModelProvider
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
import java.security.MessageDigest
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

    @Test fun unavailablePendingMediaIsUnverifiedRatherThanReportedRemoved() {
        runBlocking {
            app.preferences.welcome()
            app.database.dao().attempts(listOf(CleanupAttempt("missing", "test",
                "content://media/external_primary/images/media/9223372036854775806", "TRASH", 4, System.currentTimeMillis())))
        }
        launch()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Unverified · media trash").fetchSemanticsNodes().isNotEmpty() }
        val result = runBlocking { app.database.dao().attempts().first().single() }
        assertEquals("UNVERIFIED", result.status)
        assertNull(result.uri)
    }

    @Test fun interruptedDocumentAttemptIsNeverAssumedDeleted() {
        runBlocking {
            app.preferences.welcome()
            app.database.dao().attempts(listOf(CleanupAttempt("document", "test",
                "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fmissing-test.bin",
                "DELETE", 4, System.currentTimeMillis())))
        }
        launch()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Unverified · document deletion").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("UNVERIFIED", runBlocking { app.database.dao().attempts().first().single().status })
    }

    @Test fun protectedCopyAndSelectionSurviveReviewActivityRecreation() {
        val (a, b) = prepareReview()
        compose.onNodeWithContentDescription("Remove $prefix-b.png").performScrollTo().performClick()
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Review 1 selected · 4 B").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove $prefix-b.png").performScrollTo().assertIsOn()
        compose.onNodeWithText("Keep this").performScrollTo().performClick()
        compose.onNodeWithText("Review 0 selected · 0 B").assertIsDisplayed()
        assertEquals(false, app.storage.isTrashed(a.toString()))
        assertEquals(false, app.storage.isTrashed(b.toString()))
    }

    @Test fun changedTargetBlocksTheEntireUiCleanupBeforeAndroidConsent() {
        val (a, b) = prepareReview()
        compose.onNodeWithContentDescription("Remove $prefix-b.png").performScrollTo().performClick()
        app.contentResolver.openOutputStream(b, "wt")!!.use { it.write(byteArrayOf(8, 7, 6, 5)) }
        compose.onNodeWithText("Review 1 selected · 4 B").performClick()
        compose.onNodeWithText("Verify & request Android approval").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("Scan again.", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(runBlocking { app.database.dao().attempts().first().isEmpty() })
        assertEquals(false, app.storage.isTrashed(a.toString()))
        assertEquals(false, app.storage.isTrashed(b.toString()))
    }

    @Test fun missingKeeperBlocksUiCleanupWithoutRemovingTheRemainingCopy() {
        val (a, b) = prepareReview()
        compose.onNodeWithContentDescription("Remove $prefix-b.png").performScrollTo().performClick()
        app.contentResolver.delete(a, null, null)
        compose.onNodeWithText("Review 1 selected · 4 B").performClick()
        compose.onNodeWithText("Verify & request Android approval").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Scan again.", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runBlocking { app.database.dao().attempts().first().isEmpty() })
        assertEquals(false, app.storage.isTrashed(b.toString()))
    }

    @Test fun interruptedScanSessionIsMarkedInterruptedOnLaunch() {
        runBlocking {
            app.preferences.welcome()
            app.database.dao().session(com.storageguard.app.data.ScanSession("unfinished", System.currentTimeMillis(), scope = "synthetic test"))
        }
        launch()
        compose.waitUntil(15_000) { runBlocking { app.database.dao().sessions().first().single().status == "INTERRUPTED" } }
        assertNotNull(runBlocking { app.database.dao().sessions().first().single().endedAt })
    }

    @Test fun resetClearsPrivateStateWithoutRemovingUserFiles() {
        val (a, b) = prepareReview()
        val before = digest(a)
        runBlocking {
            app.preferences.permanent(true)
            app.preferences.addSource("D|$a")
            app.database.dao().attempts(listOf(CleanupAttempt("reset-test", "test", null, "TRASH", 4,
                System.currentTimeMillis(), completedAt = System.currentTimeMillis(), status = "SKIPPED")))
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Reset local app data").performScrollTo().performClick()
        compose.onNodeWithText("Reset app data").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Continue without media access").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            assertTrue(app.database.dao().files().isEmpty())
            assertTrue(app.database.dao().sessions().first().isEmpty())
            assertTrue(app.database.dao().attempts().first().isEmpty())
            val prefs = app.preferences.flow.first()
            assertFalse(prefs.welcomed)
            assertFalse(prefs.permanentDelete)
            assertTrue(prefs.sources.isEmpty())
        }
        assertEquals(before, digest(a))
        assertEquals(false, app.storage.isTrashed(a.toString()))
        assertEquals(false, app.storage.isTrashed(b.toString()))
    }

    @Test fun installedAppHasNoNetworkingOrBroadStoragePermissionAndBackupsAreOff() {
        val info = app.packageManager.getPackageInfo(app.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_PROVIDERS)
        val requested = info.requestedPermissions.orEmpty().toSet()
        assertFalse("android.permission.INTERNET" in requested)
        assertFalse("android.permission.MANAGE_EXTERNAL_STORAGE" in requested)
        assertEquals(0, info.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertTrue("No production provider should be exported", info.providers.orEmpty().none { it.exported })
    }

    @Test fun pauseCancelAndRestartKeepFilesUntouched() {
        val content = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val a = image("pause-a", content)
        val b = image("pause-b", content)
        val before = digest(a)
        runBlocking { app.preferences.welcome() }
        launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Scan accessible files").fetchSemanticsNodes().isNotEmpty() }
        lateinit var vm: StorageViewModel
        scenario!!.onActivity {
            vm = ViewModelProvider(it)[StorageViewModel::class.java]
            vm.scan()
            vm.pause()
        }
        compose.waitUntil(10_000) { vm.state.value.paused && vm.state.value.running }
        compose.onNodeWithText("Scan paused.").assertIsDisplayed()
        scenario!!.onActivity { vm.pause(); vm.cancel() }
        compose.waitUntil(15_000) { !vm.state.value.running }
        assertEquals("CANCELLED", runBlocking { app.database.dao().sessions().first().first().status })
        scenario!!.onActivity { vm.scan() }
        compose.waitUntil(30_000) { vm.state.value.scanFinished && !vm.state.value.running }
        assertTrue(vm.state.value.groups.any { group -> group.files.map { it.asset.uri }.containsAll(listOf(a.toString(), b.toString())) })
        assertEquals(before, digest(a))
        assertEquals(before, digest(b))
        assertEquals(false, app.storage.isTrashed(a.toString()))
        assertEquals(false, app.storage.isTrashed(b.toString()))
        assertTrue(runBlocking { app.database.dao().attempts().first().isEmpty() })
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
        assertTrue("Wait for the chosen folder's contents before granting access",
            device.wait(Until.hasObject(By.text("$prefix-folder-a.png")), 10_000))
        device.wait(Until.findObject(By.res("android", "button1").text(java.util.regex.Pattern.compile("use this folder", java.util.regex.Pattern.CASE_INSENSITIVE))), 10_000)!!.click()
        device.wait(Until.findObject(By.res("android", "button1").text(java.util.regex.Pattern.compile("allow", java.util.regex.Pattern.CASE_INSENSITIVE))), 10_000)!!.click()
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
        val limitedStorage = AndroidStorage(app, ScanLimits(maxProviderRows = 1))
        val limited = runBlocking { DuplicateScanner(limitedStorage).scan({ emit -> limitedStorage.discover(sources, { error(it) }, emit) }) }
        assertNotNull("Adapter limits must propagate as a controlled partial scan", limited.coverageLimit)
        assertTrue(limited.progress.issues > 0)
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
        // Saved folder access is revoked explicitly, then the index is invalidated.
        scenario!!.onActivity { ViewModelProvider(it)[StorageViewModel::class.java].removeSource(sources.single()) }
        compose.waitUntil(10_000) { runBlocking { app.preferences.flow.first().sources.isEmpty() } }
        assertFalse(app.contentResolver.persistedUriPermissions.any { it.uri == root })
    }

    private fun prepareMediaTrash(): Pair<Uri, Uri> {
        val (a, b) = prepareReview()
        compose.onNodeWithContentDescription("Remove $prefix-b.png").performScrollTo().performClick()
        compose.onNodeWithText("Review 1 selected · 4 B").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Check before cleanup.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Verify & request Android approval").performScrollTo().performClick()
        return a to b
    }

    private fun prepareReview(): Pair<Uri, Uri> {
        val a = image("a", byteArrayOf(1, 2, 3, 4))
        val b = image("b", byteArrayOf(1, 2, 3, 4))
        runBlocking { app.preferences.welcome() }
        launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Scan accessible files").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Scan accessible files").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("$prefix-a.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("$prefix-a.png").performScrollTo().performClick()
        return a to b
    }

    private fun digest(uri: Uri): String = app.contentResolver.openInputStream(uri)!!.use { input ->
        MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) }
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
