import Foundation

public extension Message {
    /// Human-readable one-line preview for conversation list (never raw JSON).
    var listPreview: String {
        switch msgType {
        case .text:
            return Self.textPreview(content)
        case .image:
            return "[图片]"
        case .voice:
            if let sec = Self.fileDuration(content), sec > 0 {
                return "[语音] \(sec)\""
            }
            return "[语音]"
        case .video:
            if let sec = Self.fileDuration(content), sec > 0 {
                return "[视频] \(sec)\""
            }
            return "[视频]"
        case .file:
            if let name = Self.fileName(content), !name.isEmpty {
                return "[文件] \(name)"
            }
            return "[文件]"
        case .unsupported:
            return MsgType.unsupportedPlaceholder
        }
    }

    /// Best-effort cleanup for previews already persisted as raw JSON.
    static func sanitizedListPreview(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("{"),
              let data = trimmed.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return raw
        }
        if let type = obj["type"] as? String {
            let uid = obj["uid"] as? String ?? ""
            switch type {
            case "member_joined": return "\(uid) 加入了群聊"
            case "member_left": return "\(uid) 离开了群聊"
            default: break
            }
        }
        if obj["file_id"] != nil {
            if let mime = obj["mime"] as? String {
                if mime.hasPrefix("image/") { return "[图片]" }
                if mime.hasPrefix("audio/") {
                    if let d = obj["duration"] as? Int, d > 0 { return "[语音] \(d)\"" }
                    return "[语音]"
                }
                if mime.hasPrefix("video/") {
                    if let d = obj["duration"] as? Int, d > 0 { return "[视频] \(d)\"" }
                    return "[视频]"
                }
            }
            if obj["width"] != nil || obj["height"] != nil { return "[图片]" }
            if let d = obj["duration"] as? Int, d > 0 {
                return obj["name"] != nil ? "[视频] \(d)\"" : "[语音] \(d)\""
            }
            if let name = obj["name"] as? String, !name.isEmpty { return "[文件] \(name)" }
            return "[文件]"
        }
        return raw
    }

    private static func textPreview(_ content: String) -> String {
        if let notice = systemNotice(from: content) { return notice }
        if content.hasPrefix("{"), content.contains("file_id") {
            return sanitizedListPreview(content)
        }
        return content
    }

    private static func systemNotice(from content: String) -> String? {
        guard let data = content.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = obj["type"] as? String else { return nil }
        let uid = obj["uid"] as? String ?? ""
        switch type {
        case "member_joined": return "\(uid) 加入了群聊"
        case "member_left": return "\(uid) 离开了群聊"
        default: return nil
        }
    }

    private static func fileJSON(_ content: String) -> [String: Any]? {
        guard let data = content.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return obj
    }

    private static func fileName(_ content: String) -> String? {
        fileJSON(content)?["name"] as? String
    }

    private static func fileDuration(_ content: String) -> Int? {
        let obj = fileJSON(content)
        if let d = obj?["duration"] as? Int { return d }
        if let d = obj?["duration"] as? Double { return Int(d.rounded()) }
        return nil
    }
}
