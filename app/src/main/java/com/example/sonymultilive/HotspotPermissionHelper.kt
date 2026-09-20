package com.example.sonymultilive

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

object HotspotPermissionHelper {
    const val REQUEST_CODE = 4102

    fun requiredPermission(): String = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }

    fun hasPermission(activity: Activity): Boolean {
        return activity.checkSelfPermission(requiredPermission()) == PackageManager.PERMISSION_GRANTED
    }

    fun request(activity: Activity) {
        activity.requestPermissions(arrayOf(requiredPermission()), REQUEST_CODE)
    }
}
