package com.lectormae.ui.library

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lectormae.databinding.FragmentLibraryBinding
import com.lectormae.ui.reader.ReaderActivity

class LibraryFragment : Fragment() {

    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!

    private val vm: LibraryViewModel by viewModels()

    private val adapter = BookAdapter(
        onClick = { book ->
            startActivity(Intent(requireContext(), ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_FILE_PATH, book.filePath)
                putExtra(ReaderActivity.EXTRA_TITLE, book.title)
                putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
            })
        },
        onLongClick = { book ->
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(book.title)
                .setMessage("¿Eliminar de la biblioteca?")
                .setPositiveButton("Eliminar") { _, _ -> vm.delete(book) }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    )

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importFromUri(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentLibraryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        b.recyclerView.adapter = adapter

        vm.books.observe(viewLifecycleOwner) { books ->
            adapter.submitList(books)
            b.emptyState.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        }

        vm.importing.observe(viewLifecycleOwner) { importing ->
            b.progressBar.visibility = if (importing) View.VISIBLE else View.GONE
        }

        b.fab.setOnClickListener {
            pickFile.launch(arrayOf("application/epub+zip"))
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
