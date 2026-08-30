// What Swift can see of whisper.cpp.
//
// The library is C, built as ios/Frameworks/whisper.xcframework by
// Scripts/build-whisper-xcframework.sh. Nothing else in the app touches it directly — everything
// goes through WhisperContext.swift, which owns the lifetime and the threading.
#import "whisper.h"
