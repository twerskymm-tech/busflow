package com.ystransportation.busflow.tablet

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Where the tablet is right now, for clock in/out (BusFlow needs a location on every punch). */
object Where {
    @SuppressLint("MissingPermission")
    suspend fun now(ctx: Context, waitMs: Long = 12000): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            try { lm.isProviderEnabled(it) } catch (e: Exception) { false }
        }
        // A fix from the last 2 minutes is good enough
        val recent = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
            .filter { System.currentTimeMillis() - it.time < 120000 }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent
        if (providers.isEmpty()) return null
        val fresh = withTimeoutOrNull(waitMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        try { lm.removeUpdates(this) } catch (e: Exception) { }
                        if (cont.isActive) cont.resume(location)
                    }
                    // Older Android versions call these too; without them the app would crash there
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                }
                try {
                    providers.forEach { lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
                } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { try { lm.removeUpdates(listener) } catch (e: Exception) { } }
            }
        }
        // Still nothing new: any old fix beats none
        return fresh ?: providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }.maxByOrNull { it.time }
    }
}
