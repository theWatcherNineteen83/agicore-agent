import de.metis.kernel.action.CodeGenerationAction;
import de.metis.kernel.action.ActionResult;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Quick test of CodeGenerationAction against miniedi Ollama.
 * Run: java -cp agicore-kernel/target/classes CodeGenTest
 */
public class CodeGenTest {
    public static void main(String[] args) {
        String spec = "Generate a UptimeAction that returns system uptime using 'cat /proc/uptime' or 'uptime' command. Category: read. Name: uptime.";

        CodeGenerationAction action = new CodeGenerationAction(
                spec,
                "http://192.168.22.204:11434",
                "nemotron-cascade-2:30b",
                Path.of("/tmp/metis-codegen-output"),
                Duration.ofSeconds(120));

        System.out.println("🚀 Generating code for: " + spec);
        System.out.println("Model: nemotron-cascade-2:30b @ miniedi");
        System.out.println("---");

        ActionResult result = action.execute();

        System.out.println("Success: " + result.success());
        System.out.println("Duration: " + result.duration().toMillis() + "ms");
        if (result.success()) {
            System.out.println("\n" + result.body());
        } else {
            System.out.println("Error: " + result.error());
            if (result.body() != null) {
                System.out.println(result.body());
            }
        }
    }
}
