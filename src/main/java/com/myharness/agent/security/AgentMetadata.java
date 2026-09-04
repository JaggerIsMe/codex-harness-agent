package com.myharness.agent.security;

public final class AgentMetadata {
    private AgentMetadata() {
    }

    public static String version() {
        String version = AgentMetadata.class.getPackage().getImplementationVersion();
        return version == null ? "1.0.0-SNAPSHOT" : version;
    }
}
