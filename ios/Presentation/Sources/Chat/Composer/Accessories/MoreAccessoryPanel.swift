import UIKit

@MainActor
final class MoreAccessoryPanel: UIView, ChatComposerAccessoryPanel {
    static var mode: ChatComposerAccessory { .more }

    private var actions: ChatComposerPanelActions

    init(actions: ChatComposerPanelActions) {
        self.actions = actions
        super.init(frame: .zero)
        setup()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    func prepareForDisplay() {}
    func prepareForHide() {}

    private func setup() {
        let videoBtn = makeAction(title: "视频", symbol: "video.fill", action: #selector(requestVideo))
        let fileBtn = makeAction(title: "文件", symbol: "doc.fill", action: #selector(requestFile))
        let stack = UIStackView(arrangedSubviews: [videoBtn, fileBtn])
        stack.axis = .horizontal
        stack.spacing = 28
        stack.distribution = .fillEqually
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)

        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: centerXAnchor),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -24),
        ])
        let centerY = stack.centerYAnchor.constraint(equalTo: safeAreaLayoutGuide.centerYAnchor)
        centerY.priority = UILayoutPriority(750)
        centerY.isActive = true
    }

    private func makeAction(title: String, symbol: String, action: Selector) -> UIButton {
        var cfg = UIButton.Configuration.gray()
        cfg.cornerStyle = .large
        cfg.image = UIImage(systemName: symbol)
        cfg.title = title
        cfg.imagePlacement = .top
        cfg.imagePadding = 10
        cfg.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 28, weight: .medium)
        cfg.contentInsets = NSDirectionalEdgeInsets(top: 18, leading: 22, bottom: 18, trailing: 22)
        let b = UIButton(configuration: cfg)
        b.addTarget(self, action: action, for: .touchUpInside)
        return b
    }

    @objc private func requestVideo() { actions.requestVideo?() }
    @objc private func requestFile() { actions.requestFile?() }
}
