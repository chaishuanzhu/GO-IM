package com.goim.kmp.platform

import com.goim.kmp.common.PlatformContext
import kotlinx.coroutines.flow.Flow

/**
 * 网络可达性：Android `ConnectivityManager`、iOS `NWPathMonitor`；
 * Desktop 无系统回调，恒为 true（断线靠重连退避恢复）。
 */
expect fun networkAvailability(context: PlatformContext): Flow<Boolean>
