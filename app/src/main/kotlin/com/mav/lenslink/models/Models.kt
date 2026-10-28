/*
 * Models.kt — transport + connection state shared between UI and service.
 */
package com.mav.lenslink.models

/** How the phone talks to the Canon body. */
enum class Transport {
    /** USB OTG cable through UsbManager. */
    USB,

    /** PTP/IP over the camera's own Wi-Fi hotspot. */
    WIFI,
}

/** Live connection lifecycle. */
sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(
        val transport: Transport,
        /** Retry counter shown in the UI ("attempt 2 of 3"). */
        val attempt: Int = 1,
    ) : ConnState
    data class Connected(
        val transport: Transport,
        val info: CameraInfo,
    ) : ConnState

    data class Failed(
        val transport: Transport,
        val message: String,
    ) : ConnState

    /**
     * A previously healthy session that liveness probing determined is gone
     * (camera Wi-Fi asleep/timed out). Renders as "Not connected", keeps the
     * last endpoint handy so the UI can offer a one-tap reconnect.
     */
    data class Lost(
        val transport: Transport,
        val message: String,
    ) : ConnState
}

/**
 * Snapshot of the connection dashboard: model, battery, storage, library.
 */
data class CameraInfo(
    val model: String,
    val summary: String,
    val batteryPercent: Int?,
    val batteryText: String,
    /** SD card size in GiB, null when unknown. */
    val storageTotalGb: Double?,
    /** SD card free space in GiB, null when unknown. */
    val storageFreeGb: Double?,
    /** Remaining shots guessed by the camera, null when unknown. */
    val storageFreeImages: Long?,
    val bridgeVersion: String,
)

/** A single camera file (photo/movie) as reported by the camlib. */
data class CameraFile(
    /** e.g. /store_00010001/DCIM/100CANON/ */
    val folder: String,
    /** e.g. IMG_0001.JPG */
    val name: String,
) {
    val fullPath: String get() = folder + name

    /** .JPG / .CR3 / .MP4 etc, uppercase, without the dot. */
    val extension: String
        get() = name.substringAfterLast('.', "").uppercase()

    val isMovie: Boolean get() = extension in setOf("MP4", "MOV")
    val isRaw: Boolean get() = extension in setOf("CR2", "CR3", "RAW")
}

/** Companion UI model for one configurable camera property. */
data class PropertyCard(
    /** Canonical config name, e.g. "iso" (see the ptp2 config table). */
    val configName: String,
    val title: String,
    /** Which group it renders in. */
    val group: PropertyGroup,
)

/** Logical grouping for the control panel. */
enum class PropertyGroup {
    EXPOSURE,
    COLOR,
    DRIVE,
}
