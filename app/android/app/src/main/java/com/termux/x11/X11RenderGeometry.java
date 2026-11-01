package com.termux.x11;

public final class X11RenderGeometry {
    private X11RenderGeometry() {}

    public static int alignedWidth(int width, int height) {
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("Render dimensions must be positive");
        int aligned = Math.max(8, width - width % 8);
        // libxcvt preserves this common mode despite its horizontal granularity.
        return aligned == 1360 && height == 768 ? 1366 : aligned;
    }
}
