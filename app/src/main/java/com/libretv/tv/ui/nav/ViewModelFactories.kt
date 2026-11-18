package com.libretv.tv.ui.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import com.libretv.tv.di.AppContainer
import com.libretv.tv.ui.pages.PlayerViewModel

/**
 * 页面级 ViewModel 的创建入口。
 *
 * 页面 ViewModel 不自己 new 依赖——它们从 [AppContainer] 里取需要的网关，
 * 由这里统一注入。
 */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    container: AppContainer,
    key: String? = null,
    crossinline create: (AppContainer) -> VM
): VM = viewModel(
    key = key,
    factory = viewModelFactory { initializer { create(container) } }
)

/**
 * 播放页 ViewModel：作用域限定在导航条目上。
 *
 * 起播请求经 [androidx.lifecycle.SavedStateHandle] 传递——进程被回收后重建，
 * 导航条目会把它还原出来，播放页仍能接着放同一集。
 */
@Composable
fun playerViewModel(container: AppContainer, entry: NavBackStackEntry): PlayerViewModel =
    viewModel(
        viewModelStoreOwner = entry,
        factory = viewModelFactory {
            initializer { PlayerViewModel(createSavedStateHandle(), container) }
        }
    )
