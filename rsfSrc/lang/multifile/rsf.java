package multifile;

// 一个**用 Java 写的**类，故意和 rsf 源码放在同一个目录里，用来演示
// 「多文件程序也能 import JVM 类」这件事。
//
// 关键点（README 里也写了）：Java 源文件**不参与 rsf 编译**。rsf 只从 classpath 上
// 读 .class 的签名，所以要先手工 javac 一次，再把输出目录挂到 classpath 上：
//
//   javac -encoding UTF-8 -d build\java-multifile rsfSrc\lang\multifile\rsf.java
//   gradlew run --args="run rsfSrc/lang/multifile/main.rsf"
//
// 类名是 `multifile.rsf`（包 `multifile` + 类 `rsf`），所以 rsf 那边写
// `import multifile.rsf` —— 点分路径和 JVM 的类名一一对应，不是文件路径。
public class rsf {

    /** rsf 侧 `rsf.banner()` 直接调这个方法（public static 才看得见）。 */
    public static String banner() {
        return "hello from a Java class (multifile.rsf)";
    }

    /** 顺便演示一个带参数的静态方法。 */
    public static int triple(int n) {
        return n * 3;
    }
}
