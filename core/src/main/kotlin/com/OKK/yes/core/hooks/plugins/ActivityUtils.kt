package com.OKK.yes.core.hooks.plugins

import android.annotation.SuppressLint
import android.app.Activity
import android.os.IBinder
import android.util.ArrayMap
import java.lang.reflect.Field

object ActivityUtils {

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    fun getTopMostActivity(allowPaused: Boolean = true): Activity? = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
        currentActivityThreadMethod.isAccessible = true
        val currentActivityThread = currentActivityThreadMethod.invoke(null) ?: return null

        val mActivitiesField = activityThreadClass.getDeclaredField("mActivities")
        mActivitiesField.isAccessible = true
        val activities = mActivitiesField.get(currentActivityThread) ?: return null

        val values: Collection<*> = when (activities) {
            is Map<*, *> -> activities.values
            else -> return null
        }

        var pausedField: Field? = null
        var activityField: Field? = null

        val validActivities = mutableListOf<Activity>()
        for (record in values) {
            if (record == null) continue
            if (activityField == null) {
                activityField = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
            }
            if (pausedField == null) {
                pausedField = runCatching {
                    record.javaClass.getDeclaredField("paused").apply { isAccessible = true }
                }.getOrNull()
            }

            val act = activityField.get(record) as? Activity ?: continue
            val isPaused = pausedField?.get(record) as? Boolean ?: false
            if (allowPaused || !isPaused) {
                validActivities.add(act)
            }
        }
        validActivities.lastOrNull()
    }.getOrNull()
}
