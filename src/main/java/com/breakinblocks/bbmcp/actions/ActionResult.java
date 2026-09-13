package com.breakinblocks.bbmcp.actions;

/** Immutable result returned when an action is accepted or executed. */
public record ActionResult(long id, ActionStatus.State state, String message) {
    public ActionResult {
        if (id <= 0) {
            throw new IllegalArgumentException("Action id must be positive");
        }
        if (state == null) {
            throw new NullPointerException("state");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Action message must not be blank");
        }
    }
}
