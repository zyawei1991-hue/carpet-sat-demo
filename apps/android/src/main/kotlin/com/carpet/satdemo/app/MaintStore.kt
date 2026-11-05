package com.carpet.satdemo.app

import android.content.SharedPreferences
import com.carpet.satdemo.app.ui.PrivacyTier
import com.carpet.satdemo.core.maint.Criteria
import com.carpet.satdemo.core.maint.MaintEngine
import com.carpet.satdemo.core.maint.Param
import com.carpet.satdemo.core.maint.Suggestion
import com.carpet.satdemo.core.maint.WorkItem
import com.carpet.satdemo.core.maint.WorkOrder
import com.carpet.satdemo.core.vehicle.Reading
import com.carpet.satdemo.core.vehicle.ReadingBoard
import com.carpet.satdemo.core.vehicle.ReadingKey
import com.carpet.satdemo.core.vehicle.TIRE_KEYS
import com.carpet.satdemo.core.vehicle.ValueSource
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 车况养护推荐在手机上的那一半：读数从哪来、判据填了什么、勾了哪几项。
 *
 * **规则引擎在 `:core`，这一层只负责喂它**。喂进去的假数据全部打 `Stub`，
 * 真数据（额度、车主填的）打各自的档 —— 界面上那个标是这么来的，不是画上去的。
 */
class MaintStore(private val prefs: SharedPreferences) {

    /** 判据表。第一版全是示意值，那两个「未接手册」的标会一路跟着它们 */
    val criteria = Criteria(
        tireNominal = Param(2.5, ValueSource.Nominal),
        tireDrop = Param(0.2, ValueSource.Nominal),
        serviceEveryKm = Param(10_000.0, ValueSource.Manual),
        serviceEveryDays = Param(365.0, ValueSource.Manual),
        filterEveryDays = Param(365.0, ValueSource.Manual),
        idleDays = Param(7.0, ValueSource.Manual),
        quotaRemainRatio = Param(0.20, ValueSource.Chosen),
    )

    private val _selected = MutableStateFlow<Set<Int>>(emptySet())
    val selected: StateFlow<Set<Int>> = _selected

    fun toggle(code: Int) {
        _selected.value = _selected.value.toMutableSet().let {
            if (!it.remove(code)) it.add(code)
            it
        }
    }

    fun clearSelected() {
        _selected.value = emptySet()
    }

    /** 车主填的那几项。没有车厂接口时，"能填的输入框"比"假装接了 API"诚实 */
    fun lastServiceKm(): Int = prefs.getInt(KEY_LAST_SERVICE_KM, 13_600)
    fun lastServiceDays(): Int = prefs.getInt(KEY_LAST_SERVICE_DAYS, 210)
    fun filterDays(): Int = prefs.getInt(KEY_FILTER_DAYS, 400)
    fun hasKit(): Boolean = prefs.getBoolean(KEY_HAS_KIT, false)
    fun hasChild(): Boolean = prefs.getBoolean(KEY_HAS_CHILD, false)

    fun setLastServiceKm(v: Int) = prefs.edit().putInt(KEY_LAST_SERVICE_KM, v).apply()
    fun setLastServiceDays(v: Int) = prefs.edit().putInt(KEY_LAST_SERVICE_DAYS, v).apply()
    fun setFilterDays(v: Int) = prefs.edit().putInt(KEY_FILTER_DAYS, v).apply()
    fun setHasKit(v: Boolean) = prefs.edit().putBoolean(KEY_HAS_KIT, v).apply()
    fun setHasChild(v: Boolean) = prefs.edit().putBoolean(KEY_HAS_CHILD, v).apply()

