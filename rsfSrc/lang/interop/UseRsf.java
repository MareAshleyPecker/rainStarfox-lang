// Java 调用 rsf 编译出来的类（反向互操作）。
//
// rsf 的 public 函数会被编译成普通的 public static 方法，类名取根文件名，
// 所以 Java 侧只要类名对得上就能直接调——不需要任何胶水代码。
//
//   gradlew jar
//   java -jar build\libs\rainStarfox-0.9.0.jar build rsfSrc/lang/interop/rsflib.rsf -o out
//   javac -encoding UTF-8 -d out -cp out rsfSrc/lang/interop/UseRsf.java
//   java -cp out UseRsf
//
// 注意 rsf 编译出来的 rsflib 在**默认包**里（rsflib.rsf 没有 `pkg` 声明），
// 所以这里也放在默认包，不需要 import —— `import rsflib;` 是错的：
// Java 的 import 只接包名或「包名.类名」，接不了一个类。

public class UseRsf {

    public static void main(String[] args) {
        // rsflib.rsf 编译出的类名就是 rsflib，函数都是 public static
        System.out.println(rsflib.greet("Java"));
        System.out.println("fact(5) = " + rsflib.fact(5));

        // ?string 在 Java 侧就是普通 String，可能是 null
        System.out.println("maybe(1) = " + rsflib.maybe(1));
        System.out.println("maybe(-1) = " + rsflib.maybe(-1));

        // 传 null 也行——rsf 的参数默认是可变的引用
        System.out.println("greet(null) = " + rsflib.greet(null));
    }
}
