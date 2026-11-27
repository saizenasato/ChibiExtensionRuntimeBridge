package keiyoushi.utils

import android.app.Application

/**
 * The Application the Keiyoushi parsers use to reach SharedPreferences.
 *
 * This module is a plain JVM library, so the runtime host binds the real
 * Application here instead of relying on a dependency-injection singleton.
 */
public object PreferencesHost {
    @Volatile
    public var application: Application? = null
}
