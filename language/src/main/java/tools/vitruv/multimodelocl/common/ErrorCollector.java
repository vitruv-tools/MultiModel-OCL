/*******************************************************************************
 * Copyright (c) 2026 Max Oesterle
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Max Oesterle - initial API and implementation
 *******************************************************************************/
package tools.vitruv.multimodelocl.common;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * Collects compilation errors across all compiler passes without halting execution.
 *
 * <p>Enables gathering multiple errors in a single compilation run rather than failing at the first
 * error. Supports severity levels (ERROR, WARNING, INFO) to distinguish between fatal issues and
 * informational messages.
 *
 * <p>Used throughout the 3-pass compilation pipeline:
 *
 * <ul>
 *   <li>Pass 1 (Symbol Table Construction): Undefined variable errors
 *   <li>Pass 2 (Type Checking): Type mismatches, invalid operations
 *   <li>Pass 3 (Evaluation): Runtime errors when enabled
 * </ul>
 *
 * <p>This approach improves developer experience by reporting all issues at once rather than
 * requiring iterative fixing of individual errors.
 *
 * @see CompileError for individual error representation with position and severity
 */
public class ErrorCollector {

  /** All collected errors, warnings, and informational messages */
  private final List<CompileError> errors = new ArrayList<>();

  /**
   * Adds an error with explicit position and severity information.
   *
   * @param line Line number where error occurred (1-based)
   * @param column Column number where error occurred (0-based)
   * @param message Human-readable error description
   * @param severity Error severity level
   * @param source Source file or context identifier
   */
  public void add(int line, int column, String message, ErrorSeverity severity, String source) {
    errors.add(new CompileError(line, column, message, severity, source));
  }

  /**
   * Adds a pre-constructed error object.
   *
   * @param error Complete error instance to add
   */
  public void add(CompileError error) {
    errors.add(error);
  }

  /**
   * Adds an error whose range spans the full {@link ParserRuleContext} — from its first to its
   * last token. This is the preferred overload inside visitors because the stop token gives the
   * precise end of the erroneous sub-expression, making the squiggly underline in VS Code exactly
   * as wide as the offending text.
   *
   * @param ctx      The parse tree node covering the erroneous expression
   * @param message  Human-readable error description
   * @param severity Error severity level
   * @param source   Source identifier (e.g. {@code "type-checker"})
   */
  public void add(
      ParserRuleContext ctx, String message, ErrorSeverity severity, String source) {
    Token start = ctx.getStart();
    Token stop  = ctx.getStop();

    int startLine = start != null ? start.getLine()               : 0;
    int startCol  = start != null ? start.getCharPositionInLine() : 0;
    int endLine;
    int endCol;

    if (stop != null
        && (stop.getLine() > start.getLine()
            || (stop.getLine() == start.getLine()
                && stop.getCharPositionInLine() >= start.getCharPositionInLine()))) {
      // Normal case: stop comes at or after start.
      endLine = stop.getLine();
      endCol  = stop.getCharPositionInLine() + stop.getText().length();
    } else {
      // Inverted or missing stop — ANTLR error recovery produced a bogus span.
      // Fall back to highlighting just the start token.
      endLine = startLine;
      endCol  = startCol + (start != null && start.getText() != null ? start.getText().length() : 1);
    }

    errors.add(new CompileError(startLine, startCol, endLine, endCol, message, severity, source, null));
  }

  /**
   * Adds an error that covers exactly one {@link Token} — the squiggle is as wide as the token text.
   * Use this when you know the precise offending token (e.g. an unknown metamodel name).
   */
  public void add(Token token, String message, ErrorSeverity severity, String source) {
    if (token == null) return;
    int line   = token.getLine();
    int col    = token.getCharPositionInLine();
    int endCol = col + token.getText().length();
    errors.add(new CompileError(line, col, line, endCol, message, severity, source, null));
  }

  /** Convenience overload for a {@link TerminalNode}. */
  public void add(TerminalNode node, String message, ErrorSeverity severity, String source) {
    if (node != null) add(node.getSymbol(), message, severity, source);
  }

  /**
   * Checks if any ERROR-level issues were collected.
   *
   * <p>Warnings and informational messages do not count as errors. Used to determine if compilation
   * should proceed to next phase.
   *
   * @return {@code true} if at least one ERROR was recorded, {@code false} otherwise
   */
  public boolean hasErrors() {
    return errors.stream().anyMatch(e -> e.getSeverity() == ErrorSeverity.ERROR);
  }

  /**
   * Returns all collected errors, warnings, and messages.
   *
   * @return Defensive copy of all collected issues
   */
  public List<CompileError> getErrors() {
    return new ArrayList<>(errors);
  }

  /**
   * Counts ERROR-level issues only.
   *
   * @return Number of ERROR-severity issues (excludes warnings and info messages)
   */
  public int getErrorCount() {
    return (int) errors.stream().filter(e -> e.getSeverity() == ErrorSeverity.ERROR).count();
  }
}