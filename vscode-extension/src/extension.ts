import * as vscode from 'vscode';
import * as child_process from 'child_process';
import * as util from 'util';
import * as fs from 'fs';
import * as path from 'path';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
} from 'vscode-languageclient/node';

const execFile = util.promisify(child_process.execFile);

const constraintResults = new Map<string, ConstraintStatus>();
let codeLensProvider: OCLCodeLensProvider;
let currentDecorations: Map<string, vscode.TextEditorDecorationType> = new Map();
let diagnosticCollection: vscode.DiagnosticCollection;
let languageClient: LanguageClient | undefined;

interface ConstraintStatus {
    name: string;
    passed: boolean;
    ran: boolean;
}

interface EvalResult {
    success: boolean;
    satisfied: boolean;
    errors: Array<{ line: number, column: number, message: string, severity: string }>;
    warnings: string[];
}

interface BatchEvalResult {
    success: boolean;
    constraints: ConstraintBatchResult[];
}

interface ConstraintBatchResult {
    name: string;
    success: boolean;
    satisfied: boolean;
    errors?: Array<{ line: number, column: number, message: string }>;
    warnings?: string[];
}

async function applyThemeOnFirstInstall(context: vscode.ExtensionContext): Promise<void> {
    const THEME_KEY = 'multimodelocl.themeApplied';
    const alreadyApplied = context.globalState.get<boolean>(THEME_KEY, false);
    if (alreadyApplied) {
        return;
    }
    const config = vscode.workspace.getConfiguration();
    const currentTheme = config.get<string>('workbench.colorTheme');
    if (currentTheme === 'MultiModelOCL Dark') {
        await context.globalState.update(THEME_KEY, true);
        return;
    }
    const answer = await vscode.window.showInformationMessage(
        'MultiModelOCL: Apply the included "MultiModelOCL Dark" theme for optimized syntax highlighting?',
        'Yes', 'No'
    );
    if (answer === 'Yes') {
        await config.update('workbench.colorTheme', 'MultiModelOCL Dark', vscode.ConfigurationTarget.Global);
    }
    await context.globalState.update(THEME_KEY, true);
}

