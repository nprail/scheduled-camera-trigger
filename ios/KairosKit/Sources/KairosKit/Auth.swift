import Foundation
import CryptoKit

/// Challenge/response from docs/BLE_PROTOCOL.md §5.
public enum AuthProof {
    private static func mac(secret: Data, label: String, nonce: Data, clientNonce: Data) -> Data {
        var hmac = HMAC<SHA256>(key: SymmetricKey(data: secret))
        hmac.update(data: Data(label.utf8))
        hmac.update(data: nonce)
        hmac.update(data: clientNonce)
        return Data(hmac.finalize())
    }

    /// Proof the client sends in `auth`.
    public static func client(secret: Data, nonce: Data, clientNonce: Data) -> Data {
        mac(secret: secret, label: "kairos-auth-v1", nonce: nonce, clientNonce: clientNonce)
    }

    /// Proof the device returns, so the client knows it's the real device.
    public static func server(secret: Data, nonce: Data, clientNonce: Data) -> Data {
        mac(secret: secret, label: "kairos-auth-v1-srv", nonce: nonce, clientNonce: clientNonce)
    }

    public static func randomBytes(_ count: Int) -> Data {
        var generator = SystemRandomNumberGenerator()
        return Data((0 ..< count).map { _ in UInt8.random(in: 0 ... 255, using: &generator) })
    }
}
