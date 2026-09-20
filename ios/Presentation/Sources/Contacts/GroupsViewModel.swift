import Foundation
import Domain

@MainActor
public final class GroupsViewModel {
    public private(set) var groups: [Group] = []
    public var onChange: (() -> Void)?
    public var errorMessage: String?

    private let env: AppEnvironment
    private var groupsTask: Task<Void, Never>?

    public init(env: AppEnvironment) {
        self.env = env
    }

    public func start() {
        groupsTask?.cancel()
        groupsTask = Task {
            for await list in env.groups.observeGroups() {
                groups = list.sorted {
                    $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
                }
                onChange?()
            }
        }
        Task {
            do {
                _ = try await env.groups.list()
                try? await env.conversations.syncGroupTitles()
            } catch {
                errorMessage = error.localizedDescription
                onChange?()
            }
        }
    }

    public func stop() {
        groupsTask?.cancel()
        groupsTask = nil
    }

    public func createGroup(name: String, members: [String] = []) async -> Group? {
        do {
            let group = try await env.createGroup.execute(name: name, members: members)
            return group
        } catch {
            errorMessage = error.localizedDescription
            onChange?()
            return nil
        }
    }

    public func loadFriends() async -> [Friend] {
        try? await env.friendUseCases.refresh()
        return (try? await env.friends.listFriends()) ?? []
    }

    public func joinGroup(groupId: String) async -> Bool {
        do {
            try await env.joinGroup.execute(groupId: groupId)
            _ = try? await env.groups.list()
            try? await env.conversations.syncGroupTitles()
            return true
        } catch {
            errorMessage = error.localizedDescription
            onChange?()
            return false
        }
    }

    public func leaveGroup(at index: Int) async {
        guard index >= 0, index < groups.count else { return }
        let groupId = groups[index].groupId
        do {
            try await env.leaveGroup.execute(groupId: groupId)
            _ = try? await env.groups.list()
        } catch {
            errorMessage = error.localizedDescription
            onChange?()
        }
    }
}