export function activate(context: vscode.ExtensionContext) {
    applyThemeOnFirstInstall(context);
    diagnosticCollection = vscode.languages.createDiagnosticCollection('multimodelocl');
    context.subscriptions.push(diagnosticCollection);

    codeLensProvider = new OCLCodeLensProvider();

    context.subscriptions.push(
        vscode.languages.registerCodeLensProvider(
            { language: 'multimodelocl' },
            codeLensProvider
        )
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('multimodelocl.runConstraint', async (constraintName, documentUri) => {
            await runConstraint(constraintName, documentUri);
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('multimodelocl.runAllConstraints', async () => {
            await runAllConstraints();
        })
    );

    context.subscriptions.push(
        vscode.window.onDidChangeActiveTextEditor(editor => {
            if (editor && editor.document.languageId === 'multimodelocl') {
                updateGutterIcons(editor);
                updateInlineErrors(editor);
            }
        })
    );

    context.subscriptions.push(
        vscode.workspace.onDidChangeTextDocument(event => {
            const editor = vscode.window.activeTextEditor;
            if (editor && editor.document === event.document && editor.document.languageId === 'multimodelocl') {
                updateGutterIcons(editor);
                updateInlineErrors(editor);
                triggerSuggestAfterInvNewline(editor, event);
            }
        })
    );

    if (vscode.window.activeTextEditor?.document.languageId === 'multimodelocl') {
        updateGutterIcons(vscode.window.activeTextEditor);
        updateInlineErrors(vscode.window.activeTextEditor);
    }

    // Start the language server for live diagnostics, completion, and hover.
    startLanguageClient(context);
}

function updateGutterIcons(editor: vscode.TextEditor) {
    currentDecorations.forEach((decoration, key) => {
        if (key.startsWith('constraint_')) {
            decoration.dispose();
        }
    });

    const text = editor.document.getText();
    const lines = text.split('\n');

    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        const match = line.match(/^\s*context\s+(\S+)\s+inv\s+(\w+)\s*:/);

        if (match) {
            const constraintName = match[2];
            const status = constraintResults.get(constraintName);

            let iconUri: vscode.Uri;

            if (!status || !status.ran) {
                iconUri = createRunIcon();
            } else if (status.passed) {
                iconUri = createPassIcon();
            } else {
                iconUri = createFailIcon();
            }

            const decoration = vscode.window.createTextEditorDecorationType({
                gutterIconPath: iconUri,
                gutterIconSize: 'contain'
            });

            const range = new vscode.Range(i, 0, i, 0);
            editor.setDecorations(decoration, [range]);

            currentDecorations.set(`constraint_${i}`, decoration);
        }
    }
}

interface ViolationStyle {
    symbol: string;
    color: string;
}

const SEVERITY_STYLE: Record<string, ViolationStyle> = {
    CRITICAL: { symbol: '✖', color: '#e05252' },
    WARNING:  { symbol: '⚠', color: '#e0b84a' },
    MAJOR:    { symbol: '⚠', color: '#d4773a' },
    MINOR:    { symbol: '⚠', color: '#c8b84a' },
    INFO:     { symbol: 'ℹ', color: '#5b9bd5' },
};

function parseViolationBlock(block: string): { severity: string; message: string } {
    // MultiModelOCL warning format: "[SEVERITY] constraintName @ filename :: message"
    const sevMatch = block.match(/\[([A-Z]+)\]/);
    const severity = sevMatch ? sevMatch[1] : 'WARNING';
    const sepIdx = block.lastIndexOf(' :: ');
    const message = sepIdx >= 0 ? block.substring(sepIdx + 4).trim() : block;
    return { severity, message };
}

function updateInlineErrors(editor: vscode.TextEditor, constraintDetails?: Map<string, string[]>) {
    const oldInlineDecoration = currentDecorations.get('inline_errors');
    if (oldInlineDecoration) {
        oldInlineDecoration.dispose();
    }

    const text = editor.document.getText();
    const lines = text.split('\n');
    const inlineDecorations: vscode.DecorationOptions[] = [];

    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        const match = line.match(/^\s*context\s+(\S+)\s+inv\s+(\w+)\s*:/);

        if (match) {
            const constraintName = match[2];
            const status = constraintResults.get(constraintName);

            if (status?.ran && !status.passed) {
                const lineLength = line.length;
                const range = new vscode.Range(i, lineLength, i, lineLength);

                const details = constraintDetails?.get(constraintName);

                // Build inline text from first violation
                let inlineText: string;
                let inlineColor: string;
                if (details && details.length > 0) {
                    const { severity, message } = parseViolationBlock(details[0]);
                    const style = SEVERITY_STYLE[severity] ?? SEVERITY_STYLE['WARNING'];
                    inlineColor = style.color;
                    const suffix = details.length > 1 ? ` (+${details.length - 1} more)` : '';
                    inlineText = ` ◀ ${style.symbol} ${severity}: ${message}${suffix}`;
                } else {
                    inlineText = ` ◀ ⚠ WARNING: ${constraintName} violated`;
                    inlineColor = SEVERITY_STYLE['WARNING'].color;
                }

                // Build hover with all violations as formatted blocks
                const hover = new vscode.MarkdownString();
                hover.isTrusted = true;
                hover.supportHtml = true;
                if (details && details.length > 0) {
                    for (const d of details) {
                        const { severity, message } = parseViolationBlock(d);
                        const style = SEVERITY_STYLE[severity] ?? SEVERITY_STYLE['WARNING'];
                        hover.appendMarkdown(`${style.symbol} **${severity}:** ${message}\n\n`);
                    }
                } else {
                    hover.appendMarkdown(`⚠ **WARNING:** ${constraintName} violated`);
                }

                const decoration: vscode.DecorationOptions = {
                    range,
                    hoverMessage: hover,
                    renderOptions: {
                        after: {
                            contentText: inlineText,
                            color: inlineColor,
                            margin: '0 0 0 20px'
                        }
                    }
                };

                inlineDecorations.push(decoration);
            }
        }
    }

    const inlineDecorationType = vscode.window.createTextEditorDecorationType({});
    editor.setDecorations(inlineDecorationType, inlineDecorations);
    currentDecorations.set('inline_errors', inlineDecorationType);
}

