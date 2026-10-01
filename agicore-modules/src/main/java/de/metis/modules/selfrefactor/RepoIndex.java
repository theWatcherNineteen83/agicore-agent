package de.metis.modules.selfrefactor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AST-based index of all Java classes in a repository, building a dependency graph.
 * Phase 12a Foundation.
 */
public class RepoIndex {

    private static final java.util.logging.Logger LOG = Logger.getLogger(RepoIndex.class.getName());

    private final Map<String, ClassInfo> classes = new HashMap<>();
    private final Map<String, Set<String>> dependencies = new HashMap<>();
    private final Map<String, Set<String>> dependents = new HashMap<>();

    public RepoIndex() {
        // default constructor
    }

    /**
     * Index a Java source file.
     *
     * @param filePath the path to the Java source file
     * @return true if the file was successfully indexed
     */
    public boolean indexFile(Path filePath) {
        try {
            String content = Files.readString(filePath);
            return indexSource(filePath, content);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read file: " + filePath, e);
            return false;
        }
    }

    /**
     * Index Java source content.
     *
     * @param sourcePath the virtual or real path (for identification)
     * @param content the Java source code
     * @return true if the file was successfully indexed
     */
    public boolean indexSource(Path sourcePath, String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }

        ClassInfo classInfo = parseSource(sourcePath, content);
        if (classInfo == null) {
            return false;
        }

        String className = classInfo.getFullyQualifiedName();
        classes.put(className, classInfo);

        // Build dependency edges
        Set<String> deps = classInfo.getDependencies();
        dependencies.put(className, new HashSet<>(deps));

        // Build reverse dependency map
        for (String dep : deps) {
            dependents.computeIfAbsent(dep, k -> new HashSet<>()).add(className);
        }

