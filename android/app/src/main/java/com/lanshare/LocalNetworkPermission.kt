package com.lanshare

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Android 17 (API 37) blocks traffic to and from local addresses for apps that
 * target 37+ unless the user grants the local-network runtime permission. That
 * covers the WebRTC host-candidate sockets the transfer path depends on, not
 * just the new LAN signaling path, so this has to be checked before connecting.
 *
 * The constant is kept as a string so the source still compiles against an SDK
 * that predates the permission; [required] keeps it from being requested on
 * platforms that only grant it implicitly.
 */
internal const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

internal fun requiresLocalNetworkPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 37 && context.applicationInfo.targetSdkVersion >= 37

internal fun hasLocalNetworkPermission(context: Context): Boolean =
    !requiresLocalNetworkPermission(context) ||
        ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

internal fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