function createRunIcon(): vscode.Uri {
    return vscode.Uri.parse('data:image/svg+xml;utf8,' + encodeURIComponent(`
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
            <circle cx="8" cy="8" r="7" fill="none" stroke="#858585" stroke-width="1.5"/>
            <path d="M 6 5 L 6 11 L 11 8 Z" fill="#858585"/>
        </svg>
    `));
}

function createPassIcon(): vscode.Uri {
    return vscode.Uri.parse('data:image/svg+xml;utf8,' + encodeURIComponent(`
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
            <circle cx="8" cy="8" r="7" fill="#73C991"/>
            <path d="M 5 8 L 7 10 L 11 6" stroke="white" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
    `));
}

function createFailIcon(): vscode.Uri {
    return vscode.Uri.parse('data:image/svg+xml;utf8,' + encodeURIComponent(`
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
            <circle cx="8" cy="8" r="7" fill="#F48771"/>
            <path d="M 5.5 5.5 L 10.5 10.5 M 10.5 5.5 L 5.5 10.5" stroke="white" stroke-width="2" stroke-linecap="round"/>
        </svg>
    `));
}

async function runAllConstraints() {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'multimodelocl') {
        vscode.window.showErrorMessage('No multimodelocl file open');
        return;
    }

    const document = editor.document;

    constraintResults.clear();
    diagnosticCollection.clear();
    codeLensProvider.refresh();
    updateGutterIcons(editor);
    updateInlineErrors(editor);

    try {
        const compilerPath = await findCompilerJar();
        if (!compilerPath) {
            vscode.window.showErrorMessage('multimodelocl.jar not found');
            return;
        }

        const ecoreFiles = await findEcoreFiles(document.uri);
        const instanceFiles = await findInstanceFiles(document.uri);

        if (ecoreFiles.length === 0 || instanceFiles.length === 0) {
            vscode.window.showWarningMessage('Missing .ecore or instance files');
            return;
        }

        vscode.window.showInformationMessage('Running all constraints...');

        const batchArgs = [
            '-jar', compilerPath,
            'eval-batch',
            document.fileName,
            '--ecore', ecoreFiles.join(','),
            '--xmi', instanceFiles.join(',')
        ];

        const execResult = await execFile('java', batchArgs).catch(err => ({
            stdout: err.stdout || '',
            stderr: err.stderr || err.message
        }));
        const stdout = execResult.stdout;
        const stderr = (execResult as any).stderr || '';

        // Surface CLI stderr in the output channel so debug prints are visible
        if (stderr && outputChannel) {
            outputChannel.appendLine('[CLI-STDERR] ' + stderr.trim().split('\n').join('\n[CLI-STDERR] '));
        }

        const result = JSON.parse(stdout) as BatchEvalResult;

        if (!result.success) {
            vscode.window.showErrorMessage('Batch evaluation failed');
            return;
        }

        let passed = 0;
        let failed = 0;
        const constraintDetails = new Map<string, string[]>();

        for (const constraint of result.constraints) {
            constraintResults.set(constraint.name, {
                name: constraint.name,
                passed: constraint.satisfied,
                ran: true
            });

            updateDiagnosticsForConstraintBatch(document, constraint);

            if (constraint.satisfied) {
                passed++;
            } else {
                failed++;
                if (constraint.warnings) {
                    constraintDetails.set(constraint.name, constraint.warnings);
                }
            }
        }

        codeLensProvider.refresh();
        updateGutterIcons(editor);
        updateInlineErrors(editor, constraintDetails);

        showSummaryResult(passed, failed, result.constraints);

    } catch (error: any) {
        vscode.window.showErrorMessage(`Run all failed: ${error.message}`);
    }
}

