import Foundation
import Security

/// Pairing keys, one per device, kept in the iOS Keychain.
public enum SecretStore {
    private static let service = "com.noahprail.camtrigger.pairing"

    private static func query(_ deviceId: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceId,
        ]
    }

    public static func load(deviceId: String) -> Data? {
        var q = query(deviceId)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess else { return nil }
        return item as? Data
    }

    public static func save(_ secret: Data, deviceId: String) throws {
        var q = query(deviceId)
        q[kSecValueData as String] = secret
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        var status = SecItemAdd(q as CFDictionary, nil)
        if status == errSecDuplicateItem {
            status = SecItemUpdate(
                query(deviceId) as CFDictionary,
                [kSecValueData as String: secret] as CFDictionary
            )
        }
        guard status == errSecSuccess else {
            throw DeviceError(code: "keychain", message: "Could not save the pairing key (Keychain error \(status)).")
        }
    }

    public static func delete(deviceId: String) {
        SecItemDelete(query(deviceId) as CFDictionary)
    }
}
