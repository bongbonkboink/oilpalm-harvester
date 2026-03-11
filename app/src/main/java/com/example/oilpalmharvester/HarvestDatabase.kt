package com.example.oilpalmharvester

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [HarvestRecord::class], version = 1, exportSchema = false)
abstract class HarvestDatabase : RoomDatabase() {
    abstract fun harvestDao(): HarvestDao

    companion object {
        @Volatile private var INSTANCE: HarvestDatabase? = null

        fun getInstance(context: Context): HarvestDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    HarvestDatabase::class.java,
                    "harvest_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
