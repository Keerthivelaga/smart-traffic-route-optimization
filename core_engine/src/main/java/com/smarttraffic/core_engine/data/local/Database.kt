package com.smarttraffic.core_engine.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "traffic_cache")
data class TrafficSnapshotEntity(
    @PrimaryKey val segmentId: String,
    val congestionScore: Float,
    val confidence: Float,
    val avgSpeedKph: Float,
    val anomalyScore: Float,
    val timestampIso: String,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "route_cache")
data class RouteOptionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val etaMinutes: Int,
    val distanceKm: Float,
    val confidence: Float,
    val mode: String,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "leaderboard_cache")
data class LeaderboardEntity(
    @PrimaryKey val userId: String,
    val score: Float,
    val rank: Int,
    val trustScore: Float,
    val verifiedReports: Int,
    val gpsContributions: Int,
    val streakDays: Int,
    val updatedAtEpochMs: Long,
)

@Dao
interface TrafficDao {
    @Query("SELECT * FROM traffic_cache WHERE segmentId = :segmentId")
    fun observe(segmentId: String): Flow<TrafficSnapshotEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: TrafficSnapshotEntity)
}

@Dao
interface RouteDao {
    @Query("SELECT * FROM route_cache WHERE mode = :mode ORDER BY confidence DESC")
    fun observe(mode: String): Flow<List<RouteOptionEntity>>

    @Query("DELETE FROM route_cache WHERE mode = :mode")
    suspend fun clearMode(mode: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(routes: List<RouteOptionEntity>)
}

@Dao
interface LeaderboardDao {
    @Query("SELECT * FROM leaderboard_cache ORDER BY rank ASC")
    fun observe(): Flow<List<LeaderboardEntity>>

    @Query("SELECT * FROM leaderboard_cache")
    suspend fun getAll(): List<LeaderboardEntity>

    @Query("SELECT * FROM leaderboard_cache WHERE userId = :userId LIMIT 1")
    suspend fun getByUserId(userId: String): LeaderboardEntity?

    @Query("SELECT COUNT(*) FROM leaderboard_cache")
    suspend fun countRows(): Int

    @Query("DELETE FROM leaderboard_cache")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<LeaderboardEntity>)
}

@Database(
    entities = [TrafficSnapshotEntity::class, RouteOptionEntity::class, LeaderboardEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class SmartTrafficDatabase : RoomDatabase() {
    abstract fun trafficDao(): TrafficDao
    abstract fun routeDao(): RouteDao
    abstract fun leaderboardDao(): LeaderboardDao
}


