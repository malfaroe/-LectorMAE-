package com.lectormae.data

class BookRepository(private val dao: BookDao) {

    val allBooks = dao.allBooks()

    suspend fun insertAll(books: List<Book>) = dao.insertAll(books)
    suspend fun insert(book: Book)           = dao.insert(book)
    suspend fun delete(book: Book)           = dao.delete(book)
    suspend fun updateLastOpened(id: String) = dao.updateLastOpened(id, System.currentTimeMillis())
}
