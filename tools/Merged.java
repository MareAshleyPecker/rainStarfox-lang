import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Finds lines where a comment swallowed the code that used to follow it: the accident ate a
 * line break right after a Chinese character, so two source lines ended up on one.
 */
public class Merged {
    static final Pattern CODEY = Pattern.compile(
            "(^|[^A-Za-z0-9_])(val|var|var|imutvar|if|else|for|while|return|rtn|throw|fun|private|public"
            + "|protected|internal|class|import|module|object|companion|try|catch|when|do|const)\\s");

    /** A Chinese character, a gap, then something that is clearly a statement or a call. */
    static final Pattern SWALLOWED = Pattern.compile(
            "[\\u4e00-\\u9fff\\u3001\\u3002\\uff09\\uff0c\\u300b\\u300d]\\s{2,}[A-Za-z_$][A-Za-z0-9_$]*\\s*[({=]");

    public static void main(String[] args) throws IOException {
        String[] lines = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8).split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int c = commentStart(line);
            if (c < 0) continue;
            String body = line.substring(c);
            if (!hasChinese(body)) continue;
            boolean hit = SWALLOWED.matcher(body).find();
            if (!hit) {
                Matcher m = CODEY.matcher(body);
                while (m.find()) {
                    int at = m.start(2);
                    if (at > 0 && body.charAt(at - 1) > 0x7F) { hit = true; break; }
                }
            }
            if (hit) {
                sb.append("line ").append(i + 1).append("  ").append(line).append('\n');
                n++;
            }
        }
        Files.write(Paths.get(args[1]), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("merged-looking lines: " + n);
    }

    static int commentStart(String line) {
        int a = line.indexOf("//");
        int b = line.indexOf("/*");
        int c = line.indexOf('*');
        int best = -1;
        if (a >= 0) best = a;
        if (b >= 0 && (best < 0 || b < best)) best = b;
        if (best < 0 && c == line.indexOf('*')) {
            String t = line.stripLeading();
            if (t.startsWith("*")) best = line.indexOf('*');
        }
        return best;
    }

    static boolean hasChinese(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7F) return true;
        return false;
    }
}
