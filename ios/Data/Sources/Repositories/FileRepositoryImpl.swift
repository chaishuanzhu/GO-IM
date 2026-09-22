import Foundation
import ImageIO
import Domain

public final class FileRepositoryImpl: FileRepository, @unchecked Sendable {
    private let serverConfig: ServerConfig
    private let auth: AuthRepository
    private let session: URLSession
    private let mediaStore: LocalMediaStore

    public init(
        provider: SharedMoyaProvider,
        serverConfig: ServerConfig,
        auth: AuthRepository,
        session: URLSession = .shared,
        mediaStore: LocalMediaStore = .shared
    ) {
        _ = provider
        self.serverConfig = serverConfig
        self.auth = auth
        self.session = session
        self.mediaStore = mediaStore
    }

    public func upload(data: Data, fileName: String, mime: String) async throws -> FileMeta {
        guard let user = auth.currentUser() else { throw DomainError.notAuthenticated }
        guard !data.isEmpty else { throw DomainError.invalidState("图片数据为空") }

        // Always read the live base URL (Settings may change it).
        let base = ServerConfigHolder.shared.baseURL
        let url = base.appendingPathComponent("upload")

        let boundary = "Boundary-\(UUID().uuidString)"
        var body = Data()
        func appendField(_ name: String, _ value: String) {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(value)\r\n".data(using: .utf8)!)
        }
        appendField("uid", user.uid)
        appendField("token", user.token)
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append(
            "Content-Disposition: form-data; name=\"file\"; filename=\"\(fileName)\"\r\n".data(using: .utf8)!
        )
        body.append("Content-Type: \(mime)\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n".data(using: .utf8)!)
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 120

        do {
            let (respData, response) = try await session.upload(for: request, from: body)
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            guard (200..<300).contains(status) else {
                let bodyText = String(data: respData, encoding: .utf8) ?? ""
                throw DomainError.server(status, bodyText.isEmpty ? "upload failed" : bodyText)
            }
            do {
                var meta = try JSONDecoder().decode(UploadResponseDTO.self, from: respData).toDomain()
                // Fill missing dims from the bytes we actually uploaded (ImageIO).
                if (meta.width ?? 0) <= 0 || (meta.height ?? 0) <= 0,
                   let (w, h) = Self.pixelSize(of: data)
                {
                    if (meta.width ?? 0) <= 0 { meta.width = w }
                    if (meta.height ?? 0) <= 0 { meta.height = h }
                }
                return meta
            } catch {
                let bodyText = String(data: respData, encoding: .utf8) ?? ""
                throw DomainError.network("上传响应解析失败: \(error.localizedDescription); body=\(bodyText)")
            }
        } catch let error as DomainError {
            throw error
        } catch {
            let ns = error as NSError
            throw DomainError.network(
                "上传连接失败 (\(ns.code)): \(error.localizedDescription)\nURL: \(url.absoluteString)"
            )
        }
    }

    public func stageLocalFile(data: Data, fileName: String) throws -> String {
        try mediaStore.stage(data: data, suggestedName: fileName)
    }

    public func replaceStaged(fileId: String, data: Data) throws {
        try mediaStore.replace(fileId, data: data)
    }

    public func stagedData(fileId: String) -> Data? {
        mediaStore.data(for: fileId)
    }

    public func removeStaged(fileId: String) {
        mediaStore.remove(fileId)
    }

    public func promoteStaged(localId: String, remoteFileId: String, mime: String) throws {
        let kind = LocalMediaStore.attachmentKind(mime: mime)
        try mediaStore.promote(localId: localId, remoteFileId: remoteFileId, to: kind)
    }

    public func localFileIfPresent(fileId: String) -> URL? {
        guard !LocalMediaStore.isLocalFileId(fileId) else {
            return mediaStore.urlIfPresent(for: fileId)
        }
        return mediaStore.urlIfPresent(for: fileId)
    }

    public func ensureLocalFile(fileId: String, suggestedName: String?) async throws -> URL {
        if let existing = localFileIfPresent(fileId: fileId) {
            return existing
        }
        guard let remote = remoteFileURL(fileId: fileId, thumb: false) else {
            throw DomainError.notAuthenticated
        }
        return try await FileDownloadCenter.shared.ensure(
            fileId: fileId,
            remoteURL: remote,
            suggestedName: suggestedName,
            mediaStore: mediaStore
        )
    }

    public func observeFileDownload(fileId: String, suggestedName: String?) -> AsyncStream<FileDownloadEvent> {
        if let existing = localFileIfPresent(fileId: fileId) {
            return AsyncStream { continuation in
                continuation.yield(.completed(existing))
                continuation.finish()
            }
        }
        guard let remote = remoteFileURL(fileId: fileId, thumb: false) else {
            return AsyncStream { continuation in
                continuation.yield(.failed("未登录"))
                continuation.finish()
            }
        }
        return FileDownloadCenter.shared.observe(
            fileId: fileId,
            remoteURL: remote,
            suggestedName: suggestedName,
            mediaStore: mediaStore
        )
    }

    public func fileURL(fileId: String, thumb: Bool) -> URL? {
        if let local = mediaStore.urlIfPresent(for: fileId) {
            return local
        }
        return remoteFileURL(fileId: fileId, thumb: thumb)
    }

    private func remoteFileURL(fileId: String, thumb: Bool) -> URL? {
        guard let user = auth.currentUser() else { return nil }
        var components = URLComponents(
            url: ServerConfigHolder.shared.baseURL.appendingPathComponent("file"),
            resolvingAgainstBaseURL: false
        )
        var items = [
            URLQueryItem(name: "id", value: fileId),
            URLQueryItem(name: "uid", value: user.uid),
            URLQueryItem(name: "token", value: user.token),
        ]
        if thumb {
            items.append(URLQueryItem(name: "thumb", value: "1"))
        }
        components?.queryItems = items
        return components?.url
    }

    private static func pixelSize(of data: Data) -> (Int, Int)? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        else { return nil }
        let w = intProp(props[kCGImagePropertyPixelWidth])
        let h = intProp(props[kCGImagePropertyPixelHeight])
        guard w > 0, h > 0 else { return nil }
        return (w, h)
    }

    private static func intProp(_ value: Any?) -> Int {
        if let i = value as? Int { return i }
        if let n = value as? NSNumber { return n.intValue }
        if let d = value as? Double { return Int(d) }
        return 0
    }
}
