import Foundation

/// Stretches the operator marked by hand, for the videos the recognizer cannot manage.
///
/// There are clips it will not place: sixty-five seconds of shouting over music came back as
/// eleven words, and three of the four swear words in it fell in a stretch no pass heard at all.
/// On those the operator can hear perfectly well where the swearing is, and this is how they say
/// so.
///
/// Kept per conversation and media index rather than by url. The server re-signs links whenever it
/// is asked, so a url is not the same tomorrow — or after a refresh — and marks kept against one
/// would quietly detach from the video they belong to.
struct ManualMarks {

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Every marked stretch for one video, earliest first.
    func forMedia(conversationKey: String, mediaIndex: Int) -> [CensorWindow] {
        parse(defaults.string(forKey: key(conversationKey, mediaIndex)))
    }

    func add(conversationKey: String, mediaIndex: Int, window: CensorWindow) {
        guard window.endUs > window.startUs else { return }
        let merged = merge(forMedia(conversationKey: conversationKey, mediaIndex: mediaIndex)
            + [window])
        write(conversationKey, mediaIndex, merged)
    }

    /// Removes whichever mark covers `atUs`, if any.
    func removeAt(conversationKey: String, mediaIndex: Int, atUs: Int64) {
        let remaining = forMedia(conversationKey: conversationKey, mediaIndex: mediaIndex)
            .filter { !(atUs >= $0.startUs && atUs <= $0.endUs) }
        write(conversationKey, mediaIndex, remaining)
    }

    func clear(conversationKey: String, mediaIndex: Int) {
        defaults.removeObject(forKey: key(conversationKey, mediaIndex))
    }

    private func write(_ conversationKey: String, _ mediaIndex: Int, _ windows: [CensorWindow]) {
        let encoded = windows.map { "\($0.startUs),\($0.endUs)" }.joined(separator: ";")
        if encoded.isEmpty {
            defaults.removeObject(forKey: key(conversationKey, mediaIndex))
        } else {
            defaults.set(encoded, forKey: key(conversationKey, mediaIndex))
        }
    }

    /// Overlapping or touching marks become one, so two presses over one word are one beep.
    private func merge(_ windows: [CensorWindow]) -> [CensorWindow] {
        var merged: [CensorWindow] = []
        for window in windows.sorted(by: { $0.startUs < $1.startUs }) {
            if let last = merged.last, window.startUs <= last.endUs {
                merged[merged.count - 1] = CensorWindow(
                    startUs: last.startUs,
                    endUs: max(last.endUs, window.endUs)
                )
            } else {
                merged.append(window)
            }
        }
        return merged
    }

    private func parse(_ encoded: String?) -> [CensorWindow] {
        encoded?.split(separator: ";").compactMap { entry in
            let parts = entry.split(separator: ",")
            guard parts.count == 2,
                  let start = Int64(parts[0]),
                  let end = Int64(parts[1]),
                  end > start
            else { return nil }
            return CensorWindow(startUs: start, endUs: end)
        } ?? []
    }

    private func key(_ conversationKey: String, _ mediaIndex: Int) -> String {
        "marks_\(conversationKey)_\(mediaIndex)"
    }
}
