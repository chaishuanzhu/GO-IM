package com.goim.kmp.desktop

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.goim.kmp.App
import com.goim.kmp.common.PlatformContext
import com.goim.kmp.di.initKoin
import java.awt.Dimension

private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

/** macOS 标题栏（红绿灯）高度。 */
private val MAC_TITLE_BAR_HEIGHT = 28.dp

fun main() {
    initKoin(PlatformContext.INSTANCE)
    application {
        // 标题栏样式须在窗口显示前设置，先隐藏创建、设置完再显示
        var visible by remember { mutableStateOf(!isMac) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "GO-IM",
            visible = visible,
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
        ) {
            window.minimumSize = Dimension(720, 520)
            if (isMac) {
                LaunchedEffect(Unit) {
                    // 内容延伸到标题栏下，隐藏标题文字，只保留红绿灯
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    visible = true
                }
                App(titleBarHeight = MAC_TITLE_BAR_HEIGHT) {
                    WindowDraggableArea {
                        Spacer(Modifier.fillMaxWidth().height(MAC_TITLE_BAR_HEIGHT))
                    }
                }
            } else {
                App()
            }
        }
    }
}
