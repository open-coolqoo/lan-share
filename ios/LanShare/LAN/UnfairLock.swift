import Darwin

/// `NSLock` is unavailable from async contexts under Swift 6; a pthread mutex
/// gives the same critical section without that restriction. Every use here is
/// a short, non-reentrant state read/write with no suspension while held.
final class UnfairLock: @unchecked Sendable {
    private var mutex = pthread_mutex_t()

    init() {
        pthread_mutex_init(&mutex, nil)
    }

    deinit {
        pthread_mutex_destroy(&mutex)
    }

    func lock() {
        pthread_mutex_lock(&mutex)
    }

    func unlock() {
        pthread_mutex_unlock(&mutex)
    }
}
