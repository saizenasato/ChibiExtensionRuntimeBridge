package keiyoushi.utils

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.source.online.HttpSource

public inline fun HttpSource.getPreferences(
    migration: SharedPreferences.() -> Unit = { },
): SharedPreferences = getPreferences(id).also(migration)

public inline fun HttpSource.getPreferencesLazy(
    crossinline migration: SharedPreferences.() -> Unit = { },
): Lazy<SharedPreferences> = lazy { getPreferences(migration) }

@Suppress("NOTHING_TO_INLINE")
public inline fun getPreferences(sourceId: Long): SharedPreferences {
    val application: Application? = PreferencesHost.application
    if (application != null) {
        try {
            return application.getSharedPreferences("source_$sourceId", 0x0000)
        } catch (e: Exception) {
            System.err.println("AnymeXPreferences: Failed to get SharedPreferences for source $sourceId: ${e.message}")
        }
    } else {
        System.err.println("AnymeXPreferences: No Application bound for source $sourceId")
    }
    return keiyoushi.utils.InMemorySharedPreferences()
}
