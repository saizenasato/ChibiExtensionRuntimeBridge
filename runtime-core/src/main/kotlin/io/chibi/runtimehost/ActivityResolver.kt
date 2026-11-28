/*
 * Activity resolution helper extracted from the original runtime host so that
 * backend modules (which must not depend on the host app module) can still
 * obtain an Activity for WebView / Cloudflare challenge flows.
 *
 * The host calls [update] from RuntimeBridge.initialize and whenever the
 * activity lifecycle changes; backends call [resolveActivity].
 */
package io.chibi.runtimehost

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatActivity
import java.lang.ref.WeakReference

object ActivityResolver {
    private var lastKnown: WeakReference<Activity>? = null

    fun update(activity: Activity?) {
        if (activity != null) lastKnown = WeakReference(activity)
    }

    fun resolveActivity(context: Context?): Activity? {
        lastKnown?.get()?.let { return it }
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    fun resolveAppCompatActivity(context: Context?): AppCompatActivity? =
        resolveActivity(context) as? AppCompatActivity
}
