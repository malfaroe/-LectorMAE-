package com.lectormae.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.lectormae.R
import com.lectormae.data.Book
import com.lectormae.databinding.ItemBookBinding
import java.io.File

class BookAdapter(
    private val onClick: (Book) -> Unit,
    private val onLongClick: (Book) -> Unit
) : ListAdapter<Book, BookAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val b: ItemBookBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(book: Book) {
            b.tvTitle.text  = book.title
            b.tvAuthor.text = book.author

            if (!book.coverPath.isNullOrEmpty()) {
                b.imgCover.load(File(book.coverPath)) {
                    crossfade(true)
                    placeholder(R.drawable.ic_book)
                    error(R.drawable.ic_book)
                }
            } else {
                b.imgCover.setImageResource(R.drawable.ic_book)
            }

            b.root.setOnClickListener     { onClick(book) }
            b.root.setOnLongClickListener { onLongClick(book); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Book>() {
            override fun areItemsTheSame(a: Book, b: Book) = a.id == b.id
            override fun areContentsTheSame(a: Book, b: Book) = a == b
        }
    }
}