async function runConstraint(constraintName: string, documentUri: vscode.Uri) {
    const document = await vscode.workspace.openTextDocument(documentUri);
    const editor = vscode.window.visibleTextEditors.find(e => e.document === document);

    try {
        const compilerPath = await findCompilerJar();
        if (!compilerPath) {
            vscode.window.showErrorMessage('multimodelocl.jar not found');
            return;
        }

        const ecoreFiles = await findEcoreFiles(documentUri);
        const instanceFiles = await findInstanceFiles(documentUri);

        if (ecoreFiles.length === 0 || instanceFiles.length === 0) {
            vscode.window.showWarningMessage('Missing files');
            return;
        }

        const singleConstraint = extractConstraint(document.getText(), constraintName);
        if (!singleConstraint) {
            vscode.window.showErrorMessage(`Constraint not found: ${constraintName}`);
            return;
        }

        const tempFile = await writeTempConstraint(singleConstraint);

        vscode.window.showInformationMessage(`Running: ${constraintName}...`);

        const execResult = await execFile('java', [
            '-jar', compilerPath, 'eval', tempFile,
            '--ecore', ecoreFiles.join(','),
            '--xmi', instanceFiles.join(',')
        ]).catch(err => ({ stdout: err.stdout || '', stderr: err.stderr || err.message || '' }));

        const stdout = execResult.stdout;
        const stderr = (execResult as any).stderr || '';

        if (stderr && outputChannel) {
            outputChannel.appendLine('[CLI-STDERR] ' + stderr.trim().split('\n').join('\n[CLI-STDERR] '));
        }

        if (!stdout.trim()) {
            const hint = stderr ? `\n\nStderr:\n${stderr.trim()}` : '';
            throw new Error(`Java process produced no output.${hint}`);
        }

        const result = JSON.parse(stdout) as EvalResult;

        constraintResults.set(constraintName, {
            name: constraintName,
            passed: result.satisfied,
            ran: true
        });

        updateDiagnosticsForConstraint(document, constraintName, result);

        codeLensProvider.refresh();
        if (editor) {
            updateGutterIcons(editor);
            const details = new Map<string, string[]>();
            if (!result.satisfied && result.warnings) {
                details.set(constraintName, result.warnings);
            }
            updateInlineErrors(editor, details);
        }

        showEvalResult(constraintName, result);
        fs.unlinkSync(tempFile);

    } catch (error: any) {
        vscode.window.showErrorMessage(`Run failed: ${error.message}`);
        constraintResults.set(constraintName, {
            name: constraintName,
            passed: false,
            ran: true
        });
        codeLensProvider.refresh();
        if (editor) {
            updateGutterIcons(editor);
            updateInlineErrors(editor);
        }
    }
}

function updateDiagnosticsForConstraint(
    document: vscode.TextDocument,
    constraintName: string,
    result: EvalResult
) {
    const diagnostics: vscode.Diagnostic[] = [];

    if (!result.success) {
        for (const error of result.errors) {
            const line = Math.max(0, error.line - 1);
            const range = new vscode.Range(line, 0, line, 1000);
            const diagnostic = new vscode.Diagnostic(
                range,
                `Compilation Error: ${error.message}`,
                vscode.DiagnosticSeverity.Error
            );
            diagnostic.source = 'multimodelocl';
            diagnostics.push(diagnostic);
        }
    }

    const existingDiagnostics = Array.from(diagnosticCollection.get(document.uri) || []);
    const otherDiagnostics = existingDiagnostics.filter(d => {
        const line = d.range.start.line;
        const constraintAtLine = getConstraintNameAtLine(document.getText(), line);
        return constraintAtLine !== constraintName;
    });

    diagnosticCollection.set(document.uri, [...otherDiagnostics, ...diagnostics]);
}

