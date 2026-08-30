import Foundation

/// A loaded whisper model.
///
/// Everything runs on one dedicated queue. whisper's context holds decoding state that cannot be
/// touched from two places at once, and the alternative — a lock around every call — would still
/// leave the abort flag racing with whichever pass happened to be running.
actor WhisperContext {

    struct TranscriptionFailedError: Error {
        var message: String
    }

    /// One segment as whisper reported it. With `maxLen` 1 there is one token in each.
    struct Segment {
        var text: String
        var startMs: Int64
        var endMs: Int64
    }

    private var pointer: OpaquePointer?

    private init(pointer: OpaquePointer) {
        self.pointer = pointer
    }

    deinit {
        if let pointer { whisper_free(pointer) }
    }

    static func load(model: URL) throws -> WhisperContext {
        guard FileManager.default.fileExists(atPath: model.path) else {
            throw TranscriptionFailedError(message: "Model file missing: \(model.lastPathComponent)")
        }
        var params = whisper_context_default_params()
        // Metal, which is why this is worth doing on the phone at all. Unlike the Android build
        // there is no reason to stay on the CPU here: nothing in this port asks for the
        // cross-attention alignment that would force flash attention off.
        params.use_gpu = true
        guard let pointer = whisper_init_from_file_with_params(model.path, params) else {
            throw TranscriptionFailedError(message: "Could not load \(model.lastPathComponent)")
        }
        return WhisperContext(pointer: pointer)
    }

    /// Transcribes 16 kHz mono samples.
    ///
    /// `noTimestamps` is not a printing option — it changes what the decoder produces. Suppressed,
    /// whisper transcribes swearing it otherwise replaces with an innocent near-homophone, but it
    /// answers with one thirty-second block and cannot say when. With `maxLen` 1 it emits a
    /// segment per token, which is where word timings come from. Both run, and the results are
    /// lined up afterwards.
    func transcribe(
        samples: [Float],
        noTimestamps: Bool,
        maxLen: Int32? = nil,
        beamSize: Int32 = WhisperContext.defaultBeamSize
    ) throws -> [Segment] {
        guard let pointer else {
            throw TranscriptionFailedError(message: "Model already closed")
        }
        try Task.checkCancellation()

        var params = whisper_full_default_params(
            beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY
        )
        if beamSize > 1 { params.beam_search.beam_size = beamSize }
        params.print_realtime = false
        params.print_progress = false
        params.print_timestamps = false
        params.print_special = false
        params.translate = false
        params.n_threads = Int32(Self.threadCount)
        params.offset_ms = 0
        params.single_segment = false
        params.no_timestamps = noTimestamps
        params.max_len = maxLen ?? (noTimestamps ? 0 : 1)
        params.token_timestamps = !noTimestamps
        // Every call stands alone, and must. whisper otherwise primes a call with the text of the
        // one before it, and this context is reused across passes over the same clip: on Android
        // a short snippet decoded after three full passes came back with different words and its
        // timestamps piled onto one edge of the audio, which looked like the hardware being
        // unreliable and was this.
        params.no_context = true

        let language = Self.language
        let result: Int32 = language.withCString { pointer2 in
            params.language = pointer2
            return samples.withUnsafeBufferPointer { audio in
                whisper_full(pointer, params, audio.baseAddress, Int32(audio.count))
            }
        }
        guard result == 0 else {
            throw TranscriptionFailedError(message: "Recognition failed with code \(result)")
        }

        return (0..<whisper_full_n_segments(pointer)).map { index in
            Segment(
                text: String(cString: whisper_full_get_segment_text(pointer, index)),
                // whisper counts in hundredths of a second.
                startMs: Int64(whisper_full_get_segment_t0(pointer, index)) * 10,
                endMs: Int64(whisper_full_get_segment_t1(pointer, index)) * 10
            )
        }
    }

    func close() {
        if let pointer { whisper_free(pointer) }
        pointer = nil
    }

    private static let language = "tr"

    /// Matches whisper.cpp's own command line tool.
    static let defaultBeamSize: Int32 = 5

    /// Leaves a couple of cores alone. Recognition already makes the operator wait; taking every
    /// core with it would stall the video playing behind the progress counter too.
    private static var threadCount: Int {
        max(2, min(6, ProcessInfo.processInfo.activeProcessorCount - 2))
    }
}
