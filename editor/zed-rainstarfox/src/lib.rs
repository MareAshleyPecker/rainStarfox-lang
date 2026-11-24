use zed_extension_api as zed;

/// rainStarfox language server launcher.
///
/// The compiler ships a single fat jar which embeds the LSP server; `rsf lsp`
/// is a thin wrapper around `org.example.complie.lsp.LanguageServer`, which
/// speaks LSP over stdin/stdout (see `src/main/kotlin/org/example/Main.kt`,
/// `cmdLsp()`). There is no port, no `--stdio` flag, and no extra option.
///
/// Zed resolves a bare command name against the extension's own work
/// directory, so `java` has to be resolved through the worktree's `$PATH`
/// first and passed as an absolute path.
struct RainStarfoxExtension;

const JAR_RELATIVE_PATH: &str = "build/libs/rainStarfox-0.9.0.jar";

impl zed::Extension for RainStarfoxExtension {
    fn new() -> Self {
        Self
    }

    fn language_server_command(
        &mut self,
        _language_server_id: &zed::LanguageServerId,
        worktree: &zed::Worktree,
    ) -> zed::Result<zed::Command> {
        let java = worktree.which("java").ok_or_else(|| {
            "找不到 java：请安装 JDK 并把它的 bin 目录加入 PATH（或设置 JAVA_HOME），然后重启 Zed。"
                .to_string()
        })?;

        // The LSP resolves packages and imports relative to the process working
        // directory, and Zed starts language servers in the worktree root, so
        // the jar is addressed relative to that root.
        let root = worktree.root_path();
        let root = root.trim_end_matches(['/', '\\']);
        let jar = format!("{root}/{JAR_RELATIVE_PATH}");

        Ok(zed::Command {
            command: java,
            args: vec!["-jar".to_string(), jar, "lsp".to_string()],
            env: Default::default(),
        })
    }
}

zed::register_extension!(RainStarfoxExtension);
