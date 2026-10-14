import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Prints every line that contains non-ASCII text, with its line number, for proof-reading. */
public class NonAscii {
    public static void main(String[] args) throws IOException {
        String[] lines = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8).split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean has = false;
            for (int k = 0; k < line.length(); k++) if (line.charAt(k) > 0x7F) { has = true; break; }
            if (has) sb.append(String.format("%4d| ", i + 1)).append(line).append('\n');
        }
        Files.write(Paths.get(args[1]), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("lines written");
    }
}
