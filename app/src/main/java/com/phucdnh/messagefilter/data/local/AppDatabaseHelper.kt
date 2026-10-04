package com.phucdnh.messagefilter.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.phucdnh.messagefilter.data.CapturedMessage

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        private const val DATABASE_NAME = "message_filter.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_HISTORY = "notification_history"
        const val COL_ID = "id"
        const val COL_PACKAGE_NAME = "package_name"
        const val COL_SENDER = "sender"
        const val COL_CONTENT = "content"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_IS_FORWARDED = "is_forwarded"
        const val COL_STATUS = "status"

        @Volatile
        private var instance: AppDatabaseHelper? = null

        fun getInstance(context: Context): AppDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: AppDatabaseHelper(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
            CREATE TABLE $TABLE_HISTORY (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PACKAGE_NAME TEXT NOT NULL,
                $COL_SENDER TEXT NOT NULL,
                $COL_CONTENT TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_IS_FORWARDED INTEGER NOT NULL DEFAULT 1,
                $COL_STATUS TEXT NOT NULL DEFAULT 'FORWARD'
            )
        """.trimIndent()
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COL_STATUS TEXT NOT NULL DEFAULT 'FORWARD'")
            } catch (e: Exception) {
                db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
                onCreate(db)
            }
        }
    }

    fun insertMessage(message: CapturedMessage): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PACKAGE_NAME, message.packageName)
            put(COL_SENDER, message.senderOrTitle)
            put(COL_CONTENT, message.messageContent)
            put(COL_TIMESTAMP, message.timestamp)
            put(COL_IS_FORWARDED, if (message.isForwarded) 1 else 0)
            put(COL_STATUS, message.status)
        }
        return db.insert(TABLE_HISTORY, null, values)
    }

    fun getAllMessages(limit: Int = 300): List<CapturedMessage> {
        val list = mutableListOf<CapturedMessage>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_HISTORY,
            null,
            null,
            null,
            null,
            null,
            "$COL_TIMESTAMP DESC",
            limit.toString()
        )

        cursor.use {
            val idIdx = cursor.getColumnIndexOrThrow(COL_ID)
            val pkgIdx = cursor.getColumnIndexOrThrow(COL_PACKAGE_NAME)
            val senderIdx = cursor.getColumnIndexOrThrow(COL_SENDER)
            val contentIdx = cursor.getColumnIndexOrThrow(COL_CONTENT)
            val timeIdx = cursor.getColumnIndexOrThrow(COL_TIMESTAMP)
            val forwardedIdx = cursor.getColumnIndexOrThrow(COL_IS_FORWARDED)
            val statusIdx = cursor.getColumnIndex(COL_STATUS)

            while (cursor.moveToNext()) {
                val statusVal = if (statusIdx != -1 && !cursor.isNull(statusIdx)) {
                    cursor.getString(statusIdx)
                } else {
                    if (cursor.getInt(forwardedIdx) == 1) "FORWARD" else "NORMAL"
                }

                list.add(
                    CapturedMessage(
                        id = cursor.getLong(idIdx),
                        packageName = cursor.getString(pkgIdx),
                        senderOrTitle = cursor.getString(senderIdx),
                        messageContent = cursor.getString(contentIdx),
                        timestamp = cursor.getLong(timeIdx),
                        isForwarded = cursor.getInt(forwardedIdx) == 1,
                        status = statusVal
                    )
                )
            }
        }
        return list
    }

    fun deleteMessage(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_HISTORY, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun clearAll(): Int {
        val db = writableDatabase
        return db.delete(TABLE_HISTORY, null, null)
    }
}
