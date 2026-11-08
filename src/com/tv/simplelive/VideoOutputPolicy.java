package com.tv.simplelive;

import android.os.Build;
import java.util.List;

/** Huawei's texture output can fail in AWindowHandler, including during stop. */
final class VideoOutputPolicy {
    static void configure(List<String> options) {
        if ("HUAWEI".equalsIgnoreCase(Build.MANUFACTURER) || "HUAWEI".equalsIgnoreCase(Build.BRAND)) {
            // Prefer direct MediaCodec output; software frames use the buffer output.
            options.add("--vout=android-opaque,android-display");
        }
    }
}
