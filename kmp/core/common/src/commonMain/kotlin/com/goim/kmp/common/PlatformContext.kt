package com.goim.kmp.common

/** Android 上为 `Context`；其他平台为占位单例 [PlatformContext.INSTANCE]。 */
expect abstract class PlatformContext

/** 平台与系统版本，用于开发者工具展示，例如 `Android 15`、`iOS 18.2`、`macOS 15.3`。 */
expect val platformDescription: String

/** 上报给网关的端类型（`ios` / `ipados` / `android` / `macos` / `windows` / `linux`），决定多端互踢分组。 */
expect val devicePlatform: String
