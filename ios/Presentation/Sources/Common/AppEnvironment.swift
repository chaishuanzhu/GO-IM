import Foundation
import Domain

@MainActor
public final class AppEnvironment {
    public let auth: AuthRepository
    public let connection: ConnectionRepository
    public let messages: MessageRepository
    public let conversations: ConversationRepository
    public let groups: GroupRepository
    public let friends: FriendRepository
    public let files: FileRepository
    public let search: SearchRepository

    public let loginUseCase: LoginUseCase
    public let logoutUseCase: LogoutUseCase
    public let sendText: SendTextMessageUseCase
    public let sendFile: SendFileMessageUseCase
    public let observeConversations: ObserveConversationsUseCase
    public let observeMessages: ObserveMessagesUseCase
    public let markRead: MarkReadUseCase
    public let createGroup: CreateGroupUseCase
    public let joinGroup: JoinGroupUseCase
    public let leaveGroup: LeaveGroupUseCase
    public let friendUseCases: FriendUseCases
    public let searchMessages: SearchMessagesUseCase
    public let switchTransport: SwitchTransportUseCase

    public private(set) var apiBaseURL: URL
    public var onAPIBaseURLChange: ((URL) -> Void)?

    public init(
        auth: AuthRepository,
        connection: ConnectionRepository,
        messages: MessageRepository,
        conversations: ConversationRepository,
        groups: GroupRepository,
        friends: FriendRepository,
        files: FileRepository,
        search: SearchRepository,
        apiBaseURL: URL
    ) {
        self.auth = auth
        self.connection = connection
        self.messages = messages
        self.conversations = conversations
        self.groups = groups
        self.friends = friends
        self.files = files
        self.search = search
        self.apiBaseURL = apiBaseURL

        loginUseCase = LoginUseCase(auth: auth, connection: connection)
        logoutUseCase = LogoutUseCase(auth: auth, connection: connection)
        sendText = SendTextMessageUseCase(messages: messages)
        sendFile = SendFileMessageUseCase(files: files, messages: messages)
        observeConversations = ObserveConversationsUseCase(conversations: conversations)
        observeMessages = ObserveMessagesUseCase(messages: messages)
        markRead = MarkReadUseCase(messages: messages)
        createGroup = CreateGroupUseCase(groups: groups)
        joinGroup = JoinGroupUseCase(groups: groups)
        leaveGroup = LeaveGroupUseCase(groups: groups)
        friendUseCases = FriendUseCases(friends: friends)
        searchMessages = SearchMessagesUseCase(search: search)
        switchTransport = SwitchTransportUseCase(auth: auth, connection: connection)
    }

    public func updateAPIBaseURL(_ url: URL) {
        apiBaseURL = url
        onAPIBaseURLChange?(url)
    }
}
