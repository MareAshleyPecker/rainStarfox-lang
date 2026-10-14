// rainStarfox 的编辑器支持：诊断（报错画波浪线）+ 补全。
//
// 实现方式是**调用编译器自己的 CLI**，而不是重新实现一遍分析：
//   rsf check <file> --json
//   rsf complete <file> --line L --col C
// 编译器前端本来就能给出带位置的诊断和作用域信息，复用它最不容易走样。
//
// 代价是每次请求要起一次 JVM，所以编辑器侧做了防抖；如果以后要增量分析，
// 把 runRsf 换成常驻进程即可，上面两处调用形状不用动。

import * as vscode from 'vscode';
import * as cp from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

let output: vscode.OutputChannel;
let tmpDir: string;
let tmpSeq = 0;

export function activate(context: vscode.ExtensionContext): void {
    output = vscode.window.createOutputChannel('rainStarfox');
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'rsf-vscode-'));
    context.subscriptions.push(output);

    const collection = vscode.languages.createDiagnosticCollection('rsf');
    context.subscriptions.push(collection);

    const timers = new Map<string, ReturnType<typeof setTimeout>>();

    function schedule(doc: vscode.TextDocument, delay: number): void {
        if (doc.languageId !== 'rainstarfox' || doc.uri.scheme !== 'file') return;
        const key = doc.uri.toString();
        const old = timers.get(key);
        if (old) clearTimeout(old);
        timers.set(
            key,
            setTimeout(() => {
                timers.delete(key);
                void refreshDiagnostics(doc, collection);
            }, delay),
        );
    }

    context.subscriptions.push(
        vscode.workspace.onDidOpenTextDocument((d) => schedule(d, 0)),
        vscode.workspace.onDidChangeTextDocument((e) => schedule(e.document, delayMs())),
        vscode.workspace.onDidSaveTextDocument((d) => schedule(d, 0)),
        vscode.workspace.onDidCloseTextDocument((d) => collection.delete(d.uri)),
    );

    context.subscriptions.push(
        vscode.languages.registerCompletionItemProvider(
            'rainstarfox',
            {
                async provideCompletionItems(document, position) {
                    return provideCompletions(document, position);
                },
            },
            '.',
        ),
    );

    // 打开已经存在的 .rsf 时先跑一遍
    vscode.workspace.textDocuments.forEach((d) => schedule(d, 0));
}

export function deactivate(): void {
    // 临时目录里的缓冲区副本由系统清理，这里不强求
}

// ------------------------------------------------------------------ 诊断

async function refreshDiagnostics(
    doc: vscode.TextDocument,
    collection: vscode.DiagnosticCollection,
): Promise<void> {
    try {
        const buf = writeBuffer(doc);
        const raw = await runRsf(['check', buf, '--json', '--base', path.dirname(doc.uri.fsPath)]);
        const parsed = JSON.parse(raw) as {
            diagnostics: Array<{
                line: number;
                col: number;
                endCol: number;
                severity: string;
                phase: string;
                message: string;
            }>;
        };

        collection.set(
            doc.uri,
            parsed.diagnostics.map((d) => {
                const line = clamp(d.line - 1, 0, Math.max(doc.lineCount - 1, 0));
                const len = doc.lineAt(line).text.length;
                const start = clamp(d.col - 1, 0, len);
                // 空行时 start == len，end 必须 >= start，否则 Range 非法
                const end = Math.max(start, clamp(d.endCol - 1, start, len));
                const range = new vscode.Range(line, start, line, end);
                const diag = new vscode.Diagnostic(
                    range,
                    d.message,
                    d.severity === 'error'
                        ? vscode.DiagnosticSeverity.Error
                        : vscode.DiagnosticSeverity.Warning,
                );
                diag.source = 'rsf';
                diag.code = d.phase;
                return diag;
            }),
        );
    } catch (e) {
        // 启动器找不到之类的环境问题，别在编辑器里刷屏，写到输出面板就够了
        output.appendLine(`[诊断失败] ${(e as Error).message}`);
        collection.delete(doc.uri);
    }
}

// ------------------------------------------------------------------ 补全

