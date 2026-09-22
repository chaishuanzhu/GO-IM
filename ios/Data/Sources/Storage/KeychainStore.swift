import Foundation
import Security
import Domain

public final class KeychainStore: @unchecked Sendable {
    public static let shared = KeychainStore()

    private let service = "com.goim.keychain"
    private let legacyUIDKey = "uid"
    private let legacyUsernameKey = "username"
    private let legacyTokenKey = "token"
    private let legacyTransportKey = "preferred_transport"

    public init() {
        migrateLegacySessionIfNeeded()
        migrateLegacyTransportIfNeeded()
    }

    // MARK: - Session (per uid)

    public func saveSession(uid: String, username: String, token: String) {
        let payload = SessionPayload(username: username, token: token)
        if let data = try? JSONEncoder().encode(payload),
           let json = String(data: data, encoding: .utf8) {
            set(sessionKey(uid), json)
        }
        DevicePreferences.update { $0.activeUID = uid }
    }

    public func loadSession(uid: String) -> User? {
        guard let raw = get(sessionKey(uid)),
              let data = raw.data(using: .utf8),
              let payload = try? JSONDecoder().decode(SessionPayload.self, from: data),
              !payload.token.isEmpty else {
            return nil
        }
        return User(uid: uid, username: payload.username.isEmpty ? uid : payload.username, token: payload.token)
    }

    public func loadActiveSession() -> User? {
        migrateLegacySessionIfNeeded()
        guard let uid = DevicePreferences.load().activeUID, !uid.isEmpty else { return nil }
        return loadSession(uid: uid)
    }

    /// Backward-compatible alias used by AuthRepository.
    public func loadSession() -> User? {
        loadActiveSession()
    }

    public func clearActiveSession() {
        if let uid = DevicePreferences.load().activeUID {
            delete(sessionKey(uid))
        }
        DevicePreferences.update { $0.activeUID = nil }
        delete(legacyUIDKey)
        delete(legacyUsernameKey)
        delete(legacyTokenKey)
    }

    /// Clears active session tokens; keeps `Users/{uid}` on disk.
    public func clearSession() {
        clearActiveSession()
    }

    public func clearSession(uid: String) {
        delete(sessionKey(uid))
        var prefs = DevicePreferences.load()
        if prefs.activeUID == uid {
            prefs.activeUID = nil
            prefs.save()
        }
    }

    // MARK: - Transport (device-level via Shared)

    public var preferredTransport: TransportKind {
        get {
            migrateLegacyTransportIfNeeded()
            let raw = DevicePreferences.load().preferredTransport
            if let raw, let kind = TransportKind(rawValue: raw) {
                return kind
            }
            return .webSocket
        }
        set {
            DevicePreferences.update { $0.preferredTransport = newValue.rawValue }
        }
    }

    // MARK: - Migration

    private func migrateLegacySessionIfNeeded() {
        guard let uid = get(legacyUIDKey), !uid.isEmpty,
              let token = get(legacyTokenKey), !token.isEmpty else {
            return
        }
        if loadSession(uid: uid) == nil {
            let username = get(legacyUsernameKey) ?? uid
            let payload = SessionPayload(username: username, token: token)
            if let data = try? JSONEncoder().encode(payload),
               let json = String(data: data, encoding: .utf8) {
                set(sessionKey(uid), json)
            }
        }
        var prefs = DevicePreferences.load()
        if prefs.activeUID == nil {
            prefs.activeUID = uid
            prefs.save()
        }
        delete(legacyUIDKey)
        delete(legacyUsernameKey)
        delete(legacyTokenKey)
    }

    private func migrateLegacyTransportIfNeeded() {
        guard let raw = get(legacyTransportKey), !raw.isEmpty else { return }
        var prefs = DevicePreferences.load()
        if prefs.preferredTransport == nil {
            prefs.preferredTransport = raw
            prefs.save()
        }
        delete(legacyTransportKey)
    }

    private func sessionKey(_ uid: String) -> String {
        "session.\(UserHome.sanitizeUID(uid))"
    }

    // MARK: - Keychain primitives

    private struct SessionPayload: Codable {
        var username: String
        var token: String
    }

    private func set(_ key: String, _ value: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    private func get(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func delete(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
