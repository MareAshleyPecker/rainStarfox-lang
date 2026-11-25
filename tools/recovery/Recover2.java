import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Recovers a source file that was damaged by being read as CP936 and written back as UTF-8.
 *
 * Damage model (verified against the pristine build's string constants):
 * the CP936 decoder consumes two bytes at a time. A lead byte (0x81..0xFE) followed by a byte
 * outside the trail range (0x40..0x7E, 0x80..0xFE) makes the decoder drop BOTH bytes and emit
 * one '?'. In a valid UTF-8 stream the first of those two bytes is always the final byte of a
 * multi-byte character, and the second is an ASCII byte below 0x40 (a quote, a space, a line
 * break, ...). So each placeholder hides exactly two bytes:
 *   - the character's last byte  -> chosen from the 64 characters sharing the surviving prefix
 *   - one ASCII byte             -> almost always '"', ' ' or a line break
 *
 * The last byte is picked with (1) the string constants of the compiled pristine class file,
 * which are exact, and (2) the project's own prose as a language model. The ASCII byte is picked
 * from the shape of the code that follows.
 *
 * usage: java Recover2.java <damaged.kt> <ClassFile.class> <out.kt> <report.txt>
 */
public class Recover2 {

    static final Charset GBK = Charset.forName("GBK");

    public static void main(String[] args) throws IOException {
        Path damaged = Paths.get(args[0]);
        Path classFile = Paths.get(args[1]);
        Path out = Paths.get(args[2]);
        Path report = Paths.get(args[3]);

        String raw = new String(Files.readAllBytes(damaged), StandardCharsets.UTF_8);
        if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') raw = raw.substring(1);

        List<String> fragments = fragmentsOf(classFile);
        String corpus = corpusOf(Paths.get("."));
        Corpus model = new Corpus(corpus);

        StringBuilder sb = new StringBuilder();
        StringBuilder log = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            byte[] stream = toGbkBytes(line);
            sb.append(recoverLine(stream, fragments, model, log));
            sb.append('\n');
        }
        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(report, log.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("lines=" + (raw.split("\n", -1).length) + " repairs logged=" + log.length());
        System.out.println("known string constants with Chinese: " + fragments.size());
        for (int k = 0; k < Math.min(5, fragments.size()); k++) {
            System.out.println("  const[" + k + "]=" + fragments.get(k));
        }
    }

    // ------------------------------------------------------------------ the core

    static String recoverLine(byte[] b, List<String> fragments, Corpus model, StringBuilder log) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < b.length) {
            int c = b[i] & 0xFF;
            if (c < 0x80) { out.append((char) c); i++; continue; }
            int need = c >= 0xF0 ? 3 : c >= 0xE0 ? 2 : c >= 0xC2 ? 1 : -1;
            if (need < 0) { i++; continue; }
            int survived = 0;
            while (survived < need && i + 1 + survived < b.length) {
                int cc = b[i + 1 + survived] & 0xFF;
                if (cc >= 0x80 && cc <= 0xBF) survived++; else break;
            }
            if (survived == need) {
                String s = new String(b, i, need + 1, StandardCharsets.UTF_8);
                out.append(s);
                i += need + 1;
                continue;
            }
            // damaged character: the '?' placeholder that follows swallowed two bytes
            byte[] prefix = Arrays.copyOfRange(b, i, i + survived + 1);
            String left = out.toString();
            int skip = i + survived + 2;
            String right = skip <= b.length ? new String(b, skip, b.length - skip, StandardCharsets.UTF_8) : "";
            String[] pair = lookupPair(fragments, left, right, prefix);
            String ch;
            String tail;
            if (pair != null) {
                ch = pair[0];
                tail = pair[1];
            } else {
                ch = guessChar(prefix, left, right, fragments, model);
                tail = guessTrailing(left, right, fragments, prefix);
            }
            out.append(ch);
            if (!tail.isEmpty()) out.append(tail);
            log.append("prefix=").append(hex(prefix))
               .append(" left=[").append(tail12(left)).append("] right=[").append(head(right)).append("]")
               .append(" -> ").append(ch).append(" + [").append(tail.isEmpty() ? "\\n" : tail).append("]")
               .append(pair != null ? " (exact)" : "")
               .append('\n');
            i = skip;
        }
        return out.toString();
    }

    /**
     * The exact pair of characters the placeholder swallowed, taken from a known text
     * (the string constants of the pristine build): the character that completes the surviving
     * prefix, plus the ASCII character that came after it.
     *
     * The left context is tried from longest to shortest so that short constants
     * (`0 个`, `（有：`, `）`) still match.
     */
    static String[] lookupPair(List<String> texts, String left, String right, byte[] prefix) {
        int maxKey = Math.min(12, left.length());
        for (int keyLen = maxKey; keyLen >= 1; keyLen--) {
            String key = left.substring(left.length() - keyLen);
            for (String t : texts) {
                int from = 0;
                while (true) {
                    int at = t.indexOf(key, from);
                    if (at < 0) break;
                    from = at + 1;
                    int p = at + key.length();
                    if (p + 1 >= t.length()) continue;
                    String cand = String.valueOf(t.charAt(p));
                    if (!matchesPrefix(cand, prefix)) continue;
                    String eaten = String.valueOf(t.charAt(p + 1));
                    if (eaten.charAt(0) >= 0x80) continue;
                    String rest = t.substring(p + 2);
                    int same = 0;
                    while (same < rest.length() && same < right.length() && rest.charAt(same) == right.charAt(same)) same++;
                    int need = Math.min(3, Math.min(rest.length(), right.length()));
                    if (same >= need) return new String[] { cand, eaten };
                }
            }
        }
        return null;
    }

    /** Last byte of the damaged character: exact when the text is a string literal, else the corpus. */
    static String guessChar(byte[] prefix, String left, String right, List<String> fragments, Corpus model) {
        for (String t : fragments) {
            int at = t.indexOf(tail12(left));
            while (at >= 0) {
                int p = at + tail12(left).length();
                if (p < t.length() && matchesPrefix(String.valueOf(t.charAt(p)), prefix)) return String.valueOf(t.charAt(p));
                at = t.indexOf(tail12(left), at + 1);
            }
        }
        return model.best(prefix, left, right);
    }

    /**
     * The ASCII byte that the same damaged pair swallowed.
     *
     * It is the character that followed the Chinese character in the pristine text: a quote when a
     * literal ends, a line break when a comment line ends, a space when prose continues.
     */
    static String guessTrailing(String left, String right, List<String> fragments, byte[] prefix) {
        boolean inLiteral = countQuotes(left) % 2 == 1;
        if (right.isEmpty()) {
            // nothing follows on this line: either the line break was swallowed, or — inside a
            // literal — the closing quote was
            return inLiteral ? "\"" : "";
        }
        String trimmed = right.stripLeading();
        String prev = lastChars(left, 3);
        if (inLiteral) {
            if (trimmed.startsWith("{")) return "$";        // the swallowed byte opened an interpolation
            if (trimmed.startsWith(",") || trimmed.startsWith(")") || trimmed.startsWith("+")
                    || trimmed.startsWith(";") || trimmed.startsWith("]") || trimmed.startsWith("}")) {
                return "\"";
            }
            return " ";
        }
        if (trimmed.startsWith("*") || trimmed.startsWith("/")) return "\n";
        if (looksLikeCode(trimmed)) return "\n";
        return " ";
    }

    static boolean endsSentence(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(s.length() - 1);
        return c == '。' || c == '）' || c == '」' || c == '；' || c == '，' || c == '、' || c == '“' || c == '”';
    }

    static int countQuotes(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '"' && (i == 0 || s.charAt(i - 1) != '\\')) n++;
        }
        return n;
    }

    /** Is {@code cand} the character whose UTF-8 encoding continues the surviving bytes? */
    static boolean matchesPrefix(String cand, byte[] prefix) {
        byte[] cb = cand.getBytes(StandardCharsets.UTF_8);
        if (cb.length != prefix.length + 1) return false;
        for (int k = 0; k < prefix.length; k++) if (cb[k] != prefix[k]) return false;
        return true;
    }

    /**
     * The ASCII byte that the same damaged pair swallowed.
     *
     * It is the character that followed the Chinese character in the pristine text: a quote when a
     * literal ends, a line break when a comment line ends, a space when prose continues.
     */
    static String guessTrailing(String left, String right) {
        if (right.isEmpty()) return "";            // the line break itself; the file already has it
        char first = right.charAt(0);
        char last = left.isEmpty() ? ' ' : left.charAt(left.length() - 1);
        // a block comment that continues on the next line: " * ..."
        if (first == '*' || right.startsWith("*/")) return " ";
        if (first == ' ' && right.length() > 1 && Character.isLetter(right.charAt(1))
                && (last == '。' || last == '）' || last == '」' || last == '，')) {
            return "\n";
        }
        if (first == ' ' && right.length() > 2 && right.charAt(1) == '*' && right.charAt(2) == ' ') return "\n";
        String trimmed = right.stripLeading();
        if (!trimmed.isEmpty() && (last == '。' || last == '；' || last == '）' || last == '」')) {
            if (looksLikeCode(trimmed)) return "\n";
        }
        if (last == '。' && looksLikeCode(trimmed)) return "\n";
        return " ";
    }

    static final Pattern CODE = Pattern.compile(
            "^([}\\])\\*]|//|/\\*|val |var |if |if\\(|else|for |for\\(|while |return|throw |fun |private |public "
            + "|protected |internal |class |import |module |object |companion |when |try |catch |@)");

    static boolean looksLikeCode(String s) { return CODE.matcher(s).find(); }

    // ------------------------------------------------------------------ helpers

    static String head(String s) { return s.length() <= 14 ? s : s.substring(0, 14); }

    static String tail12(String s) { return s.length() <= 12 ? s : s.substring(s.length() - 12); }

    static String lastChars(String s, int n) { return s.length() <= n ? s : s.substring(s.length() - n); }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    /** char sequence -> the CP936 bytes it was decoded from. */
    static byte[] toGbkBytes(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) { out.write(c); continue; }
            if (c == '\u20AC') { out.write(0x80); continue; }
            byte[] b = String.valueOf(c).getBytes(GBK);
            out.write(b, 0, b.length);
        }
        return out.toByteArray();
    }

    /**
     * The string constants of the pristine build.
     *
     * Every string literal of the original source is still in the compiled class file, verbatim
     * (Java's modified UTF-8 is plain UTF-8 for these characters). Reading the constant pool
     * therefore gives the exact text of every literal, including the ASCII around it.
     */
    static List<String> fragmentsOf(Path cls) throws IOException {
        byte[] b = Files.readAllBytes(cls);
        List<String> out = new ArrayList<>();
        int p = 8;
        int count = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
        p += 2;
        for (int i = 1; i < count; i++) {
            int tag = b[p] & 0xFF;
            p++;
            switch (tag) {
                case 1: {
                    int len = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
                    p += 2;
                    String s = new String(b, p, len, StandardCharsets.UTF_8);
                    p += len;
                    if (hasChinese(s) && !out.contains(s)) out.add(s);
                    break;
                }
                case 7: case 8: case 16: case 19: case 20: p += 2; break;
                case 15: p += 3; break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: p += 4; break;
                case 5: case 6: p += 8; i++; break;
                default: return out;   // unknown tag: give up, the caller still has the corpus
            }
        }
        return out;
    }

    static boolean hasChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
            if (c >= 0x3000 && c <= 0x303F) return true;
            if (c >= 0xFF01 && c <= 0xFF5E) return true;
        }
        return false;
    }

    static String corpusOf(Path root) throws IOException {
        StringBuilder sb = new StringBuilder();
        Files.walk(root)
             .filter(p -> p.toString().endsWith(".kt") || p.toString().endsWith(".md") || p.toString().endsWith(".rsf"))
             .filter(p -> !p.toString().contains("build") && !p.toString().contains(".gradle")
                       && !p.toString().contains("tools"))
             .forEach(p -> {
                 try { sb.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append('\n'); }
                 catch (IOException ignored) { }
             });
        return sb.toString();
    }

    /** Character bigram/trigram counts over the project's own Chinese text. */
    static class Corpus {
        private final Map<String, Integer> tri = new HashMap<>();
        private final Map<String, Integer> bi = new HashMap<>();

        Corpus(String text) {
            for (int i = 0; i + 2 < text.length(); i++) {
                tri.merge(text.substring(i, i + 3), 1, Integer::sum);
                bi.merge(text.substring(i, i + 2), 1, Integer::sum);
            }
        }

        String best(byte[] prefix, String left, String right) {
            String bestChar = null;
            int bestScore = -1;
            for (int last = 0x80; last <= 0xBF; last++) {
                byte[] cand = Arrays.copyOf(prefix, prefix.length + 1);
                cand[prefix.length] = (byte) last;
                String s = new String(cand, StandardCharsets.UTF_8);
                if (s.indexOf('\uFFFD') >= 0) continue;
                int score = score(s, left, right);
                if (score > bestScore) { bestScore = score; bestChar = s; }
            }
            if (bestScore <= 0) {
                // nothing in the corpus fits: fall back to the most common CJK punctuation
                if (prefix.length == 3 && (prefix[0] & 0xFF) == 0xE3 && (prefix[1] & 0xFF) == 0x80) return "。";
                if (prefix.length == 3 && (prefix[0] & 0xFF) == 0xEF && (prefix[1] & 0xFF) == 0xBC) return "，";
            }
            return bestChar == null ? "?" : bestChar;
        }

        private int score(String cand, String left, String right) {
            int s = 0;
            if (left.length() >= 2 && !right.isEmpty()) {
                s += 10 * tri.getOrDefault(left.substring(left.length() - 2) + cand + right.charAt(0), 0);
            }
            if (!left.isEmpty()) s += 4 * bi.getOrDefault(left.substring(left.length() - 1) + cand, 0);
            if (!right.isEmpty()) s += 4 * bi.getOrDefault(cand + right.charAt(0), 0);
            if (left.length() >= 2) s += bi.getOrDefault(left.substring(left.length() - 2) + cand, 0);
            return s;
        }
    }
}
