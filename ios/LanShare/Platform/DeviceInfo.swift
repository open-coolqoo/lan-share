import Foundation
import UIKit

public enum DeviceInfo {
    /// Hardware model identifier such as `iPhone15,3`.
    public static var modelIdentifier: String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let mirror = Mirror(reflecting: systemInfo.machine)
        return mirror.children.reduce(into: "") { result, element in
            guard let value = element.value as? Int8, value != 0 else { return }
            result.append(Character(UnicodeScalar(UInt8(value))))
        }
    }

    /// e.g. `iOS iPhone15,3`.
    public static var deviceLabel: String {
        "iOS \(modelIdentifier)"
    }
}
