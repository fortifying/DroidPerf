package com.droidperf.domain

/** GPU families with distinct sysfs monitoring interfaces. */
enum class GpuVendor(val displayName: String) {
    ADRENO("Qualcomm Adreno"),
    MALI("ARM Mali"),
    XCLIPSE("Samsung Xclipse"),
    POWERVR("Imagination PowerVR"),
    UNKNOWN("Unknown");

    companion object {
        /**
         * Detect the GPU vendor from the SoC / GL renderer string. We look at the
         * hardware and renderer strings because they are the only vendor-agnostic
         * identifiers available to a normal app without root.
         */
        fun detect(hardware: String?, renderer: String?, socModel: String?): GpuVendor {
            val hay = listOfNotNull(hardware, renderer, socModel)
                .joinToString(" ")
                .lowercase()
            return when {
                hay.contains("adreno") || hay.contains("kgsl") -> ADRENO
                hay.contains("xclipse") || hay.contains("samsung") -> XCLIPSE
                hay.contains("mali") || hay.contains("arm") -> MALI
                hay.contains("powervr") || hay.contains("imagination") -> POWERVR
                else -> UNKNOWN
            }
        }
    }
}
