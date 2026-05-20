package com.lectormae.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lectormae.R
import com.lectormae.data.Book
import com.lectormae.databinding.ItemBookBinding

class BookAdapter(
    private val onClick: (Book) -> Unit,
    private val onLongClick: (Book) -> Unit
) : ListAdapter<Book, BookAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val b: ItemBookBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(book: Book) {
            b.tvTitle.text  = book.title
            b.tvAuthor.text = book.author
            b.tvFormat.text = book.format

            val badgeColor = if (book.format == "EPUB")
                b.root.context.getColor(R.color.epub_color)
            else
                b.root.context.getColor(R.color.pdf_color)
            b.tvFormat.setBackgroundColor(badgeColor)

            val coverColor = if (book.format == "EPUB")
                b.root.context.getColor(R.color.epub_cover)
            else
                b.root.context.getColor(R.color.pdf_cover)
            b.coverArea.setBackgroundColor(coverColor)

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
