package com.vinstall.alwiz.util

import android.content.Context
import com.vinstall.alwiz.R

object DeviceProfile {
    fun isTv(context: Context): Boolean = context.resources.getBoolean(R.bool.is_tv)
}
