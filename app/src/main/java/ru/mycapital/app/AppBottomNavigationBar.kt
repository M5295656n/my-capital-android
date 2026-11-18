package ru.mycapital.app

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AppBottomNavigationBar(
    selected: Screen,
    onSelect: (Screen) -> Unit
) {
    NavigationBar(
        containerColor = Color(0xFF0B1422),
        contentColor = Color(0xFFAAB9D0),
        tonalElevation = 0.dp
    ) {
        listOf(Screen.Overview, Screen.Assets, Screen.Payouts, Screen.Analytics, Screen.TargetPortfolio, Screen.Operations).forEach { screen ->
            NavigationBarItem(
                selected = selected == screen || (screen == Screen.Operations && selected == Screen.Operations),
                onClick = { onSelect(screen) },
                icon = {
                    if (screen == Screen.Analytics) AnalyticsNavigationIcon()
                    else Icon(screen.icon, contentDescription = screen.title)
                },
                label = {
                    Text(
                        screen.title,
                        fontSize = if (screen == Screen.Analytics) 8.5.sp else 9.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF4B9BFF),
                    selectedTextColor = Color(0xFF69AFFF),
                    indicatorColor = Color(0xFF1B3555),
                    unselectedIconColor = Color(0xFF8797AD),
                    unselectedTextColor = Color(0xFF8797AD)
                )
            )
        }
    }
}