    /**
     * 组一帧车况。
     *
     * 车辆总线没接，所以续航/油量/胎压/里程是 `Stub`；额度是终端型号报上来的，
     * 接了真卡才是 `Device`；车主填的那几项本来就是 `User`。
     * **缺读数的规则整条不出现**，所以这里不给任何默认值兜底。
     */
    fun board(quotaWaiting: Int?, quotaCapacity: Int?, quotaReal: Boolean): ReadingBoard {
        val now = System.currentTimeMillis()
        val src = if (quotaReal) ValueSource.Device else ValueSource.Stub
        val r = mutableListOf(
            Reading(ReadingKey.RangeKm, 412.0, ValueSource.Stub, now),
            Reading(ReadingKey.FuelFraction, 0.75, ValueSource.Stub, now),
            Reading(ReadingKey.OdometerKm, 23_417.0, ValueSource.Stub, now),
            Reading(ReadingKey.IdleDays, 9.0, ValueSource.Observed, now),
            Reading(ReadingKey.LastServiceKm, lastServiceKm().toDouble(), ValueSource.User, now),
            Reading(ReadingKey.LastServiceDays, lastServiceDays().toDouble(), ValueSource.User, now),
            Reading(ReadingKey.FilterDays, filterDays().toDouble(), ValueSource.User, now),
            Reading(ReadingKey.HasFirstAidKit, if (hasKit()) 1.0 else 0.0, ValueSource.User, now),
            Reading(ReadingKey.HasChild, if (hasChild()) 1.0 else 0.0, ValueSource.User, now),
        )
        val tires = listOf(2.4, 2.4, 2.3, 2.5)
        TIRE_KEYS.forEachIndexed { i, key -> r += Reading(key, tires[i], ValueSource.Stub, now) }
        if (quotaCapacity != null && quotaCapacity > 0) {
            r += Reading(ReadingKey.QuotaCapacity, quotaCapacity.toDouble(), src, now)
            r += Reading(ReadingKey.QuotaWaiting, (quotaWaiting ?: 0).toDouble(), src, now)
        }
        return ReadingBoard(r)
    }

    fun suggest(board: ReadingBoard): List<Suggestion> = MaintEngine.suggest(board, criteria)

    /** 勾了的那几项，按严重度排好 */
    fun picked(all: List<Suggestion>): List<Suggestion> = all.filter { _selected.value.contains(it.code) }

    /**
     * 本地留存档下工单不许上行 —— 出网的只有那 21 字节索引码，但那也是出网。
     * 生效档位取「底座默认」和「家长设置」里更严的那个，和界面其他地方同一条口径。
     */
    fun canSendOut(base: PrivacyTier, choice: PrivacyTier): Boolean =
        PrivacyTier.effective(base, choice) != PrivacyTier.Strict

    fun order(picked: List<Suggestion>): WorkOrder {
        val today = LocalDate.now()
        return WorkOrder(
            year = today.year,
            month = today.monthValue,
            day = today.dayOfMonth,
            // 车架号要 OBD 接上才读得到（09 02）。现在是编的，界面上得说明
            vin6 = DEFAULT_VIN6,
            odometerKm = 23_417,
            items = picked.map { WorkItem(it.code, it.severity) },
        )
    }

    /** 工单里车架号那一段现在是不是真的 */
    val vinReal: Boolean get() = false

    /**
     * 额度那两条数是不是从真终端读的。
     *
     * 现在是 false —— 槽位和待发来自模拟模组，所以「加额度包」那条建议的读数打的是桩标。
     * GT1005 到货换成真模组后这一句翻过来，界面上那个标就自己没了（D12 要的是这件事）。
     */
    val quotaReal: Boolean get() = false

    companion object {
        const val DEFAULT_VIN6 = "882137"
        const val RECEIVER = "演示接收台"
        private const val KEY_LAST_SERVICE_KM = "maint_last_service_km"
        private const val KEY_LAST_SERVICE_DAYS = "maint_last_service_days"
        private const val KEY_FILTER_DAYS = "maint_filter_days"
        private const val KEY_HAS_KIT = "maint_has_kit"
        private const val KEY_HAS_CHILD = "maint_has_child"
    }
}
