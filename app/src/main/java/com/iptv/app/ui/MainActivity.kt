package com.iptv.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import com.iptv.app.R

class MainActivity : AppCompatActivity() {

    private lateinit var tabs: TabLayout
    private var currentTab = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tabs = findViewById(R.id.tabs)

        listOf(getString(R.string.tab_collect), getString(R.string.tab_library), getString(R.string.tab_settings))
            .forEach { tabs.addTab(tabs.newTab().setText(it)) }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) { tab?.let { switchTo(it.position) } }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        switchTo(0)
    }

    private fun switchTo(pos: Int) {
        if (pos == currentTab) return
        currentTab = pos
        val frag = when (pos) {
            0 -> CollectorFragment()
            1 -> LibraryFragment()
            else -> SettingsFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, frag)
            .commitNowAllowingStateLoss()
    }
}
