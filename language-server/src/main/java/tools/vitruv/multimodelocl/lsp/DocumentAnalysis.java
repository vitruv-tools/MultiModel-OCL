/*******************************************************************************
 * Copyright (c) 2026 Max Oesterle
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package tools.vitruv.multimodelocl.lsp;

import java.util.List;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.eclipse.lsp4j.Diagnostic;
import tools.vitruv.multimodelocl.OCLParser;
import tools.vitruv.multimodelocl.typechecker.Type;

/**
 * Immutable snapshot of one full analysis cycle for a single document.
 *
 * <p>Created by {@link DocumentAnalyzer} after every debounced edit and stored per URI in
 * {@link OCLTextDocumentService}. Consumed synchronously by hover and completion requests.
 */
public final class DocumentAnalysis {

  private final OCLParser.ContextDeclCSContext tree;
  private final ParseTreeProperty<Type> nodeTypes;
  private final List<Diagnostic> diagnostics;

  public DocumentAnalysis(
      OCLParser.ContextDeclCSContext tree,
      ParseTreeProperty<Type> nodeTypes,
      List<Diagnostic> diagnostics) {
    this.tree = tree;
    this.nodeTypes = nodeTypes;
    this.diagnostics = List.copyOf(diagnostics);
  }

  /** The ANTLR parse tree root — may contain error nodes when the document has syntax errors. */
  public OCLParser.ContextDeclCSContext getTree() {
    return tree;
  }

  /**
   * Type annotations produced by {@link tools.vitruv.multimodelocl.typechecker.TypeCheckVisitor}.
   * May be {@code null} when type checking was skipped due to severe syntax errors.
   */
  public ParseTreeProperty<Type> getNodeTypes() {
    return nodeTypes;
  }

  /** All diagnostics (syntax errors + type errors) ready to publish via {@code publishDiagnostics}. */
  public List<Diagnostic> getDiagnostics() {
    return diagnostics;
  }
}