function updateDiagnosticsForConstraintBatch(
    document: vscode.TextDocument,
    constraint: ConstraintBatchResult
) {
    const diagnostics: vscode.Diagnostic[] = Array.from(diagnosticCollection.get(document.uri) || []);

    if (!constraint.success) {
        if (constraint.errors) {
            for (const error of constraint.errors) {
                const line = Math.max(0, error.line - 1);
                const range = new vscode.Range(line, 0, line, 1000);
                const diagnostic = new vscode.Diagnostic(
                    range,
                    `Compilation Error: ${error.message}`,
                    vscode.DiagnosticSeverity.Error
                );
                diagnostic.source = 'multimodelocl';
                diagnostics.push(diagnostic);
            }
        }
    }

    diagnosticCollection.set(document.uri, diagnostics);
}

function findConstraintLine(text: string, constraintName: string): number {
    const lines = text.split('\n');
    for (let i = 0; i < lines.length; i++) {
        const match = lines[i].match(/^\s*context\s+(\S+)\s+inv\s+(\w+)\s*:/);
        if (match && match[2] === constraintName) {
            return i;
        }
    }
    return -1;
}

function getConstraintNameAtLine(text: string, line: number): string | null {
    const lines = text.split('\n');
    if (line >= 0 && line < lines.length) {
        const match = lines[line].match(/^\s*context\s+(\S+)\s+inv\s+(\w+)\s*:/);
        if (match) {
            return match[2];
        }
    }
    return null;
}

class OCLCodeLensProvider implements vscode.CodeLensProvider {
    private _onDidChangeCodeLenses = new vscode.EventEmitter<void>();
    public readonly onDidChangeCodeLenses = this._onDidChangeCodeLenses.event;

    refresh() {
        this._onDidChangeCodeLenses.fire();
    }

    provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
        const codeLenses: vscode.CodeLens[] = [];
        const text = document.getText();
        const lines = text.split('\n');

        let firstContextLine = -1;
        for (let i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith('context')) {
                firstContextLine = i;
                break;
            }
        }

        if (firstContextLine >= 0) {
            codeLenses.push(new vscode.CodeLens(
                new vscode.Range(firstContextLine, 0, firstContextLine, 0),
                {
                    title: '▶ Run All',
                    command: 'multimodelocl.runAllConstraints'
                }
            ));
        }

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i];
            const match = line.match(/^\s*context\s+(\S+)\s+inv\s+(\w+)\s*:/);

            if (match) {
                const constraintName = match[2];

                codeLenses.push(new vscode.CodeLens(
                    new vscode.Range(i, 0, i, line.length),
                    {
                        title: '▶',
                        command: 'multimodelocl.runConstraint',
                        arguments: [constraintName, document.uri]
                    }
                ));
            }
        }

        return codeLenses;
    }
}

function extractConstraint(text: string, constraintName: string): string | null {
    const lines = text.split('\n');
    let collecting = false;
    let constraint = '';
    let startLine = -1;

    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];

        if (line.match(new RegExp(`inv\\s+${constraintName}\\s*:`))) {
            collecting = true;
            startLine = i;
            for (let j = i; j >= 0; j--) {
                if (lines[j].trim().startsWith('context')) {
                    constraint = lines[j] + '\n';
                    break;
                }
            }
        }

        if (collecting && i > startLine) {
            const trimmed = line.trim();
            // Stop at the next constraint's context declaration
            if (trimmed.startsWith('context ')) {
                break;
            }
            // Skip comment lines — the CLI eval command does not strip them
            if (trimmed.startsWith('--')) {
                continue;
            }
            constraint += line + '\n';
        }
    }

    return constraint.trim() || null;
}

async function writeTempConstraint(constraint: string): Promise<string> {
    const os = await import('os');
    const tempDir = os.tmpdir();
    const tempFile = path.join(tempDir, `multimodelocl-${Date.now()}.ocl`);
    fs.writeFileSync(tempFile, constraint, 'utf-8');
    return tempFile;
}

