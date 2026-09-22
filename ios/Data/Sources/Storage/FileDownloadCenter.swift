import Foundation
import Domain

/// Shared file downloads with progress, dedupe, and continue-after-UI-dismiss.
public final class FileDownloadCenter: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    public static let shared = FileDownloadCenter()

    private let lock = NSLock()
    private var entries: [String: Entry] = [:]
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 600
        return URLSession(configuration: config, delegate: self, delegateQueue: OperationQueue())
    }()

    private struct Observer {
        let id: UUID
        let continuation: AsyncStream<FileDownloadEvent>.Continuation
    }

    private final class Entry {
        var task: URLSessionDownloadTask?
        var progress: Double = 0
        var suggestedName: String?
        var remoteURL: URL
        var result: Result<URL, Error>?
        var observers: [Observer] = []

        init(remoteURL: URL, suggestedName: String?) {
            self.remoteURL = remoteURL
            self.suggestedName = suggestedName
        }
    }

    private override init() {
        super.init()
    }

    public func reset() {
        lock.lock()
        let snapshot = Array(entries.values)
        entries.removeAll()
        lock.unlock()
        for entry in snapshot {
            entry.task?.cancel()
            for observer in entry.observers {
                observer.continuation.finish()
            }
        }
    }

    /// Start download if needed and stream events. Re-entering attaches to the same job.
    public func observe(
        fileId: String,
        remoteURL: URL,
        suggestedName: String?,
        mediaStore: LocalMediaStore
    ) -> AsyncStream<FileDownloadEvent> {
        AsyncStream { continuation in
            let observerID = UUID()

            if let existing = mediaStore.urlIfPresent(for: fileId),
               !LocalMediaStore.isLocalFileId(fileId) {
                continuation.yield(.completed(existing))
                continuation.finish()
                return
            }

            self.lock.lock()
            let entry: Entry
            if let current = self.entries[fileId] {
                entry = current
                if let name = suggestedName, entry.suggestedName == nil {
                    entry.suggestedName = name
                }
            } else {
                entry = Entry(remoteURL: remoteURL, suggestedName: suggestedName)
                self.entries[fileId] = entry
            }

            if let result = entry.result {
                self.lock.unlock()
                switch result {
                case let .success(url):
                    continuation.yield(.completed(url))
                case let .failure(error):
                    continuation.yield(.failed(error.localizedDescription))
                }
                continuation.finish()
                if case .failure = result {
                    self.lock.lock()
                    self.entries[fileId] = nil
                    self.lock.unlock()
                }
                return
            }

            entry.observers.append(Observer(id: observerID, continuation: continuation))
            continuation.yield(.progress(entry.progress))

            let needsStart = entry.task == nil
            if needsStart {
                let task = self.session.downloadTask(with: remoteURL)
                entry.task = task
                self.lock.unlock()
                task.resume()
            } else {
                self.lock.unlock()
            }

            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                defer { self.lock.unlock() }
                guard let entry = self.entries[fileId] else { return }
                entry.observers.removeAll { $0.id == observerID }
            }
        }
    }

    /// Await completion of an in-flight or new download (no duplicate network).
    public func ensure(
        fileId: String,
        remoteURL: URL,
        suggestedName: String?,
        mediaStore: LocalMediaStore
    ) async throws -> URL {
        for await event in observe(
            fileId: fileId,
            remoteURL: remoteURL,
            suggestedName: suggestedName,
            mediaStore: mediaStore
        ) {
            switch event {
            case let .completed(url):
                return url
            case let .failed(message):
                throw DomainError.network(message)
            case .progress:
                continue
            }
        }
        throw DomainError.invalidState("download ended without result")
    }

    // MARK: - URLSessionDownloadDelegate

    public func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard totalBytesExpectedToWrite > 0,
              let fileId = fileId(for: downloadTask) else { return }
        let progress = min(1, Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))
        lock.lock()
        guard let entry = entries[fileId] else {
            lock.unlock()
            return
        }
        entry.progress = progress
        let observers = entry.observers
        lock.unlock()
        for observer in observers {
            observer.continuation.yield(.progress(progress))
        }
    }

    public func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        guard let fileId = fileId(for: downloadTask) else { return }
        lock.lock()
        guard let entry = entries[fileId] else {
            lock.unlock()
            return
        }
        let suggestedName = entry.suggestedName
        let observers = entry.observers
        lock.unlock()

        do {
            // Move out of URLSession's ephemeral temp before the delegate returns.
            let staged = FileManager.default.temporaryDirectory
                .appendingPathComponent("goim-dl-\(UUID().uuidString)", isDirectory: false)
            try? FileManager.default.removeItem(at: staged)
            try FileManager.default.moveItem(at: location, to: staged)
            let finalURL = try LocalMediaStore.shared.storeDownload(
                fileId: fileId,
                from: staged,
                suggestedName: suggestedName
            )
            finish(fileId: fileId, result: .success(finalURL), observers: observers)
        } catch {
            LocalMediaStore.shared.removeIncompleteDownload(fileId: fileId)
            finish(fileId: fileId, result: .failure(error), observers: observers)
        }
    }

    public func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        guard let error, let downloadTask = task as? URLSessionDownloadTask,
              let fileId = fileId(for: downloadTask) else { return }
        lock.lock()
        guard let entry = entries[fileId], entry.result == nil else {
            lock.unlock()
            return
        }
        let observers = entry.observers
        lock.unlock()
        LocalMediaStore.shared.removeIncompleteDownload(fileId: fileId)
        finish(fileId: fileId, result: .failure(error), observers: observers)
    }

    // MARK: - Helpers

    private func finish(fileId: String, result: Result<URL, Error>, observers: [Observer]) {
        lock.lock()
        if let entry = entries[fileId] {
            entry.result = result
            entry.task = nil
            entry.observers.removeAll()
            if case .failure = result {
                entries[fileId] = nil
            }
        }
        lock.unlock()

        switch result {
        case let .success(url):
            for observer in observers {
                observer.continuation.yield(.completed(url))
                observer.continuation.finish()
            }
        case let .failure(error):
            let message = error.localizedDescription
            for observer in observers {
                observer.continuation.yield(.failed(message))
                observer.continuation.finish()
            }
        }
    }

    private func fileId(for task: URLSessionDownloadTask) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return entries.first(where: { $0.value.task === task })?.key
    }
}
