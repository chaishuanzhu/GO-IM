import Foundation
import Network
import Domain

public enum TransportState: Sendable, Equatable {
    case setup
    case preparing
    case ready
    case failed(String)
    case cancelled
}

public protocol IMTransport: AnyObject, Sendable {
    var kind: TransportKind { get }
    func start() async throws
    func send(_ data: Data) async throws
    func stop()
    var bytes: AsyncStream<Data> { get }
    var state: AsyncStream<TransportState> { get }
}

public struct IMEndpoint: Sendable {
    public let host: String
    public let httpPort: UInt16
    public let tcpPort: UInt16
    public let useTLS: Bool
    public let token: String

    public init(host: String, httpPort: UInt16, tcpPort: UInt16, useTLS: Bool, token: String) {
        self.host = host
        self.httpPort = httpPort
        self.tcpPort = tcpPort
        self.useTLS = useTLS
        self.token = token
    }

    public var httpBaseURL: URL {
        let scheme = useTLS ? "https" : "http"
        return URL(string: "\(scheme)://\(host):\(httpPort)")!
    }
}

public final class NWTCPTransport: IMTransport, @unchecked Sendable {
    public let kind: TransportKind = .tcp
    private let endpoint: IMEndpoint
    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "goim.tcp")
    private var bytesContinuation: AsyncStream<Data>.Continuation?
    private var stateContinuation: AsyncStream<TransportState>.Continuation?

    public let bytes: AsyncStream<Data>
    public let state: AsyncStream<TransportState>

    public init(endpoint: IMEndpoint) {
        self.endpoint = endpoint
        var bytesCont: AsyncStream<Data>.Continuation?
        bytes = AsyncStream { bytesCont = $0 }
        bytesContinuation = bytesCont
        var stateCont: AsyncStream<TransportState>.Continuation?
        state = AsyncStream { stateCont = $0 }
        stateContinuation = stateCont
    }

    public func start() async throws {
        let host = NWEndpoint.Host(endpoint.host)
        let port = NWEndpoint.Port(rawValue: endpoint.tcpPort)!
        let conn = NWConnection(host: host, port: port, using: .tcp)
        connection = conn
        conn.stateUpdateHandler = { [weak self] newState in
            switch newState {
            case .ready:
                self?.stateContinuation?.yield(.ready)
                self?.receiveLoop()
            case .preparing:
                self?.stateContinuation?.yield(.preparing)
            case .failed(let err):
                self?.stateContinuation?.yield(.failed(err.localizedDescription))
            case .cancelled:
                self?.stateContinuation?.yield(.cancelled)
            default:
                break
            }
        }
        conn.start(queue: queue)
    }

    public func send(_ data: Data) async throws {
        guard let connection else { throw DomainError.invalidState("tcp not connected") }
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { cont.resume(throwing: error) } else { cont.resume() }
            })
        }
    }

    public func stop() {
        connection?.cancel()
        connection = nil
        stateContinuation?.yield(.cancelled)
    }

    private func receiveLoop() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            if let data, !data.isEmpty {
                self?.bytesContinuation?.yield(data)
            }
            if isComplete || error != nil {
                self?.stateContinuation?.yield(.failed(error?.localizedDescription ?? "closed"))
                return
            }
            self?.receiveLoop()
        }
    }
}

public final class NWWebSocketTransport: IMTransport, @unchecked Sendable {
    public let kind: TransportKind = .webSocket
    private let endpoint: IMEndpoint
    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "goim.ws")
    private var bytesContinuation: AsyncStream<Data>.Continuation?
    private var stateContinuation: AsyncStream<TransportState>.Continuation?

    public let bytes: AsyncStream<Data>
    public let state: AsyncStream<TransportState>

    public init(endpoint: IMEndpoint) {
        self.endpoint = endpoint
        var bytesCont: AsyncStream<Data>.Continuation?
        bytes = AsyncStream { bytesCont = $0 }
        bytesContinuation = bytesCont
        var stateCont: AsyncStream<TransportState>.Continuation?
        state = AsyncStream { stateCont = $0 }
        stateContinuation = stateCont
    }

    public func start() async throws {
        let scheme = endpoint.useTLS ? "wss" : "ws"
        let urlString = "\(scheme)://\(endpoint.host):\(endpoint.httpPort)/ws?token=\(endpoint.token.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? endpoint.token)"
        guard let url = URL(string: urlString) else {
            throw DomainError.invalidState("bad ws url")
        }

        let wsOptions = NWProtocolWebSocket.Options()
        wsOptions.autoReplyPing = true
        let params = endpoint.useTLS ? NWParameters.tls : NWParameters.tcp
        params.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)

        let conn = NWConnection(to: .url(url), using: params)
        connection = conn
        conn.stateUpdateHandler = { [weak self] newState in
            switch newState {
            case .ready:
                self?.stateContinuation?.yield(.ready)
                self?.receiveLoop()
            case .preparing:
                self?.stateContinuation?.yield(.preparing)
            case .failed(let err):
                self?.stateContinuation?.yield(.failed(err.localizedDescription))
            case .cancelled:
                self?.stateContinuation?.yield(.cancelled)
            default:
                break
            }
        }
        conn.start(queue: queue)
    }

    public func send(_ data: Data) async throws {
        guard let connection else { throw DomainError.invalidState("ws not connected") }
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "im", metadata: [metadata])
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.send(content: data, contentContext: context, completion: .contentProcessed { error in
                if let error { cont.resume(throwing: error) } else { cont.resume() }
            })
        }
    }

    public func stop() {
        connection?.cancel()
        connection = nil
        stateContinuation?.yield(.cancelled)
    }

    private func receiveLoop() {
        connection?.receiveMessage { [weak self] data, _, _, error in
            if let data, !data.isEmpty {
                self?.bytesContinuation?.yield(data)
            }
            if let error {
                self?.stateContinuation?.yield(.failed(error.localizedDescription))
                return
            }
            // WebSocket: isComplete=true means this message finished, NOT that the
            // connection closed. Always re-arm receive for the next message.
            self?.receiveLoop()
        }
    }
}
