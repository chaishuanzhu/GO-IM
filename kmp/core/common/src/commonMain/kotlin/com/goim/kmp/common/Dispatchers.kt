package com.goim.kmp.common

import kotlinx.coroutines.CoroutineDispatcher

/** `Dispatchers.IO`（JVM / Native 均可用，但 commonMain 中不可直接引用）。 */
expect val ioDispatcher: CoroutineDispatcher
