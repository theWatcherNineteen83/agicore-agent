package de.metis.mail.security;

import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AttachmentNameSanitizer {
    private static final Logger LOG = Logger.getLogger(AttachmentNameSanitizer.class.getName());

    private static final int MAX_LENGTH = 120;
    private static final String FALLBACK_NAME = "anhang";

    // Regex für Steuerzeichen (0x00-0x1F) und DEL (0x7F)
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F]");

    // Regex für Windows-Verbotszeichen
    private static final Pattern FORBIDDEN_CHARS = Pattern.compile("[<>:\"/\\\\|?*]");

    // Regex für doppelte Endungen (z.B. .pdf.exe, .jpg.scr)
    private static final Pattern DOUBLE_EXT = Pattern.compile("\\.[a-zA-Z0-9]{1,4}\\.(?:exe|scr|cmd|bat|sh|py|js|vbs|jar|jsp|html?|php|pl|cgi|asp|aspx|cer|crt|pfx|p12|p7b|der|pem|key|pkcs7|p7m|p7c|p7s|p7b|p12|pfx|crt|cer|pem|key)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern MIME_ENCODED = Pattern.compile("=\\p{XDigit}{2}");

    public String sanitize(String name) {
        if (name == null) {
            LOG.warning("Attachment name is null, using fallback name: " + FALLBACK_NAME);
            return FALLBACK_NAME;
        }

        String sanitized = name.trim();

        // 1. RFC2047-kodierte Namen dekodieren (simplifizierte Version für =XX Sequenzen)
        if (sanitized.contains("=")) {
            sanitized = decodeRfc2047(sanitized);
        }

        // 2. Steuerzeichen entfernen
        sanitized = CONTROL_CHARS.matcher(sanitized).replaceAll("");

        // 3. Windows-Verbotszeichen entfernen
        sanitized = FORBIDDEN_CHARS.matcher(sanitized).replaceAll("");

        // 4. Pfadanteile (Schrägstriche/Backslashes wurden oben entfernt, aber prüfen auf verbleibende Segmente)
        // Da / und \ bereits durch FORBIDDEN_CHARS entfernt wurden, bleiben keine Pfadseparatorn mehr.
        // Wir entfernen dennoch alle Punkte, die am Anfang stehen könnten, später.

        // 5. Doppelte Endungen entschärfen
        // Wir ersetzen die gefährliche zweite Endung durch einen Punkt, sodass nur die sichere erste bleibt?
        // Oder wir entfernen die zweite Endung komplett?
        // Regel: "doppelte Endungen wie .pdf.exe oder .jpg.scr entschärfen"
        // Üblicherweise entfernt man die zweite Endung, um die Datei als PDF zu behandeln, nicht als EXE.
        Matcher doubleExtMatcher = DOUBLE_EXT.matcher(sanitized);
        if (doubleExtMatcher.find()) {
            // Entferne die zweite Endung (z.B. .exe), behalte die erste (.pdf)
            // Der Match ist z.B. ".pdf.exe"
            // Wir wollen das Ergebnis als "datei.pdf"
            String match = doubleExtMatcher.group();
            // Der Match beginnt mit dem Punkt der ersten Endung.
            // Wir ersetzen den Match mit nur der ersten Endung (ohne das zweite .exe)
            // Match: .pdf.exe -> Wir wollen .pdf
            // Das ist schwierig, wenn wir nur den gesamten Match haben.
            // Besser: Ersetzen wir ".pdf.exe" mit ".pdf"?
            // Nein, der String ist z.B. "test.pdf.exe". Der Match ist ".pdf.exe".
            // Wenn wir ".pdf.exe" durch ".pdf" ersetzen, bekommen wir "test.pdf". Das ist korrekt.
            
            // Aber was ist wenn es "test.exe.pdf" ist? Das ist nicht im Regex (das Regex sucht gefährliche Endungen am Ende).
            // Das Regex sucht nur nach gefährlichen Endungen am Ende.
            // Also ist "test.pdf.exe" -> ".pdf.exe" Match -> Replace mit ".pdf" -> "test.pdf".
            
            // Wie extrahieren wir die "sichere" Endung aus dem Match?
            // Der Match ist z.B. ".pdf.exe". Die erste Endung ist ".pdf".
            // Wir können den Match aufsplitten oder einfach die letzte gefährliche Endung entfernen.
            // Einfacher: Wenn der Match ".pdf.exe" ist, entfernen wir ".exe" vom Ende des Strings?
            // Nein, der Match ist Teil des Strings.
            
            // Lass uns den Match analysieren.
            // Match: ".pdf.exe"
            // Wir wollen die erste Endung behalten: ".pdf"
            // Die erste Endung ist alles außer der letzten gefährlichen Endung.
            // Gefährliche Endungen: exe, scr, etc.
            // Im Match ".pdf.exe" ist die letzte gefährliche Endung ".exe".
            // Wir ersetzen den Match durch den Match minus die letzte gefährliche Endung.
            
            // Alternativ: Wir suchen die Position der letzten gefährlichen Endung im Match.
            // Das Regex hat bereits die gefährliche Endung am Ende.
            // Wir können einfach die letzte gefährliche Endung aus dem Match entfernen.
            
            // Aber das Regex ist komplex. Lass uns eine einfachere Strategie verwenden:
            // Wenn eine doppelte Endung gefunden wird, entfernen wir die *letzte* gefährliche Endung vom Dateinamen.
            
            // Da das Regex nur am Ende matcht, können wir einfach die letzte gefährliche Endung vom sanitized String entfernen.
            // Aber wir müssen sicherstellen, dass wir die richtige Endung entfernen.
            
            // Besser: Wir ersetzen den gefundenen Match mit der "sicheren" Version.
            // Match: ".pdf.exe"
            // Sichere Version: ".pdf"
            
            // Wie bekommen wir ".pdf" aus ".pdf.exe"?
            // Wir wissen, dass die gefährlichen Endungen aus einer bekannten Liste stammen.
            // Wir können den Match prüfen, ob er mit ".pdf" beginnt? Nein, das ist zu spezifisch.
            
            // Einfachste Lösung: Entferne die letzte gefährliche Endung vom String.
            // Aber wir müssen die Grenze zwischen der ersten und zweiten Endung finden.
            
            // Alternative: Wenn DOUBLE_EXT matcht, ist die Datei gefährlich.
            // Wir entfernen die *letzte* Endung (die gefährliche) und behalten die erste.
            // Der Match enthält beide.
            // Wenn der Match ".pdf.exe" ist, ist die erste Endung ".pdf" und die zweite ".exe".
            // Wir können den Match aufteilen durch den letzten Punkt?
            // ".pdf.exe".lastIndexOf('.') -> Index von .exe
            // String safePart = match.substring(0, match.lastIndexOf('.')); // ".pdf"
            
            String safeExtensionPart = match.substring(0, match.lastIndexOf('.'));
            // Wenn safeExtensionPart leer ist oder nur Punkte, dann ist etwas falsch.
            // Aber das Regex verlangt min. 1 Zeichen für die erste Endung.
            
            sanitized = doubleExtMatcher.replaceFirst(safeExtensionPart);
        }

        // 6. Führende und abschließende Punkte und Leerzeichen trimmen
        sanitized = trimLeadingAndTrailing(sanitized);

        // 7. Prüfen ob der Name leer ist
        if (sanitized.isEmpty()) {
            LOG.warning("Sanitized attachment name is empty, using fallback name: " + FALLBACK_NAME);
            return FALLBACK_NAME;
        }

        // 8. Länge begrenzen und Endung erhalten
        sanitized = limitLength(sanitized);

        return sanitized;
    }

    public boolean isUnsafe(String name) {
        if (name == null) {
            return true;
        }

        // Wenn der Name sich nach dem Sanitizing ändert, war er unsicher
        String sanitized = sanitize(name);
        return !name.equals(sanitized);
    }

    private String decodeRfc2047(String input) {
        // Vereinfachte Dekodierung von =XX Sequenzen
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '=' && i + 2 < input.length()) {
                char hex1 = input.charAt(i + 1);
                char hex2 = input.charAt(i + 2);
                if (Character.isDigit(hex1) && Character.isDigit(hex2)) {
                    try {
                        int code = Integer.parseInt("" + hex1 + hex2, 16);
                        if (code >= 0 && code < 128) {
                            sb.append((char) code);
                            i += 2;
                            continue;
                        }
                    } catch (NumberFormatException e) {
                        // Fallback
                    }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private String trimLeadingAndTrailing(String s) {
        int start = 0;
        while (start < s.length() && (s.charAt(start) == '.' || s.charAt(start) == ' ')) {
            start++;
        }
        int end = s.length();
        while (end > start && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) {
            end--;
        }
        return s.substring(start, end);
    }

    private String limitLength(String s) {
        if (s.length() <= MAX_LENGTH) {
            return s;
        }

        // Endung identifizieren
        int lastDotIndex = s.lastIndexOf('.');
        if (lastDotIndex > 0) {
            String base = s.substring(0, lastDotIndex);
            String ext = s.substring(lastDotIndex);

            // Maximale Länge für den Basisteil berechnen
            int maxBaseLength = MAX_LENGTH - ext.length();
            if (maxBaseLength <= 0) {
                // Endung ist zu lang, verwende Fallback
                LOG.warning("Extension too long, using fallback name: " + FALLBACK_NAME);
                return FALLBACK_NAME;
            }

            String truncatedBase = base.substring(0, maxBaseLength);
            // Wenn die gekürzte Endung mit einem Punkt endet, entfernen wir ihn, da ext bereits mit Punkt beginnt
            if (truncatedBase.endsWith(".")) {
                truncatedBase = truncatedBase.substring(0, truncatedBase.length() - 1);
            }
            
            return truncatedBase + ext;
        } else {
            // Keine Endung
            return s.substring(0, MAX_LENGTH);
        }
    }
}