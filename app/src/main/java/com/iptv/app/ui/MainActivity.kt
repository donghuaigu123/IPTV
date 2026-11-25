package com.iptv.app.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.iptv.app.BuildConfig
import com.iptv.app.R

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private val fragments = mutableMapOf<Int, Fragment>()
    private var currentTab = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.tvVersion).text = "v${BuildConfig.VERSION_NAME}"
        bottomNav = findViewById(R.id.bottomNav)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_collect -> { switchTo(0); true }
                R.id.nav_library -> { switchTo(1); true }
                R.id.nav_settings -> { switchTo(2); true }
                else -> false
            }
        }
        switchTo(0)
    }

    private fun switchTo(pos: Int) {
        if (pos == currentTab) return
        currentTab = pos
        val tag = "tab_$pos"
        var frag = supportFragmentManager.findFragmentByTag(tag)
        if (frag == null) {
            frag = when (pos) {
                0 -> CollectorFragment()
                1 -> LibraryFragment()
                else -> SettingsFragment()
            }
            supportFragmentManager.beginTransaction()
                .add(R.id.container, frag, tag)
                .commitNowAllowingStateLoss()
        }
        supportFragmentManager.fragments.forEach { f ->
            if (f != frag) supportFragmentManager.beginTransaction().hide(f).commitNowAllowingStateLoss()
        }
        supportFragmentManager.beginTransaction().show(frag).commitNowAllowingStateLoss()
    }
}
