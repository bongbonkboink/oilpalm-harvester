package com.example.oilpalmharvester

import androidx.room.*

@Dao
interface HarvestDao {

    @Insert
    suspend fun insert(record: HarvestRecord): Long

    @Update
    suspend fun update(record: HarvestRecord)

    @Query("SELECT * FROM harvest_records WHERE id = :id")
    suspend fun getById(id: Long): HarvestRecord?

    @Query("SELECT * FROM harvest_records ORDER BY timestamp DESC")
    suspend fun getAll(): List<HarvestRecord>

    @Query("SELECT * FROM harvest_records WHERE timestamp >= :startOfDay AND timestamp < :endOfDay ORDER BY timestamp DESC")
    suspend fun getForDay(startOfDay: Long, endOfDay: Long): List<HarvestRecord>

    @Query("SELECT * FROM harvest_records WHERE timestamp >= :startOfMonth AND timestamp < :endOfMonth ORDER BY timestamp ASC")
    suspend fun getForMonth(startOfMonth: Long, endOfMonth: Long): List<HarvestRecord>

    @Query("SELECT * FROM harvest_records WHERE synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsynced(): List<HarvestRecord>

    @Query("SELECT * FROM harvest_records WHERE photoSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedPhotos(): List<HarvestRecord>

    @Query("UPDATE harvest_records SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("UPDATE harvest_records SET photoSynced = 1 WHERE id = :id")
    suspend fun markPhotoSynced(id: Long)

    @Query("DELETE FROM harvest_records WHERE id = :id")
    suspend fun deleteById(id: Long)
}
