package de.metis.modules.self;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Tests for Phase 12a CompileErrorReporter.
 */
class CompileErrorReporterTest {

    @Test
    void testParseCompileErrors() {
        String mavenOutput = """
                [INFO] Compiling 1 source file...
                /home/project/src/main/java/Foo.java:[12,20] error: cannot find symbol
                  symbol:   class Bar
                  location: class Foo
                /home/project/src/main/java/Foo.java:[45,8] error: incompatible types
                  required: int
                  found:    String
                [INFO] BUILD FAILURE
                """;

        var reporter = new CompileErrorReporter("/tmp");
        List<CompileErrorReporter.CompileError> errors = reporter.parse(
                mavenOutput, List.of("/src/main/java/"));

        assertEquals(2, errors.size(), "Should parse 2 errors");

        CompileErrorReporter.CompileError first = errors.get(0);
        assertTrue(first.file().contains("Foo.java"), "File should be Foo.java");
        assertEquals(12, first.line());
        assertTrue(first.message().contains("cannot find symbol"));
        assertEquals("MISSING_SYMBOL", first.severity());
    }

    @Test
    void testParseFilterByPath() {
        String mavenOutput = """
                /external/lib/External.java:[5,3] error: cannot find symbol
                /home/project/src/main/java/MyClass.java:[10,8] error: class not found
                """;

        var reporter = new CompileErrorReporter("/tmp");
        List<CompileErrorReporter.CompileError> errors = reporter.parse(
                mavenOutput, List.of("/src/main/java/"));

        assertEquals(1, errors.size(), "Should only include files matching include path");
        assertTrue(errors.get(0).file().contains("MyClass.java"));
    }

    @Test
    void testSuccessOutput() {
        String mavenOutput = "[INFO] BUILD SUCCESS\n";
        var reporter = new CompileErrorReporter("/tmp");
        List<CompileErrorReporter.CompileError> errors = reporter.parse(
                mavenOutput, List.of("/src/main/java/"));
        assertTrue(errors.isEmpty(), "No errors expected for BUILD SUCCESS");
    }

    @Test
    void testIsFixable() {
        var reporter = new CompileErrorReporter("/tmp");

        var missingSym = new CompileErrorReporter.CompileError(
                "Foo.java", 12, "cannot find symbol: class Bar", "Bar", "MISSING_SYMBOL");
        assertTrue(reporter.isFixable(missingSym));

        var typeMismatch = new CompileErrorReporter.CompileError(
                "Foo.java", 45, "incompatible types", "?", "COMPILE_ERROR");
        assertTrue(reporter.isFixable(typeMismatch));

        var notFixable = new CompileErrorReporter.CompileError(
                "Foo.java", 10, "method foo is not applicable", "?", "COMPILE_ERROR");
        assertFalse(reporter.isFixable(notFixable));
    }
}
