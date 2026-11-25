import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Repairs the string literals of a recovered source file using the string constants that
 * the pristine build baked into the compiled .class file.
 *
 * usage: java RecoverLiterals.java <recovered.kt> <ClassFile.class> <out.kt>
 */
public class RecoverLiterals {

    public static void main(String[] args) throws IOException {
        Path src = Paths.get(args[0]);
        Path cls = Paths.get(args[1]);
        Path out = Paths.get(args[2]);

        String text = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        List<String> fragments = fragmentsOf(cls);

        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int replaced = 0;
        List<String> flagged = new ArrayList<>();
        Pattern region = Pattern.compile("\"([^\"\\n]*)\"");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.indexOf('"') < 0) { sb.append(line).append('\n'); continue; }
            if (count(line, '"') % 2 != 0) flagged.add((i + 1) + ": " + line);
            Matcher m = region.matcher(line);
            StringBuilder newLine = new StringBuilder();
            int last = 0;
            while (m.find()) {
                String body = m.group(1);
                String fixed = body;
                // split at ${...} interpolations: each piece is one string constant
                List<String> pieces = new ArrayList<>();
                Matcher im = Pattern.compile("\\$\\{[^}]*\\}").matcher(body);
                int p = 0;
                while (im.find()) {
                    pieces.add(body.substring(p, im.start()));
                    p = im.end();
                }
                pieces.add(body.substring(p));
                StringBuilder rebuilt = new StringBuilder();
                int cursor = 0;
                im = Pattern.compile("\\$\\{[^}]*\\}").matcher(body);
                int piece = 0;
                while (piece < pieces.size()) {
                    String target = pieces.get(piece);
                    String best = bestFragment(target, fragments);
                    if (best != null && !best.equals(target)) { replaced++; rebuilt.append(best); }
                    else rebuilt.append(target);
                    piece++;
                    if (best == null && target.contains("?")) flagged.add((i + 1) + ": literal piece still broken: [" + target + "]");
                    if (piece < pieces.size()) {
                        if (im.find()) rebuilt.append(im.group());
                    }
                }
                newLine.append(line, last, m.start()).append('"').append(rebuilt).append('"');
                last = m.end();
            }
            newLine.append(line.substring(last));
            sb.append(newLine).append('\n');
        }

        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("literal pieces replaced: " + replaced);
        System.out.println("flagged lines: " + flagged.size());
        for (String f : flagged) System.out.println("  " + f);
    }

    static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /** Every Chinese run in the class file is a (piece of a) pristine string constant. */
    static List<String> fragmentsOf(Path cls) throws IOException {
        byte[] bytes = Files.readAllBytes(cls);
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(
                "[\u4e00-\u9fff\u3000-\u303f\uff01-\uff5e\u2018-\u201d\u2014\u2026\u2190-\u2193]{2,}")
                .matcher(text);
        while (m.find()) {
            String f = m.group();
            if (!out.contains(f)) out.add(f);
        }
        return out;
    }

    /** The fragment that best explains {@code target}: same length (approx) and few differences. */
    static String bestFragment(String target, List<String> fragments) {
        if (target.isEmpty()) return null;
        int bestDist = Integer.MAX_VALUE;
        String best = null;
        for (String f : fragments) {
            if (Math.abs(f.length() - target.length()) > 3) continue;
            int d = distance(target, f, 4);
            if (d < bestDist) { bestDist = d; best = f; }
        }
        if (best == null) return null;
        int limit = Math.max(1, Math.min(3, target.length() / 6));
        if (target.contains("?") || target.indexOf('\uFFFD') >= 0) limit = Math.max(limit, 3);
        return bestDist <= limit ? best : null;
    }

    /** Levenshtein distance, abandoning a row once it exceeds {@code cap}. */
    static int distance(String a, String b, int cap) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > cap) return cap + 1;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
