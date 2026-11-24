use zed_extension_api as zed;

/// rainStarfox language server launcher.
///
/// The compiler ships a single fat jar which embeds the LSP server; `rsf lsp`
/// is a thin wrapper around `org.example.complie.lsp.LanguageServer`, which
/// speaks LSP over stdin/stdout (see `src/main/kotlin/org/example/Main.kt`,
/// `cmdLsp()`). There is no port, no `--stdio` flag, and no extra option.
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
        // The LSP resolves packages and imports relative to the process working
        // directory, and Zed starts language servers in the worktree root, so
        // the jar is addressed relative to that root.
        let root = worktree.root_path();
        let jar = format!("{}/{}", root, JAR_RELATIVE_PATH);

        Ok(zed::Command {
            command: "java".to_string(),
            args: vec!["-jar".to_string(), jar, "lsp".to_string()],
            env: Default::default(),
        })
    }
}

zed::register_extension!(RainStarfoxExtension);