async function findCompilerJar(): Promise<string | null> {
    const config = vscode.workspace.getConfiguration('multimodelocl');
    const configured = config.get<string>('compilerPath');
    if (configured && fs.existsSync(configured)) return configured;

    const extPath = vscode.extensions.getExtension('multimodelocl.multimodelocl')?.extensionPath;
    if (extPath) {
        const bundled = path.join(extPath, 'lib', 'multimodelocl.jar');
        if (fs.existsSync(bundled)) return bundled;
    }

    return null;
}

async function findEcoreFiles(constraintFileUri?: vscode.Uri): Promise<string[]> {
    if (constraintFileUri) {
        const constraintDir = path.dirname(constraintFileUri.fsPath);
        let searchDir = constraintDir;
        const workspaceRoot = vscode.workspace.getWorkspaceFolder(constraintFileUri)?.uri.fsPath;

        while (searchDir && searchDir !== workspaceRoot) {
            const ecorePath = path.join(searchDir, 'ecore');
            if (fs.existsSync(ecorePath)) {
                const entries = fs.readdirSync(ecorePath)
                    .filter(f => f.endsWith('.ecore'))
                    .map(f => path.join(ecorePath, f))
                    .filter(f => fs.statSync(f).isFile());
                return entries;
            }
            const parentDir = path.dirname(searchDir);
            if (parentDir === searchDir) break;
            searchDir = parentDir;
        }

        const files = await vscode.workspace.findFiles(
            new vscode.RelativePattern(constraintDir, '**/ecore/*.ecore')
        );
        return files.map(uri => uri.fsPath);
    }

    return [];
}

async function findInstanceFiles(constraintFileUri?: vscode.Uri): Promise<string[]> {
    if (constraintFileUri) {
        const constraintDir = path.dirname(constraintFileUri.fsPath);
        let searchDir = constraintDir;
        const workspaceRoot = vscode.workspace.getWorkspaceFolder(constraintFileUri)?.uri.fsPath;

        while (searchDir && searchDir !== workspaceRoot) {
            const instancesPath = path.join(searchDir, 'instances');
            if (fs.existsSync(instancesPath)) {
                const entries = fs.readdirSync(instancesPath)
                    .filter(f => !f.startsWith('.'))
                    .map(f => path.join(instancesPath, f))
                    .filter(f => fs.statSync(f).isFile());
                return entries;
            }
            const parentDir = path.dirname(searchDir);
            if (parentDir === searchDir) break;
            searchDir = parentDir;
        }

        const files = await vscode.workspace.findFiles(
            new vscode.RelativePattern(constraintDir, '**/instances/*.*')
        );
        return files.map(uri => uri.fsPath);
    }

    return [];
}

let outputChannel: vscode.OutputChannel | undefined;

function showEvalResult(name: string, result: EvalResult) {
    if (!outputChannel) {
        outputChannel = vscode.window.createOutputChannel('multimodelocl');
    }

    const channel = outputChannel;
    channel.clear();
    channel.show(true);
    channel.appendLine(`=== ${name} ===\n`);

    if (!result.success) {
        channel.appendLine('❌ COMPILATION ERRORS:');
        result.errors.forEach(e => channel.appendLine(`  Line ${e.line}: ${e.message}`));
    } else if (result.satisfied) {
        channel.appendLine('✅ CONSTRAINT SATISFIED\nAll instances pass.');
    } else {
        channel.appendLine('❌ CONSTRAINT VIOLATED');
        if (result.warnings.length > 0) {
            channel.appendLine('\nViolations:');
            result.warnings.forEach(w => channel.appendLine(`  • ${w}`));
        }
    }
    channel.appendLine('');
}

