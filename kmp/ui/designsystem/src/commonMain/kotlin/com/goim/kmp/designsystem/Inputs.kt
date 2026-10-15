package com.goim.kmp.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

/**
 * 单行输入框：高 [GoImDimens.inputHeight] 的无描边胶囊，占位文字兼作标签。
 * [trailing] 放图标按钮或文字按钮，高度与输入框一致；[supportingText] 显示在框下方，[isError] 时为 error 色。
 */
@Composable
fun GoImTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Column(modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            decorationBox = { input ->
                InputFrame(
                    placeholder = placeholder.takeIf { value.isEmpty() },
                    leadingIcon = leadingIcon,
                    trailing = trailing,
                    isError = isError,
                    input = input,
                )
            },
        )
        if (supportingText != null) {
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = GoImSpacing.s4, top = GoImSpacing.s1, end = GoImSpacing.s4),
            )
        }
    }
}

/** 搜索框：带搜索图标，有内容时显示清除按钮，回车触发 [onSearch]。 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
) {
    GoImTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier.fillMaxWidth(),
        leadingIcon = Icons.Filled.Search,
        trailing = if (value.isNotEmpty()) ({ ClearButton { onValueChange("") } }) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

/** 与 [SearchField] 外观一致的只读入口，点击进入真正的搜索页。 */
@Composable
fun SearchEntry(placeholder: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().clip(CircleShape).clickable(role = Role.Button, onClick = onClick)) {
        InputFrame(placeholder = placeholder, leadingIcon = Icons.Filled.Search, trailing = null, isError = false, input = {})
    }
}

@Composable
private fun ClearButton(onClick: () -> Unit) {
    Box(
        Modifier.size(GoImSize.avatarSm).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = "清除", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(GoImSize.iconSm))
    }
}

@Composable
private fun InputFrame(
    placeholder: String?,
    leadingIcon: ImageVector?,
    trailing: (@Composable () -> Unit)?,
    isError: Boolean,
    input: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(GoImDimens.inputHeight)
            .background(if (isError) colors.errorContainer else colors.surfaceContainerHigh, CircleShape)
            .padding(
                start = if (leadingIcon != null) GoImSpacing.s3 else GoImSpacing.s4,
                end = if (trailing != null) GoImSpacing.s1 else GoImSpacing.s4,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GoImSpacing.s2),
    ) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                tint = if (isError) colors.onErrorContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(GoImSize.iconSm),
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (placeholder != null) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            input()
        }
        if (trailing != null) {
            // 尾部按钮与输入框等高，不再额外撑到 48dp
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) { trailing() }
        }
    }
}
