package de.metis.modules.speech;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Learns Shanghainese vocabulary from ASR corrections.
 * <p>
 * When Metis mishears Shanghainese text, the correction pair is analysed
 * character-by-character (Chinese has no whitespace word boundaries).
 * New characters and character bigrams are extracted and added to the
 * vocabulary store.
 * <p>
 * Key differences from German VocabularyLearningAction:
 * <ul>
 *   <li>Character-level tokenization (Unicode code points, not whitespace)</li>
 *   <li>Bigram extraction for common character combinations</li>
 *   <li>Shanghainese-specific common character filtering</li>
 *   <li>Written to a Shanghainese vocabulary file</li>
 * </ul>
 * <p>
 * Category: write (modifies vocabulary files).
 * <p>
 * Design: Shanghainese language learning, 2026-06-05.
 */
public class ShanghaineseVocabularyAction implements Action {

    public static final String NAME = "learnShanghaineseVocab";
    private static final Logger LOG = Logger.getLogger(ShanghaineseVocabularyAction.class.getName());

    private final String heardText;
    private final String correctText;
    private final Path vocabPath;

    public ShanghaineseVocabularyAction(String heardText, String correctText) {
        this(heardText, correctText,
                Path.of(System.getProperty("shanghainese.vocab.path",
                        "/data/prometheus/shanghainese-vocab.json")));
    }

    public ShanghaineseVocabularyAction(String heardText, String correctText, Path vocabPath) {
        this.heardText = heardText;
        this.correctText = correctText;
        this.vocabPath = vocabPath;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "write"; }