function showSummaryResult(passed: number, failed: number, constraints: ConstraintBatchResult[]) {
    if (!outputChannel) {
        outputChannel = vscode.window.createOutputChannel('multimodelocl');
    }

    const channel = outputChannel;
    channel.clear();
    channel.show(true);

    const total = passed + failed;
    channel.appendLine(`=== All Constraints (${total} total) ===\n`);

    if (failed === 0) {
        channel.appendLine(`✅ ALL PASSED (${passed}/${total})\n`);
    } else {
        channel.appendLine(`❌ SOME FAILED (${failed}/${total})\n`);
        channel.appendLine(`Passed: ${passed}`);
        channel.appendLine(`Failed: ${failed}\n`);
    }

    channel.appendLine('Individual Results:');
    for (const constraint of constraints) {
        const icon = constraint.satisfied ? '✅' : '❌';
        channel.appendLine(`  ${icon} ${constraint.name}`);

        if (!constraint.satisfied && constraint.warnings) {
            constraint.warnings.forEach(w => {
                channel.appendLine(`     ${w}`);
            });
        }
    }

    channel.appendLine('');

    if (failed === 0) {
        vscode.window.showInformationMessage(`✅ All ${total} constraints passed!`);
    } else {
        vscode.window.showWarningMessage(`❌ ${failed}/${total} constraints failed`);
    }
}

/**
 * After the user presses Enter on an 'inv ...:' line, automatically opens
 * the suggestion widget on the new blank/indented line below.
 */
function triggerSuggestAfterInvNewline(
    editor: vscode.TextEditor,
    event: vscode.TextDocumentChangeEvent
): void {
    for (const change of event.contentChanges) {
        if (!change.text.includes('\n')) continue;

        const newLine = change.range.start.line + 1;
        const doc = editor.document;
        if (newLine >= doc.lineCount) continue;

        // The new line must be empty / whitespace only.
        const newLineText = doc.lineAt(newLine).text;
        if (newLineText.trim() !== '') continue;

        // Walk upward from the new line: only suggest annotations when every non-blank
        // line between here and the 'inv ...:' header is itself an annotation line.
        // The moment we hit any other content (OCL body), we stop.
        let inAnnotationZone = false;
        for (let i = newLine - 1; i >= 0; i--) {
            const lineText = doc.lineAt(i).text;
            const trimmed = lineText.trim();
            if (trimmed === '') continue; // blank lines are fine
            if (/\binv\s+\w+\s*:/.test(lineText)) {
                inAnnotationZone = true; // reached the inv header — we're in the zone
                break;
            }
            if (/^\s*@(severity|message)\b/.test(lineText)) continue; // other annotation — ok
            break; // anything else means we're in the OCL body
        }
        if (!inAnnotationZone) continue;

        // Move cursor to end of indentation and fire suggest.
        const indentEnd = newLineText.length;
        const pos = new vscode.Position(newLine, indentEnd);
        editor.selection = new vscode.Selection(pos, pos);
        vscode.commands.executeCommand('editor.action.triggerSuggest');
        break;
    }
}

export function deactivate(): Thenable<void> | undefined {
    currentDecorations.forEach(decoration => decoration.dispose());
    return languageClient?.stop();
}

// ---------------------------------------------------------------------------
// Language client (LSP)
// ---------------------------------------------------------------------------

function startLanguageClient(context: vscode.ExtensionContext): void {
    const serverJar = findLanguageServerJar(context);
    if (!serverJar) {
        console.warn('[OCL-LS] language-server.jar not found — LSP features disabled.');
        return;
    }

    const serverOptions: ServerOptions = {
        command: 'java',
        args: ['-jar', serverJar],
    };

    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ language: 'multimodelocl' }],
        synchronize: {
            // Watch .ecore files so the server can react to metamodel changes (Phase 2).
            fileEvents: vscode.workspace.createFileSystemWatcher('**/*.ecore'),
        },
    };

    languageClient = new LanguageClient(
        'multimodelocl-ls',
        'MultiModel OCL Language Server',
        serverOptions,
        clientOptions
    );

    languageClient.start();
    context.subscriptions.push(languageClient);
}

function findLanguageServerJar(context: vscode.ExtensionContext): string | null {
    // 1. User-configured path.
    const config = vscode.workspace.getConfiguration('multimodelocl');
    const configured = config.get<string>('languageServerPath');
    if (configured && fs.existsSync(configured)) return configured;

    // 2. Bundled alongside the extension in lib/.
    const bundled = path.join(context.extensionPath, 'lib', 'language-server.jar');
    if (fs.existsSync(bundled)) return bundled;

    return null;
}