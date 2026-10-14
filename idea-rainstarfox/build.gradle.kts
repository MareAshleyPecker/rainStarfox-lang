import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    kotlin("jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "org.rainstarfox"
version = "0.1.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 用本机已装的 IDEA 当 SDK —— 不下载 1GB 的 IDE 分发包。
        local("D:/WINDOWS/IDEAALL/IntelliJ IDEA 2025.3.3")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // IntelliJ 平台的测试基础设施仍然会碰 JUnit 4 的类（比如跑测试的 executor），
    // 少了它连测试进程都起不来
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

intellijPlatform {
    // 界面全用代码写，没有需要「搜索选项」的表单，关掉省时间
    buildSearchableOptions.set(false)
    // 不是给 IDEA 自己写的 UI 组件，不需要 @NotNull 字节码插桩
    instrumentCode.set(false)

    pluginConfiguration {
        name.set("rainStarfox")
        // 这段文字会由 patchPluginXml 注入最终的 plugin.xml。
        // 必须拉丁字母开头且够长（JetBrains 的硬性校验），所以不用中文。
        description.set(
            "rainStarfox (.rsf) language support for the IntelliJ Platform: syntax highlighting, " +
                "live diagnostics, code completion, and one-click run/build. " +
                "Diagnostics and completion reuse the compiler's own command line " +
                "(rsf check --json / rsf complete), so the IDE and the CLI always agree.",
        )
        ideaVersion {
            sinceBuild.set("253")
            untilBuild.set("253.*")
        }
    }

    // 官方校验器：拿本机 IDE 当靶子，静态检查 API 用法与插件配置
    pluginVerification {
        ides {
            local("D:/WINDOWS/IDEAALL/IntelliJ IDEA 2025.3.3")
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
