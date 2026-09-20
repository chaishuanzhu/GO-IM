import Foundation
import Domain

@MainActor
public final class LoginViewModel {
    public var uid: String = ""
    public var username: String = ""
    public var password: String = ""
    public var isRegister = false
    public var isLoading = false
    public var errorMessage: String?
    public var onSuccess: ((User) -> Void)?

    private let env: AppEnvironment

    public init(env: AppEnvironment) {
        self.env = env
    }

    public func submit() async {
        guard !uid.isEmpty else {
            errorMessage = "UID required"
            return
        }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let user = try await env.loginUseCase.execute(
                uid: uid,
                username: username.isEmpty ? uid : username,
                password: password,
                register: isRegister
            )
            onSuccess?(user)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
