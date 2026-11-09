package com.changliu.app.hub.ledger

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** 一条"发生了什么"。账本只记事件，不记屏幕文字/截图。 */
@Entity(
    tableName = "event",
    indices = [Index("at"), Index("kind"), Index("pkg")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    /** zk / ys / driver / brain / hub */
    val source: String,
    /** app_fg / unlock / gate_shown / gate_answer / task_done / live_start / live_end / train_set / flow_transition / ai_action ... */
    val kind: String,
    val pkg: String = "",
    val ref: String = "",
    /** 时长秒数、分钟、1/0 计数等，按 kind 约定 */
    val num: Long = 0,
    val payload: String = "",
)

@Entity(tableName = "rollup", primaryKeys = ["day", "metric", "key"])
data class RollupEntity(
    val day: String,
    val metric: String,
    val key: String = "",
    val value: Long,
)

data class PkgTotal(val pkg: String, val total: Long)

@Dao
interface LedgerDao {
    @Insert
    suspend fun insert(e: EventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRollup(r: RollupEntity)

    @Query("SELECT * FROM event WHERE at >= :from AND at < :to ORDER BY at")
    suspend fun between(from: Long, to: Long): List<EventEntity>

    @Query("SELECT COALESCE(SUM(num), 0) FROM event WHERE kind = :kind AND at >= :from AND at < :to")
    suspend fun sum(kind: String, from: Long, to: Long): Long

    @Query("SELECT COALESCE(SUM(num), 0) FROM event WHERE kind = 'app_fg' AND pkg IN (:pkgs) AND at >= :from AND at < :to")
    suspend fun sumPackages(pkgs: List<String>, from: Long, to: Long): Long

    @Query("SELECT COUNT(*) FROM event WHERE kind = :kind AND at >= :from AND at < :to")
    suspend fun count(kind: String, from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM event WHERE kind = :kind AND num > 0 AND at >= :from AND at < :to")
    suspend fun countPositive(kind: String, from: Long, to: Long): Int

    @Query(
        "SELECT pkg, SUM(num) AS total FROM event WHERE kind = 'app_fg' AND at >= :from AND at < :to " +
            "GROUP BY pkg ORDER BY total DESC LIMIT :limit",
    )
    suspend fun topPackages(from: Long, to: Long, limit: Int): List<PkgTotal>

    @Query("SELECT * FROM rollup WHERE day = :day")
    suspend fun rollupOf(day: String): List<RollupEntity>

    @Query("DELETE FROM event WHERE at < :before")
    suspend fun pruneEvents(before: Long)

    @Query("DELETE FROM rollup WHERE day < :beforeDay")
    suspend fun pruneRollup(beforeDay: String)

    @Query("DELETE FROM event")
    suspend fun clearEvents()

    @Query("DELETE FROM rollup")
    suspend fun clearRollup()
}

@Database(entities = [EventEntity::class, RollupEntity::class], version = 1, exportSchema = false)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        const val NAME = "changliu.db"

        fun create(context: Context): LedgerDatabase = Room.databaseBuilder(
            context.applicationContext,
            LedgerDatabase::class.java,
            NAME,
        ).fallbackToDestructiveMigration().build()
    }
}
