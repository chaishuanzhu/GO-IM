# 14 — Chat Page Events Refactor

## Background

`ChatViewController` was the fan-in for composer delegate methods, system pickers, bubble action closures, media loaders, mention, and preview. Bubble and Composer already use shell + plugins; page-level events did not.

## Goals / Non-goals

**Goals**

- All user intents go through `ChatUIAction` → `ChatEventRouter.handle`
- VC keeps chrome only: layout, table, keyboard lift / insets, `onChange` → reload
- Composer / Bubble plugins and `ChatComposerPanelActions` unchanged in this pass
- Behavior parity

**Non-goals**

- No Coordinator / Combine / Diffable
- No merging Bubble and Composer registries
- Keyboard dual ownership stays: VC lifts dock; Composer sizes accessory height
- ViewModel method signatures unchanged

## Architecture

```
UI (Composer / Bubble / Pickers)
  → ChatUIAction
  → ChatEventRouter
  → ChatViewModel | present(picker/preview/alert) | ChatComposerHosting
```

VC implements `ChatComposerHosting` + `ChatPresentationHosting`. Router holds weak hosts.

## Layout

```
ios/Presentation/Sources/Chat/Events/
  ChatUIAction.swift
  ChatEventRouter.swift
  ChatMediaLoader.swift
```

## Keyboard ownership

| Owner | Responsibility |
|---|---|
| `ChatViewController` | Lift `composerBottomConstraint` with keyboard; table content insets |
| `ChatComposerBar` | Cache keyboard height for accessory panel expansion |

## Acceptance

- [x] Send text / sticker / voice / image confirm / more video+file
- [x] Album → image strip; camera; document send
- [x] Bubble preview / voice / retry
- [x] Keyboard vs accessory mutual exclusion
- [x] VC no longer conforms to system picker delegates (Router does)
