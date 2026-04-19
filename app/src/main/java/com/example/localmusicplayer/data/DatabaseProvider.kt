package com.example.localmusicplayer.data

import android.content.Context
import android.util.Log

object DatabaseProvider {
    private const val TAG = "DEBUG_DB"
    private var database: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
        return database ?: synchronized(this) {
            val db = AppDatabase.getDatabase(context)
            database = db
            db
        }
    }

    suspend fun <T> safeDbCall(block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Database error: ${e.message}", e)
            null
        }
    }
}