package de.metis.mail.model;

import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MailSnippetGenerator {
    private static final Logger LOG = Logger.getLogger(MailSnippetGenerator.class.getName());

    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?is)<\\s*(script|style)\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>");
    private static final Pattern BR_PATTERN = Pattern.compile("(?is)<\\s*br\\s*/?\\s*>");
    private static final Pattern P_TAG_PATTERN = Pattern.compile("(?is)<\\s*/?\\s*p\\s*/?\\s*>");
    private static final Pattern TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    public String snippet(String htmlOrText) {
        if (htmlOrText == null) {
            return "";
        }

        String processed = htmlOrText;

        processed = SCRIPT_STYLE_PATTERN.matcher(processed).replaceAll("");
        processed = BR_PATTERN.matcher(processed).replaceAll("\n");
        processed = P_TAG_PATTERN.matcher(processed).replaceAll("\n");
        processed = TAG_PATTERN.matcher(processed).replaceAll("");

        processed = decodeEntities(processed);
        processed = WHITESPACE_PATTERN.matcher(processed).replaceAll(" ");
        processed = processed.trim();

        if (processed.isEmpty()) {
            return "";
        }

        int maxLength = 200;
        if (processed.length() > maxLength) {
            String truncated = processed.substring(0, maxLength);
            int lastSpace = truncated.lastIndexOf(' ');
            if (lastSpace > 0) {
                truncated = truncated.substring(0, lastSpace);
            }
            return truncated + "...";
        }

        return processed;
    }

    private static String decodeEntities(String text) {
        return text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");
    }
}