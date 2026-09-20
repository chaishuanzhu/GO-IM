import Foundation
import Domain

@MainActor
public final class SearchViewModel {
    public var query: String = ""
    public private(set) var hits: [SearchHit] = []
    public var onChange: (() -> Void)?
    public var errorMessage: String?

    private let env: AppEnvironment

    public init(env: AppEnvironment) {
        self.env = env
    }

    public func search() async {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else {
            hits = []
            onChange?()
            return
        }
        do {
            hits = try await env.searchMessages.execute(query: q)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
            hits = []
        }
        onChange?()
    }
}
