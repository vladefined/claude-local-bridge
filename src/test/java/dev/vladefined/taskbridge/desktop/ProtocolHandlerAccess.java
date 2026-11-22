package dev.vladefined.taskbridge.desktop;

/** Test access to the package-private handler script. */
public final class ProtocolHandlerAccess {
    private ProtocolHandlerAccess() {
    }

    public static String script(int port) {
        return ProtocolHandler.script(port);
    }
}
