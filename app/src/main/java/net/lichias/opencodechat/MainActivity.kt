package net.lichias.opencodechat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewmodel.compose.viewModel
import net.lichias.opencodechat.data.Store
import net.lichias.opencodechat.ui.ChatApp
import net.lichias.opencodechat.ui.ChatViewModel
import net.lichias.opencodechat.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Store(this)
        setContent {
            val vm: ChatViewModel = viewModel(initializer = { ChatViewModel(store) })
            val density = LocalDensity.current
            AppTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, density.fontScale * vm.fontScale)
                ) {
                    ChatApp(vm)
                }
            }
        }
    }
}
