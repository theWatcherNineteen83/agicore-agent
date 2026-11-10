package de.metis.mail.model;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dekodiert kodierte Wörter in E-Mail-Headern gemäß RFC 2047.
 * Unterstützt die Codierungen UTF-8, ISO-8859-1 und Windows-1252.
 * Handhabt sowohl Base64 (B) als auch Quoted-Printable (Q) Codierung.
 */
public class Rfc2047HeaderDecoder {

    private static final Logger LOG = Logger.getLogger(Rfc2047HeaderDecoder.class.getName());

    /**
     * Pattern zur Erkennung von RFC 2047 kodierten Wörtern.
     * Format: =?charset?encoding?encoded_text?=
     * encoding: B für Base64, Q für Quoted-Printable.
     * encoded_text: Für B: Base64-Zeichen, für Q: Druckbare Zeichen und _ für Leerzeichen.
     */
    private static final Pattern RFC2047_PATTERN = Pattern.compile(
            "=(?<charset>[^=?]+)\\?(?<encoding>[BQ])\\?(?<data>[^?]+)\\?"
    );

    /**
     * Dekodiert einen einzelnen Header-Wert, der mehrere RFC 2047 kodierte Wörter enthalten kann.
     *
     * @param headerValue Der Header-String, der dekodiert werden soll.
     * @return Der dekodierte String.
     */
    public String decode(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return headerValue;
        }

        StringBuilder result = new StringBuilder(headerValue.length());
        Matcher matcher = RFC2047_PATTERN.matcher(headerValue);
        int lastEnd = 0;

        while (matcher.find()) {
            // Füge den Text vor dem aktuellen Match hinzu
            if (matcher.start() > lastEnd) {
                result.append(headerValue, lastEnd, matcher.start());
            }

            String charsetName = matcher.group("charset");
            String encoding = matcher.group("encoding");
            String encodedData = matcher.group("data");

            // Entferne umgebende Leerzeichen aus dem Charset-Namen (falls vorhanden)
            charsetName = charsetName.trim();

            String decodedSegment = decodeSingleWord(charsetName, encoding, encodedData);

            if (decodedSegment != null) {
                // Prüfe, ob der vorherige Teil des Strings mit einem Leerzeichen endet.
                // RFC 2047 besagt, dass Leerzeichen zwischen benachbarten kodierten Wörtern
                // ignoriert werden sollen, wenn sie direkt aneinander grenzen.
                // Hier behandeln wir die Verkettung direkt. Wenn im Original-String
                // zwischen zwei Matches nur ein Leerzeichen war, wird dieses hier
                // implizit verworfen, da wir nur die decodedSegments anhängen.
                // Aber wir müssen sicherstellen, dass wir nicht versehentlich
                // sinnvolle Leerzeichen entfernen, die NICHT zwischen Matches liegen.
                
                // Wenn der bisherige String mit einem Leerzeichen endet und der nächste Match
                // direkt nach einem einzigen Leerzeichen im Original kam, sollte das Leerzeichen
                // entfernt werden. 
                
                // Einfache Herangehensweise: Wir fügen das decodierte Wort an.
                // Wenn der letzte Hinzugefügte Charakter ein Leerzeichen war und der nächste
                // Match im Original direkt danach begann (also kein anderer Text dazwischen),
                // entfernen wir das Leerzeichen.
                
                // Da wir die Positionen kennen:
                // Wenn headerValue.charAt(matcher.start() - 1) == ' ' und
                // result.length() > 0 und result.charAt(result.length()-1) == ' ',
                // dann entfernen wir das letzte Leerzeichen von result.
                
                if (result.length() > 0 && result.charAt(result.length() - 1) == ' ') {
                    // Prüfen, ob im Original zwischen dem letzten End und dem aktuellen Start
                    // nur ein einzelnes Leerzeichen war.
                    int prevEnd = lastEnd; // Letzter End-Index vom letzten Mal
                    // Wir müssen wissen, was zwischen lastEnd und matcher.start() war.
                    // Wenn es genau ein Leerzeichen war, entfernen wir es.
                    String between = headerValue.substring(prevEnd, matcher.start());
                    if (between.equals(" ")) {
                        result.deleteCharAt(result.length() - 1);
                    }
                }

                result.append(decodedSegment);
            }

            lastEnd = matcher.end();
        }

