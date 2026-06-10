package com.beworking.bekey;

/**
 * Raised when Akiles refuses a gadget action because the door hardware is
 * unreachable (the Akiles bridge/gateway is offline). This is a transient,
 * non-fault condition for us — the request was valid and the user was
 * authorized; the physical device just isn't answering right now. Callers
 * should surface a "try again / use your PIN" message, not a 500.
 */
public class BeKeyHardwareOfflineException extends RuntimeException {
    public BeKeyHardwareOfflineException(String message) {
        super(message);
    }
}
