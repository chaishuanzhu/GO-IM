package com.goim.kmp.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import com.goim.kmp.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 按 UID 查询用户资料（昵称 / 头像）；登录后由 app 层提供，界面各处共用同一缓存。 */
fun interface ProfileLookup {
    fun observe(uid: String): Flow<UserProfile?>
}

val LocalProfileLookup = staticCompositionLocalOf { ProfileLookup { flowOf(null) } }

@Composable
fun rememberProfile(uid: String): UserProfile? {
    val lookup = LocalProfileLookup.current
    val flow = remember(uid, lookup) { if (uid.isEmpty()) flowOf(null) else lookup.observe(uid) }
    val profile by flow.collectAsState(null)
    return profile
}

/** 优先用资料缓存中的昵称，未知时用 [fallback]（为空则用 UID）。 */
@Composable
fun rememberDisplayName(uid: String, fallback: String = uid): String =
    rememberProfile(uid)?.nickname?.takeIf { it.isNotBlank() } ?: fallback.ifBlank { uid }

/**
 * 用户头像：有头像图片时显示缩略图，加载前 / 失败时显示首字母。
 * [avatarUrl] 已知时（如自己的资料）直接使用，否则从资料缓存读取。
 */
@Composable
fun UserAvatar(
    uid: String,
    name: String? = null,
    size: Dp = GoImDimens.listAvatarSize,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
) {
    val profile = if (avatarUrl == null) rememberProfile(uid) else null
    val url = avatarUrl ?: profile?.avatarUrl.orEmpty()
    val title = profile?.displayName ?: name?.takeIf { it.isNotBlank() } ?: uid
    Box(modifier.size(size)) {
        MonogramAvatar(title, size = size)
        if (url.isNotEmpty()) {
            AsyncImage(
                model = avatarThumbUrl(url),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

/** 网关为头像生成的 200px 缩略图（无缩略图时网关返回原图）。 */
fun avatarThumbUrl(url: String): String = url + (if ('?' in url) "&" else "?") + "thumb=1"
