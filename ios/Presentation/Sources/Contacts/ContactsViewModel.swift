import Foundation
import Domain

struct FriendSection: Equatable {
    let title: String
    let friends: [Friend]
}

@MainActor
public final class ContactsViewModel {
    public private(set) var friends: [Friend] = []
    public private(set) var requests: [FriendRequest] = []
    private(set) var friendSections: [FriendSection] = []
    private(set) var sectionIndexTitles: [String] = []
    public var onChange: (() -> Void)?
    public var errorMessage: String?

    private let env: AppEnvironment
    private var friendsTask: Task<Void, Never>?
    private var requestsTask: Task<Void, Never>?

    public init(env: AppEnvironment) {
        self.env = env
    }

    public func start() {
        friendsTask?.cancel()
        requestsTask?.cancel()
        friendsTask = Task {
            for await list in env.friendUseCases.observeFriends() {
                friends = list
                rebuildSections()
                onChange?()
            }
        }
        requestsTask = Task {
            for await list in env.friendUseCases.observeRequests() {
                requests = list
                onChange?()
            }
        }
        Task {
            do { try await env.friendUseCases.refresh() }
            catch { errorMessage = error.localizedDescription; onChange?() }
        }
    }

    public func stop() {
        friendsTask?.cancel()
        requestsTask?.cancel()
        friendsTask = nil
        requestsTask = nil
    }

    public func addFriend(uid: String) async {
        do {
            try await env.friendUseCases.sendRequest(to: uid)
            try await env.friendUseCases.refresh()
        } catch {
            errorMessage = error.localizedDescription
            onChange?()
        }
    }

    public func respond(to uid: String, accept: Bool) async {
        do {
            try await env.friendUseCases.respond(to: uid, accept: accept)
            // Optimistic UI: drop the request immediately even if an observer yield is missed.
            requests.removeAll { $0.fromUID == uid }
            onChange?()
        } catch {
            errorMessage = error.localizedDescription
            onChange?()
        }
    }

    func friend(at indexPath: IndexPath) -> Friend? {
        // Section 0 is shortcuts; friend sections start at 1.
        let section = indexPath.section - 1
        guard section >= 0, section < friendSections.count else { return nil }
        let friends = friendSections[section].friends
        guard indexPath.row >= 0, indexPath.row < friends.count else { return nil }
        return friends[indexPath.row]
    }

    func sectionForIndexTitle(_ title: String) -> Int? {
        guard let idx = sectionIndexTitles.firstIndex(of: title) else { return nil }
        return idx + 1
    }

    private func rebuildSections() {
        var buckets: [String: [Friend]] = [:]
        for friend in friends {
            let key = ContactSort.sectionKey(for: friend.displayName)
            buckets[key, default: []].append(friend)
        }
        let keys = buckets.keys.sorted { ContactSort.compareSectionKeys($0, $1) }
        friendSections = keys.map { key in
            let sorted = (buckets[key] ?? []).sorted {
                ContactSort.compareNames($0.displayName, $1.displayName)
            }
            return FriendSection(title: key, friends: sorted)
        }
        sectionIndexTitles = keys
    }
}

enum ContactSort {
    static func sectionKey(for name: String) -> String {
        let latin = latinized(name)
        guard let first = latin.first else { return "#" }
        if first.isLetter {
            return String(first).uppercased()
        }
        return "#"
    }

    static func compareSectionKeys(_ a: String, _ b: String) -> Bool {
        if a == "#" { return false }
        if b == "#" { return true }
        return a < b
    }

    static func compareNames(_ a: String, _ b: String) -> Bool {
        latinized(a).localizedCaseInsensitiveCompare(latinized(b)) == .orderedAscending
    }

    /// Prefer Latin/pinyin-ish order so Chinese names group under A–Z.
    private static func latinized(_ string: String) -> String {
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        let mutable = NSMutableString(string: trimmed)
        CFStringTransform(mutable, nil, kCFStringTransformToLatin, false)
        CFStringTransform(mutable, nil, kCFStringTransformStripDiacritics, false)
        return (mutable as String).lowercased()
    }
}

extension Friend {
    var displayName: String {
        username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? uid : username
    }
}
