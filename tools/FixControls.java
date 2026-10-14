import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Repairs the leftover control characters of the encoding accident.
 *
 * Where the CP936 decoder met an invalid byte pair it sometimes emitted U+0001 instead of '?'.
 * Those characters are placeholders for a swallowed pair: the last byte of the Chinese character
 * (already restored by the recovery) plus one ASCII byte. This decides that ASCII byte.
 */
public class FixControls {
    public static void main(String[] args) throws IOException {
        Path path = Paths.get(args[0]);
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        int fixed = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= ' ' || c == '\t' || c == '\r' || c == '\n') { out.append(c); continue; }
            char prev = i > 0 ? text.charAt(i - 1) : ' ';
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\n';
            String after = text.substring(i + 1, Math.min(text.length(), i + 40));
            String insert;
            if (next == '{') insert = "$";
            else if (next == 'f') insert = "$";
            else if (next == '\n') insert = "";
            else if (prev == '"') insert = "";
            else if (after.stripLeading().startsWith("*")) insert = "\n";
            else insert = "\"";
            out.append(insert);
            fixed++;
        }
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("control characters repaired: " + fixed);
    }
}
