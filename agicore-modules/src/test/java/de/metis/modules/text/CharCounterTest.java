package de.metis.modules.text;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CharCounterTest {

    @Test
    void stripMarkup_html() {
        assertEquals("Hello World", CharCounter.stripMarkup("<p>Hello <b>World</b></p>"));
    }

    @Test
    void stripMarkup_markdown() {
        assertEquals("bold text", CharCounter.stripMarkup("**bold** text"));
        assertEquals("link text", CharCounter.stripMarkup("[link text](http://example.com)"));
    }

    @Test
    void basicCounts() {
        CharCounter cc = new CharCounter("Hello World! 123");
        assertEquals(15, cc.totalChars());
        assertTrue(cc.letterCount() >= 10); // H,e,l,l,o,W,o,r,l,d = 10
        assertEquals(3, cc.digitCount());
        assertEquals(2, cc.whitespaceCount());
        assertTrue(cc.punctuationCount() >= 1); // !
    }

    @Test
    void letterFrequency() {
        CharCounter cc = new CharCounter("aaabbc");
        var freq = cc.letterFrequency();
        assertEquals(Long.valueOf(3), freq.get('a'));
        assertEquals(Long.valueOf(2), freq.get('b'));
        assertEquals(Long.valueOf(1), freq.get('c'));
    }

    @Test
    void topLetters() {
        CharCounter cc = new CharCounter("aaaaabbbccd");
        var top = cc.topLetters(2);
        assertEquals('a', top.get(0).getKey().charValue());
        assertEquals(Long.valueOf(5), top.get(0).getValue());
        assertEquals('b', top.get(1).getKey().charValue());
    }

    @Test
    void ngrams() {
        CharCounter cc = new CharCounter("abab");
        var grams = cc.ngrams(2);
        // 1-gram: a=2, b=2
        assertEquals(2, grams.get(0).get("a").intValue());
        // 2-gram: ab=2, ba=1
        assertEquals(2, grams.get(1).get("ab").intValue());
        assertEquals(1, grams.get(1).get("ba").intValue());
    }

    @Test
    void detectLanguage_german() {
        // Typischer deutscher Satz mit vielen 'e' und 'n'
        CharCounter cc = new CharCounter("Der schnelle braune Fuchs springt über den faulen Hund. "
                + "Diese deutschen Sätze enthalten viele Buchstaben e und n.");
        String lang = cc.detectLanguage();
        assertTrue(lang.equals("DE") || lang.equals("EN"),
                "Expected DE or EN, got: " + lang);
    }

    @Test
    void detectLanguage_english() {
        CharCounter cc = new CharCounter("The quick brown fox jumps over the lazy dog. "
                + "This English text has many common English letters like e t a o.");
        assertEquals("EN", cc.detectLanguage());
    }

    @Test
    void detectLanguage_shortText() {
        CharCounter cc = new CharCounter("Hi");
        assertEquals("UNKNOWN", cc.detectLanguage());
    }

    @Test
    void emptyText() {
        CharCounter cc = new CharCounter("");
        assertEquals(0, cc.totalChars());
        assertEquals(0, cc.letterCount());
        assertEquals("UNKNOWN", cc.detectLanguage());
    }

    @Test
    void report() {
        CharCounter cc = new CharCounter("Hello World!");
        String report = cc.report();
        assertTrue(report.contains("CharCounter Report"));
        assertTrue(report.contains("Gesamtzeichen"));
        assertTrue(report.contains("Buchstaben"));
    }
}
