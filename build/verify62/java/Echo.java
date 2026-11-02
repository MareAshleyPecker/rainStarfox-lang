public class Echo {
    private final String tag;

    public Echo(String tag) { this.tag = tag; }

    public static String hi(String name) {
        return "java:" + name;
    }

    public static String join(String a, String b) {
        return a + "-" + b + "-" + a.length();
    }

    public String prefix(String s) {
        return "[" + tag + "]" + s;
    }
}
