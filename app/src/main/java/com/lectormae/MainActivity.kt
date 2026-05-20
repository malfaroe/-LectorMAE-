package com.lectormae

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lectormae.databinding.ActivityMainBinding
import com.lectormae.ui.library.LibraryFragment

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, LibraryFragment())
                .commit()
        }
    }
}
