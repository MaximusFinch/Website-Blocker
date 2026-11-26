package app.siteblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Starts the ad blocker again after a restart or an app update, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!AdBlockService.isEnabled(ctx) || VpnService.prepare(ctx) != null) return
        try { AdBlockService.start(ctx) } catch (_: Exception) {}
    }
}
