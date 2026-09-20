import Foundation
import Security
import Domain

public final class KeychainStore: @unchecked Sendable {
    public static let shared = KeychainStore()

    private let service = "com.goim.keychain"
    private let uidKey = "uid"
    private let usernameKey = "username"
    private let tokenKey = "token"
    private let transportKey = "preferred_transport"

    public init() {}

    public func saveSession(uid: String, username: String, token: String) {
        set(uidKey, uid)
        set(usernameKey, username)
        set(tokenKey, token)
    }

    public func clearSession() {
        delete(uidKey)
        delete(usernameKey)
        delete(tokenKey)
    }

    public func loadSession() -> User? {
        guard let uid = get(uidKey), !uid.isEmpty,
              let token = get(tokenKey), !token.isEmpty else {
            return nil
        }
        let username = get(usernameKey) ?? uid
        return User(uid: uid, username: username, token: token)
    }

    public var preferredTransport: TransportKind {
        get {
            guard let raw = get(transportKey),
                  let kind = TransportKind(rawValue: raw) else {
                return .webSocket
            }
            return kind
        }
        set {
            set(transportKey, newValue.rawValue)
        }
    }

    // MARK: - Keychain primitives

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
