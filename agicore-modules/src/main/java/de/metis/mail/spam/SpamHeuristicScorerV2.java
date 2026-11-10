package de.metis.mail.spam;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpamHeuristicScorerV2 {

    private static final Logger LOG = Logger.getLogger(SpamHeuristicScorerV2.class.getName());

    private static final Pattern DOMAIN_PATTERN = Pattern.compile("@([\\w.-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_LINK_PATTERN = Pattern.compile("https?://[^\\s<>\"'\\)\\]]+", Pattern.CASE_INSENSITIVE);

    private static final String[] URGENCY_WORDS = {
            "sofort", "jetzt", "passwort", "bestaetige", "konto", "gesperrt"
    };

    private static final String[] SUSPICIOUS_TLDS = {
            ".biz", ".info", ".click"
    };

    public int score(String fromHeader, String returnPathHeader, String subject, String body, String rawHeaders) {
        int score = 0;

        try {
            // 1. sender_mismatch (Points: 20)
            if (checkSenderMismatch(fromHeader, returnPathHeader)) {
                score += 20;
                LOG.log(Level.FINE, "Heuristic triggered: sender_mismatch (+20)");
            }

            // 2. suspicious_tld (Points: 10)
            if (checkSuspiciousTld(fromHeader, returnPathHeader)) {
                score += 10;
                LOG.log(Level.FINE, "Heuristic triggered: suspicious_tld (+10)");
            }

            // 3. uppercase_subject (Points: 20)
            if (checkUppercaseSubject(subject)) {
                score += 20;
                LOG.log(Level.FINE, "Heuristic triggered: uppercase_subject (+20)");
            }

            // 4. link_count (Points: 15)
            if (checkLinkCount(body)) {
                score += 15;
                LOG.log(Level.FINE, "Heuristic triggered: link_count (+15)");
            }

            // 5. urgency_words (Points: 15)
            if (checkUrgencyWords(subject, body)) {
                score += 15;
                LOG.log(Level.FINE, "Heuristic triggered: urgency_words (+15)");
            }

            // 6. missing_auth (Points: 10)
            if (checkMissingAuth(rawHeaders)) {
                score += 10;
                LOG.log(Level.FINE, "Heuristic triggered: missing_auth (+10)");
            }

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error during spam scoring", e);
        }

        // Cap at 100
        if (score > 100) {
            score = 100;
        }

        LOG.log(Level.FINE, "Final spam score: {0}", score);
        return score;
    }

    private boolean checkSenderMismatch(String fromHeader, String returnPathHeader) {
        String fromDomain = extractDomain(fromHeader);
        String returnPathDomain = extractDomain(returnPathHeader);

        if (fromDomain == null || returnPathDomain == null) {
            return false;
        }

        return !fromDomain.equalsIgnoreCase(returnPathDomain);
    }

    private boolean checkSuspiciousTld(String fromHeader, String returnPathHeader) {
        String fromDomain = extractDomain(fromHeader);
        String returnPathDomain = extractDomain(returnPathHeader);

        String domainToCheck = fromDomain != null ? fromDomain : returnPathDomain;
        
        if (domainToCheck == null) {
            return false;
        }

        String lowerDomain = domainToCheck.toLowerCase();
        for (String tld : SUSPICIOUS_TLDS) {
            if (lowerDomain.endsWith(tld)) {
                return true;
            }
        }
        return false;
    }

    private boolean checkUppercaseSubject(String subject) {
        if (subject == null || subject.isEmpty()) {
            return false;
        }

        int totalLetters = 0;
        int upperLetters = 0;

        for (char c : subject.toCharArray()) {
            if (Character.isLetter(c)) {
                totalLetters++;
                if (Character.isUpperCase(c)) {
                    upperLetters++;
                }
            }
        }

        if (totalLetters == 0) {
            return false;
        }

        double ratio = (double) upperLetters / totalLetters;
        return ratio > 0.70;
    }

    private boolean checkLinkCount(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }

        Matcher matcher = HTTP_LINK_PATTERN.matcher(body);
        int count = 0;
        while (matcher.find()) {
            count++;
        }

        return count >= 5;
    }

    private boolean checkUrgencyWords(String subject, String body) {
        String combinedText = "";
        if (subject != null) {
            combinedText += subject + " ";
        }
        if (body != null) {
            combinedText += body;
        }

        String lowerText = combinedText.toLowerCase();
        for (String word : URGENCY_WORDS) {
            if (lowerText.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private boolean checkMissingAuth(String rawHeaders) {
        if (rawHeaders == null) {
            return true;
        }
        String lowerHeaders = rawHeaders.toLowerCase();
        return !lowerHeaders.contains("authentication-results:");
    }

    private String extractDomain(String header) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        Matcher matcher = DOMAIN_PATTERN.matcher(header);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}