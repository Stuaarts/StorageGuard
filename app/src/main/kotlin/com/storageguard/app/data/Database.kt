package com.storageguard.app.data

import android.content.Context
import androidx.room.*
import com.storageguard.core.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "scan_sessions")
data class ScanSession(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val scope: String,
    val status: String = "RUNNING",
    val discovered: Int = 0,
    val errors: Int = 0,
)

@Entity(tableName = "files", indices = [Index("scanId"), Index("groupId")])
data class FileRecord(
    @PrimaryKey val uri: String,
    val identity: String?, val name: String, val mime: String, val size: Long?, val modified: Long?,
    val location: String, val source: String, val removable: Boolean,
    val sha256: String?, val groupId: String?, val scanId: String,
) {
    fun asset() = Asset(uri, identity, name, mime, size, modified, location, Source.valueOf(source), removable)
}

@Entity(tableName = "duplicate_groups")
data class GroupRecord(@PrimaryKey val id: String, val scanId: String, val sizeBytes: Long, val sha256: String)

@Entity(tableName = "cleanup_attempts", indices = [Index("requestedAt")])
data class CleanupAttempt(
    @PrimaryKey val id: String,
    val batch: String,
    // URIs are kept only while a request needs reconciliation; terminal audit rows omit paths/names.
    val uri: String?,
    val action: String,
    val bytes: Long,
    val requestedAt: Long,
    val completedAt: Long? = null,
    val status: String = "REQUESTED",
    val detail: String = "Awaiting verified provider outcome",
)

@Dao
interface StorageDao {
    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC LIMIT 20") fun sessions(): Flow<List<ScanSession>>
    @Query("SELECT * FROM cleanup_attempts ORDER BY requestedAt DESC LIMIT 200") fun attempts(): Flow<List<CleanupAttempt>>
    @Query("SELECT * FROM files") suspend fun files(): List<FileRecord>
    @Query("SELECT * FROM cleanup_attempts WHERE status = 'REQUESTED'") suspend fun pending(): List<CleanupAttempt>
    @Upsert suspend fun session(value: ScanSession)
    @Upsert suspend fun attempts(values: List<CleanupAttempt>)
    @Insert suspend fun files(values: List<FileRecord>)
    @Insert suspend fun groups(values: List<GroupRecord>)
    @Query("DELETE FROM files") suspend fun clearFiles()
    @Query("DELETE FROM duplicate_groups") suspend fun clearGroups()
    @Query("DELETE FROM cleanup_attempts") suspend fun clearAttempts()
    @Query("DELETE FROM scan_sessions") suspend fun clearSessions()
    @Query("UPDATE scan_sessions SET status = 'INTERRUPTED', endedAt = :now WHERE status = 'RUNNING'") suspend fun interruptScans(now: Long)
    @Query("DELETE FROM scan_sessions WHERE id NOT IN (SELECT id FROM scan_sessions ORDER BY startedAt DESC LIMIT 20)") suspend fun trimSessions()
    @Query("DELETE FROM cleanup_attempts WHERE status != 'REQUESTED' AND id NOT IN (SELECT id FROM cleanup_attempts ORDER BY requestedAt DESC LIMIT 200)") suspend fun trimAttempts()
}

@Database(entities = [ScanSession::class, FileRecord::class, GroupRecord::class, CleanupAttempt::class], version = 1, exportSchema = true)
abstract class StorageDatabase : RoomDatabase() {
    abstract fun dao(): StorageDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, StorageDatabase::class.java, "storageguard.db").build()
    }
}

suspend fun StorageDatabase.saveResult(scanId: String, result: ScanResult) = withTransaction {
    val hashes = result.hashed.associateBy { it.asset.uri }
    val groupIds = result.groups.flatMap { group -> group.files.map { it.asset.uri to group.id } }.toMap()
    dao().clearFiles()
    dao().clearGroups()
    dao().files(result.inventory.map { asset ->
        FileRecord(asset.uri, asset.identity, asset.name, asset.mime, asset.size, asset.modified, asset.location,
            asset.source.name, asset.removable, hashes[asset.uri]?.sha256, groupIds[asset.uri], scanId)
    })
    dao().groups(result.groups.map { GroupRecord(it.id, scanId, it.size, it.files.first().sha256) })
}
