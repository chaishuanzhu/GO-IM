package com.goim.kmp.platform

import com.goim.kmp.common.PlatformContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual fun networkAvailability(context: PlatformContext): Flow<Boolean> = flowOf(true)
