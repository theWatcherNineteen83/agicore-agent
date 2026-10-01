package de.metis.modules.selfrefactor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the AGI_EDI_ROADMAP.md file to extract phase information,
 * track progress via todo checkboxes, and calculate coverage percentages.
 *
 * <p>This class is part of the self-refactoring module and provides
 * strategic insights into the development progress of the Metis AGI system.</p>
 */
public class RoadmapReader {

    private static final Logger LOG = Logger.getLogger(RoadmapReader.class.getName());
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private static final Pattern PHASE_HEADER_PATTERN = Pattern.compile("^##\\s+(Phase\\s+[\\w\\d]+[a-zA-Z]?)\\s*[:\\-]?\\s*(.*)$");
    private static final Pattern TODO_ITEM_PATTERN = Pattern.compile("^\\s*[-*+]?\\s*\\[([ xX])\\]\\s*(.*)$");
    private static final Pattern COVERAGE_SECTION_PATTERN = Pattern.compile("^###\\s+Coverage\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COVERAGE_ENTRY_PATTERN = Pattern.compile("^\\s*[-*+]?\\s*\\*\\*(\\d+[.%]?)\\*\\*\\s*(.*)$");

    private final Map<String, PhaseInfo> phases;

    public RoadmapReader() {
        this.phases = new LinkedHashMap<>();
    }

    /**
     * Parses the roadmap markdown file from the default location.
     *
     * @return the parsed roadmap data
     * @throws IOException if the file cannot be read
     */
    public RoadmapData parse() throws IOException {
        Path defaultPath = Paths.get("AGI_EDI_ROADMAP.md");
        return parse(defaultPath);
    }

    /**
     * Parses the roadmap markdown file from a specified path.
     *
     * @param filePath the path to the markdown file
     * @return the parsed roadmap data
     * @throws IOException if the file cannot be read
     */
    public RoadmapData parse(Path filePath) throws IOException {
        LOG.info("Starting roadmap parsing from: " + filePath);
        
        if (!Files.exists(filePath)) {
            throw new IOException("Roadmap file not found: " + filePath);
        }

        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        LOG.fine("Read " + lines.size() + " lines from roadmap");

        this.phases.clear();
        PhaseInfo currentPhase = null;
        boolean inCoverageSection = false;
        int totalTodos = 0;
        int completedTodos = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            
            if (line.isEmpty()) {
                continue;
            }

            Matcher phaseMatcher = PHASE_HEADER_PATTERN.matcher(line);
            if (phaseMatcher.matches()) {
                String phaseName = phaseMatcher.group(1).trim();
                String phaseTitle = phaseMatcher.group(2).trim();
                
                LOG.fine("Found phase: " + phaseName);
                
                currentPhase = new PhaseInfo(phaseName, phaseTitle);
                this.phases.put(phaseName, currentPhase);
                inCoverageSection = false;
                continue;
            }

            if (COVERAGE_SECTION_PATTERN.matcher(line).matches()) {
                inCoverageSection = true;
                LOG.fine("Entering coverage section for phase: " + (currentPhase != null ? currentPhase.name : "unknown"));
                continue;
            }

            if (currentPhase != null) {
                if (inCoverageSection) {
                    Matcher coverageMatcher = COVERAGE_ENTRY_PATTERN.matcher(line);
                    if (coverageMatcher.matches()) {
                        String coverageValue = coverageMatcher.group(1);
                        String coverageDescription = coverageMatcher.group(2).trim();
                        
                        try {
                            double coverage = parsePercentage(coverageValue);
                            currentPhase.coverageEntries.add(new CoverageEntry(coverage, coverageDescription));
                            LOG.fine("Added coverage entry: " + coverage + "% - " + coverageDescription);
                        } catch (NumberFormatException e) {
                            LOG.warning("Invalid coverage value: " + coverageValue);
                        }
                    } else if (!line.startsWith("#") && !line.startsWith("-") && !line.startsWith("*")) {
                        inCoverageSection = false;
                    }
                } else {
                    Matcher todoMatcher = TODO_ITEM_PATTERN.matcher(line);
                    if (todoMatcher.matches()) {
                        String checkbox = todoMatcher.group(1);
                        String todoText = todoMatcher.group(2).trim();
                        
                        boolean completed = checkbox.equalsIgnoreCase("x");
                        
                        TodoItem todoItem = new TodoItem(todoText, completed, i + 1);
                        currentPhase.todos.add(todoItem);
                        
                        totalTodos++;
                        if (completed) {
                            completedTodos++;
                        }
                        
                        LOG.finest("Parsed todo item (line " + (i + 1) + "): [" + checkbox + "] " + todoText);
                    }
                }
            }
        }

        // Calculate coverage for each phase
        for (PhaseInfo phase : this.phases.values()) {
            phase.calculateCoverage();
            LOG.fine("Calculated coverage for phase " + phase.name + ": " + phase.coveragePercent + "%");
        }

        double overallCoverage = calculateOverallCoverage();
        LOG.info("Roadmap parsing complete. Phases: " + this.phases.size() + ", Total Todos: " + totalTodos + ", Completed: " + completedTodos + ", Overall Coverage: " + String.format("%.2f", overallCoverage) + "%");

        return new RoadmapData(this.phases, totalTodos, completedTodos, overallCoverage, filePath, LocalDateTime.now());
    }

    private double calculateOverallCoverage() {
        if (this.phases.isEmpty()) {
            return 0.0;
        }
        
        double totalCoverage = 0.0;
        for (PhaseInfo phase : this.phases.values()) {
            totalCoverage += phase.coveragePercent;
        }
        
        return totalCoverage / this.phases.size();
    }

