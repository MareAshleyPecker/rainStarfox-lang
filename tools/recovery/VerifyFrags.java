import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Compares the Chinese text runs of a source file against those of the pristine build's class file.
 * Both sides are split the same way, so interpolation boundaries cancel out and the differences
 * that remain are real.
 */
public class VerifyFrags {
    static final Pattern RUN = Pattern.compile(
            "[\u4e00-\u9fff\u3000-\u303f\uff01-\uff5e\u2018-\u201d\u2014\u2026\u2190-\u2193]{2,}");

    public static void main(String[] args) throws IOException {
        Set<String> fromClass = runs(new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8));
        String src = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        Set<String> fromSrc = runs(src);
        StringBuilder sb = new StringBuilder();
        sb.append("class runs: ").append(fromClass.size()).append("  source runs: ").append(fromSrc.size()).append('\n');
        for (String f : fromClass) {
            if (!fromSrc.contains(f)) {
                sb.append("ONLY-IN-CLASS: ").append(f).append("   (in source: ")
                  .append(src.contains(f) ? "yes(inside a longer run)" : "no").append(")\n");
            }
        }
        for (String f : fromSrc) {
            if (!fromClass.contains(f)) sb.append("ONLY-IN-SOURCE: ").append(f).append('\n');
        }
        Files.write(Paths.get(args[2]), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("report written");
    }

    static Set<String> runs(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = RUN.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }
}
