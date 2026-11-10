package de.metis.mail.security;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuthenticationResultsParserV2 {

    private static final Logger LOG = java.util.logging.Logger.getLogger(AuthenticationResultsParserV2.class.getName());

    private final String spfDomain;
    private final String dkimDomain;
    private final String dmarcDomain;
    private final String spfResult;
    private final String dkimResult;
    private final String dmarcResult;

    public AuthenticationResultsParserV2(String spfDomain, String dkimDomain, String dmarcDomain, 
                                         String spfResult, String dkimResult, String dmarcResult) {
        this.spfDomain = spfDomain;
        this.dkimDomain = dkimDomain;
        this.dmarcDomain = dmarcDomain;
        this.spfResult = spfResult;
        this.dkimResult = dkimResult;
        this.dmarcResult = dmarcResult;
    }

    public static AuthenticationResultsParserV2 parse(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            throw new IllegalArgumentException("Authentication-Results header value cannot be null or empty");
        }

        // Regex to match key=value pairs where the value may be quoted
        Pattern kvPattern = Pattern.compile("([a-zA-Z0-9._-]+)\\s*=\\s*\"?([^\"]*?)\"?(?=\\s+[a-zA-Z0-9._-]+=|\\s*$|$)");
        Matcher matcher = kvPattern.matcher(headerValue);

        Map<String, String> pairs = new HashMap<>();

        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase();
            String value = matcher.group(2).trim();
            
            // If the value is empty and the match included quotes, it might be an empty quoted string
            if (value.isEmpty() && headerValue.charAt(matcher.start(2)) == '"') {
                 value = "";
            }
            
            pairs.put(key, value);
            
            LOG.finest("Extracted pair: " + key + " = " + value);
        }

        // Extract SPF
        String spfResult = pairs.getOrDefault("spf", "none");
        String spfDomain = null;
        
        // SPF domain comes from smtp.mailfrom or header.from (if smtp.mailfrom is not present or empty)
        String smtpMailfrom = pairs.get("smtp.mailfrom");
        if (smtpMailfrom != null && !smtpMailfrom.isEmpty()) {
            spfDomain = extractDomain(smtpMailfrom);
        } else {
            String headerFrom = pairs.get("header.from");
            if (headerFrom != null && !headerFrom.isEmpty()) {
                spfDomain = extractDomain(headerFrom);
            }
        }

        // Extract DKIM
        String dkimResult = pairs.getOrDefault("dkim", "none");
        String dkimDomain = null;
        
        // DKIM domain comes from header.d
        String headerD = pairs.get("header.d");
        if (headerD != null && !headerD.isEmpty()) {
            dkimDomain = extractDomain(headerD);
        }

        // Extract DMARC
        String dmarcResult = pairs.getOrDefault("dmarc", "none");
        String dmarcDomain = null;
        
        // DMARC domain comes from header.from
        String headerFromForDmarc = pairs.get("header.from");
        if (headerFromForDmarc != null && !headerFromForDmarc.isEmpty()) {
            dmarcDomain = extractDomain(headerFromForDmarc);
        }

        // Validation: "niemals null wenn ein Key=value im Tag vorhanden ist"
        // If the key existed in the map but extraction failed, we should probably keep the raw value or null?
        // The goal says: "niemals null wenn ein Key=value im Tag vorhanden ist"
        // This implies if `smtp.mailfrom` exists, spfDomain should not be null.
        // My extractDomain returns the full string if no @ is found. So it handles this.
        
        // However, if the key itself is missing from the header, the domain is null.
        // The requirement says "niemals null wenn ein Key=value im Tag vorhanden ist".
        // So if `smtp.mailfrom` key is present, spfDomain must not be null.
        // If `header.d` key is present, dkimDomain must not be null.
        // If `header.from` key is present, dmarcDomain must not be null.
        
        // Let's verify the logic again.
        // If smtp.mailfrom is present, we use it.
        // If smtp.mailfrom is NOT present, but header.from IS, we use header.from for SPF.
        
        // Wait, the requirement says "spf-Domain aus smtp.mailfrom ODER header.from".
        // And "niemals null wenn ein Key=value im Tag vorhanden ist".
        // Does this mean if `smtp.mailfrom` is present, spfDomain must not be null? Yes.
        // Does it mean if `header.from` is present, spfDomain must not be null? 
        // The spec says SPF domain comes from smtp.mailfrom OR header.from.
        // Usually, if smtp.mailfrom is present, it takes precedence.
        // If smtp.mailfrom is absent, but header.from is present, we use header.from.
        
        // Let's refine the extraction to ensure non-null if the source key exists.
        
        if (pairs.containsKey("smtp.mailfrom")) {
             spfDomain = extractDomainNonEmpty(pairs.get("smtp.mailfrom"));
        } else if (pairs.containsKey("header.from")) {
             spfDomain = extractDomainNonEmpty(pairs.get("header.from"));
        }

        if (pairs.containsKey("header.d")) {
             dkimDomain = extractDomainNonEmpty(pairs.get("header.d"));
        }

        if (pairs.containsKey("header.from")) {
             dmarcDomain = extractDomainNonEmpty(pairs.get("header.from"));
        }

        return new AuthenticationResultsParserV2(
            spfDomain, dkimDomain, dmarcDomain,
            spfResult, dkimResult, dmarcResult
        );
    }

    private static String extractDomain(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        // Simple check if it contains @
        int atIndex = value.lastIndexOf('@');
        if (atIndex > 0 && atIndex < value.length() - 1) {
            return value.substring(atIndex + 1).toLowerCase();
        }
        // If no @, assume it's a domain or local part? 
        // RFC 5321 says domain part is after @. 
        // If no @, it might be a local part or a domain name without TLD context?
        // For safety, if no @, return the whole string trimmed and lowercased.
        return value.trim().toLowerCase();
    }

    private static String extractDomainNonEmpty(String value) {
        String domain = extractDomain(value);
        if (domain == null || domain.isEmpty()) {
             // If the value was non-empty but extraction resulted in null/empty (e.g. "@"), 
             // we might want to return the original trimmed value to satisfy "never null if key present"?
             // Or maybe null is acceptable if it's malformed? 
             // The requirement says "niemals null wenn ein Key=value im Tag vorhanden ist".
             // This suggests if the key is in the map, the result should not be null.
             // So if extractDomain returns null/empty, return the original value?
             // Let's return the original value trimmed and lowercased if extraction fails.
             if (value != null && !value.trim().isEmpty()) {
                 return value.trim().toLowerCase();
             }
        }
        return domain;
    }

    public String getSpfDomain() {
        return spfDomain;
    }

    public String getDkimDomain() {
        return dkimDomain;
    }

    public String getDmarcDomain() {
        return dmarcDomain;
    }

    public String getSpfResult() {
        return spfResult;
    }

    public String getDkimResult() {
        return dkimResult;
    }

    public String getDmarcResult() {
        return dmarcResult;
    }

    @Override
    public String toString() {
        return "AuthenticationResultsParserV2{" +
                "spfDomain='" + spfDomain + '\'' +
                ", dkimDomain='" + dkimDomain + '\'' +
                ", dmarcDomain='" + dmarcDomain + '\'' +
                ", spfResult='" + spfResult + '\'' +
                ", dkimResult='" + dkimResult + '\'' +
                ", dmarcResult='" + dmarcResult + '\'' +
                '}';
    }
}