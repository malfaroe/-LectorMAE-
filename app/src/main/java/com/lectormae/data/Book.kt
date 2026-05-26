package com.lectormae.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String,        // MD5 del path — evita duplicados
    val title: String,
    val author: String,
    val filePath: String,
    val format: String,                // "EPUB" | "PDF"
    val coverPath: String?,
    val fileSize: Long,
    val lastChapter: Int = 0,
    val lastPage: Int = 0,
    val lastOpened: Long = 0L,
    val addedDate: Long = System.currentTimeMillis()
)
