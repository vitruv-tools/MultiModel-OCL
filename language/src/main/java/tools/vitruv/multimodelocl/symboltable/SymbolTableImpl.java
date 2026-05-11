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
package tools.vitruv.multimodelocl.symboltable;

import org.eclipse.emf.ecore.EClass;
import tools.vitruv.multimodelocl.pipeline.MetamodelWrapperInterface;
import tools.vitruv.multimodelocl.typechecker.Type;

/**
 * Default implementation of the {@link SymbolTable} interface.
 *
 * <p>This implementation maintains a hierarchy of {@link Scope}s and starts with a {@link
 * GlobalScope} as the initial and current scope.
 *
 * <p>Variables are defined in the currently active scope, whereas types and operations are always
 * defined in the global scope.
 *
 * <p>The symbol table also provides a unified lookup mechanism for semantic {@link Type}s,
 * including primitive OCL types and metamodel-based types.
 *
 * @see SymbolTable
 * @see Scope
 * @see GlobalScope
 */
public class SymbolTableImpl implements SymbolTable {

  /** The global (outermost) scope of the symbol table. */
  private final GlobalScope globalScope;

  /**
   * Wrapper providing access to the underlying EMF metamodels.
   *
   * <p>Used to resolve qualified metamodel types (e.g., {@code Metamodel::Class}).
   */
  private final MetamodelWrapperInterface wrapper;

  /** The currently active scope. */
  private Scope currentScope;

  /**
   * Creates a new symbol table instance.
   *
   * <p>The symbol table is initialized with a {@link GlobalScope} as the current scope.
   *
   * @param wrapper the metamodel wrapper used to resolve metamodel types
   */
  public SymbolTableImpl(MetamodelWrapperInterface wrapper) {
    this.globalScope = new GlobalScope();
    this.currentScope = globalScope;
    this.wrapper = wrapper;
  }

  /**
   * Pushes the given scope onto the scope stack, making it the current active scope.
   *
   * <p>All subsequent symbol lookups and definitions operate within {@code newScope} until {@link
   * #exitScope()} is called.
   *
   * @param newScope the scope to enter; must not be {@code null}
   */
  @Override
  public void enterScope(Scope newScope) {
    currentScope = newScope;
  }

  /**
   * Pops the current scope, restoring the enclosing scope as the active scope.
   *
   * <p>If the current scope has no enclosing scope (i.e. it is the global root scope), the call is
   * a no-op and the current scope remains unchanged.
   */
  @Override
  public void exitScope() {
    if (currentScope.getEnclosingScope() != null) {
      currentScope = currentScope.getEnclosingScope();
    }
  }

  /**
   * Defines a variable symbol in the current active scope.
   *
   * <p>Delegates to {@link Scope#defineVariable} on the current scope. If a symbol with the same
   * name already exists in the current scope, the behaviour is determined by the scope
   * implementation.
   *
   * @param symbol the variable symbol to define; must not be {@code null}
   */
  @Override
  public void defineVariable(VariableSymbol symbol) {
    currentScope.defineVariable(symbol);
  }

  /**
   * Defines a type symbol in the global scope.
   *
   * <p>Type definitions are always registered at the global scope level, regardless of the current
   * active scope, ensuring that type names are universally visible throughout the entire constraint
   * expression.
   *
   * @param symbol the type symbol to define; must not be {@code null}
   */
  @Override
  public void defineType(TypeSymbol symbol) {
    // Types are only defined in the global scope
    globalScope.defineType(symbol);
  }

  /**
   * Defines an operation symbol in the global scope.
   *
   * <p>Operation definitions are always registered at the global scope level, regardless of the
   * current active scope, ensuring that operation names are universally visible throughout the
   * entire constraint expression.
   *
   * @param symbol the operation symbol to define; must not be {@code null}
   */
  @Override
  public void defineOperation(OperationSymbol symbol) {
    // Operations are only defined in the global scope
    globalScope.defineOperation(symbol);
  }

