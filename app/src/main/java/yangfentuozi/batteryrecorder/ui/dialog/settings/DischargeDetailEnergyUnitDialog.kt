package yangfentuozi.batteryrecorder.ui.dialog.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import yangfentuozi.batteryrecorder.R
import yangfentuozi.batteryrecorder.ui.theme.AppShape

/**
 * 在用户首次把放电详情能量单位切到 mAh 时展示说明。
 *
 * @param onDismiss 用户取消或关闭弹窗时回调。
 * @param onConfirm 用户确认继续开启 mAh 显示时回调。
 * @return 无；仅渲染说明弹窗。
 */
@Composable
fun DischargeDetailEnergyUnitDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.dialog_discharge_detail_energy_unit_title))
        },
        text = {
            Text(stringResource(R.string.dialog_discharge_detail_energy_unit_message))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.dialog_discharge_detail_energy_unit_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        shape = AppShape.extraLarge
    )
}
