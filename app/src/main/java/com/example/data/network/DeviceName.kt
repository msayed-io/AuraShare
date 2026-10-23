package com.example.data.network

import android.os.Build

internal object DeviceName {
    fun current(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
        .replace(Regex("[\\p{Cc}\\p{Cf}]"), "")
        .trim()
        .take(80)
}
