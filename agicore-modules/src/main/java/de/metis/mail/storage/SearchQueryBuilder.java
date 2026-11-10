package de.metis.mail.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bausichere Suchanfragen für die SQLite-Mail-Suche.
 * Erkennt Feld-Präfixe (from:, subject:, has:attachment) und erstellt
 * SQL-Fragmente mit Platzhaltern und Parameterlisten.
 */
public class SearchQueryBuilder {

    private static final Logger LOG = Logger.getLogger(SearchQueryBuilder.class.getName());

    private static final Pattern FIELD_PATTERN = Pattern.compile(
        "^\\s*(from|subject|has:attachment)\\s*:\\s*(.+)$",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Erzeugt ein sicheres SQL-WHERE-Fragment und eine Parameterliste aus Nutzereingabe.
     *
     * @param userInput die Sucheingabe des Nutzers
     * @return ein Objekt mit SQL-Fragment und Parameterliste
     */
    public SearchQuery build(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            LOG.fine("Leere Sucheingabe - neutrale Klausel wird zurückgegeben");
            return new SearchQuery("1=1", new ArrayList<>());
        }

        String trimmedInput = userInput.trim();

        // Prüfe auf Feld-Präfixe
        Matcher matcher = FIELD_PATTERN.matcher(trimmedInput);

        if (matcher.matches()) {
            String field = matcher.group(1).toLowerCase();
            String value = matcher.group(2).trim();

            // Escape Sonderzeichen in der Nutzereingabe
            String escapedValue = escapeSqlWildcards(value);

            switch (field) {
                case "from":
                    LOG.fine("Von-Suche für: " + escapedValue);
                    return new SearchQuery(
                        "sender LIKE ? COLLATE NOCASE",
                        List.of("%" + escapedValue + "%")
                    );

                case "subject":
                    LOG.fine("Betreff-Suche für: " + escapedValue);
                    return new SearchQuery(
                        "subject LIKE ? COLLATE NOCASE",
                        List.of("%" + escapedValue + "%")
                    );

                case "has:attachment":
                    LOG.fine("Anhang-Suche für: " + escapedValue);
                    // Für has:attachment wird ein Boolean-Feld vorausgesetzt
                    if ("true".equalsIgnoreCase(escapedValue) || "yes".equalsIgnoreCase(escapedValue) || "1".equals(escapedValue)) {
                        return new SearchQuery(
                            "has_attachment = 1",
                            new ArrayList<>()
                        );
                    } else if ("false".equalsIgnoreCase(escapedValue) || "no".equalsIgnoreCase(escapedValue) || "0".equals(escapedValue)) {
                        return new SearchQuery(
                            "has_attachment = 0",
                            new ArrayList<>()
                        );
                    } else {
                        LOG.warning("Ungültiger Wert für has:attachment: " + escapedValue + " - wird als true interpretiert");
                        return new SearchQuery(
                            "has_attachment = 1",
                            new ArrayList<>()
                        );
                    }

                default:
                    LOG.warning("Unbekanntes Feld-Präfix: " + field);
                    // Fallback zu Volltextsuche
                    return buildFullTextSearch(escapedValue);
            }
        }

        // Volltextsuche über Betreff und Absender
        LOG.fine("Volltextsuche für: " + trimmedInput);
        return buildFullTextSearch(escapeSqlWildcards(trimmedInput));
    }

    /**
     * Erzeugt ein SQL-Fragment für Volltextsuche über Betreff und Absender.
     *
     * @param escapedValue der escaped Suchbegriff
     * @return ein SearchQuery-Objekt mit SQL und Parametern
     */
    private SearchQuery buildFullTextSearch(String escapedValue) {
        String sql = "(subject LIKE ? COLLATE NOCASE OR sender LIKE ? COLLATE NOCASE)";
        List<String> params = new ArrayList<>();
        params.add("%" + escapedValue + "%");
        params.add("%" + escapedValue + "%");
        return new SearchQuery(sql, params);
    }

    /**
     * Escapet SQL-Wildcards (Prozent und Unterstrich) in der Nutzereingabe.
     *
     * @param input die Nutzereingabe
     * @return die escaped Eingabe
     */
    private String escapeSqlWildcards(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }

    /**
     * Repräsentiert ein sicheres SQL-WHERE-Fragment mit zugehöriger Parameterliste.
     */
    public static class SearchQuery {
        private final String sqlFragment;
        private final List<String> parameters;

        public SearchQuery(String sqlFragment, List<String> parameters) {
            this.sqlFragment = sqlFragment;
            this.parameters = parameters;
        }

        public String getSqlFragment() {
            return sqlFragment;
        }

        public List<String> getParameters() {
            return parameters;
        }

        @Override
        public String toString() {
            return "SearchQuery{sql='" + sqlFragment + "', params=" + parameters + '}';
        }
    }
}