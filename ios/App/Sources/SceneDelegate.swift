import UIKit

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        self.window = window
        CompositionRoot.bootstrap(window: window)
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        _Concurrency.Task {
            await CompositionRoot.environment?.connection.ensureConnected()
        }
    }
}
