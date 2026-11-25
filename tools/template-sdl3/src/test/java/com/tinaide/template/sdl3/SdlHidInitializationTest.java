package org.libsdl.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Runs against the same SDL Java sources copied into the exported APK. */
public class SdlHidInitializationTest {
    private Field resolved;
    private Field modernBridge;
    private Object previousResolved;
    private Object previousModernBridge;

    @Before
    public void isolateNativeCapabilityCache() throws Exception {
        resolved = field("sResolved");
        modernBridge = field("sModernBridge");
        previousResolved = resolved.get(null);
        previousModernBridge = modernBridge.get(null);
        resolved.set(null, true);
    }

    @After
    public void restoreNativeCapabilityCache() throws Exception {
        resolved.set(null, previousResolved);
        modernBridge.set(null, previousModernBridge);
    }

    @Test
    public void legacyBridgeInitializesWithoutCallingTheMissingQuery() throws Exception {
        modernBridge.set(null, false);
        assertTrue(SdlNativeCompat.shouldInitializeHidapi(() -> {
            throw new AssertionError("Legacy libraries have no HID capability query");
        }));
    }

    @Test
    public void modernBridgeUsesTheNativeEnablementResult() throws Exception {
        modernBridge.set(null, true);
        AtomicBoolean called = new AtomicBoolean();
        assertTrue(SdlNativeCompat.shouldInitializeHidapi(() -> {
            called.set(true);
            return true;
        }));
        assertTrue(called.get());
    }

    @Test
    public void explicitNativeDisablementSkipsInitialization() throws Exception {
        modernBridge.set(null, true);
        assertFalse(SdlNativeCompat.shouldInitializeHidapi(() -> false));
    }

    @Test
    public void partialSnapshotWithMissingHidQueryUsesTheLegacyBridge() throws Exception {
        modernBridge.set(null, true);
        assertTrue(SdlNativeCompat.shouldInitializeHidapi(() -> {
            throw new UnsatisfiedLinkError("nativeIsHIDAPIEnabled");
        }));
    }

    @Test
    public void unrelatedFailuresAreNotMisclassifiedAsMissingNativeQueries() throws Exception {
        modernBridge.set(null, true);
        assertThrows(IllegalStateException.class, () -> SdlNativeCompat.shouldInitializeHidapi(() -> {
            throw new IllegalStateException("Unexpected query failure");
        }));
    }

    private static Field field(String name) throws Exception {
        Field field = SdlNativeCompat.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