        LOG.log(Level.FINE, "Indexed class: {0} with {1} dependencies", new Object[]{className, deps.size()});
        return true;
    }

    /**
     * Parse Java source code to extract class information.
     * This is a simplified AST-like parser using regex patterns for:
     * - package declaration
     * - import statements
     * - class/interface/enum declarations
     * - type references in fields, method parameters, return types, etc.
     */
    private ClassInfo parseSource(Path sourcePath, String content) {
        String packageName = extractPackage(content);
        if (packageName == null) {
            packageName = "";
        }

        Set<String> imports = extractImports(content);

        // Extract the primary class/interface/enum name
        String className = extractClassName(content);
        if (className == null) {
            LOG.log(Level.FINE, "No class found in: {0}", sourcePath);
            return null;
        }

        String fqn = packageName.isEmpty() ? className : packageName + "." + className;

        // Extract type references (potential dependencies)
        Set<String> typeRefs = extractTypeReferences(content);

        // Filter to only include types that are imported or in the same package
        Set<String> resolvedDeps = resolveDependencies(typeRefs, imports, packageName, className);

        return new ClassInfo(fqn, className, packageName, sourcePath, resolvedDeps);
    }

    private String extractPackage(String content) {
        Pattern pattern = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private Set<String> extractImports(String content) {
        Set<String> imports = new HashSet<>();
        Pattern pattern = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            imports.add(matcher.group(2));
        }
        return imports;
    }

    private String extractClassName(String content) {
        // Try to find public class, public interface, public enum, or just class/interface/enum
        Pattern[] patterns = {
                Pattern.compile("\\b(?:public|private|protected|abstract|final)?\\s*(?:class|interface|enum)\\s+(\\w+)"),
                Pattern.compile("\\b(?:class|interface|enum)\\s+(\\w+)")
        };

        for (Pattern p : patterns) {
            Matcher m = p.matcher(content);
            if (m.find()) {
                String name = m.group(1);
                // Filter out common keywords
                if (!"new".equals(name) && !"return".equals(name) && !"if".equals(name) && !"else".equals(name)) {
                    return name;
                }
            }
        }
        return null;
    }

    /**
     * Extract potential type references from the source code.
     * This looks for capitalized identifiers that might be type references.
     */
    private Set<String> extractTypeReferences(String content) {
        Set<String> refs = new HashSet<>();
        // Match capitalized identifiers (potential class names)
        Pattern pattern = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b");
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String candidate = matcher.group(1);
            // Filter out common Java keywords and built-in types
            if (isLikelyTypeReference(candidate)) {
                refs.add(candidate);
            }
        }
        return refs;
    }

    private boolean isLikelyTypeReference(String name) {
        // Filter out common keywords and built-in types that are not project dependencies
        Set<String> excluded = Set.of(
                "String", "Integer", "Long", "Double", "Float", "Boolean", "Character", "Byte", "Short",
                "Object", "Class", "System", "Thread", "RuntimeException", "Exception", "Throwable",
                "ArrayList", "HashMap", "HashSet", "LinkedList", "TreeMap", "TreeSet", "LinkedHashMap", "LinkedHashSet",
                "List", "Map", "Set", "Collection", "Iterable", "Iterator", "Optional",
                "Pattern", "Matcher", "Paths", "Files", "Path", "Stream", "Collectors",
                "Level", "Logger", "Logger", "IO", "IO",
                "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface", "SafeVarargs", "InheritableThreadLocal"
        );
        return !excluded.contains(name);
    }

    /**
     * Resolve dependencies by matching type references against imports and package scope.
     */
    private Set<String> resolveDependencies(Set<String> typeRefs, Set<String> imports, String packageName, String currentClassName) {
        Set<String> deps = new HashSet<>();

        // Create a map from simple name to fully qualified name based on imports
        Map<String, String> importMap = new HashMap<>();
        for (String imp : imports) {
            String simpleName = imp.substring(imp.lastIndexOf('.') + 1);
            importMap.put(simpleName, imp);
        }

        for (String ref : typeRefs) {
            if (ref.equals(currentClassName)) {
                continue; // Skip self-reference
            }

            String resolved = null;

            // Check if it's an imported type
            if (importMap.containsKey(ref)) {
                resolved = importMap.get(ref);
            } else if (packageName != null && !packageName.isEmpty()) {
                // Check if it's in the same package
                resolved = packageName + "." + ref;
            } else {
                resolved = ref;
            }

            // Verify the resolved class exists in our index or is a known JDK class
            // For now, include it as a dependency if it resolves to a non-JDK class
            // In a full implementation, we'd check against the index
            if (!isJdkClass(resolved)) {
                deps.add(resolved);
            }
        }

        return deps;
    }

    private boolean isJdkClass(String fqn) {
        return fqn.startsWith("java.") || fqn.startsWith("javax.") || fqn.startsWith("sun.") || fqn.startsWith("jdk.");
    }

    /**
     * Get all indexed classes.
     */
    public Map<String, ClassInfo> getAllClasses() {
        return new HashMap<>(classes);
    }

    /**
     * Get dependencies for a given class.
     */
    public Set<String> getDependencies(String className) {
        return dependencies.getOrDefault(className, new HashSet<>());
    }

    /**
     * Get classes that depend on the given class.
     */
    public Set<String> getDependents(String className) {
        return dependents.getOrDefault(className, new HashSet<>());
    }

    /**
     * Get the full dependency graph.
     */
    public Map<String, Set<String>> getDependencyGraph() {
        return new HashMap<>(dependencies);
    }

    /**
     * Get the full reverse dependency graph.
     */
    public Map<String, Set<String>> getReverseDependencyGraph() {
        return new HashMap<>(dependents);
    }

    /**
     * Get classes that are not depended upon by any other class (potential leaves).
     */
    public Set<String> getLeafClasses() {
        Set<String> allClasses = classes.keySet();
        Set<String> allDependents = new HashSet<>();
        for (Set<String> deps : dependencies.values()) {
            allDependents.addAll(deps);
        }
        Set<String> leaves = new HashSet<>(allClasses);
        leaves.removeAll(allDependents);
        return leaves;
    }

    /**
     * Get classes that are depended upon by the most other classes (potential hubs).
     */
    public List<ClassInfo> getTopDependedClasses(int limit) {
        return dependents.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(limit)
                .map(e -> classes.get(e.getKey()))
                .filter(c -> c != null)
                .collect(Collectors.toList());
    }

    /**
     * Index an entire directory tree for Java files.
     *
     * @param rootPath the root directory to scan
     * @return number of files indexed
     */
    public int indexDirectory(Path rootPath) {
        int count = 0;
        if (rootPath == null || !Files.exists(rootPath)) {
            LOG.warning("Root path does not exist: " + rootPath);
            return 0;
        }

        try (Stream<Path> walk = Files.walk(rootPath)) {
            List<Path> javaFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());

            for (Path javaFile : javaFiles) {
                if (indexFile(javaFile)) {
                    count++;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error walking directory: " + rootPath, e);
        }

        LOG.log(Level.INFO, "Indexed {0} Java files from {1}", new Object[]{count, rootPath});
        return count;
    }

    /**
     * Clear all indexed data.
     */
    public void clear() {
        classes.clear();
        dependencies.clear();
        dependents.clear();
    }

    /**
     * Get the total number of indexed classes.
     */
    public int size() {
        return classes.size();
    }

    /**
     * Check if a class is indexed.
     */
    public boolean containsClass(String className) {
        return classes.containsKey(className);
    }

    /**
     * Get a specific class info.
     */
    public ClassInfo getClassInfo(String className) {
        return classes.get(className);
    }

    /**
     * Inner class representing indexed class information.
     */
    public static class ClassInfo {
        private final String fullyQualifiedName;
        private final String simpleName;
        private final String packageName;
        private final Path sourcePath;
        private final Set<String> dependencies;

        public ClassInfo(String fullyQualifiedName, String simpleName, String packageName, Path sourcePath, Set<String> dependencies) {
            this.fullyQualifiedName = fullyQualifiedName;
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.sourcePath = sourcePath;
            this.dependencies = new HashSet<>(dependencies);
        }

        public String getFullyQualifiedName() {
            return fullyQualifiedName;
        }

        public String getSimpleName() {
            return simpleName;
        }

        public String getPackageName() {
            return packageName;
        }

        public Path getSourcePath() {
            return sourcePath;
        }

        public Set<String> getDependencies() {
            return new HashSet<>(dependencies);
        }

        @Override
        public String toString() {
            return "ClassInfo{" +
                    "fullyQualifiedName='" + fullyQualifiedName + '\'' +
                    ", simpleName='" + simpleName + '\'' +
                    ", packageName='" + packageName + '\'' +
                    ", sourcePath=" + sourcePath +
                    ", dependencies=" + dependencies +
                    '}';
        }
    }
}