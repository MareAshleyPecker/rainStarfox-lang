import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Forward simulation of the accident that damaged a source file.
 *
 * The file was read as CP936 (the ANSI code page of this machine) and written back as UTF-8.
 * The CP936 decoder walks the byte stream two bytes at a time: a byte below 0x80 is an ASCII
 * character, 0x80 is the euro sign, and a lead byte (0x81..0xFE) followed by a valid trail byte
 * (0x40..0x7E or 0x80..0xFE) is one double-byte character. A lead byte followed by an invalid
 * trail consumes BOTH bytes and yields a single replacement character — which is why some
 * ASCII punctuation and line breaks disappeared.
 *
 * Usage: java Forward.java <pristine.kt> <out.kt>
 */
public class Forward {

    public static void main(String[] args) throws IOException {
        byte[] input = Files.readAllBytes(Paths.get(args[0]));
        byte[] mangled = mangle(input);
        Files.write(Paths.get(args[1]), mangled);
        System.out.println("in=" + input.length + " out=" + mangled.length);
    }

    /** UTF-8 bytes -> CP936 decode -> UTF-8 bytes, exactly as PowerShell did it. */
    public static byte[] mangle(byte[] utf8) {
        StringBuilder sb = new StringBuilder();
        Charset gbk = Charset.forName("GBK");
        int i = 0;
        while (i < utf8.length) {
            int b = utf8[i] & 0xFF;
            if (b < 0x80) { sb.append((char) b); i++; continue; }
            if (b == 0x80) { sb.append('\u20AC'); i++; continue; }
            if (b >= 0x81 && i + 1 < utf8.length) {
                int b2 = utf8[i + 1] & 0xFF;
                boolean validTrail = (b2 >= 0x40 && b2 <= 0x7E) || (b2 >= 0x80 && b2 <= 0xFE);
                if (validTrail) {
                    String decoded = new String(new byte[] { (byte) b, (byte) b2 }, gbk);
                    if (decoded.length() == 1 && decoded.charAt(0) != '\uFFFD') {
                        sb.append(decoded.charAt(0));
                        i += 2;
                        continue;
                    }
                }
                // invalid pair: both bytes are consumed, one replacement character comes out
                sb.append('?');
                i += 2;
                continue;
            }
            sb.append('?');
            i++;
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
