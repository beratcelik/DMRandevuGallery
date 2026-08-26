import Foundation

/// When a video reached us on Instagram, phrased the way the operator thinks about it: today and
/// yesterday are named, anything older gets its date (with the year only when it is not the
/// current one). Returns nil for a missing or unparseable timestamp, so callers can simply omit
/// the label.
func formatSentAt(_ isoTimestamp: String?) -> String? {
    guard let isoTimestamp, !isoTimestamp.isEmpty,
          let date = Formatters.parse(isoTimestamp) else { return nil }

    let calendar = Formatters.calendar
    let time = Formatters.time.string(from: date)

    if calendar.isDateInToday(date) { return "Bugün \(time)" }
    if calendar.isDateInYesterday(date) { return "Dün \(time)" }

    let sameYear = calendar.component(.year, from: date)
        == calendar.component(.year, from: Date())
    let day = sameYear
        ? Formatters.dayMonth.string(from: date)
        : Formatters.dayMonthYear.string(from: date)
    return "\(day) \(time)"
}

private enum Formatters {

    static let calendar = Calendar.current

    static let time = formatter("HH:mm")
    static let dayMonth = formatter("d MMMM")
    static let dayMonthYear = formatter("d MMMM yyyy")

    /// The server sends ISO-8601, sometimes with fractional seconds and sometimes without, so
    /// both shapes are tried rather than assuming one.
    static func parse(_ text: String) -> Date? {
        withFraction.date(from: text) ?? plain.date(from: text)
    }

    private static let withFraction: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let plain = ISO8601DateFormatter()

    private static func formatter(_ format: String) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "tr_TR")
        formatter.dateFormat = format
        return formatter
    }
}