        // Füge den restlichen Text nach dem letzten Match hinzu
        if (lastEnd < headerValue.length()) {
            result.append(headerValue, lastEnd, headerValue.length());
        }

        return result.toString();
    }

    /**
     * Dekodiert alle Werte in einem Map von Headern.
     *
     * @param headers Ein Map mit Header-Namen als Keys und Header-Werten als Values.
     * @return Ein neues Map mit den dekodierten Werten.
     */
    public Map<String, String> decodeHeader(Map<String, String> headers) {
        if (headers == null) {
            return new HashMap<>();
        }

        Map<String, String> decodedHeaders = new HashMap<>(headers.size());
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            decodedHeaders.put(entry.getKey(), decode(entry.getValue()));
        }
        return decodedHeaders;
    }

    /**
     * Dekodiert ein einzelnes RFC 2047 kodierte Wort.
     *
     * @param charsetName Der Name des Charsets.
     * @param encoding    Die Codierung ('B' oder 'Q').
     * @param encodedData Die codierten Daten.
     * @return Der dekodierte String oder null, wenn das Charset unbekannt oder die Dekodierung fehlschlägt.
     */
    private String decodeSingleWord(String charsetName, String encoding, String encodedData) {
        // Normalisiere den Charset-Namen für die Suche
        String normalizedCharsetName = normalizeCharsetName(charsetName);

        Charset charset = getSupportedCharset(normalizedCharsetName);
        if (charset == null) {
            LOG.fine("Unbekanntes oder nicht unterstütztes Charset: " + charsetName + " in RFC 2047 Wort");
            return null;
        }

        try {
            byte[] decodedBytes;

            switch (encoding) {
                case "B":
                    decodedBytes = Base64.getDecoder().decode(encodedData);
                    break;
                case "Q":
                    // Für Q-Codierung müssen wir _ zu Leerzeichen konvertieren und dann dekodieren
                    String qData = encodedData.replace('_', ' ');
                    // In Q-Codierung sind escape-Sequenzen der Form =XX (hex) möglich,
                    // aber RFC 2047 Q-Codierung verwendet einfache hex escapes für nicht-druckbare Zeichen.
                    // Standard Java String hat keinen eingebauten QP-Decoder.
                    // Wir müssen es manuell oder über eine einfache Umwandlung machen.
                    // RFC 2047 Q: "=" hex hex -> byte, "_" -> " ", alles andere -> byte
                    decodedBytes = decodeQpToBytes(qData);
                    break;
                default:
                    LOG.fine("Unbekannte Encoding: " + encoding);
                    return null;
            }

            return new String(decodedBytes, charset);

        } catch (IllegalArgumentException e) {
            LOG.fine("Fehler bei der Dekodierung von RFC 2047 Wort: " + e.getMessage());
            return null;
        } catch (Exception e) {
            LOG.fine("Unerwarteter Fehler bei der Dekodierung von RFC 2047 Wort: " + e.getMessage());
            return null;
        }
    }

    /**
     * Dekodiert Quoted-Printable-String gemäß RFC 2047 Q-Codierung zu Bytes.
     * "_" wird zu einem Leerzeichen konvertiert.
     * "=XX" (hex) wird zu einem Byte konvertiert.
     *
     * @param qData Der Q-codierte String (bereits mit _ durch Leerzeichen ersetzt).
     * @return Ein byte-Array mit den dekodierten Werten.
     */
    private byte[] decodeQpToBytes(String qData) {
        if (qData == null || qData.isEmpty()) {
            return new byte[0];
        }

        byte[] result = new byte[qData.length() * 2]; // Maximale Größe schätzen
        int resultIndex = 0;
        int i = 0;

        while (i < qData.length()) {
            char c = qData.charAt(i);

            if (c == '=' && i + 2 < qData.length()) {
                // Prüfe, ob die nächsten zwei Zeichen hex sind
                char hex1 = qData.charAt(i + 1);
                char hex2 = qData.charAt(i + 2);

                if (isHexChar(hex1) && isHexChar(hex2)) {
                    int high = Character.digit(hex1, 16);
                    int low = Character.digit(hex2, 16);
                    result[resultIndex++] = (byte) ((high << 4) | low);
                    i += 3;
                    continue;
                }
            }

            // Normaler Zeichen: als UTF-8 Byte einfügen?
            // Nein, RFC 2047 Q-Codierung: Nicht-codierte Zeichen sind ASCII.
            // Da wir den String bereits haben und nur die escaped Sequenzen behandeln,
            // müssen wir den normalen ASCII-Char als Byte einfügen.
            // Aber was, wenn der String bereits UTF-8-Zeichen enthält?
            // In RFC 2047 Q-Codierung sind die "rohen" Zeichen immer 7-Bit ASCII.
            // Die dekodierten Bytes werden dann mit dem angegebenen Charset interpretiert.
            // Also: Jeder nicht-escaped Zeichen ist ein ASCII-Char -> sein ASCII-Wert.
            result[resultIndex++] = (byte) c;
            i++;
        }

        // Kopiere nur die genutzten Bytes
        if (resultIndex < result.length) {
            byte[] trimmed = new byte[resultIndex];
            System.arraycopy(result, 0, trimmed, 0, resultIndex);
            return trimmed;
        }
        return result;
    }

    private boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Normalisiert den Charset-Namen, um gängige Varianten zu erkennen.
     */
    private String normalizeCharsetName(String name) {
        if (name == null) {
            return "";
        }
        String n = name.toLowerCase().replace("-", "").replace("_", "").replace(" ", "");
        // Entferne führende/abschließende Anführungszeichen, falls vorhanden
        if (n.startsWith("\"") && n.endsWith("\"")) {
            n = n.substring(1, n.length() - 1);
        }
        return n;
    }

    /**
     * Liefert das unterstützte Charset basierend auf dem normalisierten Namen.
     */
    private Charset getSupportedCharset(String normalizedName) {
        switch (normalizedName) {
            case "utf8":
            case "utf-8": // Falls nicht normalisiert wurde
            case "utf8unicode":
                return StandardCharsets.UTF_8;
            case "iso88591":
            case "latin1":
            case "iso8859-1":
            case "iso_8859-1":
            case "88591":
                return StandardCharsets.ISO_8859_1;
            case "windows1252":
            case "cp1252":
            case "windows-1252":
            case "ansi_x3.4-1968": // Falscher Alias, aber manchmal benutzt
                try {
                    return Charset.forName("windows-1252");
                } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                    LOG.fine("Windows-1252 nicht verfügbar: " + e.getMessage());
                    return null;
                }
            default:
                // Versuche, das Charset direkt zu finden, falls es im JDK verfügbar ist
                try {
                    // Versuche, den originalen Namen zu finden (falls nicht normalisiert)
                    return Charset.forName(normalizedName);
                } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                    return null;
                }
        }
    }

    /**
     * Hilfsklasse für Standard-Charsets, um von Charset.forName() unabhängig zu sein,
     * wo möglich.
     */
    private static class StandardCharsets {
        public static final Charset UTF_8;
        public static final Charset ISO_8859_1;

        static {
            try {
                UTF_8 = Charset.forName("UTF-8");
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
            try {
                ISO_8859_1 = Charset.forName("ISO-8859-1");
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}