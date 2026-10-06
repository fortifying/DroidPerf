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
         * Detect the GPU vendor from the SoC / GL renderer / vendor strings.
         */
        fun detect(
            hardware: String?,
            renderer: String?,
            socModel: String?,
            socManufacturer: String? = null,
            glVendor: String? = null,
        ): GpuVendor {
            val hay = listOfNotNull(hardware, renderer, socModel, socManufacturer, glVendor)
                .joinToString(" ")
                .lowercase()
            return when {
                hay.contains("adreno") || hay.contains("kgsl") ||
                    hay.contains("qualcomm") || hay.contains("qcom") ||
                    hay.contains("sm8") || hay.contains("sm7") || hay.contains("sm6") || hay.contains("sm4") -> ADRENO

                hay.contains("xclipse") || hay.contains("sgpu") -> XCLIPSE

                hay.contains("mali") || hay.contains("valhall") || hay.contains("bifrost") ||
                    hay.contains("midgard") || hay.contains("immortalis") ||
                    hay.contains("mt6") || hay.contains("mt8") || hay.contains("mtk") ||
                    hay.contains("mediatek") || hay.contains("dimensity") || hay.contains("helio") ||
                    hay.contains("arm") -> MALI

                hay.contains("powervr") || hay.contains("rogue") || hay.contains("imagination") -> POWERVR

                hay.contains("samsung") -> XCLIPSE

                else -> UNKNOWN
            }
        }
    }
}
