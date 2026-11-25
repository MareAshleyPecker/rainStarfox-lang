import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Reports the places where a recovered source file is still visibly damaged:
 * replacement characters, unbalanced quotes, comments that swallowed code,
 * and question marks that sit inside string literals.
 */
public class Lint {
    public static void main(String[] args) throws IOException {
        List<String> lines = Arrays.asList(
                new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8).split("\n", -1));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            List<String> why = new ArrayList<>();
            if (line.indexOf('\uFFFD') >= 0) why.add("FFFD");
            if (count(line, '"') % 2 != 0) why.add("unbalanced-quote");
            int slash = line.indexOf("//");
            if (slash >= 0) {
                String tail = line.substring(slash);
                for (int k = 1; k < tail.length(); k++) {
                    char c = tail.charAt(k);
                    if (c > 0x7F) { continue; }
                    if (tail.charAt(k - 1) > 0x7F && "{};".indexOf(c) >= 0) { why.add("comment-swallows-code"); break; }
                }
            }
            if (line.indexOf('\uFFFD') >= 0 || line.indexOf("<MainType>") < 0) {
                // question marks inside a literal are almost always a lost byte
                int q = line.indexOf("?\"");
                if (q >= 0) why.add("question-in-literal");
            }
            if (!why.isEmpty()) {
                sb.append("line ").append(i + 1).append(" [").append(String.join(",", why)).append("] ").append(line).append('\n');
                n++;
            }
        }
        Files.write(Paths.get(args[1]), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("flagged lines: " + n + " / " + lines.size());
    }

    static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
