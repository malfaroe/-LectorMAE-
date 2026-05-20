package com.lectormae.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lectormae.databinding.FragmentLibraryBinding

class LibraryFragment : Fragment() {

    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!

    private val vm: LibraryViewModel by viewModels()

    private val adapter = BookAdapter(
        onClick      = { book -> Toast.makeText(requireContext(), "Abriendo: ${book.title}", Toast.LENGTH_SHORT).show() },
        onLongClick  = { book ->
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

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.scanStorage()
        else Toast.makeText(requireContext(), "Se necesita permiso para escanear libros.", Toast.LENGTH_LONG).show()
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

        vm.scanning.observe(viewLifecycleOwner) { scanning ->
            b.progressBar.visibility = if (scanning) View.VISIBLE else View.GONE
        }

        b.fab.setOnClickListener {
            pickFile.launch(arrayOf("application/epub+zip", "application/pdf", "*/*"))
        }

        checkAndScan()
    }

    private fun checkAndScan() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES   // API 33+ (fallback — no existe READ_MEDIA_DOCUMENTS en 33)
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED) {
            vm.scanStorage()
        } else {
            requestPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
