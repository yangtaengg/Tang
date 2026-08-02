import XCTest
@testable import SmsRelayMenuBar

final class WebSocketServerAuthenticationTests: XCTestCase {
    func testManualPairingCodeHasSixtyBitsOfInputEntropy() {
        let code = TokenFactory.randomManualCode()

        XCTAssertNotNil(code.range(of: #"^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$"#, options: .regularExpression))
        XCTAssertEqual(code.filter { $0 != "-" }.count, 12)
    }
}
