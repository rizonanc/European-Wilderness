package com.github.xandergos.terraindiffusionmc.worldgen.settings;

import java.util.Objects;

public final class WorldSessionLifetime {
    private Object owner;

    public synchronized void bind(Object server) {
        this.owner = Objects.requireNonNull(server);
    }

    public synchronized boolean owns(Object server) {
        return this.owner != null && this.owner == server;
    }

    public synchronized boolean release(Object server) {
        if (this.owner != null && this.owner == server) {
            this.owner = null;
            return true;
        } else {
            return false;
        }
    }
}
