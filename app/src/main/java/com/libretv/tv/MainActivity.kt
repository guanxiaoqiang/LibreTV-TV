package com.libretv.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.libretv.tv.di.AppContainer
import com.libretv.tv.ui.TvBackgroundBrush
import com.libretv.tv.ui.nav.LibreNavHost

/**
 * 应用入口。
 *
 * 页面与返回栈全部交给 [LibreNavHost]（navigation-compose），
 * Activity 只负责设置主题与承载 composition。
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as LibreTVApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LibreTVApp).container
        setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(TvBackgroundBrush)
            ) {
                LibreNavHost(container = container)
            }
        }
    }

    /** 回到前台：这才允许发起订阅的自动同步。 */
    override fun onStart() {
        super.onStart()
        container.setForeground(true)
    }

    /** 退到后台：停止自动同步。 */
    override fun onStop() {
        super.onStop()
        container.setForeground(false)
    }
}
