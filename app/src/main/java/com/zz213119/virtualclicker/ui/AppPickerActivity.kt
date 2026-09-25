package com.zz213119.virtualclicker.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zz213119.virtualclicker.R

/**
 * 应用选择器。取代 MainActivity 里原来把包名倒进一个 TextView 的 loadApps()。
 * 用法：startActivityForResult(Intent(this, AppPickerActivity::class.java), REQ_CODE)
 * 结果：resultCode == RESULT_OK 时，data 里带 EXTRA_PACKAGE_NAME / EXTRA_LABEL
 */
class AppPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_LABEL = "extra_label"
    }

    private enum class Tab { USER, SYSTEM }

    private lateinit var searchInput: EditText
    private lateinit var tabUserApps: TextView
    private lateinit var tabSystemApps: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnCancel: TextView
    private lateinit var btnConfirm: TextView

    private lateinit var adapter: AppListAdapter

    private var userApps: List<AppEntry> = emptyList()
    private var systemApps: List<AppEntry> = emptyList()
    private var currentTab: Tab = Tab.USER
    private var currentQuery: String = ""
    private var selectedEntry: AppEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        searchInput = findViewById(R.id.searchInput)
        tabUserApps = findViewById(R.id.tabUserApps)
        tabSystemApps = findViewById(R.id.tabSystemApps)
        recyclerView = findViewById(R.id.appRecyclerView)
        btnCancel = findViewById(R.id.btnCancel)
        btnConfirm = findViewById(R.id.btnConfirm)

        adapter = AppListAdapter { entry ->
            selectedEntry = entry
            adapter.setSelected(entry.packageName)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        tabUserApps.setOnClickListener { switchTab(Tab.USER) }
        tabSystemApps.setOnClickListener { switchTab(Tab.SYSTEM) }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString().orEmpty()
                applyFilter()
            }
        })

        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        btnConfirm.setOnClickListener {
            val entry = selectedEntry
            if (entry == null) {
                Toast.makeText(this, "请先选择一个应用", Toast.LENGTH_SHORT).show()
            } else {
                val data = Intent().apply {
                    putExtra(EXTRA_PACKAGE_NAME, entry.packageName)
                    putExtra(EXTRA_LABEL, entry.label)
                }
                setResult(RESULT_OK, data)
                finish()
            }
        }

        loadAppsAsync()
    }

    private fun switchTab(tab: Tab) {
        if (currentTab == tab) return
        currentTab = tab
        tabUserApps.setBackgroundResource(
            if (tab == Tab.USER) R.drawable.bg_tab_selected else 0
        )
        tabUserApps.setTextColor(if (tab == Tab.USER) 0xFFFFFFFF.toInt() else 0xFF333333.toInt())
        tabSystemApps.setBackgroundResource(
            if (tab == Tab.SYSTEM) R.drawable.bg_tab_selected else 0
        )
        tabSystemApps.setTextColor(if (tab == Tab.SYSTEM) 0xFFFFFFFF.toInt() else 0xFF333333.toInt())
        applyFilter()
    }

    /**
     * PackageManager 查询 + 图标加载在大列表下不便宜，丢到后台线程，
     * 避免卡主线程 ANR。项目目前没引入协程依赖，这里就用最朴素的 Thread。
     */
    private fun loadAppsAsync() {
        Thread {
            val pm = packageManager
            val all = pm.getInstalledApplications(0)

            val entries = all.map { info ->
                AppEntry(
                    label = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    icon = info.loadIcon(pm),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }.sortedBy { it.label.lowercase() }

            val user = entries.filter { !it.isSystem }
            val system = entries.filter { it.isSystem }

            Handler(Looper.getMainLooper()).post {
                userApps = user
                systemApps = system
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        val source = if (currentTab == Tab.USER) userApps else systemApps
        val filtered = if (currentQuery.isBlank()) {
            source
        } else {
            val q = currentQuery.trim().lowercase()
            source.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        adapter.submitList(filtered)
        adapter.setSelected(selectedEntry?.packageName)
    }
}
