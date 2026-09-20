# GO-IM iOS Client

UIKit client for the GO-IM gateway (Clean Architecture: Domain / Data / Presentation / App).

## Prerequisites

- Xcode 16+ (Swift 6)
- [Tuist](https://tuist.io) (`brew install tuist`)

## Generate & open

```bash
cd ios
tuist install
tuist generate
open GOIM.xcworkspace
```

## Configure API base URL

The app reads `API_BASE_URL` from Info.plist (injected via `App/Project.swift` build settings).

Default: `http://127.0.0.1:8080`

Override in Xcode: **GOIM target → Build Settings → API_BASE_URL**, or edit `App/Project.swift`.

TCP transport uses host from the base URL and port **8081**.

## Run

1. Start the GO-IM gateway (`go run ./cmd/gateway/` from the repo root).
2. Select an iOS Simulator and run the **GOIM** scheme.
3. Log in (dev mode) or register, then chat over WebSocket or TCP (Settings → Transport).

## Modules

| Module | Role |
|--------|------|
| **Domain** | Entities, repository protocols, use cases |
| **Data** | Moya HTTP, Keychain, GRDB, NWTransport, IMPipeline, protobuf wire codec |
| **Presentation** | UIKit screens (Login, Chats, Contacts, Search, Settings) |
| **App** | Composition root, AppDelegate / SceneDelegate |

Presentation imports **Domain only**. App wires Data implementations into Presentation.

## Proto

Wire codec is hand-written in `Data/Sources/Proto/ProtobufCodec.swift` to match `api/proto/message.proto`. See `Scripts/generate-proto.sh` for optional future `swift-protobuf` generation.
