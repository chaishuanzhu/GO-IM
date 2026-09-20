import UIKit

/// Shared Apple HIG-aligned appearance and helpers for GO-IM.
@MainActor
public enum GOIMAppearance {
    public static func apply() {
        let nav = UINavigationBarAppearance()
        nav.configureWithDefaultBackground()
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
        UINavigationBar.appearance().compactAppearance = nav
        UINavigationBar.appearance().prefersLargeTitles = true

        let tab = UITabBarAppearance()
        tab.configureWithDefaultBackground()
        UITabBar.appearance().standardAppearance = tab
        if #available(iOS 15.0, *) {
            UITabBar.appearance().scrollEdgeAppearance = tab
        }

        UITableView.appearance().sectionHeaderTopPadding = 8
    }
}

enum GOIMStyle {
    static let bubbleCorner: CGFloat = 18
    static let bubbleMaxWidthRatio: CGFloat = 0.72
    static let avatarSize: CGFloat = 40
    static let listAvatarSize: CGFloat = 48
    static let composerMinHeight: CGFloat = 36

    static var outgoingBubble: UIColor { .systemBlue }
    static var incomingBubble: UIColor { .secondarySystemFill }
    static var outgoingText: UIColor { .white }
    static var incomingText: UIColor { .label }
}

enum GOIMFormat {
    static func messageTime(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
        let cal = Calendar.current
        let formatter = DateFormatter()
        formatter.locale = .current
        if cal.isDateInToday(date) {
            formatter.dateFormat = "HH:mm"
            return formatter.string(from: date)
        }
        if cal.isDateInYesterday(date) {
            formatter.dateFormat = "HH:mm"
            return "昨天 \(formatter.string(from: date))"
        }
        if cal.isDate(date, equalTo: Date(), toGranularity: .year) {
            formatter.dateFormat = "M/d HH:mm"
        } else {
            formatter.dateFormat = "yyyy/M/d"
        }
        return formatter.string(from: date)
    }

    static func conversationTime(_ ms: Int64) -> String {
        guard ms > 0 else { return "" }
        let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
        let cal = Calendar.current
        let formatter = DateFormatter()
        formatter.locale = .current
        if cal.isDateInToday(date) {
            formatter.dateFormat = "HH:mm"
        } else if cal.isDateInYesterday(date) {
            return "昨天"
        } else if cal.isDate(date, equalTo: Date(), toGranularity: .year) {
            formatter.dateFormat = "M/d"
        } else {
            formatter.dateFormat = "yyyy/M/d"
        }
        return formatter.string(from: date)
    }

    static func monogram(from title: String) -> String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = trimmed.first else { return "?" }
        return String(first).uppercased()
    }
}

extension UIImage {
    static func goimAvatar(monogram: String, size: CGFloat, color: UIColor = .systemBlue) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size))
        return renderer.image { _ in
            let rect = CGRect(origin: .zero, size: CGSize(width: size, height: size))
            color.setFill()
            UIBezierPath(ovalIn: rect).fill()
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: size * 0.42, weight: .semibold),
                .foregroundColor: UIColor.white,
            ]
            let text = monogram as NSString
            let textSize = text.size(withAttributes: attrs)
            text.draw(
                at: CGPoint(x: (size - textSize.width) / 2, y: (size - textSize.height) / 2),
                withAttributes: attrs
            )
        }.withRenderingMode(.alwaysOriginal)
    }
}

extension UIViewController {
    func goimPresentActionSheet(
        _ alert: UIAlertController,
        sourceView: UIView?,
        sourceBarButton: UIBarButtonItem? = nil
    ) {
        if let pop = alert.popoverPresentationController {
            if let sourceBarButton {
                pop.barButtonItem = sourceBarButton
            } else if let sourceView {
                pop.sourceView = sourceView
                pop.sourceRect = sourceView.bounds
            } else {
                pop.sourceView = view
                pop.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
                pop.permittedArrowDirections = []
            }
        }
        present(alert, animated: true)
    }
}
