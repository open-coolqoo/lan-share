import Foundation

/// The room-create retry schedule, kept apart from the network calls so the
/// first-launch recovery can be unit tested without a device.
///
/// The launch attempt runs before iOS has answered the local network prompt,
/// and that prompt gates the app's whole Wi-Fi path, so the request fails and
/// nothing else fetches a code. The schedule therefore has to outlast however
/// long the user takes to read the prompt.
enum CreateRetryPolicy {
    /// Delays between automatic room-create retries. The first ones are quick
    /// because their usual cause is the local network prompt; the interval then
    /// settles at ``maxDelaySeconds``.
    static let delaysSeconds: [Double] = [
        1, 2, 3, 5, 5, 5, 5, 10, 10, 10, 15, 15, 20, 30,
    ]

    /// The interval retries settle at once the ladder is used up: slow enough
    /// not to compete with the server's per-IP create/join limit, quick enough
    /// that a code still appears on its own.
    static let maxDelaySeconds: Double = 30

    /// Minimum gap between create attempts triggered by regaining the
    /// foreground, so quickly switching apps cannot hammer the server.
    static let foregroundMinGapMs: Int64 = 2_000

    /// Delay before the next automatic create retry.
    ///
    /// The ladder is finite but the schedule is not: past its end the interval
    /// stays at ``maxDelaySeconds`` instead of the retries stopping, because
    /// giving up while no code exists is what left a first launch with an empty
    /// Connect tab.
    static func delaySeconds(attempt: Int) -> Double {
        guard attempt >= 0, attempt < delaysSeconds.count else { return maxDelaySeconds }
        return delaysSeconds[attempt]
    }

    /// What regaining the foreground should do about a missing code.
    enum ForegroundAction: Equatable {
        case none
        case retryNow
        /// Wait out the rest of the minimum gap, then retry.
        case retryAfter(seconds: Double)
    }

    /// A foreground event only retries while a code is still missing, and never
    /// while a create or join is already running. Inside the minimum gap it
    /// schedules the remaining wait rather than returning: with no retry task
    /// pending that early return used to be a dead end, leaving the Connect tab
    /// empty with nothing scheduled to fill it.
    static func foregroundAction(
        hasCode: Bool,
        hasRoom: Bool,
        busy: Bool,
        millisecondsSinceLastAttempt: Int64,
    ) -> ForegroundAction {
        guard !hasCode, !hasRoom, !busy else { return .none }
        let remaining = foregroundMinGapMs - millisecondsSinceLastAttempt
        return remaining > 0 ? .retryAfter(seconds: Double(remaining) / 1000.0) : .retryNow
    }
}
