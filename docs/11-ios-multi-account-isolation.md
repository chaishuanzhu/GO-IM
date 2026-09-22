# iOS 多用户本地数据隔离

同机多账号时，本地库与附件按 `Users/{uid}/` 物理隔离，避免换号串消息/媒体。

## 目录布局

```
Application Support/GOIM/
  Shared/
    device.json              # activeUID, apiBaseURL, preferredTransport
  Users/
    {uid}/
      db/goim.sqlite
      media/                 # 图片 / 视频 / 语音
      files/                 # 文件类型消息
      tmp/                   # 收发临时区
  Catalog/
    Stickers/                # 设备共享贴纸包缓存
```

Keychain：`session.{uid}` 存 token/username；当前账号由 `Shared/device.json` 的 `activeUID` 指向。退出登录清除 active session，**不删除** `Users/{uid}`。

## 收发附件

- **发送**：选文件后写入 `tmp/`（`local:` id）；上传并投递成功后 move 到 `files/` 或 `media/`（按 mime），文件名使用服务端 `file_id`。
- **下载（文件消息）**：若 `files/{fileId}` 已存在则直接打开；否则整文件下到 `tmp/`，成功后再 move 到 `files/`。本阶段不做断点续传（需 Gateway Range）。

## 会话切换

登录成功 / 恢复 session 时：打开对应 `UserHome`，重建 DB、repos、`AppEnvironment` 与 inbound pumps。退出时：断连、清 Keychain active session、停 `VoicePlayer`、清 Kingfisher / `URLCache`、关库引用并回到登录页。

## 迁移

若仍存在旧路径 `GOIM/goim.sqlite` 或 `GOIM/staged/`，启动时在已知 `activeUID` 下一次性迁入 `Users/{uid}/`；旧 `GOIM/Stickers` 迁入 `Catalog/Stickers`。
