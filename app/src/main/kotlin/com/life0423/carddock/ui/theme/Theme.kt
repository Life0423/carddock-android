/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.life0423.carddock.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// 标题画在 primaryContainer 上。亮色若用 primary（#00506E / #23759B）只有约 1.72:1，
// 所以配对 onPrimaryContainer：亮色白，暗色 #F4F9FF。
private val DarkColorScheme = darkColorScheme(
    primary = primaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    tertiary = tertiaryDark
)

private val LightColorScheme = lightColorScheme(
    primary = primaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    tertiary = tertiaryLight
)

/**
 * 应用级 Material 3 主题。色值在 Color.kt 里手维护，品牌色以后再换。
 *
 * @param darkTheme 为 true 时用深色方案，默认跟随系统。
 * @param dynamicColor 仅当它为 true 且 API ≥ 31 时才用壁纸动态色。默认是 false，首版用 Color.kt 里的固定色板。
 * @param content 套上主题之后要显示的界面。
 */
@Composable
fun CardDockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

