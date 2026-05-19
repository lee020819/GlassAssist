package com.example.glassassist

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "glassassist.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS protection_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                userId TEXT NOT NULL,
                date TEXT NOT NULL,
                time TEXT NOT NULL,
                timestampMs INTEGER NOT NULL,
                keyword TEXT NOT NULL,
                videoUri TEXT
            )""")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS qa_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                userId TEXT NOT NULL,
                date TEXT NOT NULL,
                time TEXT NOT NULL,
                question TEXT NOT NULL,
                answer TEXT NOT NULL
            )""")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS video_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                userId TEXT NOT NULL,
                date TEXT NOT NULL,
                time TEXT NOT NULL,
                videoUri TEXT
            )""")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS meter_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                userId TEXT NOT NULL,
                date TEXT NOT NULL,
                time TEXT NOT NULL,
                location TEXT NOT NULL,
                videoUri TEXT
            )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // 접객보호
    fun insertProtection(userId: String, date: String, time: String, timestampMs: Long, keyword: String) {
        writableDatabase.insert("protection_records", null, ContentValues().apply {
            put("userId", userId); put("date", date); put("time", time)
            put("timestampMs", timestampMs); put("keyword", keyword)
        })
    }

    fun linkProtectionVideo(userId: String, timestampMs: Long, videoUri: String) {
        writableDatabase.execSQL(
            "UPDATE protection_records SET videoUri = ? WHERE userId = ? AND timestampMs = ?",
            arrayOf(videoUri, userId, timestampMs.toString())
        )
    }

    fun getProtectionRecords(userId: String): List<ProtectionData> {
        val list = mutableListOf<ProtectionData>()
        readableDatabase.rawQuery(
            "SELECT date, time, timestampMs, keyword, videoUri FROM protection_records WHERE userId = ? ORDER BY timestampMs DESC",
            arrayOf(userId)
        ).use { c ->
            while (c.moveToNext())
                list.add(ProtectionData(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4)))
        }
        return list
    }

    // Q&A
    fun insertQa(userId: String, date: String, time: String, question: String, answer: String) {
        writableDatabase.insert("qa_records", null, ContentValues().apply {
            put("userId", userId); put("date", date); put("time", time)
            put("question", question); put("answer", answer)
        })
    }

    fun getQaRecords(userId: String): List<QaData> {
        val list = mutableListOf<QaData>()
        readableDatabase.rawQuery(
            "SELECT date, time, question, answer FROM qa_records WHERE userId = ? ORDER BY id DESC",
            arrayOf(userId)
        ).use { c ->
            while (c.moveToNext())
                list.add(QaData(c.getString(0), c.getString(1), c.getString(2), c.getString(3)))
        }
        return list
    }

    // 영상
    fun insertVideo(userId: String, date: String, time: String, videoUri: String?) {
        writableDatabase.insert("video_records", null, ContentValues().apply {
            put("userId", userId); put("date", date); put("time", time)
            videoUri?.let { put("videoUri", it) }
        })
    }

    fun getVideoRecords(userId: String): List<VideoData> {
        val list = mutableListOf<VideoData>()
        readableDatabase.rawQuery(
            "SELECT date, time, videoUri FROM video_records WHERE userId = ? ORDER BY id DESC",
            arrayOf(userId)
        ).use { c ->
            while (c.moveToNext())
                list.add(VideoData(c.getString(0), c.getString(1), c.getString(2)))
        }
        return list
    }

    // 계량기
    fun insertMeter(userId: String, date: String, time: String, location: String, videoUri: String? = null) {
        writableDatabase.insert("meter_records", null, ContentValues().apply {
            put("userId", userId); put("date", date); put("time", time); put("location", location)
            videoUri?.let { put("videoUri", it) }
        })
    }

    fun getMeterRecords(userId: String): List<MeterData> {
        val list = mutableListOf<MeterData>()
        readableDatabase.rawQuery(
            "SELECT date, time, location, videoUri FROM meter_records WHERE userId = ? ORDER BY id DESC",
            arrayOf(userId)
        ).use { c ->
            while (c.moveToNext())
                list.add(MeterData(c.getString(0), c.getString(1), c.getString(2), c.getString(3)))
        }
        return list
    }

    fun deleteAllRecords(userId: String) {
        writableDatabase.let { db ->
            db.delete("protection_records", "userId = ?", arrayOf(userId))
            db.delete("qa_records", "userId = ?", arrayOf(userId))
            db.delete("video_records", "userId = ?", arrayOf(userId))
            db.delete("meter_records", "userId = ?", arrayOf(userId))
        }
    }

    data class ProtectionData(val date: String, val time: String, val timestampMs: Long, val keyword: String, val videoUri: String?)
    data class QaData(val date: String, val time: String, val question: String, val answer: String)
    data class VideoData(val date: String, val time: String, val videoUri: String?)
    data class MeterData(val date: String, val time: String, val location: String, val videoUri: String?)
}
