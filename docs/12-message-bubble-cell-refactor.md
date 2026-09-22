# 12 — Message Bubble Cell Refactor

## Background

`MessageBubbleCell` previously owned chrome (alignment, meta, send status), dual-track Auto Layout (image vs text), JSON parsing, media loading, voice playback UI, and mime-based type correction. Extending a new `msg_type` required editing that single ~765-line file.

## Goals / Non-goals

**Goals**

- New message type = Mapper case + one ContentView + Registry entry (shell unchanged)
- Shell cell ≤ ~200 lines
- Parsing and type decisions leave the cell
- Visual and interaction parity with the pre-refactor UI

**Non-goals**

- No SwiftUI / Diffable / Compositional Layout migration
- No per-type `UITableViewCell` classes
- No protocol or server changes
- No estimated-height work in the first ship

## Architecture

```
Message → MessageBubbleMapper → MessageBubbleViewModel
                                      ↓
                              MessageBubbleCell (shell)
                                      ↓
                                 contentHost
                                      ↓
                            MessageContentView (plugin)
                                      ↓
                            MessageContentActions
```

**Principle:** the shell is an envelope; message bodies are pluggable; parsing lives in the Mapper.

```
MessageBubbleCell (shell)
├── metaLabel
├── bubbleRow
│   ├── statusAccessory
│   └── bubbleView
│       └── contentHost  ← current MessageContentView
└── statusLabel
```

## Directory layout

```
ios/Presentation/Sources/Chat/Bubble/
  MessageBubbleViewModel.swift
  MessageBubbleMapper.swift
  MessageContentView.swift          // protocol + Registry
  MessageBubbleCell.swift           // thin shell
  MessageBubbleLayout.swift
  Contents/
    TextMessageContentView.swift
    ImageMessageContentView.swift
    StickerMessageContentView.swift
    VoiceMessageContentView.swift
    VideoMessageContentView.swift
    FileMessageContentView.swift
    SystemMessageContentView.swift
```

Domain provides shared decode helpers (`Message.decodeFileMeta`, `Message.systemNotice(from:)`) so list preview and bubble mapping do not diverge.

## View models

```swift
struct MessageBubbleViewModel: Equatable {
    enum Alignment { case leading, trailing, center }
    var alignment: Alignment
    var metaText: String?
    var metaTextAlignment: NSTextAlignment
    var status: MessageStatus
    var isOutgoing: Bool
    var bubbleStyle: BubbleStyle  // outgoing | incoming | clear | system
    var content: MessageContentModel
}

enum MessageContentModel: Equatable {
    case text(String)
    case unsupported(String)
    case system(String)
    case image(ImageContent)
    case sticker(StickerContent)
    case voice(VoiceContent)
    case video(VideoContent)
    case file(FileContent)
}

struct MessageContentActions {
    var onRetry: (() -> Void)?
    var onPreview: ((MediaPreviewItem) -> Void)?
    var onPlayVoice: ((URL, Int) -> Void)?
    var loadSticker: ((StickerRef) async -> Data?)?
}
```

`MediaPreviewItem` remains the preview payload; video/file content stores fields and builds the item on tap.

## Mapper rules

`MessageBubbleMapper.map(_:fileURL:)`:

1. System notice first: `msgType == .text` and parseable `member_joined/left` → `.system`, `alignment = .center`
2. Mime correction (former `configureFile`): `file` + `image/*` → image; `audio/*` → voice; `video/*` → video
3. Image size via `MessageBubbleLayout.imageSize` into `displaySize`
4. Parse failures fall back to `[图片]` / `[表情]` etc. (same copy as before)
5. URLs resolved in the Mapper; ContentViews do not take a `fileURL` closure

## Content protocol

```swift
@MainActor
protocol MessageContentView: UIView {
    static var reuseKey: String { get }
    func prepareForReuse()
    func apply(
        _ model: MessageContentModel,
        chrome: MessageChromeTokens,
        actions: MessageContentActions
    )
}
```

Shell keeps `currentContent` + `currentKey`:

- Same `reuseKey` → `apply` only
- Different → remove old, pin new to `contentHost`

| ContentView | Owns | Does not own |
|---|---|---|
| Text / System / Unsupported | Copy styling | Alignment, send status |
| Image | Kingfisher, `boundFileId`, fixed W×H | Bubble fill color |
| Sticker | StickerImageCache + Task | Same |
| Voice | Waveform, VoicePlayer observers | Status accessory |
| Video / File | Icon + title, preview tap | URL parsing |

## Shell API

```swift
func configure(vm: MessageBubbleViewModel, actions: MessageContentActions)
```

Steps: alignment → meta/status → `bubbleStyle` → install content → `apply`.

`ChatViewController` maps then configures:

```swift
let vm = MessageBubbleMapper.map(m) { id, thumb in
    env.files.fileURL(fileId: id, thumb: thumb)
}
cell.configure(vm: vm, actions: .init(...))
```

## Layout rules

1. Image/sticker: Content owns fixed size; shell does not keep six image constraints
2. Text-like: stack inside Content; `preferredMaxLayoutWidth` computed in Content (or shell passes `maxBubbleWidth`)
3. Voice waveform: attach/detach only inside `VoiceMessageContentView`
4. Corner radius only on `bubbleView`; image content uses `cornerRadius = 0`

## Phased delivery

### Phase 0 — Models and parse (zero visual diff)

- Add ViewModel / Mapper / Layout
- Domain unified file-meta / system-notice decode
- Cell uses map internally; public `configure(message:)` can wrap temporarily
- Remove private `FileMetaJSON` from the cell

### Phase 1 — Image + Sticker ContentViews

- Introduce `contentHost`; only these two types use plugins
- Accept: GIF stickers do not flash; sizes; tap preview

### Phase 2 — Remaining types + drop dual-track constraints

- Text / System / Voice / Video / File
- Move VoicePlayer observers into `VoiceMessageContentView`
- Delete `showImageContent` / `showTextContent`
- Shell is chrome-only

### Phase 3 — Registry and extension checklist

- Registry finalized; file-header checklist for new types
- Optional light pooling of same-type ContentViews (not required)

## Extending a new type

1. Add `MsgType` case in Domain
2. Mapper emits a new `MessageContentModel` case
3. Add `XxxMessageContentView`
4. Register in `MessageContentRegistry`
5. Extend `MediaPreviewItem` if preview is needed

**Do not change the shell cell.**

## Risks

| Risk | Mitigation |
|---|---|
| Flash on type switch | Same `reuseKey` → apply only; Image/Sticker keep `boundKey` |
| System notice alignment | Mapper `alignment: .center` + leading+trailing active |
| Voice observer leaks | ContentView `prepareForReuse` + `deinit` |
| Preview vs bubble parse drift | Shared Domain decode |

## Acceptance

- [x] Text / image / voice playback / video / file / sticker GIF / system notice (architecture covers all; manual QA on device)
- [x] Sending / failed retry / recalled (shell status accessory preserved)
- [x] file+mime correction still works (`MessageBubbleMapper.mapFile`)
- [x] Image dock, preview, download pages unchanged (no edits outside bubble path except Domain decode share)
- [x] This document matches the implementation under `ios/Presentation/Sources/Chat/Bubble/`
