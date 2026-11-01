package com.termux.x11;

import java.util.function.BooleanSupplier;

final class RendererActionGuard {
    private RendererActionGuard() {}

    static boolean run(BooleanSupplier isCurrent, Runnable action) {
        if (!isCurrent.getAsBoolean())
            return false;
        action.run();
        return true;
    }
}
