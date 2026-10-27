package com.carpet.satdemo.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import com.carpet.satdemo.core.protocol.GnssSource

/**
 * 手机侧定位。GT1005 的 GNSS 是补充/校验源，不是主源 ——
 * 手机的 3-5m 在有窗条件下比模块的 2.5m CEP 更容易拿到，且不占卫星额度。
 *
 * 只用平台自带的 LocationManager，不引 Play Services：
 * 演示机是国产 ROM，GMS 大概率没有，引了反而多一个变数。
 */
class LocationMonitor(
    private val context: Context,
    private val onFix: (lat: Double, lon: Double, source: GnssSource, hdop: Double?, ageSec: Double?) -> Unit,
) : LocationListener {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var lastAt = 0L

    fun start() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // MINIMUM_UPDATE_TIME 设 2s：演示时观众要看到点在动，5s 一步会被质疑"是不是假的"
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 1f, this)
        manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5_000L, 5f, this)
    }

    fun stop() {
        runCatching { manager.removeUpdates(this) }
    }

    override fun onLocationChanged(location: Location) {
        val now = SystemClock.elapsedRealtime()
        val age = if (lastAt == 0L) 0.0 else (now - lastAt) / 1000.0
        lastAt = now
        onFix(
            location.latitude,
            location.longitude,
            GnssSource.phone,
            if (location.hasAccuracy()) location.accuracy / 5.0 else null,
            age,
        )
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
