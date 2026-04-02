package yangfentuozi.batteryrecorder.ui.components.settings.sections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import yangfentuozi.batteryrecorder.shared.config.SettingsConstants
import yangfentuozi.batteryrecorder.ui.components.global.M3ESwitchWidget
import yangfentuozi.batteryrecorder.ui.components.global.SplicedColumnGroup
import yangfentuozi.batteryrecorder.ui.components.settings.SettingsItem
import yangfentuozi.batteryrecorder.ui.dialog.settings.CalibrationDialog
import yangfentuozi.batteryrecorder.ui.model.SettingsUiProps
import yangfentuozi.batteryrecorder.ui.model.displayName
import yangfentuozi.batteryrecorder.ui.theme.AppShape
import yangfentuozi.batteryrecorder.shared.config.dataclass.UpdateChannel

@Composable
fun CalibrationSection(
    props: SettingsUiProps
) {
    val state = props.state
    val rootActions = props.actions
    val actions = props.actions.calibration
    var showDialog by remember { mutableStateOf(false) }
    var showUpdateChannelMenu by remember { mutableStateOf(false) }

    SplicedColumnGroup(
        title = "常规",
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        item {
            M3ESwitchWidget(
                text = "启动时检测更新",
                checked = state.checkUpdateOnStartup,
                onCheckedChange = rootActions.setCheckUpdateOnStartup
            )
        }

        item {
            Box(modifier = Modifier.fillMaxWidth()) {
                SettingsItem(
                    title = "版本更新通道",
                    valueText = state.updateChannel.displayName,
                    onClick = { showUpdateChannelMenu = true }
                )

                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    DropdownMenu(
                        expanded = showUpdateChannelMenu,
                        onDismissRequest = { showUpdateChannelMenu = false },
                        shape = AppShape.large,
                        offset = DpOffset(x = 0.dp, y = (-24).dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text("稳定版") },
                            onClick = {
                                rootActions.setUpdateChannel(UpdateChannel.Stable)
                                showUpdateChannelMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("预发布") },
                            onClick = {
                                rootActions.setUpdateChannel(UpdateChannel.Prerelease)
                                showUpdateChannelMenu = false
                            }
                        )
                    }
                }
            }
        }

        item {
            M3ESwitchWidget(
                text = "串联双电芯",
                checked = state.dualCellEnabled,
                onCheckedChange = actions.setDualCellEnabled
            )
        }

        item {
            M3ESwitchWidget(
                text = "放电也显示正值",
                checked = state.dischargeDisplayPositive,
                onCheckedChange = actions.setDischargeDisplayPositiveEnabled
            )
        }

        item {
            SettingsItem(
                title = "电流单位校准",
                summary = "调整电流读数的倍率"
            ) { showDialog = true }
        }
    }

    if (showDialog) {
        CalibrationDialog(
            currentValue = state.calibrationValue,
            dualCellEnabled = state.dualCellEnabled,
            serviceConnected = props.serviceConnected,
            onDismiss = { showDialog = false },
            onSave = { value ->
                actions.setCalibrationValue(value)
                showDialog = false
            },
            onReset = {
                actions.setCalibrationValue(SettingsConstants.calibrationValue.def)
                showDialog = false
            }
        )
    }
}
