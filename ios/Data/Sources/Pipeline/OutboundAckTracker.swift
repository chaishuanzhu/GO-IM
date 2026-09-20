import Foundation
import Domain

/// Tracks outbound messages awaiting CmdAck.
/// Timeout 3s × up to 3 automatic retries (same clientSeq), then mark failed.
public actor OutboundAckTracker {
    public typealias RetryHandler = @Sendable (Message) async -> Bool
    public typealias FailHandler = @Sendable (Int64) async -> Void

    private struct Pending {
        var message: Message
        /// How many automatic retries have already been performed.
        var retriesDone: Int
        var task: Task<Void, Never>
    }

    private var pending: [Int64: Pending] = [:]
    private let timeoutSeconds: TimeInterval
    private let maxRetries: Int
    private let onRetry: RetryHandler
    private let onFail: FailHandler

    public init(
        timeoutSeconds: TimeInterval = 3,
        maxRetries: Int = 3,
        onRetry: @escaping RetryHandler,
        onFail: @escaping FailHandler
    ) {
        self.timeoutSeconds = timeoutSeconds
        self.maxRetries = maxRetries
        self.onRetry = onRetry
        self.onFail = onFail
    }

    /// Register (or re-arm) after a successful socket write.
    public func register(_ message: Message, retriesDone: Int = 0) {
        let seq = message.clientSeq
        cancelTimer(seq)
        let task = Task { [timeoutSeconds] in
            try? await Task.sleep(nanoseconds: UInt64(timeoutSeconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self.handleTimeout(seq: seq)
        }
        pending[seq] = Pending(message: message, retriesDone: retriesDone, task: task)
    }

    public func acknowledge(seq: Int64) {
        cancelTimer(seq)
        pending[seq] = nil
    }

    public func cancelAll() {
        for seq in pending.keys {
            cancelTimer(seq)
        }
        pending.removeAll()
    }

    private func cancelTimer(_ seq: Int64) {
        pending[seq]?.task.cancel()
    }

    private func handleTimeout(seq: Int64) async {
        guard let entry = pending[seq] else { return }
        pending[seq] = nil

        if entry.retriesDone >= maxRetries {
            await onFail(seq)
            return
        }
        let ok = await onRetry(entry.message)
        if ok {
            register(entry.message, retriesDone: entry.retriesDone + 1)
        } else {
            await onFail(seq)
        }
    }
}
