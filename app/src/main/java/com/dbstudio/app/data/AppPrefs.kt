package com.dbstudio.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 全局可观察偏好（与 DbStore 持久化同步） */
object AppPrefs {

    var themeMode by mutableStateOf(0) // 0 跟随系统 / 1 浅色 / 2 深色
    var pageSize by mutableStateOf(200)
    var ready by mutableStateOf(false)

    fun init(context: Context) {
        DbStore.init(context)
        themeMode = DbStore.themeMode
        pageSize = DbStore.pageSize
        ready = true
    }

    fun persist(context: Context) {
        DbStore.themeMode = themeMode
        DbStore.pageSize = pageSize
    }
}
