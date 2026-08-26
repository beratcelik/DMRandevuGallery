import CoreMedia

extension CMTime {

    /// This instant in milliseconds, or nil when it is not a real instant.
    var milliseconds: Int64? { scaled(by: 1_000) }

    /// This instant in microseconds, or nil when it is not a real instant.
    var microseconds: Int64? { scaled(by: 1_000_000) }

    /// The whole reason these exist rather than calling `CMTimeGetSeconds` at each site.
    ///
    /// A `CMTime` is routinely not a number: a player answers `.invalid` for its position until
    /// its item is ready, and `.indefinite` for the duration of a stream. `CMTimeGetSeconds` turns
    /// both into NaN, and converting NaN to an integer does not clamp or return zero — it traps
    /// and takes the app down. That is exactly how the first build crashed the moment the gallery
    /// started polling a player whose video had not loaded yet.
    private func scaled(by factor: Double) -> Int64? {
        guard isNumeric else { return nil }
        let value = CMTimeGetSeconds(self) * factor
        guard value.isFinite, value.magnitude < Double(Int64.max) else { return nil }
        return Int64(value)
    }
}
