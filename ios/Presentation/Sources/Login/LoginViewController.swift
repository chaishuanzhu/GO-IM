import UIKit

@MainActor
public final class LoginViewController: UIViewController, UITextFieldDelegate {
    private let env: AppEnvironment
    private let viewModel: LoginViewModel
    public var onLoggedIn: (() -> Void)?

    private let scrollView = UIScrollView()
    private let contentStack = UIStackView()
    private let brandLabel = UILabel()
    private let subtitleLabel = UILabel()
    private let uidField = UITextField()
    private let usernameField = UITextField()
    private let passwordField = UITextField()
    private let registerSwitch = UISwitch()
    private let submitButton = UIButton(type: .system)
    private let errorLabel = UILabel()
    private let activity = UIActivityIndicatorView(style: .medium)

    public init(env: AppEnvironment) {
        self.env = env
        viewModel = LoginViewModel(env: env)
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        navigationItem.largeTitleDisplayMode = .never
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            image: UIImage(systemName: "gearshape"),
            style: .plain,
            target: self,
            action: #selector(openServerSettings)
        )
        navigationItem.rightBarButtonItem?.accessibilityLabel = "服务器设置"
        configureUI()
        viewModel.onSuccess = { [weak self] _ in
            self?.onLoggedIn?()
        }
    }

    @objc private func openServerSettings() {
        view.endEditing(true)
        let settings = ServerSettingsViewController(env: env)
        navigationController?.pushViewController(settings, animated: true)
    }

    private func configureUI() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.keyboardDismissMode = .interactive
        view.addSubview(scrollView)

        brandLabel.text = "GO-IM"
        brandLabel.font = .systemFont(ofSize: 34, weight: .bold)
        brandLabel.textAlignment = .center
        brandLabel.adjustsFontForContentSizeCategory = true

        subtitleLabel.text = "登录后开始聊天"
        subtitleLabel.font = .preferredFont(forTextStyle: .subheadline)
        subtitleLabel.textColor = .secondaryLabel
        subtitleLabel.textAlignment = .center
        subtitleLabel.adjustsFontForContentSizeCategory = true

        styleField(uidField, placeholder: "用户 ID", contentType: .username)
        styleField(usernameField, placeholder: "用户名（可选）", contentType: .nickname)
        styleField(passwordField, placeholder: "密码", contentType: .password)
        passwordField.isSecureTextEntry = true
        uidField.delegate = self
        usernameField.delegate = self
        passwordField.delegate = self
        passwordField.returnKeyType = .go

        let registerLabel = UILabel()
        registerLabel.text = "新用户注册"
        registerLabel.font = .preferredFont(forTextStyle: .body)
        registerLabel.adjustsFontForContentSizeCategory = true
        let registerRow = UIStackView(arrangedSubviews: [registerLabel, UIView(), registerSwitch])
        registerRow.axis = .horizontal
        registerRow.alignment = .center
        registerRow.isLayoutMarginsRelativeArrangement = true
        registerRow.layoutMargins = UIEdgeInsets(top: 8, left: 4, bottom: 8, right: 4)

        var config = UIButton.Configuration.filled()
        config.cornerStyle = .large
        config.title = "继续"
        config.baseBackgroundColor = .systemBlue
        config.buttonSize = .large
        submitButton.configuration = config
        submitButton.addTarget(self, action: #selector(submitTapped), for: .touchUpInside)

        errorLabel.textColor = .systemRed
        errorLabel.numberOfLines = 0
        errorLabel.font = .preferredFont(forTextStyle: .footnote)
        errorLabel.textAlignment = .center
        activity.hidesWhenStopped = true

        let card = UIStackView(arrangedSubviews: [
            uidField, usernameField, passwordField, registerRow,
        ])
        card.axis = .vertical
        card.spacing = 12
        card.isLayoutMarginsRelativeArrangement = true
        card.layoutMargins = UIEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)
        card.backgroundColor = .secondarySystemGroupedBackground
        card.layer.cornerRadius = 12
        card.layer.cornerCurve = .continuous

        contentStack.axis = .vertical
        contentStack.spacing = 20
        contentStack.translatesAutoresizingMaskIntoConstraints = false
        contentStack.addArrangedSubview(brandLabel)
        contentStack.addArrangedSubview(subtitleLabel)
        contentStack.setCustomSpacing(28, after: subtitleLabel)
        contentStack.addArrangedSubview(card)
        contentStack.addArrangedSubview(submitButton)
        contentStack.addArrangedSubview(activity)
        contentStack.addArrangedSubview(errorLabel)
        scrollView.addSubview(contentStack)

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),

            contentStack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 48),
            contentStack.leadingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.leadingAnchor, constant: 24),
            contentStack.trailingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.trailingAnchor, constant: -24),
            contentStack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -24),
            contentStack.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor, constant: -48),

            uidField.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
            usernameField.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
            passwordField.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
            submitButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 50),
        ])
    }

    private func styleField(_ field: UITextField, placeholder: String, contentType: UITextContentType) {
        field.placeholder = placeholder
        field.borderStyle = .roundedRect
        field.autocapitalizationType = .none
        field.autocorrectionType = .no
        field.textContentType = contentType
        field.font = .preferredFont(forTextStyle: .body)
        field.adjustsFontForContentSizeCategory = true
        field.clearButtonMode = .whileEditing
    }

    public func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        if textField === uidField {
            usernameField.becomeFirstResponder()
        } else if textField === usernameField {
            passwordField.becomeFirstResponder()
        } else {
            submitTapped()
        }
        return true
    }

    @objc private func submitTapped() {
        view.endEditing(true)
        viewModel.uid = uidField.text ?? ""
        viewModel.username = usernameField.text ?? ""
        viewModel.password = passwordField.text ?? ""
        viewModel.isRegister = registerSwitch.isOn
        activity.startAnimating()
        submitButton.isEnabled = false
        Task {
            await viewModel.submit()
            activity.stopAnimating()
            submitButton.isEnabled = true
            errorLabel.text = viewModel.errorMessage
            if viewModel.errorMessage != nil {
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            }
        }
    }
}
