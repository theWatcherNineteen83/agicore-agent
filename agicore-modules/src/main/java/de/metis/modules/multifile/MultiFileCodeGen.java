package de.metis.modules.multifile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MultiFileCodeGen
 * 
 * A utility class that processes a string containing multiple Java source files
 * formatted with specific delimiters, writes them to a target directory,
 * compiles them using javac, and returns the results.
 * 
 * Expected input format:
 * ===FILE: com/example/MyClass.java===
 * package com.example;
 * public class MyClass { }
 * ===END===
 * 
 * ===FILE: com/example/AnotherClass.java===
 * package com.example;
 * public class AnotherClass { }
 * ===END===
 */
public class MultiFileCodeGen {

    private static final Logger LOG = Logger.getLogger(MultiFileCodeGen.class.getName());

    private static final Pattern FILE_PATTERN = Pattern.compile(
        "===FILE:\\s*([^=]+?)===\\s*\n(.*?)\\s*===END===",
        Pattern.DOTALL
    );

    /**
     * Result object containing the list of written file paths and the compiler output.
     */
    public static class MultiFileCodeGenResult {
        private final List<Path> writtenFiles;
        private final String compileOutput;
        private final boolean compileSuccess;

        public MultiFileCodeGenResult(List<Path> writtenFiles, String compileOutput, boolean compileSuccess) {
            this.writtenFiles = writtenFiles;
            this.compileOutput = compileOutput;
            this.compileSuccess = compileSuccess;
        }

        public List<Path> getWrittenFiles() {
            return writtenFiles;
        }

        public String getCompileOutput() {
            return compileOutput;
        }

        public boolean isCompileSuccess() {
            return compileSuccess;
        }
    }

    /**
     * Processes the input string containing multiple Java files, writes them to the target directory,
     * and compiles them.
     *
     * @param codeContent The string containing multiple Java files in the specified format.
     * @param targetDir The directory where the Java files should be written and compiled.
     * @return A MultiFileCodeGenResult containing the list of written file paths and compile output.
     * @throws IOException If an I/O error occurs while writing files or running the compiler.
     */
    public MultiFileCodeGenResult process(String codeContent, String targetDir) throws IOException {
        if (codeContent == null || codeContent.trim().isEmpty()) {
            throw new IllegalArgumentException("Code content cannot be null or empty");
        }
        if (targetDir == null || targetDir.trim().isEmpty()) {
            throw new IllegalArgumentException("Target directory cannot be null or empty");
        }

        Path targetPath = Paths.get(targetDir);
        
        // Create target directory if it doesn't exist
        if (!Files.exists(targetPath)) {
            Files.createDirectories(targetPath);
            LOG.log(Level.INFO, "Created target directory: {0}", targetPath);
        }

        // Parse the content to extract file paths and their content
        List<Path> writtenFiles = parseAndWriteFiles(codeContent, targetPath);
        
        if (writtenFiles.isEmpty()) {
            LOG.warning("No Java files found in the provided content");
            return new MultiFileCodeGenResult(List.of(), "No files to compile.", false);
        }

        LOG.log(Level.INFO, "Successfully wrote {0} files to {1}", new Object[]{writtenFiles.size(), targetPath});

        // Compile the written files
        String compileOutput = compileFiles(writtenFiles, targetPath);
        boolean success = !compileOutput.contains("error") || compileOutput.isEmpty();

        return new MultiFileCodeGenResult(writtenFiles, compileOutput, success);
    }

