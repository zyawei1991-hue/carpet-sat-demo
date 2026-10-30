package com.carpet.satdemo.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.carpet.satdemo.app.ui.CarpetRoot
import com.carpet.satdemo.app.ui.CarpetTheme

private val PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.POST_NOTIFICATIONS,
)

/**
 * 装配入口。**这个类现在只剩三件事：要权限、把监听接上、把 Compose 挂上去。**
 *
 * 引擎、蜂窝/定位监听、圆屏服务器都搬到 [AppRuntime]（挂在 Application 上）了 ——
 * §5.3 硬要求 #2 说"切场景不能断链"，而上一版这些字段写在 Activity 里，
 * 转一次屏就得重连一次，演示时那一下正好把圆屏踢下线。
 */
class MainActivity : ComponentActivity() {

    private val state: AppRuntime get() = (application as CarpetApp).appRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // edge-to-edge：内容画到状态栏/手势条底下，避让交给 Scaffold 的 windowInsets。
        // targetSdk 34 在 Android 14 上本来就被系统强制成这个模式，这里显式写出来是为了
        // Android 11–13 也走同一条路径 —— 否则同一套界面在两代系统上是两种留白
        enableEdgeToEdge()
        requestPermissions(PERMISSIONS, 1)
        state.startMonitors()
        setContent { CarpetTheme { CarpetRoot(state) } }
    }

    override fun onResume() {
        super.onResume()
        // 权限是异步批下来的，回到前台再试一次；startMonitors 自己幂等
        state.startMonitors()
    }

    override fun onDestroy() {
        // 真正退出（返回键退干净）才停服务。转屏和深色切换走到这里时 isFinishing 是 false，
        // 那种情况下停服务就等于自己把展示端踢了
        if (isFinishing) state.setGuard(false)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.none { it == PackageManager.PERMISSION_GRANTED }) {
            state.runtime.log("定位权限被拒 —— 车载端没有位置源，App 只能演示链路那一半")
            return
        }
        state.startMonitors()
    }
}
