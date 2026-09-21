import Foundation

/// Pure reconnect scheduling helpers (testable without Network.framework).
public enum ReconnectPolicy: Sendable {
    public static let initialDelayNs: UInt64 = 1_000_000_000
    public static let maxDelayNs: UInt64 = 30_000_000_000
    /// ±20% jitter.
    public static let jitterFraction: Double = 0.2

    /// Exponential backoff: `1s → 2s → 4s → …` capped at `maxDelayNs`, with optional jitter.
    /// - Parameter attempt: zero-based reconnect attempt count.
    public static func delayNanoseconds(attempt: Int, jitter: Double? = nil) -> UInt64 {
        let exp = max(0, attempt)
        let shift = min(exp, 31)
        let raw = initialDelayNs &<< UInt64(shift)
        let base = min(raw, maxDelayNs)
        let j = jitter ?? Double.random(in: -jitterFraction...jitterFraction)
        let clamped = max(-jitterFraction, min(jitterFraction, j))
        let scaled = Double(base) * (1.0 + clamped)
        return UInt64(max(0, scaled.rounded()))
    }

    public static func mayReconnect(
        intentionalDisconnect: Bool,
        authExpired: Bool,
        hasUser: Bool
    ) -> Bool {
        !intentionalDisconnect && !authExpired && hasUser
    }

    /// Classify transport / connect failures that should stop the reconnect loop.
    public static func isAuthFailure(_ message: String) -> Bool {
        let lower = message.lowercased()
        let markers = [
            "401",
            "unauthorized",
            "invalid token",
            "missing token",
            "jwt",
            "auth expired",
            "notauthenticated",
            "login rejected",
            "login failed",
        ]
        return markers.contains { lower.contains($0) }
    }
}
