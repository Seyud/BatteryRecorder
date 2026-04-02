package yangfentuozi.batteryrecorder.utils

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 页面级 Scaffold 统一只消费顶部与水平安全区。
 *
 * 设计依据：
 * - 根 Activity 已启用 edge-to-edge；
 * - 底部导航手势区应由具体页面按内容类型决定是否避让，
 *   不能继续交给 Scaffold 一刀切处理，否则内容无法延伸到小白条区域。
 *
 * @return 仅包含顶部与水平安全区的 WindowInsets。
 */
@Composable
fun batteryRecorderScaffoldInsets(): WindowInsets {
    return WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
}

/**
 * 返回当前导航栏手势区对应的底部安全距离。
 *
 * @return 底部导航栏 inset 对应的 dp 值。
 */
@Composable
fun navigationBarBottomPadding(): Dp {
    return WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
}

/**
 * 在现有 PaddingValues 的底部追加导航栏安全距离。
 *
 * @param extraBottom 业务层额外需要保留的底部间距。
 * @return 叠加后的底部内边距。
 */
@Composable
fun PaddingValues.bottomWithNavigationBar(extraBottom: Dp = 0.dp): Dp {
    return calculateBottomPadding() + navigationBarBottomPadding() + extraBottom
}
