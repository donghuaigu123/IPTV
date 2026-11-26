package com.iptv.app.ui

import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.iptv.app.BuildConfig
import com.iptv.app.R
import com.iptv.app.data.PlaylistBus

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

        // 外部以 m3u/m3u8/http(s) 链接唤起：记下待导入地址并切到频道页自动导入
        val incoming: Uri? = intent?.data
        if (incoming != null) {
            val url = incoming.toString()
            if (url.contains("://")) {
                PlaylistBus.pendingImportUrl = url
                switchTo(1)
                Toast.makeText(this, "已收到直播源链接，正在导入…", Toast.LENGTH_SHORT).show()
                return
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
