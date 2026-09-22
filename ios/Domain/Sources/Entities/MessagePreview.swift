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
            if let sec = Self.decodeFileMeta(content)?.duration, sec > 0 {
                return "[语音] \(sec)\""
            }
            return "[语音]"
        case .video:
            if let sec = Self.decodeFileMeta(content)?.duration, sec > 0 {
                return "[视频] \(sec)\""
            }
            return "[视频]"
        case .file:
            if let name = Self.decodeFileMeta(content)?.name, !name.isEmpty {
                return "[文件] \(name)"
            }
            return "[文件]"
        case .sticker:
            return "[表情]"
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
        if let notice = systemNotice(fromObject: obj) {
            return notice
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

    /// Decode chat file/image/voice/video JSON payload into `FileMeta`.
    static func decodeFileMeta(_ content: String) -> FileMeta? {
        guard let data = content.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(FileMetaContent.self, from: data).asFileMeta()
    }

    /// Group system notice copy, if `content` is a known notice payload.
    static func systemNotice(from content: String) -> String? {
        guard let data = content.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return systemNotice(fromObject: obj)
    }

    private static func systemNotice(fromObject obj: [String: Any]) -> String? {
        guard let type = obj["type"] as? String else { return nil }
        let uid = obj["uid"] as? String ?? ""
        switch type {
        case "member_joined": return "\(uid) 加入了群聊"
        case "member_left": return "\(uid) 离开了群聊"
        default: return nil
        }
    }

    private static func textPreview(_ content: String) -> String {
        if let notice = systemNotice(from: content) { return notice }
        if content.hasPrefix("{"), content.contains("file_id") {
            return sanitizedListPreview(content)
        }
        return content
    }
}

/// Wire JSON for file-like message content (`file_id`, dimensions, duration, …).
private struct FileMetaContent: Decodable {
    let fileId: String
    let name: String?
    let size: Int64?
    let mime: String?
    let width: Int?
    let height: Int?
    let duration: Int?

    enum CodingKeys: String, CodingKey {
        case fileId = "file_id"
        case name, size, mime, width, height, duration
        case thumbWidth = "thumb_width"
        case thumbHeight = "thumb_height"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let s = try? c.decode(String.self, forKey: .fileId) {
            fileId = s
        } else if let n = try? c.decode(Int64.self, forKey: .fileId) {
            fileId = String(n)
        } else {
            throw DecodingError.dataCorruptedError(forKey: .fileId, in: c, debugDescription: "file_id missing")
        }
        name = try? c.decode(String.self, forKey: .name)
        if let s = try? c.decode(Int64.self, forKey: .size) {
            size = s
        } else if let s = try? c.decode(Int.self, forKey: .size) {
            size = Int64(s)
        } else {
            size = nil
        }
        mime = try? c.decode(String.self, forKey: .mime)
        let w = (try? c.decode(Int.self, forKey: .width))
            ?? (try? c.decode(Int.self, forKey: .thumbWidth))
        let h = (try? c.decode(Int.self, forKey: .height))
            ?? (try? c.decode(Int.self, forKey: .thumbHeight))
        width = (w ?? 0) > 0 ? w : nil
        height = (h ?? 0) > 0 ? h : nil
        if let d = try? c.decode(Int.self, forKey: .duration) {
            duration = d
        } else if let d = try? c.decode(Double.self, forKey: .duration) {
            duration = Int(d.rounded())
        } else {
            duration = nil
        }
    }

    func asFileMeta() -> FileMeta {
        FileMeta(
            fileId: fileId,
            name: name ?? "file",
            size: size ?? 0,
            mime: mime ?? "application/octet-stream",
            width: width,
            height: height,
            duration: duration
        )
    }
}
