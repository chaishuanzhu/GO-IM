import Foundation
#if canImport(FLEX)
import FLEX
#endif

/// Thin wrapper around FLEX. Preference defaults to **off**.
enum FLEXSupport {
    static let preferenceKey = "goim.developer.flexEnabled"

    static var isEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: preferenceKey) }
        set {
            UserDefaults.standard.set(newValue, forKey: preferenceKey)
            apply(enabled: newValue)
        }
    }

    /// Apply saved preference at launch (no-op when still default `false`).
    static func applySavedPreference() {
        apply(enabled: isEnabled)
    }

    static func apply(enabled: Bool) {
        #if canImport(FLEX)
        if enabled {
            FLEXManager.shared.showExplorer()
        } else {
            FLEXManager.shared.hideExplorer()
        }
        #endif
    }

    static func showExplorer() {
        #if canImport(FLEX)
        FLEXManager.shared.showExplorer()
        #endif
    }
}
