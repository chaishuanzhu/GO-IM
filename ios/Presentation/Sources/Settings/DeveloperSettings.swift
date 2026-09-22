import Foundation

/// Device-level developer toggles. All features default to **off**.
public enum DeveloperSettings {
    public static let flexEnabledKey = "goim.developer.flexEnabled"

    /// FLEX in-app explorer. Defaults to `false` when key is unset.
    public static var isFLEXEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: flexEnabledKey) }
        set { UserDefaults.standard.set(newValue, forKey: flexEnabledKey) }
    }
}
