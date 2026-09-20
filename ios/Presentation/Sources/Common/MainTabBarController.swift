import UIKit

@MainActor
public final class MainTabBarController: UITabBarController {
    private let env: AppEnvironment

    public init(env: AppEnvironment) {
        self.env = env
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    public override func viewDidLoad() {
        super.viewDidLoad()

        let conversations = UINavigationController(
            rootViewController: ConversationListViewController(env: env)
        )
        conversations.tabBarItem = UITabBarItem(
            title: "聊天",
            image: UIImage(systemName: "bubble.left.and.bubble.right"),
            selectedImage: UIImage(systemName: "bubble.left.and.bubble.right.fill")
        )

        let contacts = UINavigationController(
            rootViewController: ContactsViewController(env: env)
        )
        contacts.tabBarItem = UITabBarItem(
            title: "通讯录",
            image: UIImage(systemName: "person.2"),
            selectedImage: UIImage(systemName: "person.2.fill")
        )

        let search = UINavigationController(
            rootViewController: SearchViewController(env: env)
        )
        search.tabBarItem = UITabBarItem(
            title: "搜索",
            image: UIImage(systemName: "magnifyingglass"),
            tag: 2
        )

        let settings = UINavigationController(
            rootViewController: SettingsViewController(env: env)
        )
        settings.tabBarItem = UITabBarItem(
            title: "设置",
            image: UIImage(systemName: "gearshape"),
            selectedImage: UIImage(systemName: "gearshape.fill")
        )

        viewControllers = [conversations, contacts, search, settings]
        delegate = self
    }
}

extension MainTabBarController: UITabBarControllerDelegate {
    public func tabBarController(_ tabBarController: UITabBarController, shouldSelect viewController: UIViewController) -> Bool {
        if tabBarController.selectedViewController !== viewController {
            UISelectionFeedbackGenerator().selectionChanged()
        }
        return true
    }
}
