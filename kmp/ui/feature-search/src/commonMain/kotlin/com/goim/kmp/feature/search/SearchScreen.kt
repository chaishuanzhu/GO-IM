package com.goim.kmp.feature.search

import com.goim.kmp.model.ChatType

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.goim.kmp.designsystem.EmptyState
import com.goim.kmp.designsystem.GoImFormat
import com.goim.kmp.designsystem.MonogramAvatar
import com.goim.kmp.designsystem.UserAvatar
import com.goim.kmp.designsystem.rememberDisplayName
import com.goim.kmp.model.SearchHit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel, onOpenHit: (SearchHit, String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("搜索") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("搜索消息") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "清除") }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 56.dp),
            )
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            if (state.isLocalFallback && state.searched) {
                Text(
                    "服务端搜索不可用，仅显示本机记录",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            when {
                state.error != null -> EmptyState(Icons.Filled.Warning, "搜索失败", state.error)
                state.query.isBlank() -> EmptyState(Icons.Filled.Search, "搜索聊天记录", "输入关键词，按回车搜索")
                state.searched && state.rows.isEmpty() -> EmptyState(Icons.Filled.Search, "未找到相关消息", "换个关键词试试")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { "${it.hit.conversationId}-${it.hit.serverMsgId}-${it.hit.localMessageId}" }) { row ->
                        HitRow(row, state.query.trim(), onClick = { onOpenHit(row.hit, row.conversationTitle) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HitRow(row: SearchRow, query: String, onClick: () -> Unit) {
    val highlightStyle = SpanStyle(
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        background = MaterialTheme.colorScheme.tertiaryContainer,
        fontWeight = FontWeight.SemiBold,
    )
    val isGroup = row.hit.chatType == ChatType.GROUP
    val title = if (isGroup) row.conversationTitle else rememberDisplayName(row.hit.peerOrGroupId, row.conversationTitle)
    val sender = rememberDisplayName(row.hit.fromUid)
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(highlight("$sender: ${row.hit.content}", query, highlightStyle), maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            if (isGroup) MonogramAvatar(title) else UserAvatar(row.hit.peerOrGroupId, title)
        },
        trailingContent = {
            Text(
                GoImFormat.conversationTime(row.hit.timestampMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp).clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
    )
}

private fun highlight(text: String, query: String, style: SpanStyle) = buildAnnotatedString {
    if (query.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var start = 0
    while (true) {
        val index = text.indexOf(query, start, ignoreCase = true)
        if (index < 0) break
        append(text.substring(start, index))
        withStyle(style) { append(text.substring(index, index + query.length)) }
        start = index + query.length
    }
    append(text.substring(start))
}
