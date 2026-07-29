package com.hry.camera.usbcamerademo;

/** Defines the user-visible next step after persisting a moment save decision. */
final class AtlasPostDecisionFlow {
    enum Action {
        RETRY_DECISION,
        FINISH
    }

    private AtlasPostDecisionFlow() {
    }

    static Action afterSave(boolean saved) {
        return saved ? Action.FINISH : Action.RETRY_DECISION;
    }
}
