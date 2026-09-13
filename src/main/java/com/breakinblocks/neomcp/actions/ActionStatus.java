package com.breakinblocks.neomcp.actions;

/** Immutable snapshot of an action's state. */
public record ActionStatus(long id, State state, int elapsedTicks, String message) {
    public ActionStatus {
        if (id <= 0) {
            throw new IllegalArgumentException("Action id must be positive");
        }
        if (elapsedTicks < 0) {
            throw new IllegalArgumentException("Elapsed ticks cannot be negative");
        }
        if (state == null) {
            throw new NullPointerException("state");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Action message must not be blank");
        }
    }

    public enum State {
        STARTED,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }
}
