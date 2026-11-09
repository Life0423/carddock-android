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

import androidx.compose.ui.graphics.Color

/**
 * 手维护的主题色，不是构建生成物。
 *
 * 数值来自 Material Theme Builder（主色 2300668B，Color Match）。仓库里没有生成器配置，
 * 无法重生成，所以这里就是源头，只留界面用到的常量。
 * 亮色 [onPrimaryContainerLight] 用白：容器 #23759B 上大约 5.14:1。
 * 暗色 [onPrimaryContainerDark] 沿用同一份色表里的 #F4F9FF，和容器配成一对前景色。
 */

val primaryLight = Color(0xFF00506E)
val primaryContainerLight = Color(0xFF23759B)
val onPrimaryContainerLight = Color(0xFFFFFFFF)
val secondaryLight = Color(0xFF486271)
val tertiaryLight = Color(0xFF613A73)

val primaryDark = Color(0xFF87CFF9)
val primaryContainerDark = Color(0xFF005C7D)
val onPrimaryContainerDark = Color(0xFFF4F9FF)
val secondaryDark = Color(0xFFB0CADC)
val tertiaryDark = Color(0xFFE6B5F8)
