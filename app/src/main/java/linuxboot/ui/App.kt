package linuxboot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import linuxboot.R
import linuxboot.desktop.ViewerScreen
import linuxboot.firmware.FirmwareViewModel
import linuxboot.firmware.Screen
import linuxboot.system.SystemInfo

@Composable
private fun AppNavBar(vm: FirmwareViewModel, screen: Screen) {
    val tabs = listOf(
        Triple("Home", R.drawable.ic_home, Screen.Home),
        Triple("Install", R.drawable.ic_download, Screen.InstallList),
        Triple("Systems", R.drawable.ic_layers, Screen.BootManager),
        Triple("Settings", R.drawable.ic_tune, Screen.Config),
    )
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        tabs.forEach { (label, icon, target) ->
            val selected = screen == target || (target == Screen.Home && screen == Screen.Boot)
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) vm.open(target) },
                icon = { Ico(icon) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
fun LinuxBootApp(onExit: () -> Unit) {
    val vm: FirmwareViewModel = viewModel()
    val context = LocalContext.current
    // Re-read real RAM and storage figures whenever the screen changes.
    val info = remember(vm.screen) { SystemInfo.detect(context) }
    val screen = vm.screen

    if (screen == Screen.Viewer) {
        ViewerScreen(vm)
        return
    }

    BackHandler(enabled = screen != Screen.Home && screen != Screen.Boot) { vm.back() }

    val topLevel = screen == Screen.Home || screen == Screen.Boot ||
        screen == Screen.InstallList || screen == Screen.BootManager || screen == Screen.Config

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { if (topLevel) AppNavBar(vm, screen) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                Screen.Home, Screen.Boot -> HomeScreen(vm, info)
                Screen.InstallList -> InstallListScreen(vm)
                is Screen.InstallDetail -> InstallDetailScreen(vm, screen.id)
                Screen.BootManager -> SystemsScreen(vm)
                is Screen.DistroDetail -> DistroDetailScreen(vm, screen.id)
                is Screen.ConfirmRemove -> {
                    DistroDetailScreen(vm, screen.id)
                    ConfirmRemoveDialog(vm, screen.id)
                }
                is Screen.ConfirmRestore -> {
                    DistroDetailScreen(vm, screen.id)
                    ConfirmRestoreDialog(vm, screen.id)
                }
                Screen.Config -> SettingsScreen(vm, onExit)
                Screen.Hardware -> HardwareScreen(vm, info)
                Screen.Recovery -> MaintenanceScreen(vm)
                Screen.Viewer -> Unit
            }
        }
    }
}
