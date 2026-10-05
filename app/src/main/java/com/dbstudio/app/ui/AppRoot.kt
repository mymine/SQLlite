package com.dbstudio.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dbstudio.app.data.AppPrefs
import com.dbstudio.app.ui.components.GlassBottomBar
import com.dbstudio.app.ui.components.GlassTabItem
import com.dbstudio.app.ui.screens.DataScreen
import com.dbstudio.app.ui.screens.DatabasesScreen
import com.dbstudio.app.ui.screens.DiffScreen
import com.dbstudio.app.ui.screens.SettingsScreen
import com.dbstudio.app.ui.screens.SqlScreen
import com.dbstudio.app.ui.screens.TableDetailScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val TABS = listOf(
    GlassTabItem("数据库", Icons.Rounded.Storage),
    GlassTabItem("数据", Icons.Rounded.TableChart),
    GlassTabItem("对比", Icons.Rounded.CompareArrows),
    GlassTabItem("SQL", Icons.Rounded.Terminal),
    GlassTabItem("设置", Icons.Rounded.Settings),
)

private fun tabScreen(index: Int): Screen = when (index) {
    0 -> Screen.Databases
    1 -> Screen.Data
    2 -> Screen.Diff
    3 -> Screen.Sql
    else -> Screen.Settings
}

@Composable
fun AppRoot(nav: AppNav = remember { AppNav() }) {
    val dark = when (AppPrefs.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val hazeState = rememberHazeState()
    val current = nav.current

    BackHandler(enabled = nav.stack.size > 1) { nav.back() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState),
        ) {
            BackgroundGlow()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(bottom = 88.dp),
            ) {
                when (current) {
                    is Screen.Databases -> DatabasesScreen(nav)
                    is Screen.Data -> DataScreen(nav)
                    is Screen.Diff -> DiffScreen(nav)
                    is Screen.Sql -> SqlScreen(nav)
                    is Screen.Settings -> SettingsScreen(nav)
                    is Screen.TableDetail -> TableDetailScreen(nav, current.table)
                }
            }
        }

        GlassBottomBar(
            hazeState = hazeState,
            items = TABS,
            selectedIndex = current.tabIndex,
            onSelect = { nav.switchTab(tabScreen(it)) },
            darkTheme = dark,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

@Composable
private fun BoxScope.BackgroundGlow() {
    val dark = isSystemInDarkTheme()
    val a1 = if (dark) Color(0x665B5BD6) else Color(0x4D5B5BD6)
    val a2 = if (dark) Color(0x5500A6A6) else Color(0x3D00A6A6)
    val a3 = if (dark) Color(0x55B0457A) else Color(0x33B0457A)
    Box(
        modifier = Modifier
            .size(340.dp)
            .align(Alignment.TopEnd)
            .offset(x = 90.dp, y = (-90).dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(a1, Color.Transparent))),
    )
    Box(
        modifier = Modifier
            .size(280.dp)
            .align(Alignment.CenterStart)
            .offset(x = (-110).dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(a2, Color.Transparent))),
    )
    Box(
        modifier = Modifier
            .size(300.dp)
            .align(Alignment.BottomEnd)
            .offset(x = 70.dp, y = 120.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(a3, Color.Transparent))),
    )
}
