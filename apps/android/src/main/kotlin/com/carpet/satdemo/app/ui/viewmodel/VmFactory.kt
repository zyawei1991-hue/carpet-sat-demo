package com.carpet.satdemo.app.ui.viewmodel

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carpet.satdemo.app.AppRuntime

/**
 * VM 的获取口。四颗 Tab 的 VM 都通过工厂拿（AppRuntime 没有 DI 容器，
 * 工厂闭包里直接引用 `app` 单例），测试宿主 ComponentActivity 自带
 * ViewModelStoreOwner，所以 `viewModel {}` 在 Robolectric 里也照常工作。
 *
 * 统一放在这一个文件里：页面不许各自写 `viewModel { HomeViewModel(app) }` ——
 * 那样"哪一页用哪个 VM"就散了，增删一颗 Tab 要全局搜。
 */
class RuntimeVmFactory(private val app: AppRuntime) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        HomeViewModel::class.java -> HomeViewModel(app)
        VehicleViewModel::class.java -> VehicleViewModel(app)
        FamilyViewModel::class.java -> FamilyViewModel(app)
        MeViewModel::class.java -> MeViewModel(app)
        else -> throw IllegalArgumentException("没注册的 VM：${modelClass.name}")
    } as T
}

@Composable
fun homeVm(app: AppRuntime): HomeViewModel = viewModel(factory = RuntimeVmFactory(app), key = "home")

@Composable
fun vehicleVm(app: AppRuntime): VehicleViewModel = viewModel(factory = RuntimeVmFactory(app), key = "vehicle")

@Composable
fun familyVm(app: AppRuntime): FamilyViewModel = viewModel(factory = RuntimeVmFactory(app), key = "family")

@Composable
fun meVm(app: AppRuntime): MeViewModel = viewModel(factory = RuntimeVmFactory(app), key = "me")

