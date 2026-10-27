# GO-IM Design Tokens

GO-IM 客户端（Kotlin Multiplatform + Compose Multiplatform，Material 3）的设计令牌，格式参考 [designtoken.md](https://designtoken.md/)。
本文件是 UI 规范的唯一来源；实现位于 `ui/designsystem`：

| 令牌 | 代码 |
|------|------|
| 色彩 | `GoImTheme.kt` — `LightColors` / `DarkColors`、`GoImChatColors`；`GoImTokens.kt` — `GoImMediaColors` |
| 字体 | `GoImTokens.kt` — `GoImTypography`（作为 `MaterialTheme.typography`） |
| 间距 | `GoImTokens.kt` — `GoImSpacing` |
| 尺寸 | `GoImTokens.kt` — `GoImSize`；`GoImTheme.kt` — `GoImDimens` |
| 圆角 | `GoImTheme.kt` — `GoImShapes`（作为 `MaterialTheme.shapes`） |
| 高度 | `GoImTokens.kt` — `GoImElevation` |

改令牌时两边同步修改。

## 使用规则

1. **颜色只走 `MaterialTheme.colorScheme` 角色**（或 `GoImTheme.chatColors` / `GoImMediaColors`），业务代码不写 `Color(0x…)`、`Color.Black` 等字面量。
2. **文字只用 9 个规范角色**（见「字体」），不写 `fontSize` / `lineHeight`；强调用 `fontWeight = FontWeight.SemiBold`（未读会话、搜索命中）。
3. **padding / 间隙 / Spacer 只用 `GoImSpacing`**。不在刻度上的值（2、6、10…）就近取整到刻度。
4. **头像、图标、触控区用 `GoImSize`**；断点、窗格宽度、气泡等用 `GoImDimens`。
5. **圆角用 `MaterialTheme.shapes.*` 或 `CircleShape`**，不写 `RoundedCornerShape(n.dp)`（气泡形状在 `GoImDimens` 里统一定义）。
6. **输入框一律是无描边的胶囊，高度统一为 input-height（40dp）**：表单用 `GoImTextField`，搜索用 `SearchField` / `SearchEntry`，不直接使用 M3 `TextField`（固定 56dp 高）或 `OutlinedTextField`（浮动标签缺口会切进圆角）。提示文字放在占位符里，不用浮动标签。表单主按钮用 M3 `Button` 默认高度（40dp），与输入框等高。多行输入（聊天输入框）最小高 40dp、`shapes.extraLarge`，单行时同样呈胶囊。
7. 组件内部的专有几何尺寸（面板高度、贴纸 / 缩略图边长、弹层宽度等）可以就地写，但必须是 4 的倍数；语音波形条（3dp 宽、2dp 间隔）属于绘制细节，不受此限。
8. **宽屏（≥ breakpoint-expanded）不跳全屏二级页**：导航栏与列表常驻，详情（聊天、新的朋友、群组、好友信息）显示在右栏，左侧对应行高亮；聊天的群 / 好友信息是聊天右侧的侧边面板（聊天栏不足 chat-with-panel-min-width 时浮在聊天之上）；添加好友、创建 / 加入群聊这类短任务用弹窗。窄屏时这些都是全屏入栈的二级页。同一页面通过 `Presentation`（Page 返回箭头 / Pane 无导航按钮 / Overlay 关闭按钮）适配三种呈现。Esc 逐层关闭侧边面板与右栏。
9. Android 12+ 默认启用动态取色，令牌中的色值是静态方案（其他平台 / 关闭动态取色时生效）；代码引用角色而非色值，两种方案自动适配。

## Color

品牌种子色 **#415F91**（Material Theme Builder，Tonal Spot）。色阶 50–900 对应 M3 色调 T95–T10：

| 色阶 | 50 | 100 | 200 | 300 | 400 | 500 | 600 | 700 | 800 | 900 |
|------|----|-----|-----|-----|-----|-----|-----|-----|-----|-----|
| M3 色调 | T95 | T90 | T80 | T70 | T60 | T50 | T40 | T30 | T20 | T10 |

### Primary（蓝，hue 263 · chroma 36）

| 令牌 | 值 | 用途 |
|------|----|------|
| primary-50 | #ECF0FF | — |
| primary-100 | #D6E3FF | 浅色 `primaryContainer`；深色 `onPrimaryContainer` |
| primary-200 | #AAC7FF | 深色 `primary`；浅色 `inversePrimary` |
| primary-300 | #8EACE3 | — |
| primary-400 | #7491C7 | — |
| primary-500 | #5A77AB | — |
| primary-600 | #415F91 | **品牌色**；浅色 `primary`、发送气泡底色 |
| primary-700 | #284777 | 浅色 `onPrimaryContainer`；深色 `primaryContainer` |
| primary-800 | #0A305F | 深色 `onPrimary` |
| primary-900 | #001B3E | — |

### Secondary（灰蓝，chroma 16）

| 令牌 | 值 | 用途 |
|------|----|------|
| secondary-50 | #ECF0FF | — |
| secondary-100 | #DAE2F9 | 浅色 `secondaryContainer`（TonalIcon、导航指示器）；深色 `onSecondaryContainer` |
| secondary-200 | #BEC6DC | 深色 `secondary` |
| secondary-300 | #A2ABC0 | — |
| secondary-400 | #8891A5 | — |
| secondary-500 | #6E778A | — |
| secondary-600 | #565F71 | 浅色 `secondary` |
| secondary-700 | #3E4759 | 浅色 `onSecondaryContainer`；深色 `secondaryContainer` |
| secondary-800 | #283141 | 深色 `onSecondary` |
| secondary-900 | #131C2B | — |

### Tertiary（紫，hue +60 · chroma 24）

| 令牌 | 值 | 用途 |
|------|----|------|
| tertiary-50 | #FFEBFE | — |
| tertiary-100 | #FAD8FD | 浅色 `tertiaryContainer`（首字母头像第三色）；深色 `onTertiaryContainer` |
| tertiary-200 | #DDBCE0 | 深色 `tertiary` |
| tertiary-300 | #C0A1C4 | — |
| tertiary-400 | #A487A9 | — |
| tertiary-500 | #896E8E | — |
| tertiary-600 | #705575 | 浅色 `tertiary` |
| tertiary-700 | #573E5C | 浅色 `onTertiaryContainer`；深色 `tertiaryContainer` |
| tertiary-800 | #3F2844 | 深色 `onTertiary` |
| tertiary-900 | #28132E | — |

### Neutral（chroma 6，表面与文字）

| 令牌 | 值 | 用途 |
|------|----|------|
| neutral-50 | #F0F0F7 | 浅色 `inverseOnSurface` |
| neutral-100 | #E2E2E9 | 浅色 `surfaceContainerHighest`（接收气泡、输入框底色）；深色 `onSurface` |
| neutral-200 | #C5C6CD | — |
| neutral-300 | #AAABB1 | — |
| neutral-400 | #8F9097 | — |
| neutral-500 | #75777D | — |
| neutral-600 | #5D5E64 | — |
| neutral-700 | #45474C | — |
| neutral-800 | #2E3036 | 浅色 `inverseSurface` |
| neutral-900 | #191C20 | 浅色 `onSurface`、`onBackground` |

Neutral Variant（chroma 8）：100 #E0E2EC = 浅色 `surfaceVariant`；200 #C4C6D0 = 浅色 `outlineVariant` / 深色 `onSurfaceVariant`；500 #74777F = 浅色 `outline`；700 #44474E = 浅色 `onSurfaceVariant` / 深色 `outlineVariant`。

Error（M3 基准）：600 #BA1A1A = 浅色 `error`；100 #FFDAD6 = 浅色 `errorContainer`；200 #FFB4AB = 深色 `error`。

### 表面层级

| 角色 | 浅色 | 深色 | 用途 |
|------|------|------|------|
| surface / background | #F9F9FF | #111318 | 页面底色、窗格 |
| surfaceContainerLowest | #FFFFFF | #0C0E13 | — |
| surfaceContainerLow | #F3F3FA | #191C20 | — |
| surfaceContainer | #EDEDF4 | #1D2024 | 输入栏、导航栏 |
| surfaceContainerHigh | #E7E8EE | #282A2F | 设置页资料卡 |
| surfaceContainerHighest | #E2E2E9 | #33353A | 接收气泡、输入框、空状态图标底 |

### 语义色

| 令牌 | 取值 | 用途 |
|------|------|------|
| chat.outgoingBubble / outgoingText | `primary` / `onPrimary` | 自己发出的消息 |
| chat.incomingBubble / incomingText | `surfaceContainerHighest` / `onSurface` | 收到的消息 |
| media.background | #000000 | 图片 / 视频全屏查看器 |
| media.scrim | #000000 @ 45% | 图片上的上传进度遮罩 |
| media.content | #FFFFFF | 媒体层上的图标、进度 |
| unread badge | `error` / `onError` | 未读数（M3 Badge） |
| presence.online | #2E9E4F | 头像在线圆点、聊天顶栏「在线」（`GoImPresenceColors`，不随主题变化） |
| danger | `error` / `errorContainer` | 退出群聊、删除会话的滑动背景、危险操作按钮 |

## Typography

系统字体（Android Roboto + Noto Sans CJK、Apple SF + 苹方、Windows Segoe UI + 微软雅黑）。9 级字号，每级映射到一个**规范 M3 角色**；其余 M3 角色指向同级样式，供 M3 组件内部使用。

| 级别 | 规范角色 | 字号 | 字重 | 行高 | 字距 | 也用于 | 用途 |
|------|----------|------|------|------|------|--------|------|
| display | `displaySmall` | 32sp | 600 | 40sp | 0 | displayLarge / Medium | 登录页品牌名 |
| h1 | `headlineSmall` | 24sp | 600 | 32sp | 0 | headlineLarge / Medium | 资料页姓名、弹窗标题、表情字形 |
| h2 | `titleLarge` | 20sp | 600 | 28sp | 0 | — | 顶栏标题、资料卡姓名、`@` 字形按钮 |
| h3 | `titleMedium` | 16sp | 600 | 24sp | 0.1sp | — | 聊天页标题、空状态标题 |
| body-lg | `bodyLarge` | 16sp | 400 | 24sp | 0.15sp | — | 消息正文、输入框、列表主文本 |
| body | `bodyMedium` | 14sp | 400 | 20sp | 0.25sp | — | 默认正文、列表辅助文本、提示条 |
| label | `labelLarge` | 14sp | 500 | 20sp | 0.1sp | titleSmall | 按钮、分组标题、Tab |
| body-sm | `bodySmall` | 12sp | 400 | 16sp | 0.4sp | — | 分组说明、表单错误、开发者日志（等宽） |
| caption | `labelSmall` | 12sp | 500 | 16sp | 0.5sp | labelMedium | 时间戳、发送者、时间分隔、徽标、导航标签 |

## Spacing

基准 4dp，`GoImSpacing.s{n}` = n × 4dp。

| 令牌 | 值 | 典型用途 |
|------|----|----------|
| s1 | 4dp | 图标与文字的紧凑间隔、列表行内垂直补白 |
| s2 | 8dp | 组件间默认间隔、消息间距、窗格外边距 |
| s3 | 12dp | 提示条内边距、文件卡片内间隔 |
| s4 | 16dp | 页面水平边距、列表项内边距、气泡水平内边距 |
| s5 | 20dp | 资料卡内边距 |
| s6 | 24dp | 表单水平边距、区块间隔 |
| s8 | 32dp | 空状态外边距 |
| s10 | 40dp | — |
| s12 | 48dp | — |
| s16 | 64dp | — |
| s20 | 80dp | — |
| s24 | 96dp | — |

## Sizing

| 令牌 | 值 | 用途 |
|------|----|------|
| icon-xs | 16dp | 发送中进度 |
| icon-sm | 20dp | 提示条图标、按钮内进度、语音播放 |
| icon-md | 24dp | 默认图标（M3） |
| icon-lg | 40dp | TonalIcon 容器、拖放上传提示、录音麦克风 |
| icon-xl | 64dp | 空状态图标容器 |
| avatar-sm | 32dp | @ 成员选择 |
| avatar-md | 40dp | 消息头像、聊天顶栏、联系人 / 账号列表 |
| avatar-lg | 48dp | 会话列表（默认） |
| avatar-xl | 64dp | 设置页资料卡 |
| avatar-2xl | 80dp | 联系人 / 群资料页、登录页品牌图标 |
| avatar-3xl | 96dp | 个人资料编辑 |
| touch-target | 48dp | 最小触控区、表情格、媒体播放按钮 |
| stroke-thin | 2dp | 进度环描边、拖放高亮边框（focus ring）、在线圆点描边 |
| presence-dot | 12dp | 头像右下角在线圆点 |

布局（`GoImDimens`）：

| 令牌 | 值 | 说明 |
|------|----|------|
| breakpoint-medium | 600dp | ≥ 600 为中等宽度（导航栏 → 侧边导航轨） |
| breakpoint-expanded | 840dp | ≥ 840 为宽屏（列表 + 详情双栏） |
| list-pane-width | 360dp | 双栏布局中列表窗格宽度 |
| form-max-width | 420dp | 登录 / 注册表单最大宽度 |
| dialog-width | 480dp | 宽屏表单弹窗宽度 |
| dialog-max-height | 640dp | 宽屏表单弹窗最大高度（添加好友、创建群聊） |
| compact-dialog-height | 400dp | 单字段表单弹窗高度（加入群聊） |
| side-panel-width | 360dp | 聊天右侧信息面板宽度 |
| chat-with-panel-min-width | 720dp | 聊天栏不足此宽度时信息面板浮在聊天之上 |
| input-height | 40dp | 所有输入框（表单、搜索框、搜索入口）高度，消息输入框最小高度，发送按钮尺寸 |
| fab-clearance | 88dp | 列表底部与 Snackbar 为 FAB 让出的空间 |
| bubble-max-width | 72% | 气泡相对消息区宽度的上限 |

## Radius

| 令牌 | M3 shape | 值 | 用途 |
|------|----------|----|------|
| xs | `extraSmall` | 4dp | 气泡尾角、Snackbar / 菜单（M3 默认） |
| sm | `small` | 8dp | 表情格、图片缩略图 |
| md | `medium` | 12dp | 图片消息、文件图标底、卡片 |
| lg | `large` | 16dp | 消息气泡、附件操作块、搜索结果项 |
| xl | `extraLarge` | 28dp | 窗格、资料卡、输入框、弹窗 |
| full | `CircleShape` | 50% | 头像、TonalIcon、按钮（M3 默认）、所有单行输入框、波形条 |

## Elevation

M3 以色调高度为主：层级越高，表面色越接近 `surfaceContainerHighest`；阴影只用于浮层。

| 令牌 | 值 | 用途 |
|------|----|------|
| level0 | 0dp | 页面、列表、气泡 |
| level1 | 1dp | Elevated Card（M3 默认，当前未用） |
| level2 | 3dp | 导航栏、顶栏滚动后 |
| level3 | 6dp | FAB |
| level4 | 8dp | 输入栏表情 / @ 弹层（shadowElevation） |
| level5 | 12dp | — |
| focus ring | 2dp `primary` 描边，`shapes.extraLarge` | 拖放上传高亮 |
| overlay | `scrim` / `media.scrim` | 弹窗遮罩（M3 默认 32%）、媒体上的进度遮罩 45% |

交互状态层（M3 state layer，由组件自动处理）：hover 8%、focus 10%、pressed 10%、dragged 16%；禁用态内容 38%、容器 12%。

## Component Tokens

### Button

| 变体 | 背景 | 文字 | 边框 | 高度 | 圆角 | 用途 |
|------|------|------|------|------|------|------|
| Filled（主操作） | `primary` | `onPrimary` | — | 40dp（与输入框等高） | full | 登录 / 注册、表单提交 |
| Filled Tonal | `secondaryContainer` | `onSecondaryContainer` | — | 40dp | full | 次要操作（接受好友请求等） |
| Outlined | transparent | `primary` | 1dp `outline` | 40dp | full | 并列的备选操作 |
| Text | transparent | `primary`（危险时 `error`） | — | 40dp | full | 弹窗按钮、行内链接 |
| FAB | `primaryContainer` | `onPrimaryContainer` | — | 56dp | lg | 新建会话 / 添加好友 |
| Icon (filled) | `primary` | `onPrimary` | — | 40dp | full | 发送（与输入框等高） |

文字样式统一 `labelLarge`；hover / focus / pressed 由 M3 state layer 提供，禁用态为 `onSurface` 12% 背景 + 38% 文字。

### Card / Surface

| 组件 | 背景 | 圆角 | 内边距 | 高度 |
|------|------|------|--------|------|
| 资料卡（设置页） | `surfaceContainerHigh` | xl | s5 | level0 |
| 窗格 PaneSurface | `surface` | xl | — | level0（外边距 s2） |
| 表单弹窗 | `surface` | xl | 外边距 s6 | level3 阴影；宽 dialog-width，标题栏左侧关闭按钮 |
| 信息侧边面板 | `surface` | — | — | 并排时左侧 `outlineVariant` 分隔线；浮层时 level3 阴影 |
| 账号列表卡（登录页） | `surface` + 1dp `outlineVariant` | md | — | level0 |
| 输入栏 | `surfaceContainer` | — | 水平 s3 / s2，垂直 s2 | level0，顶部 `outlineVariant` 分隔线 |

### Input

| 组件 | 背景 | 边框 | 聚焦 | 错误 | 圆角 |
|------|------|------|------|------|------|
| 表单输入（`GoImTextField`） | `surfaceContainerHigh` | 无 | 光标 `primary` | 底色 `errorContainer`，`bodySmall` 说明为 `error` | full，高 40dp；占位符 `bodyMedium` · `onSurfaceVariant`，可选 icon-sm 前置图标与尾部操作（文字按钮 / 图标按钮） |
| 消息输入框（BasicTextField） | `surfaceContainerHighest` | 无 | 光标 `primary` | — | xl；最小高 40dp（单行为胶囊），`bodyLarge`，内边距 s4 / s2，最多 5 行 |
| 搜索框（`SearchField`） | `surfaceContainerHigh` | 无 | 光标 `primary` | — | full，高 40dp；icon-sm 搜索图标，`bodyMedium` 文字，有内容时显示清除按钮 |

### Chat

| 令牌 | 值 |
|------|----|
| bubble.padding | 水平 s4 · 垂直 s2 |
| bubble.radius | lg 16dp，靠近发送方的尾角 xs 4dp |
| bubble.text | `bodyLarge` |
| bubble.meta | `labelSmall`，`onSurfaceVariant`，补白 s2 / s1 |
| message.gap | s2 |
| message.avatar | avatar-md 40dp |
| file-card | 最小宽度 180dp，图标底 40dp · md · 内容色 12% |
| image | 默认 180dp，md 圆角，不画气泡底色 |
| sticker | 消息 140dp，面板 64dp（格 72dp） |
| composer.panel | 高 260dp；表情格 48dp |

### List

| 组件 | 规格 |
|------|------|
| 会话行 | M3 ListItem，头像 avatar-lg，标题 `bodyLarge`（未读 SemiBold），摘要 `bodyMedium`，时间 `labelSmall`，未读 Badge |
| 可选中行（宽屏列表） | 两侧外边距 s2，lg 圆角；选中时底色 `secondaryContainer`（会话、通讯录行一致） |
| 设置行 | M3 ListItem，透明底，前置图标 `onSurfaceVariant`（危险行 `error`） |
| 分组标题 | `labelLarge` · `primary`，补白 s4 / s2 |
| 搜索入口（`SearchEntry`） | 与 `SearchField` 外观相同的只读胶囊，点击进入搜索页；外边距 s4 / s2 |
| 好友请求项 | avatar-lg + 名称 `bodyLarge`（右侧时间 `labelSmall`）+ 说明 `bodyMedium`；下方等宽按钮行（Outlined「拒绝」+ Filled「接受」，间隔 s2）；内边距 s4 / s3 |
| 用户搜索结果 | M3 ListItem，avatar-lg；可添加时 Filled Tonal「添加」（发送中显示 icon-xs 进度），否则 `labelLarge` · `onSurfaceVariant` 状态文字（自己 / 已是好友 / 已发送） |
| 多选列表页（创建群聊） | 顶部表单输入 + 分组标题（显示已选数）；行为 M3 ListItem，avatar-md + 右侧 Checkbox，整行可点；主操作固定在底部（Filled · 40dp，内边距 s4 / s3，避让导航栏与键盘） |
| 单表单页（加入群聊） | 居中列，最大宽度 form-max-width，内边距 s6、间隔 s4：icon-xl TonalIcon（primaryContainer）+ 说明 `bodyMedium` + 输入框 + Filled 主按钮（40dp） |
| 空状态 | icon-xl 图标底 `surfaceContainerHighest`，标题 `titleMedium`，说明 `bodyMedium`，最大宽度 320dp |

## Visual Reference Card

> 冷静、清晰的蓝灰色 Material 3 即时通讯界面。浅色底 #F9F9FF 上铺大圆角（28dp）窗格；品牌蓝 #415F91 只用于主按钮、发出的消息气泡和强调文字，接收气泡是浅灰 #E2E2E9。气泡为 16dp 圆角、发送侧尾角 4dp 的圆角矩形，正文 16sp。头像统一为圆形，无图时用 primary / secondary / tertiary 容器色的首字母。间距严格遵循 4dp 网格，页面边距 16dp，消息间距 8dp。阴影极少，层级靠色调区分；深色模式反转为 #111318 底与 #AAC7FF 主色。Android 12+ 随壁纸取色，结构不变。