  /**
   * Resolves a variable symbol by name, searching from the current active scope outward.
   *
   * <p>Delegates to {@link Scope#resolveVariable} on the current scope, which walks the enclosing
   * scope chain until the variable is found or the global scope is exhausted.
   *
   * @param name the name of the variable to resolve
   * @return the matching {@link VariableSymbol}, or {@code null} if not found
   */
  @Override
  public VariableSymbol resolveVariable(String name) {
    return currentScope.resolveVariable(name);
  }

  /**
   * Resolves a type symbol by name, searching from the current active scope outward.
   *
   * <p>Delegates to {@link Scope#resolveType} on the current scope, which walks the enclosing scope
   * chain until the type is found or the global scope is exhausted.
   *
   * @param name the name of the type to resolve
   * @return the matching {@link TypeSymbol}, or {@code null} if not found
   */
  @Override
  public TypeSymbol resolveType(String name) {
    return currentScope.resolveType(name);
  }

  /**
   * Resolves an operation symbol by name, searching from the current active scope outward.
   *
   * <p>Delegates to {@link Scope#resolveOperation} on the current scope, which walks the enclosing
   * scope chain until the operation is found or the global scope is exhausted.
   *
   * @param name the name of the operation to resolve
   * @return the matching {@link OperationSymbol}, or {@code null} if not found
   */
  @Override
  public OperationSymbol resolveOperation(String name) {
    return currentScope.resolveOperation(name);
  }

  /**
   * Returns the current active scope.
   *
   * <p>The current scope is updated by calls to {@link #enterScope} and {@link #exitScope}, and
   * reflects the innermost scope at any point during constraint compilation or evaluation.
   *
   * @return the current active {@link Scope}; never {@code null}
   */
  @Override
  public Scope getCurrentScope() {
    return currentScope;
  }

  /**
   * Returns the global scope.
   *
   * <p>The global scope is the outermost scope in the scope chain and serves as the root for all
   * type, operation, and variable lookups. It is created once at construction time and remains
   * constant throughout the lifetime of the symbol table.
   *
   * @return the global {@link Scope}; never {@code null}
   */
  @Override
  public Scope getGlobalScope() {
    return globalScope;
  }

  /**
   * Looks up a semantic {@link Type} by name.
   *
   * <p>The lookup proceeds in the following order:
   *
   * <ol>
   *   <li>Primitive OCL types ({@code Integer}, {@code Double}, {@code Float}, {@code String},
   *       {@code Boolean})
   *   <li>Qualified metamodel types of the form {@code Metamodel::Class}
   *   <li>Unqualified metamodel types registered in the global scope
   *   <li>Short-name fallback: search all loaded metamodels for a class whose simple name matches
   *       (handles unqualified type annotations like {@code Coordinate} in let-expressions)
   * </ol>
   *
   * @param typeName the name of the type (qualified or unqualified)
   * @return the resolved {@link Type}, or {@code null} if not found
   */
  @Override
  public Type lookupType(String typeName) {
    // 1. Primitive types
    switch (typeName) {
      case "Integer":
        return Type.INTEGER;
      case "Double":
      case "Real":
        return Type.DOUBLE;
      case "Float":
        return Type.FLOAT;
      case "String":
        return Type.STRING;
      case "Boolean":
        return Type.BOOLEAN;
      case "OclAny":
        return Type.ANY;
      default:
        break;
    }

    // 2. Qualified metamodel type (Metamodel::Class)
    if (typeName.contains("::")) {
      String[] parts = typeName.split("::");
      if (parts.length == 2) {
        EClass eClass = wrapper.resolveEClass(parts[0], parts[1]);
        if (eClass != null) {
          return Type.metaclassType(eClass);
        }
      }
    }

    // 3. Unqualified type registered in the global scope (e.g., via SymbolTableBuilder)
    TypeSymbol symbol = globalScope.resolveType(typeName);
    if (symbol != null && symbol.getType() != null) {
      return symbol.getType();
    }

    // 4. Short-name fallback: search all loaded metamodels
    //    Handles unqualified annotations like "Coordinate" in let-declarations
    EClass eClass = wrapper.resolveEClassByShortName(typeName);
    if (eClass != null) {
      return Type.metaclassType(eClass);
    }

    return null;
  }
}
