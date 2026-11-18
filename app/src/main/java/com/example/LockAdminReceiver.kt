package com.example

import android.app.admin.DeviceAdminReceiver

/** Izin "Administrator perangkat" yang hanya meminta hak mengunci layar (force-lock). */
class LockAdminReceiver : DeviceAdminReceiver()
