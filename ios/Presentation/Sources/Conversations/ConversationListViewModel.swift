import Foundation
import Domain

@MainActor
public final class ConversationListViewModel {
    public private(set) var conversations: [Conversation] = []
    public var onChange: (() -> Void)?

    private let env: AppEnvironment
    private var observeTask: Task<Void, Never>?

    public init(env: AppEnvironment) {
        self.env = env
    }

    public func start() {
        observeTask?.cancel()
        observeTask = Task {
            for await list in env.observeConversations.execute() {
                conversations = list
                onChange?()
            }
        }
        Task {
            _ = try? await env.groups.list()
            try? await env.conversations.syncGroupTitles()
        }
    }

    public func stop() {
        observeTask?.cancel()
        observeTask = nil
    }

    public func deleteConversation(at index: Int) async {
        guard conversations.indices.contains(index) else { return }
        let id = conversations[index].id
        try? await env.conversations.deleteConversation(id: id)
    }
}
