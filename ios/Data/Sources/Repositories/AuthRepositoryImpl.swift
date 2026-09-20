import Foundation
import Moya
import Domain

public final class AuthRepositoryImpl: AuthRepository, @unchecked Sendable {
    private let provider: SharedMoyaProvider
    private let keychain: KeychainStore
    private let authPlugin: GOIMAuthPlugin

    public init(provider: SharedMoyaProvider, keychain: KeychainStore, authPlugin: GOIMAuthPlugin) {
        self.provider = provider
        self.keychain = keychain
        self.authPlugin = authPlugin
        if let user = keychain.loadSession() {
            authPlugin.uid = user.uid
            authPlugin.token = user.token
        }
    }

    public func login(uid: String, username: String, password: String) async throws -> User {
        let dto: LoginResponseDTO = try await provider.requestDecodable(
            .login(uid: uid, username: username, password: password)
        )
        return User(uid: dto.uid, username: dto.username, token: dto.token)
    }

    public func register(uid: String, username: String, password: String) async throws -> User {
        let dto: LoginResponseDTO = try await provider.requestDecodable(
            .register(uid: uid, username: username, password: password)
        )
        return User(uid: dto.uid, username: dto.username, token: dto.token)
    }

    public func currentUser() -> User? {
        keychain.loadSession()
    }

    public func logout() async {
        keychain.clearSession()
        authPlugin.uid = ""
        authPlugin.token = ""
    }

    public func saveSession(_ user: User) async {
        keychain.saveSession(uid: user.uid, username: user.username, token: user.token)
        authPlugin.uid = user.uid
        authPlugin.token = user.token
    }
}
