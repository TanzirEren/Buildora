package com.tenix.buildora.ai;

/** One model offered by a provider. */
public final class AiModel {
    public String id;
    public String name;          // display name (may equal id)
    public boolean free;
    public boolean enabled = true;
    public int context;          // tokens, 0 = unknown

    public AiModel() {}

    public AiModel(String id) { this.id = id; this.name = id; }

    public String label() { return name == null || name.isEmpty() ? id : name; }
}
