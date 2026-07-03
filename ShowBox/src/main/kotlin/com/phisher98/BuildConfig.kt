package com.phisher98

/**
 * Compatibility shim for code that expects BuildConfig in the provider package.
 * The Android Gradle namespace is com.sad25kag, so the generated BuildConfig
 * lives in com.sad25kag and is re-exported here for ShowBox sources/settings.
 */
internal object BuildConfig {
    const val LIBRARY_PACKAGE_NAME = com.sad25kag.BuildConfig.LIBRARY_PACKAGE_NAME
    const val SuperToken = com.sad25kag.BuildConfig.SuperToken
}
