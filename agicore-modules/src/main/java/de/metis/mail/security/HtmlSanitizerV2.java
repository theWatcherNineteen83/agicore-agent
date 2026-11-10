package de.metis.mail.security;

import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HtmlSanitizerV2 {

    private static final Logger LOG = Logger.getLogger(HtmlSanitizerV2.class.getName());

    private static final Set<String> ALLOWED_TAGS = Set.of(
            "p", "div", "span", "a", "img", "table", "tr", "td", "th", "ul", "ol", "li", "b", "i", "br"
    );

    // Matches a complete HTML tag: <name attributes>
    private static final Pattern TAG_PATTERN = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:[^<>\"']|\"[^\"]*\"|'[^']*')*?)(/?)>");

    // Matches attribute name and optional value: name="value" or name='value' or name
    private static final Pattern ATTR_PATTERN = Pattern.compile("(\\w[\\w-]*)\\s*(?:=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]*)))?");

    // Matches script tag content: <script ...>...</script>
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Matches style tag content: <style ...>...</style>
    private static final Pattern STYLE_PATTERN = Pattern.compile("<style[^>]*>.*?</style>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public String sanitize(String input) {
        if (input == null) {
            return "";
        }
        try {
            return doSanitize(input);
        } catch (Exception e) {
            LOG.severe("Sanitization failed: " + e.getMessage());
            return "";
        }
    }

    private String doSanitize(String input) {
        String result = input;

        // Remove script and style tags with content
        result = SCRIPT_PATTERN.matcher(result).replaceAll("");
        result = STYLE_PATTERN.matcher(result).replaceAll("");

        // Process all remaining tags
        Matcher matcher = TAG_PATTERN.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String groupSlash = matcher.group(1); // "/" for closing tag
            String tagName = matcher.group(2).toLowerCase();
            String attributes = matcher.group(3);
            String groupSelfClose = matcher.group(4); // "/" for self-closing

            if (ALLOWED_TAGS.contains(tagName)) {
                String cleanAttributes = cleanAttributes(tagName, attributes);
                String replacement;
                if (groupSlash != null && !groupSlash.isEmpty()) {
                    // Closing tag
                    replacement = "</" + tagName + ">";
                } else {
                    // Opening tag
                    if (groupSelfClose != null && !groupSelfClose.isEmpty()) {
                        replacement = "<" + tagName + cleanAttributes + " />";
                    } else {
                        replacement = "<" + tagName + cleanAttributes + ">";
                    }
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                // Not allowed, remove the tag (keep content if it's an opening tag)
                matcher.appendReplacement(sb, "");
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String cleanAttributes(String tagName, String attributes) {
        if (attributes == null || attributes.trim().isEmpty()) {
            return "";
        }

        StringBuilder cleanAttrs = new StringBuilder();
        Matcher attrMatcher = ATTR_PATTERN.matcher(attributes);
        while (attrMatcher.find()) {
            String attrName = attrMatcher.group(1).toLowerCase();
            String attrValue;

            // Determine the value based on which group matched
            if (attrMatcher.group(2) != null) {
                attrValue = attrMatcher.group(2);
            } else if (attrMatcher.group(3) != null) {
                attrValue = attrMatcher.group(3);
            } else if (attrMatcher.group(4) != null) {
                attrValue = attrMatcher.group(4);
            } else {
                attrValue = ""; // Attribute without value
            }

            // Remove on* attributes
            if (attrName.startsWith("on")) {
                continue;
            }

            // Clean URL attributes
            if (attrValue != null) {
                String lowerValue = attrValue.toLowerCase();
                if (attrName.equals("href") || attrName.equals("src") || attrName.equals("action")) {
                    if (lowerValue.startsWith("javascript:") || lowerValue.startsWith("data:") || lowerValue.startsWith("vbscript:")) {
                        // Neutralize by replacing with empty or safe value
                        attrValue = "";
                    }
                }
            }

            if (cleanAttrs.length() > 0) {
                cleanAttrs.append(" ");
            }
            if (attrValue == null || attrValue.isEmpty()) {
                cleanAttrs.append(attrName);
            } else {
                cleanAttrs.append(attrName).append("=\"").append(sanitizeAttributeValue(attrValue)).append("\"");
            }
        }
        return cleanAttrs.toString();
    }

    private String sanitizeAttributeValue(String value) {
        if (value == null) {
            return "";
        }
        // Remove quotes that might break out of the attribute
        return value.replace("\"", "&quot;").replace("'", "&#39;");
    }
}