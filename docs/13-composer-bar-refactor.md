# 13 — Chat Composer Bar Refactor

## Background

`ChatComposerBar` previously owned dock chrome, keyboard/safe-area height orchestration, and inline emoji / voice / more panels, while image and sticker were already separate types. Extending a mode required editing a ~900-line file and `setAccessory` branches.

## Goals / Non-goals

**Goals**

- Shell ≤ ~350 lines: chrome, text, tools, height pins, mode switch
- New mode = accessory case + panel + registry entry
- `ChatComposerBarDelegate` and `ChatViewController` behavior unchanged
- Visual / interaction parity (sticker remains a tab inside emoji)

**Non-goals**

- No dock visual redesign
- System pickers stay in the view controller
- Sticker is not a top-level tool
- No interactive keyboard-dismiss rewrite
- Not merged with the bubble content registry

## Architecture

```
ChatViewController → ChatComposerBar (shell)
                         └── accessoryHost
                               └── ChatComposerAccessoryPanel (plugin)
```

Shell owns keyboard observation, `expandedAccessoryHeight()`, mutual exclusive bottom pins, and home-indicator insets. Panels own mode UI and call into the shell via actions / existing image delegate.

## Layout

```
ios/Presentation/Sources/Chat/Composer/
  ChatComposerBar.swift
  ChatComposerAccessory.swift       // enum + protocol + registry
  Accessories/
    EmojiAccessoryPanel.swift
    VoiceAccessoryPanel.swift
    MoreAccessoryPanel.swift
    ChatImageAccessoryPanel.swift
    StickerAccessoryPanel.swift
```

## Protocol

```swift
@MainActor
protocol ChatComposerAccessoryPanel: UIView {
    static var mode: ChatComposerAccessory { get }
    func prepareForDisplay()
    func prepareForHide()
    func applyBottomSafeInset(_ inset: CGFloat)
}
```

Registry caches one instance per mode. Fill constraints: leading/trailing always; top/bottom only while accessory height > 0.

## Image tip constraints

Photo-access tip uses a single top track: content always pins to `tipBanner.bottom`; tip height 0 + spacing 10 when hidden, height 32 + spacing 8 when visible. Avoids mutually exclusive top constraints.

## Extending a mode

1. Add `ChatComposerAccessory` case
2. Implement `ChatComposerAccessoryPanel`
3. Register in `ChatComposerAccessoryRegistry`
4. Add tool button wiring in the shell if needed

## Acceptance

- [x] Text / @ / emoji insert / sticker send
- [x] Voice hold / release / cancel / permission failure
- [x] Image tip / pick / original / camera album
- [x] More → video, file
- [x] Keyboard vs accessory mutual exclusion
- [x] No tip Unsatisfiable Constraints (single-track tip top)