    @Override
    public ApprovalLevel approvalLevel() {
        return ApprovalLevel.NOTIFY; // safe write, reversible
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            // 1. Extract characters from both texts
            Set<String> heardChars = extractCharacters(heardText);
            Set<String> correctChars = extractCharacters(correctText);

            // 2. Find missing characters (in correct but not heard)
            Set<String> missingChars = new LinkedHashSet<>(correctChars);
            missingChars.removeAll(heardChars);

            // 3. Extract bigrams for better word recognition
            Set<String> correctBigrams = extractBigrams(correctText);
            Set<String> heardBigrams = extractBigrams(heardText);
            Set<String> missingBigrams = new LinkedHashSet<>(correctBigrams);
            missingBigrams.removeAll(heardBigrams);

            // 4. Filter out common characters (very high-frequency CJK)
            Set<String> newChars = missingChars.stream()
                    .filter(c -> !isCommonCjk(c))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            Set<String> newBigrams = missingBigrams.stream()
                    .filter(b -> !isCommonBigram(b))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // 5. Load existing vocabulary
            Map<String, Object> vocab = loadVocab();

            // 6. Update vocabulary
            @SuppressWarnings("unchecked")
            Set<String> existingChars = new LinkedHashSet<>(
                    (List<String>) vocab.getOrDefault("characters", List.of()));
            @SuppressWarnings("unchecked")
            Set<String> existingBigrams = new LinkedHashSet<>(
                    (List<String>) vocab.getOrDefault("bigrams", List.of()));

            int charAdded = 0;
            for (String c : newChars) {
                if (existingChars.add(c)) charAdded++;
            }
            int bigramAdded = 0;
            for (String b : newBigrams) {
                if (existingBigrams.add(b)) bigramAdded++;
            }

            // 7. Track learning stats
            double retention = correctChars.isEmpty() ? 0 :
                    (double) intersection(heardChars, correctChars).size() / correctChars.size();

            // 8. Save updated vocabulary
            int charAddedCount = charAdded;
            int bigramAddedCount = bigramAdded;
            if (charAdded > 0 || bigramAdded > 0) {
                vocab.put("characters", new ArrayList<>(existingChars));
                vocab.put("bigrams", new ArrayList<>(existingBigrams));
                vocab.put("total_chars_learned",
                        ((Number) vocab.getOrDefault("total_chars_learned", 0)).intValue()
                                + charAddedCount + bigramAddedCount);
                vocab.put("last_updated", Instant.now().toString());
                saveVocab(vocab);

                LOG.info(() -> "Shanghainese vocab: +" + charAddedCount + " chars, +"
                        + bigramAddedCount + " bigrams (retention: "
                        + String.format("%.0f%%", retention * 100) + ")");
            }

            int totalLearned = charAdded + bigramAdded;
            return ActionResult.ok(NAME,
                    String.format("Learned %d items (%d chars, %d bigrams) | vocab: %d total, retention: %.0f%%",
                            totalLearned, charAdded, bigramAdded,
                            existingChars.size() + existingBigrams.size(),
                            retention * 100),
                    start);

        } catch (IOException e) {
            return ActionResult.fail(NAME, "Vocab file error: " + e.getMessage(), start);
        } catch (Exception e) {
            return ActionResult.fail(NAME, "Shanghainese vocab failed: " + e.getMessage(), start);
        }
    }

    // ── character extraction ──

    static Set<String> extractCharacters(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> chars = new LinkedHashSet<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            // Include CJK characters, letters, digits
            if (Character.isIdeographic(cp) || Character.isLetterOrDigit(cp)) {
                chars.add(new String(Character.toChars(cp)));
            }
            i += Character.charCount(cp);
        }
        return chars;
    }

    static Set<String> extractBigrams(String text) {
        if (text == null || text.isBlank()) return Set.of();
        // Extract from CJK-only sequences
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isIdeographic(cp)) {
                cjk.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }

        Set<String> bigrams = new LinkedHashSet<>();
        String cjkStr = cjk.toString();
        for (int i = 0; i < cjkStr.length() - 1; i++) {
            bigrams.add(cjkStr.substring(i, i + 2));
        }
        return bigrams;
    }

    // ── common character filtering ──

    private boolean isCommonCjk(String character) {
        if (character == null || character.isEmpty()) return true;
        int cp = character.codePointAt(0);
        return COMMON_CJK_CODEPOINTS.contains(cp);
    }

    private boolean isCommonBigram(String bigram) {
        return bigram == null || bigram.length() < 2
                || COMMON_BIGRAMS.contains(bigram);
    }

    // Top ~80 most common Chinese characters (by frequency)
    private static final Set<Integer> COMMON_CJK_CODEPOINTS = Set.of(
            0x7684, // 的 (de)
            0x4E00, // 一 (yi)
            0x662F, // 是 (shi)
            0x4E0D, // 不 (bu)
            0x4E86, // 了 (le)
            0x5728, // 在 (zai)
            0x4EBA, // 人 (ren)
            0x6709, // 有 (you)
            0x6211, // 我 (wo)
            0x4ED6, // 他 (ta)
            0x8FD9, // 这 (zhe)
            0x4E2D, // 中 (zhong)
            0x5927, // 大 (da)
            0x6765, // 来 (lai)
            0x4E0A, // 上 (shang)
            0x56FD, // 国 (guo)
            0x4E2A, // 个 (ge)
            0x5230, // 到 (dao)
            0x8BF4, // 说 (shuo)
            0x4EEC, // 们 (men)
            0x4E3A, // 为 (wei)
            0x5B50, // 子 (zi)
            0x548C, // 和 (he)
            0x4E5F, // 也 (ye)
            0x5C31, // 就 (jiu)
            0x8981, // 要 (yao)
            0x4F1A, // 会 (hui)
            0x53EF, // 可 (ke)
            0x4F60, // 你 (ni)
            0x5F97, // 得 (de)
            0x7740, // 着 (zhe)
            0x597D, // 好 (hao)
            0x751F, // 生 (sheng)
            0x80FD, // 能 (neng)
            0x81EA, // 自 (zi)
            0x5B66, // 学 (xue)
            0x5730, // 地 (di)
            0x5C0F, // 小 (xiao)
            0x5929, // 天 (tian)
            0x65F6, // 时 (shi)
            0x51FA, // 出 (chu)
            0x4E0B, // 下 (xia)
            0x770B, // 看 (kan)
            0x6240, // 所 (suo)
            0x7528, // 用 (yong)
            0x884C, // 行 (xing)
            0x8FC7, // 过 (guo)
            0x5E74, // 年 (nian)
            0x5F00, // 开 (kai)
            0x5BF9, // 对 (dui)
            0x91CC, // 里 (li)
            0x8FD8, // 还 (hai)
            0x540E, // 后 (hou)
            0x524D, // 前 (qian)
            0x591A, // 多 (duo)
            0x6CA1, // 没 (mei)
            0x53BB, // 去 (qu)
            0x90A3, // 那 (na)
            0x90FD, // 都 (dou)
            0x5F88, // 很 (hen)
            0x8DDF, // 跟 (gen)
            0x4E0E, // 与 (yu)
            0x4EE5, // 以 (yi)
            0x53CA, // 及 (ji)
            0x5B83  // 它 (ta)
    );

    // Common Chinese bigrams (grammar particles, common compounds)
    private static final Set<String> COMMON_BIGRAMS = Set.of(
            "我们", "他们", "你们", "什么", "怎么", "为什么",
            "因为", "所以", "但是", "虽然", "如果", "可以",
            "没有", "不是", "这个", "那个", "哪个", "一个",
            "已经", "还是", "或者", "而且", "然后", "不过",
            "知道", "觉得", "看到", "听到", "来到", "出来",
            "今天", "明天", "昨天", "现在", "以前", "以后",
            "上海", "北京", "中国", "中文", "英语", "日本",
            "时候", "地方", "东西", "事情", "问题", "方法"
    );

    // ── file I/O ──

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadVocab() throws IOException {
        if (!Files.exists(vocabPath)) {
            Map<String, Object> fresh = new LinkedHashMap<>();
            fresh.put("language", "Shanghainese-Wu");
            fresh.put("characters", new ArrayList<>());
            fresh.put("bigrams", new ArrayList<>());
            fresh.put("total_chars_learned", 0);
            fresh.put("created", Instant.now().toString());
            return fresh;
        }
        String json = Files.readString(vocabPath);
        return parseSimpleJson(json);
    }

    private void saveVocab(Map<String, Object> vocab) throws IOException {
        Files.createDirectories(vocabPath.getParent());
        Files.writeString(vocabPath, toSimpleJson(vocab));
    }

    // ── minimal JSON without external dependencies ──

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return map;
        json = json.substring(1, json.length() - 1);

        int i = 0;
        while (i < json.length()) {
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length()) break;
            if (json.charAt(i) == ',') { i++; continue; }

            // key
            int keyStart = json.indexOf('"', i);
            int keyEnd = json.indexOf('"', keyStart + 1);
            String key = json.substring(keyStart + 1, keyEnd);
            i = json.indexOf(':', keyEnd) + 1;

            // value
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (json.charAt(i) == '"') {
                int vEnd = json.indexOf('"', i + 1);
                map.put(key, json.substring(i + 1, vEnd));
                i = vEnd + 1;
            } else if (json.charAt(i) == '[') {
                int vEnd = json.indexOf(']', i);
                String arrContent = json.substring(i + 1, vEnd);
                List<String> list = Arrays.stream(arrContent.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.replaceAll("^\"|\"$", ""))
                        .collect(Collectors.toList());
                map.put(key, list);
                i = vEnd + 1;
            } else {
                // number or boolean
                int vEnd = i;
                while (vEnd < json.length() && json.charAt(vEnd) != ','
                        && json.charAt(vEnd) != '}' && json.charAt(vEnd) != ']') vEnd++;
                String numStr = json.substring(i, vEnd).trim();
                try {
                    map.put(key, Integer.parseInt(numStr));
                } catch (NumberFormatException e) {
                    map.put(key, numStr);
                }
                i = vEnd;
            }
        }
        return map;
    }

    private static String toSimpleJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{\n");
        int count = 0;
        for (var entry : map.entrySet()) {
            if (count > 0) sb.append(",\n");
            sb.append("  \"").append(entry.getKey()).append("\": ");
            Object val = entry.getValue();
            if (val instanceof String s) {
                sb.append('"').append(escape(s)).append('"');
            } else if (val instanceof List<?> list) {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append('"').append(escape(String.valueOf(list.get(i)))).append('"');
                }
                sb.append(']');
            } else {
                sb.append(val);
            }
            count++;
        }
        sb.append("\n}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static <T> Set<T> intersection(Set<T> a, Set<T> b) {
        Set<T> result = new LinkedHashSet<>(a);
        result.retainAll(b);
        return result;
    }
}
