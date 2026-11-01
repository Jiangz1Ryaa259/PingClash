package dev.pingclash.diagnostics;

import dev.pingclash.diagnostics.Models.Code;
import dev.pingclash.diagnostics.Models.State;

public final class ProbeFailure extends Exception {
    public final State state;
    public final Code code;

    public ProbeFailure(State state, Code code) {
        super(code.name());
        this.state = state;
        this.code = code;
    }
}

