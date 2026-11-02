package com.orailnoor.droiddesk.display

import kotlin.math.roundToInt

enum class ProfileKind(val wireName: String) {
    PHONE("phone"), WIDE("wide"), MONITOR("monitor"),
}

enum class RenderPolicy(val wireName: String) {
    NATIVE("native"), CAP_1080("cap1080"), CAP_1440("cap1440"), LEGACY_PHONE("legacyPhone");

    companion object {
        fun fromWire(value: String?): RenderPolicy? = entries.firstOrNull { it.wireName == value }
    }
}

data class PixelSize(val width: Int, val height: Int)
data class PixelInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class DisplaySettings(
    val renderPolicy: RenderPolicy,
    val uiScalePercent: Int?,
    val inputMode: Int,
) {
    init {
        require(uiScalePercent == null || uiScalePercent in UI_SCALES) { "Invalid UI scale" }
        require(inputMode in 1..3) { "Invalid input mode" }
    }

    companion object {
        val UI_SCALES = setOf(100, 125, 150, 175, 200)
    }
}

data class DisplayProfile(
    val kind: ProfileKind,
    val settings: DisplaySettings,
    val dpi: Int,
    val cursorPx: Int,
    val dockPx: Int,
    val autoMaximize: Boolean,
)

object DisplayProfilePolicy {
    fun remainingInsets(
        view: PixelSize,
        window: PixelSize,
        viewLeft: Int,
        viewTop: Int,
        insets: PixelInsets,
    ): PixelInsets {
        require(view.width >= 0 && view.height >= 0 && window.width > 0 && window.height > 0)
        return PixelInsets(
            (insets.left - viewLeft.coerceAtLeast(0)).coerceIn(0, view.width),
            (insets.top - viewTop.coerceAtLeast(0)).coerceIn(0, view.height),
            (insets.right - (window.width - viewLeft - view.width).coerceAtLeast(0)).coerceIn(0, view.width),
            (insets.bottom - (window.height - viewTop - view.height).coerceAtLeast(0)).coerceIn(0, view.height),
        )
    }

    fun classify(
        isDefaultDisplay: Boolean,
        smallestWidthDp: Int,
        isDesktopContext: Boolean,
    ): ProfileKind = when {
        !isDefaultDisplay || isDesktopContext -> ProfileKind.MONITOR
        smallestWidthDp >= 600 -> ProfileKind.WIDE
        else -> ProfileKind.PHONE
    }

    fun defaults(kind: ProfileKind) = DisplaySettings(
        if (kind == ProfileKind.PHONE) RenderPolicy.LEGACY_PHONE else RenderPolicy.NATIVE,
        null,
        1,
    )

    fun phoneDefaultProfile() = DisplayProfile(ProfileKind.PHONE, defaults(ProfileKind.PHONE), 140, 32, 88, true)

    fun resolve(
        kind: ProfileKind,
        content: PixelSize,
        override: DisplaySettings?,
    ): DisplayProfile {
        require(content.width > 0 && content.height > 0) { "Window content must be measured" }
        val settings = override ?: defaults(kind)
        if (kind == ProfileKind.PHONE && settings.uiScalePercent == null) {
            return phoneDefaultProfile().copy(settings = settings)
        }
        val percent = settings.uiScalePercent ?: when {
            kind == ProfileKind.WIDE -> 150
            minOf(content.width, content.height) <= 1080 -> 100
            minOf(content.width, content.height) <= 1440 -> 150
            else -> 200
        }
        return DisplayProfile(
            kind,
            settings,
            (96 * percent / 100.0).roundToInt(),
            (24 * percent / 100.0).roundToInt(),
            (48 * percent / 100.0).roundToInt(),
            kind == ProfileKind.PHONE,
        )
    }

    fun renderSize(content: PixelSize, policy: RenderPolicy): PixelSize? {
        if (content.width <= 0 || content.height <= 0) return null
        if (policy == RenderPolicy.NATIVE) return content
        if (policy == RenderPolicy.LEGACY_PHONE) {
            return PixelSize(
                (content.width.toLong() * 100 / 150).toInt().coerceAtLeast(1),
                (content.height.toLong() * 100 / 150).toInt().coerceAtLeast(1),
            )
        }
        val longEdge = if (policy == RenderPolicy.CAP_1080) 1920 else 2560
        val shortEdge = if (policy == RenderPolicy.CAP_1080) 1080 else 1440
        val capWidth = if (content.width < content.height) shortEdge else longEdge
        val capHeight = if (content.width < content.height) longEdge else shortEdge
        if (content.width <= capWidth && content.height <= capHeight) return content
        return if (content.width.toLong() * capHeight > content.height.toLong() * capWidth) {
            PixelSize(capWidth, (content.height.toLong() * capWidth / content.width).toInt().coerceAtLeast(1))
        } else {
            PixelSize((content.width.toLong() * capHeight / content.height).toInt().coerceAtLeast(1), capHeight)
        }
    }
}
