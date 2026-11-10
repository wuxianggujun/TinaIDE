package org.libsdl.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;

public class SdlNativeCompatTest {
    @Test
    public void version35WithoutRegisteredCapabilitiesKeepsCompatibleCallbacks() throws Exception {
        Field resolved = field("sResolved");
        Field major = field("sMajorVersion");
        Field minor = field("sMinorVersion");
        Field modern = field("sModernBridge");
        Object oldResolved = resolved.get(null), oldMajor = major.get(null), oldMinor = minor.get(null), oldModern = modern.get(null);
        try {
            resolved.set(null, true);
            major.set(null, 3);
            minor.set(null, 5);
            modern.set(null, false);
            assertFalse(SdlNativeCompat.supportsSubsystemQuery());
            assertFalse(SdlNativeCompat.supportsHidapiQuery());
            assertFalse(SdlNativeCompat.supportsHdrHeadroom());
            assertFalse(SdlNativeCompat.supportsJoySensor());
            assertFalse(SdlNativeCompat.joystickReportsSensors());
            assertFalse(SdlNativeCompat.pinchCarriesFocus());
            assertEquals(SDL.SDL_INIT_EVERYTHING, SdlNativeCompat.compiledSubsystems());
        } finally {
            resolved.set(null, oldResolved);
            major.set(null, oldMajor);
            minor.set(null, oldMinor);
            modern.set(null, oldModern);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = SdlNativeCompat.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
