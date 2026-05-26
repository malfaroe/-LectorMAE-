package com.lectormae.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.*
import com.lectormae.data.AppDatabase
import com.lectormae.data.Book
import com.lectormae.data.BookRepository
import com.lectormae.util.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = BookRepository(AppDatabase.get(app).bookDao())

    val books: LiveData<List<Book>> = repo.allBooks.asLiveData()

    private val _importing = MutableLiveData(false)
    val importing: LiveData<Boolean> = _importing

    fun importFromUri(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        _importing.postValue(true)
        val book = FileScanner.fromUri(getApplication(), uri)
        if (book != null) repo.insert(book)
        _importing.postValue(false)
    }

    fun delete(book: Book) = viewModelScope.launch { repo.delete(book) }
}
