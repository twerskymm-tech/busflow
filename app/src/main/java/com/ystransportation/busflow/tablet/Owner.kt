package com.ystransportation.busflow.tablet

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * "Owner mode": a bus tablet set up (factory reset + setup QR) with BusFlow as its owner.
 * Then BusFlow updates itself with nobody tapping, the tablet is locked to BusFlow,
 * it opens BusFlow when it turns on, and location is always allowed.
 * The office can unlock it for a few minutes from BusFlow (Tablets page).
 */
object Owner {
    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    fun isOwner(ctx: Context): Boolean = try { dpm(ctx).isDeviceOwnerApp(ctx.packageName) } catch (e: Exception) { false }

    /** Everything owner mode turns on. Safe to run again (on every start). */
    fun setup(ctx: Context) {
        if (!isOwner(ctx)) return
        val d = dpm(ctx); val a = admin(ctx)
        runCatching { d.setLockTaskPackages(a, arrayOf(ctx.packageName)) }
        if (Build.VERSION.SDK_INT >= 28) runCatching {
            d.setLockTaskFeatures(a, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
        }
        // Opens BusFlow when the tablet turns on, and the Home button comes back to it
        runCatching {
            val home = ComponentName(ctx, "${ctx.packageName}.Home")
            ctx.packageManager.setComponentEnabledSetting(home, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            val f = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
            d.addPersistentPreferredActivity(a, f, home)
        }
        // No permission questions for the driver
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        perms.forEach { p -> runCatching { d.setPermissionGrantState(a, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) } }
        if (Build.VERSION.SDK_INT >= 30) runCatching { d.setLocationEnabled(a, true) }
        // No lock screen; screen stays on while charging (in the bus)
        runCatching { d.setKeyguardDisabled(a, true) }
        runCatching { d.setGlobalSetting(a, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7") }
        // Android system updates only at night (1–5 AM), never in the middle of a route
        runCatching { d.setSystemUpdatePolicy(a, SystemUpdatePolicy.createWindowedInstallPolicy(60, 300)) }
    }

    // ── Locked to BusFlow (kiosk), unless the office unlocked it for a while ──
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("owner", Context.MODE_PRIVATE)
    fun unlockedUntil(ctx: Context): Long = prefs(ctx).getLong("unlock_until", 0L)
    fun setUnlockedUntil(ctx: Context, ms: Long) { prefs(ctx).edit().putLong("unlock_until", ms).apply() }
    fun isUnlocked(ctx: Context) = System.currentTimeMillis() < unlockedUntil(ctx)

    /** Call from every screen's onResume. */
    fun keepLocked(activity: Activity) {
        if (!isOwner(activity)) return
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val locked = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        if (isUnlocked(activity)) { if (locked) runCatching { activity.stopLockTask() } }
        else if (!locked) runCatching { activity.startLockTask() }
    }
}

/** BusFlow as the tablet's owner (device admin). */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Owner.setup(context)
        context.startActivity(Intent(context, PairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Setup-QR steps Android 12+ asks the owner app for: "fully managed tablet", then "done". */
class ProvisionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> {
                setResult(RESULT_OK, Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE))
            }
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                Owner.setup(this)
                setResult(RESULT_OK)
            }
            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}

/** After an update installs itself: open BusFlow again. */
class UpdatedReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Owner.setup(context)
        if (Owner.isOwner(context)) runCatching {
            context.startActivity(Intent(context, PairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }
}

/** Result of a silent install (owner mode) or the "Install?" prompt otherwise. */
class InstallResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)
        if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