    /**
     * Parses the code content to extract individual Java files and writes them to the target directory.
     *
     * @param codeContent The content string containing multiple files.
     * @param targetDir The base directory where files will be written.
     * @return A list of Paths to the written Java files.
     * @throws IOException If an I/O error occurs.
     */
    private List<Path> parseAndWriteFiles(String codeContent, Path targetDir) throws IOException {
        List<Path> writtenFiles = new ArrayList<>();
        Matcher matcher = FILE_PATTERN.matcher(codeContent);

        while (matcher.find()) {
            String relativeFilePath = matcher.group(1).trim();
            String fileContent = matcher.group(2);

            // Ensure the path is valid and ends with .java
            if (!relativeFilePath.toLowerCase().endsWith(".java")) {
                LOG.log(Level.WARNING, "Skipping file with invalid extension: {0}", relativeFilePath);
                continue;
            }

            // Prevent directory traversal attacks
            if (relativeFilePath.contains("..")) {
                LOG.log(Level.WARNING, "Skipping file with potential directory traversal: {0}", relativeFilePath);
                continue;
            }

            Path filePath = targetDir.resolve(relativeFilePath);

            // Ensure parent directories exist
            Path parentDir = filePath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            // Selfrefactor-Gate (Phase 12b)
            de.metis.modules.selfrefactor.SelfRefactorGate.Decision gate =
                    de.metis.modules.selfrefactor.SelfRefactorGate.check(targetDir, filePath);
            if (gate.blocked()) {
                Path staged = gate.stagedPath();
                Files.createDirectories(staged.getParent());
                Files.writeString(staged, fileContent, StandardCharsets.UTF_8);
                LOG.log(Level.WARNING,
                        "GATE(12b): existing source not overwritten, staged at {0}", staged);
                continue; // Original bleibt unveraendert, Datei laeuft nicht in den Compile-Check
            }

            // Write the file
            Files.writeString(filePath, fileContent, StandardCharsets.UTF_8);
            LOG.log(Level.FINE, "Wrote file: {0}", filePath);
            writtenFiles.add(filePath);
        }

        return writtenFiles;
    }

    /**
     * Compiles the specified Java files using javac.
     *
     * @param javaFiles The list of Java file paths to compile.
     * @param targetDir The directory to use as the working directory for compilation.
     * @return The output from the javac compiler.
     * @throws IOException If an I/O error occurs while running the compiler.
     */
    private String compileFiles(List<Path> javaFiles, Path targetDir) throws IOException {
        if (javaFiles.isEmpty()) {
            return "No files to compile.";
        }

        // Build the javac command
        List<String> command = new ArrayList<>();
        command.add("javac");
        command.add("-d");
        command.add(targetDir.toString());

        for (Path file : javaFiles) {
            command.add(file.toString());
        }

        LOG.log(Level.INFO, "Executing command: {0}", String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(targetDir.toFile());
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();

            // Read the output
            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            String result = output.toString();

            if (exitCode != 0) {
                LOG.log(Level.SEVERE, "Compilation failed with exit code {0}:\n{1}", new Object[]{exitCode, result});
            } else {
                LOG.log(Level.INFO, "Compilation succeeded.");
            }

            return result.isEmpty() ? "Compilation successful with no output." : result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Compilation process interrupted", e);
        }
    }

    /**
     * Main method for testing purposes.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        String sampleContent = """
            ===FILE: com/example/Calculator.java===
            package com.example;

            public class Calculator {
                public int add(int a, int b) {
                    return a + b;
                }
            }
            ===END===

            ===FILE: com/example/Main.java===
            package com.example;

            public class Main {
                public static void main(String[] args) {
                    Calculator calc = new Calculator();
                    System.out.println("2 + 3 = " + calc.add(2, 3));
                }
            }
            ===END===""";

        String targetDir = "/tmp/multifile_codegen_test";

        try {
            MultiFileCodeGen gen = new MultiFileCodeGen();
            MultiFileCodeGenResult result = gen.process(sampleContent, targetDir);

            System.out.println("Written files:");
            for (Path file : result.getWrittenFiles()) {
                System.out.println("  " + file);
            }
            System.out.println("\nCompile Output:");
            System.out.println(result.getCompileOutput());
            System.out.println("\nCompile Success: " + result.isCompileSuccess());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}