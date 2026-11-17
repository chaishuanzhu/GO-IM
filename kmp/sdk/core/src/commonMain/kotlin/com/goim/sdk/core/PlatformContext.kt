package com.goim.sdk.core

/**
 * Host platform handle for [GoImClient.create].
 *
 * Re-exported from `com.goim.kmp.common` so hosts can depend on the Core SDK surface:
 * Android = `android.content.Context`, other targets = [INSTANCE][com.goim.kmp.common.PlatformContext].
 */
typealias PlatformContext = com.goim.kmp.common.PlatformContext