async function provideCompletions(
    doc: vscode.TextDocument,
    position: vscode.Position,
): Promise<vscode.CompletionItem[]> {
    try {
        const buf = writeBuffer(doc);
        const raw = await runRsf([
            'complete',
            buf,
            '--line',
            String(position.line + 1),
            '--col',
            String(position.character + 1),
            '--base',
            path.dirname(doc.uri.fsPath),
        ]);
        const parsed = JSON.parse(raw) as {
            items: Array<{ label: string; kind: string; detail: string }>;
        };

        // 已经敲出来的前缀：用来让 VS Code 能按前缀过滤（否则要用户手动筛选）
        const lineText = doc.lineAt(position.line).text;
        const prefix = /[A-Za-z_][A-Za-z0-9_]*$/.exec(lineText.slice(0, position.character))?.[0] ?? '';
        const range = prefix
            ? new vscode.Range(
                  position.translate(0, -prefix.length),
                  position,
              )
            : undefined;

        return parsed.items.map((it) => {
            const item = new vscode.CompletionItem(it.label, kindOf(it.kind));
            item.detail = it.detail;
            item.range = range;
            // 关键字和类型排在后面，符号排前面
            item.sortText = (it.kind === 'keyword' || it.kind === 'type' ? 'z' : 'a') + it.label;
            return item;
        });
    } catch (e) {
        output.appendLine(`[补全失败] ${(e as Error).message}`);
        return [];
    }
}

function kindOf(kind: string): vscode.CompletionItemKind {
    switch (kind) {
        case 'function':
            return vscode.CompletionItemKind.Function;
        case 'method':
            return vscode.CompletionItemKind.Method;
        case 'field':
            return vscode.CompletionItemKind.Field;
        case 'variable':
            return vscode.CompletionItemKind.Variable;
        case 'type':
            return vscode.CompletionItemKind.TypeParameter;
        default:
            return vscode.CompletionItemKind.Keyword;
    }
}

// ------------------------------------------------------------------ 与编译器通信

/** 把编辑器里的缓冲区写成一个临时文件，交给编译器分析（这样未保存的改动也能看到）。 */
function writeBuffer(doc: vscode.TextDocument): string {
    const file = path.join(tmpDir, `buf${tmpSeq++}-${path.basename(doc.uri.fsPath)}`);
    fs.writeFileSync(file, doc.getText(), 'utf8');
    return file;
}

function delayMs(): number {
    return vscode.workspace.getConfiguration('rainstarfox').get<number>('diagnosticsDelay', 500);
}

/** 解析出启动器：用户配的优先，否则在工作区里找 jar / exe。 */
function launcher(): { cmd: string; args: string[] } {
    const cfg = vscode.workspace.getConfiguration('rainstarfox');
    const javaCmd = cfg.get<string>('javaCommand', 'java');

    const configured = cfg.get<string>('launcher', '');
    if (configured) {
        return wrap(configured, javaCmd);
    }

    for (const folder of vscode.workspace.workspaceFolders ?? []) {
        const root = folder.uri.fsPath;

        const libs = path.join(root, 'build', 'libs');
        if (fs.existsSync(libs)) {
            const jar = fs
                .readdirSync(libs)
                .filter((f) => /^rainStarfox-.*\.jar$/.test(f))
                .sort()
                .pop();
            if (jar) return wrap(path.join(libs, jar), javaCmd);
        }

        const exe = path.join(root, 'build', 'native', 'rainStarfox', 'rainStarfox.exe');
        if (fs.existsSync(exe)) return wrap(exe, javaCmd);
    }

    throw new Error(
        '找不到 rsf 启动器。先跑 `gradlew jar` 或 `gradlew exe`，' +
            '或者用设置 rainstarfox.launcher 指定路径。',
    );
}

function wrap(target: string, javaCmd: string): { cmd: string; args: string[] } {
    return target.toLowerCase().endsWith('.jar')
        ? { cmd: javaCmd, args: ['-jar', target] }
        : { cmd: target, args: [] };
}

function runRsf(args: string[]): Promise<string> {
    const { cmd, args: prefix } = launcher();
    output.appendLine(`> ${cmd} ${[...prefix, ...args].join(' ')}`);
    return new Promise((resolve, reject) => {
        cp.execFile(
            cmd,
            [...prefix, ...args],
            { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, windowsHide: true },
            (err, stdout, stderr) => {
                if (err && !stdout) {
                    reject(new Error(stderr || err.message));
                    return;
                }
                resolve(stdout);
            },
        );
    });
}

function clamp(v: number, lo: number, hi: number): number {
    return Math.max(lo, Math.min(hi, v));
}
