package com.example.oilpalmharvester

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "harvest_records")
data class HarvestRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val harvesterId: String,
    val blockId: String,
    val ripeBunches: Int,
    val emptyBunches: Int,
    val latitude: Double,
    val longitude: Double,
    val photoPath: String,
    val timestamp: Long,
    val synced: Boolean = false,
    val photoSynced: Boolean = false
)