    private double parsePercentage(String value) {
        if (value.endsWith("%")) {
            value = value.substring(0, value.length() - 1);
        }
        return Double.parseDouble(value);
    }

    /**
     * Represents a single todo item in the roadmap.
     */
    public static class TodoItem {
        private final String text;
        private final boolean completed;
        private final int lineNumber;
        private LocalDateTime completionDate;

        public TodoItem(String text, boolean completed, int lineNumber) {
            this.text = text;
            this.completed = completed;
            this.lineNumber = lineNumber;
        }

        public String getText() {
            return text;
        }

        public boolean isCompleted() {
            return completed;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public LocalDateTime getCompletionDate() {
            return completionDate;
        }

        public void setCompletionDate(LocalDateTime completionDate) {
            this.completionDate = completionDate;
        }

        @Override
        public String toString() {
            return "[X] " + text + (completionDate != null ? " (completed: " + DATE_FORMAT.format(completionDate) + ")" : "");
        }
    }

    /**
     * Represents a coverage entry in the roadmap.
     */
    public static class CoverageEntry {
        private final double percentage;
        private final String description;

        public CoverageEntry(double percentage, String description) {
            this.percentage = percentage;
            this.description = description;
        }

        public double getPercentage() {
            return percentage;
        }

        public String getDescription() {
            return description;
        }

        @Override
        public String toString() {
            return String.format("%.1f%% - %s", percentage, description);
        }
    }

    /**
     * Represents a phase in the roadmap with its todos and coverage information.
     */
    public static class PhaseInfo {
        private final String name;
        private final String title;
        private final List<TodoItem> todos;
        private final List<CoverageEntry> coverageEntries;
        private double coveragePercent;

        public PhaseInfo(String name, String title) {
            this.name = name;
            this.title = title;
            this.todos = new ArrayList<>();
            this.coverageEntries = new ArrayList<>();
            this.coveragePercent = 0.0;
        }

        public void calculateCoverage() {
            if (!coverageEntries.isEmpty()) {
                double totalCoverage = 0.0;
                for (CoverageEntry entry : coverageEntries) {
                    totalCoverage += entry.getPercentage();
                }
                this.coveragePercent = totalCoverage / coverageEntries.size();
            } else {
                // Calculate based on todos if no explicit coverage section
                if (!todos.isEmpty()) {
                    long completed = todos.stream().filter(TodoItem::isCompleted).count();
                    this.coveragePercent = (double) completed / todos.size() * 100.0;
                } else {
                    this.coveragePercent = 0.0;
                }
            }
        }

        public String getName() {
            return name;
        }

        public String getTitle() {
            return title;
        }

        public List<TodoItem> getTodos() {
            return new ArrayList<>(todos);
        }

        public List<CoverageEntry> getCoverageEntries() {
            return new ArrayList<>(coverageEntries);
        }

        public double getCoveragePercent() {
            return coveragePercent;
        }

        public int getTotalTodos() {
            return todos.size();
        }

        public int getCompletedTodos() {
            return (int) todos.stream().filter(TodoItem::isCompleted).count();
        }

        public int getPendingTodos() {
            return todos.size() - getCompletedTodos();
        }

        @Override
        public String toString() {
            return String.format("Phase %s: %s (Coverage: %.2f%%, Todos: %d/%d completed)", 
                    name, title, coveragePercent, getCompletedTodos(), getTotalTodos());
        }
    }

    /**
     * Contains all parsed roadmap data.
     */
    public static class RoadmapData {
        private final Map<String, PhaseInfo> phases;
        private final int totalTodos;
        private final int completedTodos;
        private final double overallCoverage;
        private final Path sourceFile;
        private final LocalDateTime parsedAt;

        public RoadmapData(Map<String, PhaseInfo> phases, int totalTodos, int completedTodos, 
                           double overallCoverage, Path sourceFile, LocalDateTime parsedAt) {
            this.phases = new LinkedHashMap<>(phases);
            this.totalTodos = totalTodos;
            this.completedTodos = completedTodos;
            this.overallCoverage = overallCoverage;
            this.sourceFile = sourceFile;
            this.parsedAt = parsedAt;
        }

        public Map<String, PhaseInfo> getPhases() {
            return new LinkedHashMap<>(phases);
        }

        public int getTotalTodos() {
            return totalTodos;
        }

        public int getCompletedTodos() {
            return completedTodos;
        }

        public int getPendingTodos() {
            return totalTodos - completedTodos;
        }

        public double getOverallCoverage() {
            return overallCoverage;
        }

        public Path getSourceFile() {
            return sourceFile;
        }

        public LocalDateTime getParsedAt() {
            return parsedAt;
        }

        public List<PhaseInfo> getPhasesInOrder() {
            return new ArrayList<>(phases.values());
        }

        public PhaseInfo getPhaseByName(String name) {
            return phases.get(name);
        }

        public List<String> getPhaseNames() {
            return new ArrayList<>(phases.keySet());
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Roadmap Report\n");
            sb.append("===============\n");
            sb.append("Source File: ").append(sourceFile).append("\n");
            sb.append("Parsed At: ").append(DATE_FORMAT.format(parsedAt)).append("\n");
            sb.append("Total Todos: ").append(totalTodos).append("\n");
            sb.append("Completed: ").append(completedTodos).append("\n");
            sb.append("Pending: ").append(getPendingTodos()).append("\n");
            sb.append("Overall Coverage: ").append(String.format("%.2f", overallCoverage)).append("%\n");
            sb.append("\nPhases:\n");
            
            for (PhaseInfo phase : phases.values()) {
                sb.append(phase).append("\n");
            }
            
            return sb.toString();
        }
    }
}