/*
 * CameraTypes.kt — lightweight data classes mirroring the raw strings the
 * JNI control layer returns. Kept dependency-free so both the library and
 * the app consume them directly.
 */
package com.mav.cameranativebridge

/**
 * Result of [NativeCameraInterface.getConfig] / `nativeGetConfig`:
 * `[label, type, readonly, current, choice0, choice1, …]`
 */
data class CameraConfig(
    /** Canonical config name, e.g. "iso". */
    val name: String,
    /** Human-readable widget label, e.g. "ISO Speed Rating". */
    val label: String,
    /** gphoto2 CameraWidgetType ordinal (WINDOW=0, SECTION=1, TEXT=2, …). */
    val type: Int,
    /** Whether the camera reports this property read-only right now. */
    val readonly: Boolean,
    /** Current value, always in the same string form as [choices]. */
    val current: String,
    /** Valid choices for MENU/RADIO widgets; empty otherwise. */
    val choices: List<String>,
) {
    internal companion object {
        /** Widget type ordinals (gphoto2/gphoto2-widget.h). */
        const val TYPE_WINDOW = 0
        const val TYPE_SECTION = 1
        const val TYPE_TEXT = 2
        const val TYPE_RANGE = 3
        const val TYPE_TOGGLE = 4
        const val TYPE_RADIO = 5
        const val TYPE_MENU = 6
        const val TYPE_BUTTON = 7
        const val TYPE_DATE = 8

        /**
         * Null-tolerant by design: JNI unfilled slots historically surfaced as
         * null elements (r8-tracked NPE on `CharSequence.length()`), and the
         * native side may still return null when the widget vanished.
         */
        fun from(name: String, raw: Array<String?>?): CameraConfig? {
            if (raw == null || raw.size < 5) return null
            val type = raw[1]?.toIntOrNull() ?: return null
            return CameraConfig(
                name = name,
                label = raw[0] ?: "",
                type = type,
                readonly = raw[2] == "1",
                current = raw[3] ?: "",
                choices = raw.drop(4).filterNotNull().filter { it.isNotEmpty() },
            )
        }
    }

    val isChoiceType: Boolean get() = type == TYPE_RADIO || type == TYPE_MENU
    val isToggle: Boolean get() = type == TYPE_TOGGLE
    val isRange: Boolean get() = type == TYPE_RANGE
    val isText: Boolean get() = type == TYPE_TEXT
    val isDate: Boolean get() = type == TYPE_DATE
}

/**
 * Parsed `gp_camera_capture` result `"<folder>\n<name>"`.
 * Folder may be empty when the camlib does not report the store path.
 */
data class CameraCaptureResult(
    val folder: String,
    val name: String,
) {
    internal companion object {
        fun from(raw: String): CameraCaptureResult? {
            val newline = raw.indexOf('\n')
            if (newline < 0) return null
            return CameraCaptureResult(
                folder = raw.substring(0, newline),
                name = raw.substring(newline + 1),
            )
        }
    }
}

/**
 * Parsed storage (SD card) line
 * `"basedir\tlabel\tdescription\tcapacityKB\tfreeKB\tfreeImages"`.
 */
data class CameraStorage(
    val baseDir: String,
    val label: String,
    val description: String,
    /** Capacity in KiB, 0 when the camera did not report it. */
    val capacityKb: Long,
    /** Free space in KiB, 0 when the camera did not report it. */
    val freeKb: Long,
    /** Remaining shots guessed by the camera, 0 when unknown. */
    val freeImages: Long,
) {
    /** 0.0–1.0 usage fraction, or NaN when capacity unknown. */
    val usageFraction: Float
        get() = if (capacityKb > 0) (capacityKb - freeKb) / capacityKb.toFloat() else Float.NaN

    internal companion object {
        fun from(raw: String): CameraStorage {
            val f = raw.split('\t')
            fun at(i: Int) = f.getOrNull(i).orEmpty()
            return CameraStorage(
                baseDir = at(0),
                label = at(1),
                description = at(2),
                capacityKb = at(3).toLongOrNull() ?: 0L,
                freeKb = at(4).toLongOrNull() ?: 0L,
                freeImages = at(5).toLongOrNull() ?: 0L,
            )
        }
    }
}

/**
 * Per-file metadata parsed from `"sizeBytes\tmtimeSec\twidth\theight\tMIME"`.
 * Zero / "unknown" fields mean the camera did not report them.
 */
data class CameraFileInfo(
    /** Exact file size in bytes, or -1 when unknown. */
    val sizeBytes: Long,
    /** Last-modified epoch seconds, 0 when unknown. */
    val mtimeSec: Long,
    /** Pixel width, 0 when the camera did not report it. */
    val widthPx: Int,
    /** Pixel height, 0 when the camera did not report it. */
    val heightPx: Int,
    /** MIME type, e.g. "image/jpeg"; "unknown" when absent. */
    val mime: String,
) {
    companion object {
        val UNKNOWN = CameraFileInfo(-1L, 0L, 0, 0, "unknown")

        fun from(raw: String?): CameraFileInfo {
            if (raw.isNullOrBlank()) return UNKNOWN
            val f = raw.split('\t')
            return CameraFileInfo(
                sizeBytes = f.getOrNull(0)?.toLongOrNull() ?: -1L,
                mtimeSec = f.getOrNull(1)?.toLongOrNull() ?: 0L,
                widthPx = f.getOrNull(2)?.toIntOrNull() ?: 0,
                heightPx = f.getOrNull(3)?.toIntOrNull() ?: 0,
                mime = f.getOrNull(4).orEmpty().ifEmpty { "unknown" },
            )
        }
    }

    /** true when the size came from the camera rather than "unknown". */
    val hasSize: Boolean get() = sizeBytes >= 0
}
