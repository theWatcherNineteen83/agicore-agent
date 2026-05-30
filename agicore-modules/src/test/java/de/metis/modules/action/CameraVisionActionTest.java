package de.metis.modules.action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies multi-modal memory: a snapshot is hashed (sha256), addressed by
 * record path/sha256, and the SnapshotRef record is exposed.
 * No network or filesystem-writing is exercised here; we only verify the
 * static helpers and the record contract.
 */
class CameraVisionActionTest {

    @Test
    void snapshotRefRecordExists() throws Exception {
        // Construct via reflection to bypass the public constructor side effects.
        var refClass = Class.forName(
                "de.metis.modules.action.CameraVisionAction$SnapshotRef");
        var ctor = refClass.getDeclaredConstructor(String.class, String.class);
        Object ref = ctor.newInstance("/tmp/x.jpg", "abc123def456");

        Method pathM = refClass.getMethod("path");
        Method shaM = refClass.getMethod("sha256");
        assertEquals("/tmp/x.jpg", pathM.invoke(ref));
        assertEquals("abc123def456", shaM.invoke(ref));
    }

    @Test
    void sha256IsDeterministic() throws Exception {
        Method sha = CameraVisionAction.class.getDeclaredMethod("sha256", byte[].class);
        sha.setAccessible(true);
        String a = (String) sha.invoke(null, (Object) "hello".getBytes());
        String b = (String) sha.invoke(null, (Object) "hello".getBytes());
        String c = (String) sha.invoke(null, (Object) "world".getBytes());
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(64, a.length(), "sha256 hex must be 64 chars");
    }
}
