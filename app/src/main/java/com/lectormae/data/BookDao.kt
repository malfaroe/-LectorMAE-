package com.lectormae.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY lastOpened DESC, addedDate DESC")
    fun allBooks(): Flow<List<Book>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(books: List<Book>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: Book)

    @Delete
    suspend fun delete(book: Book)

    @Query("UPDATE books SET lastOpened = :time WHERE id = :id")
    suspend fun updateLastOpened(id: String, time: Long)
}
