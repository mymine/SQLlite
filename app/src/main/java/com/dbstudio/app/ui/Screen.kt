package com.dbstudio.app.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/** 应用内的页面（底栏 5 个一级页 + 二级页） */
sealed interface Screen {
    val tabIndex: Int

    data object Databases : Screen {
        override val tabIndex = 0
    }

    data object Data : Screen {
        override val tabIndex = 1
    }

    data object Diff : Screen {
        override val tabIndex = 2
    }

    data object Sql : Screen {
        override val tabIndex = 3
    }

    data object Settings : Screen {
        override val tabIndex = 4
    }

    /** 表详情（结构 + 数据预览） */
    data class TableDetail(val table: String) : Screen {
        override val tabIndex = 0
    }
}

/** 简易导航栈 */
class AppNav {
    val stack: SnapshotStateList<Screen> = mutableStateListOf(Screen.Databases)
    val current: Screen get() = stack.last()

    fun switchTab(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }

    fun push(screen: Screen) {
        stack.add(screen)
    }

    /** 返回 true 表示已消费返回事件 */
    fun back(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            return true
        }
        return false
    }
}
