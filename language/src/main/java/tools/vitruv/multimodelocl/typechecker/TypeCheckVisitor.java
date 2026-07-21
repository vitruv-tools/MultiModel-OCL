package tools.vitruv.multimodelocl.typechecker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcorePackage;
import tools.vitruv.multimodelocl.OCLParser;
import tools.vitruv.multimodelocl.common.AbstractPhaseVisitor;
import tools.vitruv.multimodelocl.common.CompileError;
import tools.vitruv.multimodelocl.common.ErrorCollector;
import tools.vitruv.multimodelocl.common.ErrorSeverity;
import tools.vitruv.multimodelocl.evaluator.EvaluationVisitor;
import tools.vitruv.multimodelocl.pipeline.MetamodelWrapperInterface;
import tools.vitruv.multimodelocl.symboltable.Scope;
import tools.vitruv.multimodelocl.symboltable.ScopeAnnotator;
import tools.vitruv.multimodelocl.symboltable.Symbol;
import tools.vitruv.multimodelocl.symboltable.SymbolTable;
import tools.vitruv.multimodelocl.symboltable.VariableSymbol;

/**
 * Phase 2 visitor that performs static type checking on OCL expressions.
 *
 * <p>This visitor implements the <b>type checking phase</b> of the MultiModelOCLInterface compiler
 * pipeline, operating after symbol table construction (Phase 1) and before evaluation (Phase 3). It
 * validates type rules, ensures type safety, and annotates the parse tree with type information.
 *
 * <h2>Core Responsibilities</h2>
 *
 * <ul>
 *   <li><b>Type inference:</b> Determines the type of each expression in the parse tree
 *   <li><b>Type checking:</b> Validates that operations are applied to compatible types
 *   <li><b>Type annotation:</b> Stores computed types in {@code nodeTypes} for use by the evaluator
 *   <li><b>Error reporting:</b> Collects type errors with source location information
 *   <li><b>Scope management:</b> Handles variable scoping for let-expressions and iterators
 * </ul>
 *
 * <h2>Architecture</h2>
 *
 * The type checker uses a {@code receiverStack} to track receiver types during navigation chain
 * type checking. For example, in {@code person.company.employees}, the stack helps propagate types
 * through the chain:
 *
 * <ol>
 *   <li>Push {@code Person} type
 *   <li>Check {@code .company} → push {@code Company} type
 *   <li>Check {@code .employees} → push {@code Set(Employee)} type
 * </ol>
 *
 * <h2>Type System Features</h2>
 *
 * <ul>
 *   <li>"Everything is a collection" - singletons are {@code Collection(T,1,1)}
 *   <li><b>Collection types:</b> Set, Bag, Sequence, OrderedSet with element types
 *   <li><b>Primitive types:</b> Integer, String, Boolean, Real (Double)
 *   <li><b>Metaclass types:</b> Types representing EMF EClasses
 *   <li><b>Type conformance:</b> Subtype relationships and type compatibility checking
 *   <li><b>Implicit operations:</b> Implicit collect on collections during property access
 * </ul>
 *
 * <h2>Error Handling</h2>
 *
 * Type errors are collected in the {@link ErrorCollector} with severity levels (ERROR, WARNING).
 * The visitor continues checking after errors to report multiple issues in one pass. Errors
 * include:
 *
 * <ul>
 *   <li>Type mismatches in operations (e.g., {@code 5 + "hello"})
 *   <li>Unknown properties or operations
 *   <li>Incompatible types in if-then-else branches
 *   <li>Invalid receiver types for operations
 *   <li>Variable redefinition in scopes
 * </ul>
 *
 * <h2>Usage in Pipeline</h2>
 *
 * <pre>{@code
 * SymbolTable symbolTable = symbolTableBuilder.build(parseTree);
 * TypeCheckVisitor typeChecker = new TypeCheckVisitor(
 *     symbolTable, metamodelWrapper, errorCollector);
 * typeChecker.setTokenStream(tokens);
 * typeChecker.visit(parseTree);
 *
 * if (!errorCollector.hasErrors()) {
 *     ParseTreeProperty<Type> nodeTypes = typeChecker.getNodeTypes();
 *     // Pass to evaluation phase
 * }
 * }</pre>
 *
 * @see Type The type system implementation
 * @see TypeResolver Helper class for binary operation type resolution
 * @see EvaluationVisitor Phase 3 visitor that uses the type information
 */
public class TypeCheckVisitor extends AbstractPhaseVisitor<Type> {

  // ==================== Instance Fields ====================

  /**
   * Stack of receiver types for navigation chain type checking.
   *
   * <p>During navigation like {@code a.b.c()}, this stack tracks the type at each step:
   *
   * <ul>
   *   <li>Push type of {@code a}
   *   <li>Check {@code .b} using type from stack, push result type
   *   <li>Check {@code .c()} using type from stack
   * </ul>
   *
   * This allows operation visitor methods to access their receiver type via {@code
   * receiverStack.peek()}.
   */
  private final Deque<Type> receiverStack = new ArrayDeque<>();

  /**
   * Maps parse tree nodes to their computed types.
   *
   * <p>This property is populated during type checking and retrieved by {@link #getNodeTypes()} for
   * use in the evaluation phase. The evaluator uses these pre-computed types for type-dependent
   * operations.
   */
  private final ParseTreeProperty<Type> nodeTypes = new ParseTreeProperty<>();

  /** Symbol table for variable and type resolution. */
  private final SymbolTable symbolTable;

  /**
   * Scope annotator for retrieving scopes created in Pass 1.
   *
   * <p>Pass 1 (SymbolTableBuilder) annotates parse tree nodes with their scopes. Pass 2 (this
   * visitor) retrieves these annotations to enter the correct scopes.
   */
  private final ScopeAnnotator scopeAnnotator;

  /**
   * Token stream for accessing keyword positions.
   *
   * <p>Used in {@link #visitIfExpCS} to determine which expressions belong to condition,
   * then-branch, and else-branch based on keyword positions.
   */
  private org.antlr.v4.runtime.TokenStream tokens;

  // ==================== Constructor ====================

  /**
   * Constructs a TypeCheckVisitor for Phase 2 of the compilation pipeline.
   *
   * @param symbolTable The symbol table containing variable and type definitions from Phase 1
   * @param wrapper The metamodel wrapper providing access to ECore metamodel information
   * @param errors The error collector for reporting type errors
   * @param scopeAnnotator The scope annotator containing scope annotations from Phase 1
   */
  public TypeCheckVisitor(
      SymbolTable symbolTable,
      MetamodelWrapperInterface wrapper,
      ErrorCollector errors,
      ScopeAnnotator scopeAnnotator) {
    super(symbolTable, wrapper, errors);
    this.symbolTable = symbolTable;
    this.scopeAnnotator = scopeAnnotator;
  }

  // ==================== Error Reporting ====================

  /** OCL keywords that look like variable names and are commonly mistyped. */
  private static final List<String> KEYWORD_LIKE_NAMES = List.of("self", "true", "false");

  /** Keyword binary operators — candidates for "did you mean?" suggestions. */
  private static final List<String> KNOWN_BINARY_OPS = List.of("and", "or", "xor", "implies");

  private static final String ERR_DID_YOU_MEAN = "' — did you mean '";
  private static final String ERR_UNKNOWN_OP = "Unknown operation '";
  private static final String ERR_OP_DOES_NOT_EXIST = "' — this operation does not exist";

  private static int editThreshold(int len) {
    if (len <= 3) {
      return 1;
    }
    if (len <= 6) {
      return 2;
    }
    return 3;
  }

  /**
   * Computes the Damerau-Levenshtein (Optimal String Alignment) distance between two strings.
   *
   * <p>Counts insertions, deletions, substitutions and <em>adjacent transpositions</em> (e.g.
   * {@code "adn"} → {@code "and"} = 1 move). This is better than plain Levenshtein for catching
   * real typing mistakes where two neighbouring characters are accidentally swapped.
   *
   * <p>Time O(|a|·|b|), space O(|a|·|b|).
   */
  private static int levenshtein(String a, String b) {
    if (a.equals(b)) {
      return 0;
    }
    if (a.isEmpty()) {
      return b.length();
    }
    if (b.isEmpty()) {
      return a.length();
    }

    int la = a.length();
    int lb = b.length();
    int[][] d = new int[la + 1][lb + 1];

    for (int i = 0; i <= la; i++) {
      d[i][0] = i;
    }
    for (int j = 0; j <= lb; j++) {
      d[0][j] = j;
    }

    for (int i = 1; i <= la; i++) {
      for (int j = 1; j <= lb; j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        d[i][j] =
            Math.min(
                d[i - 1][j] + 1, // deletion
                Math.min(
                    d[i][j - 1] + 1, // insertion
                    d[i - 1][j - 1] + cost)); // substitution
        // Adjacent transposition (Damerau extension)
        if (i > 1
            && j > 1
            && a.charAt(i - 1) == b.charAt(j - 2)
            && a.charAt(i - 2) == b.charAt(j - 1)) {
          d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + cost);
        }
      }
    }

    return d[la][lb];
  }

  /**
   * All operation names known to MultiModelOCL.
   *
   * <p>Used by {@link #suggestOperation} to propose the closest match when an unknown operation
   * name is encountered.
   */
  private static final List<String> KNOWN_OPERATIONS =
      List.of(
          // Collection query
          "isEmpty", "notEmpty", "size", "count", "includes", "excludes", "includesAll",
          "excludesAll", "first", "last", "at", "reverse",
          // Collection modification
          "including", "excluding", "union", "intersection", "symmetricDifference", "flatten",
          "append", "prepend", "insertAt", "subSequence",
          // Collection conversion
          "asSet", "asBag", "asSequence", "asOrderedSet", "lift",
          // Aggregation
          "sum", "max", "min", "avg",
          // Numeric
          "abs", "floor", "ceil", "ceiling", "round",
          // Iterator
          "select", "reject", "collect", "collectNested", "forAll", "exists", "one", "any",
          "isUnique", "sortedBy", "iterate",
          // String
          "concat", "substring", "length", "toUpper", "toLower", "indexOf", "equalsIgnoreCase",
          "characters", "tokenize", "substituteAll", "substituteFirst", "matches", "toInteger",
          "toReal",
          // Type / meta
          "oclIsKindOf", "oclIsTypeOf", "oclAsType", "allInstances");

  /** Operations that take no arguments — used to phrase the "add parentheses" quick fix. */
  private static final java.util.Set<String> NO_ARG_OPS =
      java.util.Set.of(
          "size", "isEmpty", "notEmpty", "first", "last", "reverse", "asSet", "asBag",
          "asSequence", "asOrderedSet", "lift", "abs", "floor", "ceiling", "round",
          "allInstances");

  /**
   * Returns the single closest known operation name to {@code typed}, regardless of distance.
   *
   * <p>Used to populate the Quick Fix even when the name is too different for a confident "did you
   * mean?" message. Returns {@code null} only when {@code KNOWN_OPERATIONS} is empty.
   */
  private static String bestOperation(String typed) {
    String lower = typed.toLowerCase(java.util.Locale.ROOT);
    String best = null;
    int bestDist = Integer.MAX_VALUE;
    for (String known : KNOWN_OPERATIONS) {
      int dist = levenshtein(lower, known.toLowerCase(java.util.Locale.ROOT));
      if (dist < bestDist) {
        bestDist = dist;
        best = known;
      }
    }
    return best;
  }

  /**
   * Returns the closest known operation name to {@code typed} if it is within the edit-distance
   * threshold, otherwise returns empty.
   *
   * <p>Threshold scales with name length:
   *
   * <ul>
   *   <li>1–3 chars → max distance 1
   *   <li>4–6 chars → max distance 2
   *   <li>7+ chars → max distance 3
   * </ul>
   *
   * <p>Comparison is case-insensitive so "Select" suggests "select".
   */
  private static java.util.Optional<String> suggestOperation(String typed) {
    int threshold = editThreshold(typed.length());
    String lower = typed.toLowerCase(java.util.Locale.ROOT);

    String best = null;
    int bestDist = threshold + 1;

    for (String known : KNOWN_OPERATIONS) {
      int dist = levenshtein(lower, known.toLowerCase(java.util.Locale.ROOT));
      if (dist < bestDist) {
        bestDist = dist;
        best = known;
      }
    }

    return java.util.Optional.ofNullable(best);
  }

  /**
   * Handles undefined symbol errors.
   *
   * <p>Reports a "did you mean" suggestion when the name is a near-miss of a keyword that looks
   * like a variable ({@code self}, {@code true}, {@code false}).
   *
   * @param name The undefined symbol name
   * @param ctx The parse tree context where the error occurred
   */
  @Override
  protected void handleUndefinedSymbol(String name, ParserRuleContext ctx) {
    String lower = name.toLowerCase(java.util.Locale.ROOT);

    String bestKeyword =
        KEYWORD_LIKE_NAMES.stream()
            .min(
                java.util.Comparator.comparingInt(
                    k -> levenshtein(lower, k.toLowerCase(java.util.Locale.ROOT))))
            .orElse(null);
    int kwDist =
        bestKeyword == null
            ? Integer.MAX_VALUE
            : levenshtein(lower, bestKeyword.toLowerCase(java.util.Locale.ROOT));

    int threshold = editThreshold(name.length());
    String message;
    String suggestion;
    if (kwDist <= threshold) {
      message = "Undefined variable '" + name + ERR_DID_YOU_MEAN + bestKeyword + "'?";
      suggestion = bestKeyword;
    } else {
      message = "Undefined variable: " + name;
      suggestion = null;
    }

    org.antlr.v4.runtime.Token tok = ctx.getStart();
    int endCol = tok.getCharPositionInLine() + tok.getText().length();
    errors.add(
        new CompileError(
            tok.getLine(),
            tok.getCharPositionInLine(),
            tok.getLine(),
            endCol,
            message,
            ErrorSeverity.ERROR,
            "type-checker",
            null,
            suggestion));
  }

  // ==================== Context Declaration ====================

  /**
   * Type checks the top-level context declaration.
   *
   * <p>Processes all classifier contexts (e.g., {@code context Person}, {@code context Company})
   * and returns the type of the last one.
   *
   * @param ctx The context declaration node
   * @return The type of the last classifier context
   */
  @Override
  public Type visitContextDeclCS(OCLParser.ContextDeclCSContext ctx) {
    Type lastType = Type.ERROR;

    for (OCLParser.ClassifierContextCSContext classifierCtx : ctx.classifierContextCS()) {
      lastType = visit(classifierCtx);
    }

    nodeTypes.put(ctx, lastType);
    return lastType;
  }

  /**
   * Type checks a classifier context declaration.
   *
   * <p>A classifier context binds constraints to a metaclass type. This method:
   *
   * <ol>
   *   <li>Resolves the context type (qualified or unqualified name)
   *   <li>Creates a new scope for the context
   *   <li>Binds {@code self} to the context type
   *   <li>Type checks all invariants within this scope
   * </ol>
   *
   * <p><b>Example:</b>
   *
   * <pre>{@code
   * context Person inv:           // Unqualified
   *   self.age >= 0
   *
   * context model::Employee inv:  // Qualified
   *   self.salary > 0
   * }</pre>
   *
   * @param ctx The classifier context node
   * @return The context type (metaclass type)
   */
  /**
   * Type checks a classifier context declaration.
   *
   * <p>Enters the scope created by Pass 1 (SymbolTableBuilder) which already contains the 'self'
   * variable.
   */
  @Override
  public Type visitClassifierContextCS(OCLParser.ClassifierContextCSContext ctx) {
    // Resolve context type (qualified or unqualified)
    Type contextType;
    if (ctx.metamodel != null && ctx.className != null) {
      // Qualified: metamodel::ClassName
      String qualifiedName = ctx.metamodel.getText() + "::" + ctx.className.getText();
      contextType = symbolTable.lookupType(qualifiedName);
    } else if (ctx.contextName != null) {
      // Unqualified: ClassName
      contextType = symbolTable.lookupType(ctx.contextName.getText());
    } else {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Invalid context declaration",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    if (contextType == null) {
      // Pass 1 (SymbolTableBuilder) already reported a precise error for the unknown
      // metamodel or class name — suppress the duplicate diagnostic here.
      return Type.ERROR;
    }

    nodeTypes.put(ctx, contextType);

    // Enter scope created by Pass 1 - scope already contains 'self' variable
    Scope contextScope = scopeAnnotator.getScope(ctx);
    if (contextScope == null) {
      // Pass 1 failed for this context (e.g. unknown type) and never created a scope.
      // The error was already reported there — bail out silently.
      return Type.ERROR;
    }

    symbolTable.enterScope(contextScope);

    try {
      // Type-check all invariants
      for (OCLParser.InvCSContext inv : ctx.invCS()) {
        visit(inv);
      }
      return contextType;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Type checks an invariant constraint.
   *
   * <p>Invariants must evaluate to Boolean type. This method checks all specification expressions
   * and validates that they are conformant to Boolean.
   *
   * <p><b>Example:</b>
   *
   * <pre>{@code
   * inv: self.age >= 0        // Must be Boolean
   * inv: self.name.size() > 0 // Must be Boolean
   * }</pre>
   *
   * @param ctx The invariant node
   * @return Type.BOOLEAN if valid, Type.ERROR otherwise
   */
  @Override
  public Type visitInvCS(OCLParser.InvCSContext ctx) {
    // Check for duplicate @severity / @message annotations
    long severityCount =
        ctx.annotationCS().stream()
            .filter(a -> a instanceof OCLParser.SeverityAnnotationContext)
            .count();
    long messageCount =
        ctx.annotationCS().stream()
            .filter(a -> a instanceof OCLParser.MessageAnnotationContext)
            .count();
    if (severityCount > 1) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "@severity may only appear once per constraint",
          ErrorSeverity.ERROR,
          "type-checker");
    }
    if (messageCount > 1) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "@message may only appear once per constraint",
          ErrorSeverity.ERROR,
          "type-checker");
    }
    // Validate annotations before type-checking the body
    for (OCLParser.AnnotationCSContext ann : ctx.annotationCS()) {
      visit(ann);
    }

    List<OCLParser.SpecificationCSContext> specs = ctx.specificationCS();
    Type resultType = Type.BOOLEAN;

    for (OCLParser.SpecificationCSContext spec : specs) {
      // Snapshot error count before visiting so we can detect whether the spec
      // itself produced any type errors (unknown identifier, bad property, etc.).
      int errorsBefore = errors.getErrors().size();

      Type specType = visit(spec);

      boolean newErrorsInSpec = errors.getErrors().size() > errorsBefore;

      // Three conditions each indicate the inferred type is unreliable and a
      // "Invariant must be Boolean" diagnostic would be pure noise:
      //  1. specType is already Type.ERROR (propagated from a child)
      //  2. ANTLR planted an ErrorNode / MissingTokenNode in the subtree
      //     (error-recovery insertion/deletion inside e.g. a forAll body)
      //  3. The spec visit itself emitted new errors (unknown property/identifier
      //     etc.) — the type is a best-effort inference that may be wrong
      if (specType.equals(Type.ERROR) || hasErrorNode(spec) || newErrorsInSpec) continue;

      // Check Boolean conformance (handles both Boolean and !Boolean!)
      Type checkType =
          specType.isSingleton() || specType.isCollection() ? specType.getElementType() : specType;
      if (!checkType.isConformantTo(Type.BOOLEAN)) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "Invariant must be Boolean, got " + specType,
            ErrorSeverity.ERROR,
            "type-checker");
        resultType = Type.ERROR;
      }
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  // ==================== Annotations ====================

  private static final java.util.Set<String> VALID_SEVERITIES =
      java.util.Set.of("CRITICAL", "WARNING", "MAJOR", "MINOR", "INFO");

  @Override
  public Type visitSeverityAnnotation(OCLParser.SeverityAnnotationContext ctx) {
    String val = ctx.severityValue.getText();
    if (!VALID_SEVERITIES.contains(val)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Unknown severity level '" + val + "'. Valid values: CRITICAL, WARNING, MAJOR, MINOR, INFO",
          ErrorSeverity.ERROR,
          "type-checker");
    }
    return Type.BOOLEAN;
  }

  @Override
  public Type visitMessageAnnotation(OCLParser.MessageAnnotationContext ctx) {
    return Type.BOOLEAN;
  }

  // ==================== Type Expressions ====================

  /**
   * Type checks a type expression.
   *
   * <p>Type expressions represent types in declarations and type operations. Delegates to either
   * type name or type literal checking.
   *
   * @param ctx The type expression node
   * @return The resolved type
   */
  @Override
  public Type visitTypeExpCS(OCLParser.TypeExpCSContext ctx) {
    if (ctx.typeNameExpCS() != null) {
      return visitTypeNameExpCS(ctx.typeNameExpCS());
    }
    if (ctx.typeLiteralCS() != null) {
      return visitTypeLiteralCS(ctx.typeLiteralCS());
    }

    errors.add(
        ctx.getStart().getLine(),
        ctx.getStart().getCharPositionInLine(),
        "Invalid type expression",
        ErrorSeverity.ERROR,
        "type-checker");
    return Type.ERROR;
  }

  /**
   * Type checks a type literal (primitive or collection type).
   *
   * @param ctx The type literal node
   * @return The resolved type
   */
  @Override
  public Type visitTypeLiteralCS(OCLParser.TypeLiteralCSContext ctx) {
    if (ctx.primitiveTypeCS() != null) {
      return visitPrimitiveTypeCS(ctx.primitiveTypeCS());
    }
    if (ctx.collectionTypeCS() != null) {
      return visit(ctx.collectionTypeCS());
    }

    errors.add(
        ctx.getStart().getLine(),
        ctx.getStart().getCharPositionInLine(),
        "Invalid type literal",
        ErrorSeverity.ERROR,
        "type-checker");
    return Type.ERROR;
  }

  /**
   * Type checks a collection type declaration.
   *
   * <p>Constructs collection types from their syntax. Supports:
   *
   * <ul>
   *   <li>{@code Set(T)} - unordered, unique
   *   <li>{@code Bag(T)} - unordered, non-unique
   *   <li>{@code Sequence(T)} - ordered, non-unique
   *   <li>{@code OrderedSet(T)} - ordered, unique
   *   <li>{@code Collection(T)} - generic (defaults to Set)
   * </ul>
   *
   * <p><b>Example:</b> {@code Set(Integer)}, {@code Sequence(Person)}
   *
   * @param ctx The collection type node
   * @return The constructed collection type
   */
  @Override
  public Type visitCollectionTypeCS(OCLParser.CollectionTypeCSContext ctx) {
    String kind = ctx.collectionKind.getText();

    // Get element type if specified
    Type elementType = Type.ANY; // Default placeholder
    if (ctx.typeExpCS() != null) {
      elementType = visit(ctx.typeExpCS());
      if (elementType == Type.ERROR) {
        return Type.ERROR;
      }
    }

    Type collectionType =
        switch (kind) {
          case "Set" -> Type.set(elementType);
          case "Sequence" -> Type.sequence(elementType);
          case "Bag" -> Type.bag(elementType);
          case "OrderedSet" -> Type.orderedSet(elementType);
          case "Collection" -> Type.set(elementType); // Generic → Set
          default -> {
            errors.add(
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine(),
                "Unknown collection type: " + kind,
                ErrorSeverity.ERROR,
                "type-checker");
            yield Type.ERROR;
          }
        };

    nodeTypes.put(ctx, collectionType);
    return collectionType;
  }

  /**
   * Handles collection type identifiers (lexical tokens).
   *
   * <p>Not used for actual type construction - parent {@link #visitCollectionTypeCS} handles that.
   *
   * @param ctx The collection type identifier node
   * @return Type.ANY (placeholder)
   */
  @Override
  public Type visitCollectionTypeIdentifier(OCLParser.CollectionTypeIdentifierContext ctx) {
    return Type.ANY; // Placeholder, not used
  }

  /**
   * Type checks a primitive type reference.
   *
   * <p>Maps OCL primitive type names to internal {@link Type} constants:
   *
   * <ul>
   *   <li>Boolean → {@link Type#BOOLEAN}
   *   <li>Integer → {@link Type#INTEGER}
   *   <li>Real → {@link Type#DOUBLE}
   *   <li>String → {@link Type#STRING}
   *   <li>ID → {@link Type#STRING} (mapped)
   *   <li>UnlimitedNatural → {@link Type#INTEGER} (mapped)
   *   <li>OclAny → {@link Type#ANY}
   * </ul>
   *
   * @param ctx The primitive type node
   * @return The corresponding primitive type
   */
  @Override
  public Type visitPrimitiveTypeCS(OCLParser.PrimitiveTypeCSContext ctx) {
    String typeName = ctx.getText();

    Type primitiveType =
        switch (typeName) {
          case "Boolean" -> Type.BOOLEAN;
          case "Integer" -> Type.INTEGER;
          case "Real" -> Type.DOUBLE;
          case "String" -> Type.STRING;
          case "ID" -> Type.STRING; // Map to String
          case "UnlimitedNatural" -> Type.INTEGER; // Map to Integer
          case "OclAny" -> Type.ANY;
          default -> null;
        };

    if (primitiveType == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Unknown primitive type: " + typeName,
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    nodeTypes.put(ctx, primitiveType);
    return primitiveType;
  }

  /**
   * Type checks a type name expression (qualified or unqualified).
   *
   * <p>Resolves type names to their corresponding types. Handles:
   *
   * <ul>
   *   <li>Primitive types: Integer, String, Boolean, Real
   *   <li>Metamodel types (qualified): {@code metamodel::ClassName}
   *   <li>Metamodel types (unqualified): {@code ClassName}
   * </ul>
   *
   * <p><b>Examples:</b>
   *
   * <ul>
   *   <li>{@code Integer} → Type.INTEGER
   *   <li>{@code Person} → metaclass type for Person
   *   <li>{@code company::Employee} → metaclass type for Employee
   * </ul>
   *
   * @param ctx The type name expression node
   * @return The resolved type
   */
  @Override
  public Type visitTypeNameExpCS(OCLParser.TypeNameExpCSContext ctx) {
    String typeName;

    if (ctx.metamodel != null && ctx.className != null) {
      typeName = ctx.metamodel.getText() + "::" + ctx.className.getText();
    } else if (ctx.unqualified != null) {
      typeName = ctx.unqualified.getText();
    } else {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Invalid type name",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    // Check primitives first
    Type primitiveType =
        switch (typeName) {
          case "Integer" -> Type.INTEGER;
          case "String" -> Type.STRING;
          case "Boolean" -> Type.BOOLEAN;
          case "Real", "Double" -> Type.DOUBLE;
          case "OclAny" -> Type.ANY;
          default -> null;
        };

    if (primitiveType != null) {
      nodeTypes.put(ctx, primitiveType);
      return primitiveType;
    }

    // Lookup in symbol table (metamodel types)
    Type resolvedType = symbolTable.lookupType(typeName);
    if (resolvedType == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Unknown type: " + typeName,
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    nodeTypes.put(ctx, resolvedType);
    return resolvedType;
  }

  // ==================== Specifications & Expressions ====================

  /**
   * Type checks a specification (OCL constraint body).
   *
   * <p>Evaluates all expressions in sequence and returns the type of the last one.
   *
   * @param ctx The specification node
   * @return The type of the last expression
   */
  @Override
  public Type visitSpecificationCS(OCLParser.SpecificationCSContext ctx) {
    if (ctx.expCS().isEmpty()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Empty specification",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Type resultType = null;
    boolean hasError = false;
    for (OCLParser.ExpCSContext exp : ctx.expCS()) {
      Type t = visit(exp);
      if (Type.ERROR.equals(t)) {
        hasError = true;
      }
      resultType = t;
    }

    // If any sub-expression already produced an error (e.g. an unknown identifier
    // that was mis-parsed as a standalone expCS due to specificationCS : expCS*),
    // propagate ERROR so that visitInvCS does not pile on a spurious
    // "Invariant must be Boolean" diagnostic on top of the real error.
    if (hasError) {
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Delegates expression type checking to infixed expression.
   *
   * @param ctx The expression node
   * @return The expression type
   */
  @Override
  public Type visitExpCS(OCLParser.ExpCSContext ctx) {
    return visit(ctx.infixedExpCS());
  }

  // ==================== Comparison Operations ====================

  /**
   * Type checks equality comparison (==).
   *
   * <p>Validates that operands are comparable (same type or one conforms to the other).
   *
   * <p><b>Example:</b> {@code 5 == 10} → Boolean
   *
   * @param ctx The equality comparison operation node
   * @return Type.BOOLEAN if operands are comparable, Type.ERROR otherwise
   */
  @Override
  public Type visitEqualityComparison(OCLParser.EqualityComparisonContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);
    Type resultType = Type.BOOLEAN;

    // Check if types are comparable
    if (!areComparable(leftType, rightType)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Cannot compare incompatible types: " + leftType + " and " + rightType,
          ErrorSeverity.ERROR,
          "type-checker");
      resultType = Type.ERROR;
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks inequality comparison (!=).
   *
   * <p>Validates that operands are comparable (same type or one conforms to the other).
   *
   * <p><b>Example:</b> {@code "hello" != "world"} → Boolean
   *
   * @param ctx The inequality comparison operation node
   * @return Type.BOOLEAN if operands are comparable, Type.ERROR otherwise
   */
  @Override
  public Type visitInequalityComparison(OCLParser.InequalityComparisonContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);
    Type resultType = Type.BOOLEAN;

    // Check if types are comparable
    if (!areComparable(leftType, rightType)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Cannot compare incompatible types: " + leftType + " and " + rightType,
          ErrorSeverity.ERROR,
          "type-checker");
      resultType = Type.ERROR;
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  private boolean areOrderable(Type t1, Type t2) {
    Type e1 = t1.isSingleton() ? t1.getElementType() : t1;
    Type e2 = t2.isSingleton() ? t2.getElementType() : t2;
    if (e1 == Type.ERROR || e2 == Type.ERROR) return true;
    if (e1 == Type.ANY || e2 == Type.ANY) return true;
    return TypeResolver.isNumeric(e1) && TypeResolver.isNumeric(e2);
  }

  /**
   * Type checks less-than comparison (&lt;).
   *
   * <p>Validates that operands are comparable (same type or one conforms to the other).
   *
   * <p><b>Example:</b> {@code 5 &lt; 10} → Boolean
   *
   * @param ctx The less-than comparison operation node
   * @return Type.BOOLEAN if operands are comparable, Type.ERROR otherwise
   */
  @Override
  public Type visitLessThanComparison(OCLParser.LessThanComparisonContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);
    Type resultType = Type.BOOLEAN;
    if (!areOrderable(leftType, rightType)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Ordering comparison requires numeric or String types, got: "
              + leftType
              + " and "
              + rightType,
          ErrorSeverity.ERROR,
          "type-checker");
      resultType = Type.ERROR;
    }
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks less-than-or-equal comparison (&lt;=).
   *
   * <p>Validates that operands are comparable (same type or one conforms to the other).
   *
   * <p><b>Example:</b> {@code 5 &lt;= 10} → Boolean
   *
   * @param ctx The less-than-or-equal comparison operation node
   * @return Type.BOOLEAN if operands are comparable, Type.ERROR otherwise
   */
  @Override
  public Type visitLessThanOrEqualComparison(OCLParser.LessThanOrEqualComparisonContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);
    Type resultType = Type.BOOLEAN;
    if (!areOrderable(leftType, rightType)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Ordering comparison requires numeric or String types, got: "
              + leftType
              + " and "
              + rightType,
          ErrorSeverity.ERROR,
          "type-checker");
      resultType = Type.ERROR;
    }
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks greater-than comparison (>).
   *
   * <p>Validates that operands are comparable (same type or one conforms to the other).
   *
   * <p><b>Example:</b> {@code 10 > 5} → Boolean
   *
   * @param ctx The greater-than comparison operation node
   * @return Type.BOOLEAN if operands are comparable, Type.ERROR otherwise
   */
  @Override
  public Type visitGreaterThanComparison(OCLParser.GreaterThanComparisonContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);
    Type resultType = Type.BOOLEAN;
    if (!areOrderable(leftType, rightType)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Ordering comparison requires numeric or String types, got: "
              + leftType
              + " and "
              + rightType,
          ErrorSeverity.ERROR,
          "type-checker");
      resultType = Type.ERROR;
    }
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks greater-than-or-equal comparison (>=).
   *
   * <p>Validates that operands are comparable (same type or one conforms to the other).
   *
   * <p><b>Example:</b> {@code 10 >= 5} → Boolean
   *
   * @param ctx The greater-than-or-equal comparison operation node
   * @return Type.BOOLEAN if operands are comparable, Type.ERROR otherwise
   */
  @Override
  public Type visitGreaterThanOrEqualComparison(OCLParser.GreaterThanOrEqualComparisonContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);
    Type resultType = Type.BOOLEAN;
    if (!areOrderable(leftType, rightType)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Ordering comparison requires numeric or String types, got: "
              + leftType
              + " and "
              + rightType,
          ErrorSeverity.ERROR,
          "type-checker");
      resultType = Type.ERROR;
    }
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Checks if two types are comparable.
   *
   * <p>Types are comparable if:
   *
   * <ul>
   *   <li>Either is Type.ERROR (error already reported)
   *   <li>They are equal
   *   <li>One conforms to the other (subtype relationship)
   * </ul>
   *
   * @param t1 First type
   * @param t2 Second type
   * @return true if types are comparable
   */
  private boolean areComparable(Type t1, Type t2) {
    if (t1 == Type.ERROR || t2 == Type.ERROR) return true;
    if (t1.equals(t2)) return true;
    if (t1.isConformantTo(t2) || t2.isConformantTo(t1)) return true;

    // Optional ANY (?Any?) is comparable to anything — represents null
    if ((t1.isOptional() && t1.getElementType() == Type.ANY)
        || (t2.isOptional() && t2.getElementType() == Type.ANY)) {
      return true;
    }

    // Unwrap singletons for comparison
    Type e1 =
        t1.isSingleton() ? t1.getElementType() : (t1.isCollection() ? t1.getElementType() : t1);
    Type e2 =
        t2.isSingleton() ? t2.getElementType() : (t2.isCollection() ? t2.getElementType() : t2);
    if (!e1.equals(t1) || !e2.equals(t2)) {
      return areComparable(e1, e2);
    }

    return false;
  }

  // ==================== Arithmetic Operations ====================

  /**
   * Type checks multiplicative operations (* and /).
   *
   * <p>Uses {@link TypeResolver#resolveBinaryOp} to determine result type based on operand types.
   *
   * <p><b>Examples:</b>
   *
   * <ul>
   *   <li>{@code 6 * 7} → Integer
   *   <li>{@code 10 / 2} → Integer
   * </ul>
   *
   * @param ctx The multiplicative operation node
   * @return The result type (typically Integer)
   */
  @Override
  public Type visitMultiplicative(OCLParser.MultiplicativeContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);

    String operator = ctx.op.getText();
    Type resultType = TypeResolver.resolveBinaryOp(operator, leftType, rightType);

    if (resultType == Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Type mismatch: cannot apply '" + operator + "' to " + leftType + " and " + rightType,
          ErrorSeverity.ERROR,
          "type-checker");
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks additive operations (+ and -).
   *
   * <p>Uses {@link TypeResolver#resolveBinaryOp} to determine result type.
   *
   * <p><b>Examples:</b>
   *
   * <ul>
   *   <li>{@code 3 + 4} → Integer
   *   <li>{@code 10 - 3} → Integer
   * </ul>
   *
   * @param ctx The additive operation node
   * @return The result type (typically Integer)
   */
  @Override
  public Type visitAdditive(OCLParser.AdditiveContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);

    String operator = ctx.op.getText();
    Type resultType = TypeResolver.resolveBinaryOp(operator, leftType, rightType);

    if (resultType == Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Type mismatch: cannot apply '" + operator + "' to " + leftType + " and " + rightType,
          ErrorSeverity.ERROR,
          "type-checker");
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  // ==================== Logical Operations ====================

  /**
   * Type checks logical AND operation.
   *
   * <p>Uses {@link TypeResolver#resolveBinaryOp} to validate Boolean operands and return Boolean
   * result.
   *
   * <p><b>Example:</b> {@code true and false} → Boolean
   *
   * @param ctx The logical AND operation node
   * @return Type.BOOLEAN if operands are Boolean, Type.ERROR otherwise
   */
  @Override
  public Type visitLogicalAnd(OCLParser.LogicalAndContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);

    String operator = "and";
    Type resultType = TypeResolver.resolveBinaryOp(operator, leftType, rightType);

    if (resultType == Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Type mismatch: cannot apply '" + operator + "' to " + leftType + " and " + rightType,
          ErrorSeverity.ERROR,
          "type-checker");
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks logical OR operation.
   *
   * <p>Uses {@link TypeResolver#resolveBinaryOp} to validate Boolean operands and return Boolean
   * result.
   *
   * <p><b>Example:</b> {@code true or false} → Boolean
   *
   * @param ctx The logical OR operation node
   * @return Type.BOOLEAN if operands are Boolean, Type.ERROR otherwise
   */
  @Override
  public Type visitLogicalOr(OCLParser.LogicalOrContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);

    String operator = "or";
    Type resultType = TypeResolver.resolveBinaryOp(operator, leftType, rightType);

    if (resultType == Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Type mismatch: cannot apply '" + operator + "' to " + leftType + " and " + rightType,
          ErrorSeverity.ERROR,
          "type-checker");
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks logical XOR operation.
   *
   * <p>Uses {@link TypeResolver#resolveBinaryOp} to validate Boolean operands and return Boolean
   * result.
   *
   * <p><b>Example:</b> {@code true xor true} → Boolean
   *
   * @param ctx The logical XOR operation node
   * @return Type.BOOLEAN if operands are Boolean, Type.ERROR otherwise
   */
  @Override
  public Type visitLogicalXor(OCLParser.LogicalXorContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);

    String operator = "xor";
    Type resultType = TypeResolver.resolveBinaryOp(operator, leftType, rightType);

    if (resultType == Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Type mismatch: cannot apply '" + operator + "' to " + leftType + " and " + rightType,
          ErrorSeverity.ERROR,
          "type-checker");
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the implication operation (implies).
   *
   * <p>Both operands must be Boolean. Returns Boolean.
   *
   * <p><b>Example:</b> {@code age >= 18 implies canVote == true} → Boolean
   *
   * @param ctx The implication operation node
   * @return Type.BOOLEAN if both operands are Boolean
   */
  @Override
  public Type visitImplication(OCLParser.ImplicationContext ctx) {
    Type leftType = visit(ctx.left);
    Type rightType = visit(ctx.right);

    if (!leftType.isConformantTo(Type.BOOLEAN)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Left operand of 'implies' must be Boolean, got " + leftType,
          ErrorSeverity.ERROR,
          "type-checker");
    }

    if (!rightType.isConformantTo(Type.BOOLEAN)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Right operand of 'implies' must be Boolean, got " + rightType,
          ErrorSeverity.ERROR,
          "type-checker");
    }

    nodeTypes.put(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  // ==================== Unary Operations & Navigation ====================

  /**
   * Visits a primary expression with navigation (`primaryWithNav`) in the AST.
   *
   * <p>This method performs type checking for a primary expression that may be followed by a chain
   * of navigation operations (e.g., property or association accesses).
   *
   * @param ctx the context of the primary expression with navigation in the AST
   * @return the resulting type of the expression after applying the navigation chain; {@link
   *     Type#ERROR} if the base expression has no type
   */
  @Override
  public Type visitPrimaryWithNav(OCLParser.PrimaryWithNavContext ctx) {
    // Get base type from primary expression
    Type currentType = visit(ctx.base);

    if (currentType == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Base expression has no type",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    // Process navigation chain using receiverStack
    List<OCLParser.NavigationChainCSContext> navChain = ctx.navigationChainCS();
    receiverStack.push(currentType);
    for (OCLParser.NavigationChainCSContext nav : navChain) {
      Type resultType = visit(nav);
      receiverStack.pop();
      receiverStack.push(resultType);
      currentType = resultType;
    }
    if (!navChain.isEmpty()) {
      receiverStack.pop();
    }

    nodeTypes.put(ctx, currentType);
    return currentType;
  }

  /**
   * Visits a unary minus ({@code -}) node in the AST.
   *
   * <p>Accepts any numeric base type: {@link Type#INTEGER}, {@link Type#FLOAT}, or {@link
   * Type#DOUBLE}. The result type equals the operand type (i.e., {@code -3.14f} stays FLOAT).
   *
   * <p>Supports singleton and collection receivers via one level of unwrapping.
   *
   * @param ctx the context of the unary minus node in the AST
   * @return the type of the operand if valid; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitUnaryMinus(OCLParser.UnaryMinusContext ctx) {
    Type operandType = visit(ctx.operand);

    // Only unwrap singletons ¡T!, not collections
    Type baseType = operandType.isSingleton() ? operandType.getElementType() : operandType;

    if (baseType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Unary minus requires numeric type, got " + operandType,
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    if (!TypeResolver.isNumeric(baseType) && baseType != Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Unary minus requires numeric type, got " + operandType,
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    nodeTypes.put(ctx, operandType);
    return operandType;
  }

  /**
   * Visits a logical NOT (`not`) node in the AST.
   *
   * <p>This method performs type checking for the operand of a logical NOT expression. It supports
   * both scalar and collection types (one level of unwrapping):
   *
   * <ul>
   *   <li>If the operand is a singleton or a collection, the base element type is extracted.
   *   <li>Allowed base type is {@link Type#BOOLEAN}.
   *   <li>If the type is invalid, an error is added to the error list and {@link Type#ERROR} is
   *       returned.
   * </ul>
   *
   * The type of the operand is stored in the {@code nodeTypes} map.
   *
   * @param ctx the context of the logical NOT node in the AST
   * @return the type of the operand if valid; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitLogicalNot(OCLParser.LogicalNotContext ctx) {
    Type operandType = visit(ctx.operand);

    // Only unwrap singletons ¡T!, not collections
    Type baseType = operandType.isSingleton() ? operandType.getElementType() : operandType;

    if (baseType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Logical not requires Boolean type, got " + operandType,
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    if (baseType != Type.BOOLEAN && baseType != Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Logical not requires Boolean type, got " + operandType,
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    nodeTypes.put(ctx, operandType);
    return operandType;
  }

  // ==================== Cross-Metamodel Support ====================

  /**
   * Type checks fully qualified metaclass references.
   *
   * <p>Resolves qualified names like {@code spaceMission::Spacecraft} to their corresponding
   * metaclass types and handles navigation chains (typically {@code .allInstances()}).
   *
   * <p><b>Example:</b> {@code satelliteSystem::Satellite.allInstances()} → Set(Satellite)
   *
   * @param ctx The prefixed qualified name node
   * @return The result type after navigation
   */
  @Override
  public Type visitPrefixedQualified(OCLParser.PrefixedQualifiedContext ctx) {
    String metamodel = ctx.metamodel.getText();
    String className = ctx.className.getText();

    // First try: EClass (metamodel::ClassName)
    EClass eClass = specification.resolveEClass(metamodel, className);
    if (eClass != null) {
      Type metaclassType = Type.metaclassType(eClass);
      nodeTypes.put(ctx, metaclassType);
      receiverStack.push(metaclassType);
      Type currentType = metaclassType;
      for (OCLParser.NavigationChainCSContext nav : ctx.navigationChainCS()) {
        currentType = visit(nav);
        nodeTypes.put(nav, currentType);
        receiverStack.pop();
        receiverStack.push(currentType);
      }
      if (!ctx.navigationChainCS().isEmpty()) {
        receiverStack.pop();
      }
      return currentType;
    }

    // Second try: Enum literal (EnumName::LiteralName)
    org.eclipse.emf.ecore.EEnum eEnum = specification.resolveEEnum(metamodel);
    if (eEnum != null) {
      org.eclipse.emf.ecore.EEnumLiteral literal = eEnum.getEEnumLiteral(className);
      if (literal != null) {
        Type enumType = Type.enumType(eEnum);
        nodeTypes.put(ctx, enumType);
        return enumType;
      }
    }

    // Underline the metamodel name token so the squiggle spans the full identifier,
    // not just its first character.
    errors.add(
        ctx.metamodel,
        "Cannot resolve " + metamodel + "::" + className,
        ErrorSeverity.ERROR,
        "type-checker");
    return Type.ERROR;
  }

  // ==================== Navigation ====================

  /**
   * Delegates navigation chain type checking to navigation target.
   *
   * @param ctx The navigation chain node
   * @return The navigation result type
   */
  @Override
  public Type visitNavigationChainCS(OCLParser.NavigationChainCSContext ctx) {
    return visit(ctx.navigationTargetCS());
  }

  /**
   * Type checks property navigation.
   *
   * <p>Unwraps singleton {@code !T!} receivers before property access. After the RC2
   * iterator-variable fix all iterator vars are {@code ¡T!}, so this is the normal path for
   * navigating from iterator variables.
   *
   * @param ctx The property navigation node
   * @return The property type
   */
  @Override
  public Type visitPropertyNav(OCLParser.PropertyNavContext ctx) {
    Type receiverType = receiverStack.peek();

    // Unwrap singleton !T! to T for property access — every iterated
    // element is ¡T!, so unwrapping here is the standard path
    if (receiverType.isSingleton()) {
      receiverType = receiverType.getElementType();
    }

    String propertyName = ctx.propertyAccess().propertyName.getText();
    Type resultType = typeCheckPropertyAccess(
        receiverType, propertyName, ctx.propertyAccess().propertyName);
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks operation navigation.
   *
   * <p>Delegates to the specific operation call visitor.
   *
   * @param ctx The operation navigation node
   * @return The operation result type
   */
  @Override
  public Type visitOperationNav(OCLParser.OperationNavContext ctx) {
    return visit(ctx.operationCall());
  }

  /**
   * Type checks property access on a receiver.
   *
   * <p>Handles both singleton and collection receivers:
   *
   * <ul>
   *   <li><b>Singleton:</b> {@code person.name} → direct property access
   *   <li><b>Collection:</b> {@code companies.name} → implicit collect, returns flattened
   *       collection
   * </ul>
   *
   * @param receiverType The type of the receiver
   * @param propName The property name
   * @param propertyNameToken The exact token for the property name — used to underline precisely
   *     the identifier rather than just the first character of the expression.
   * @return The property type (may be wrapped in collection)
   */
  private Type typeCheckPropertyAccess(
      Type receiverType, String propName, org.antlr.v4.runtime.Token propertyNameToken) {
    // Implicit collect for collections
    if (receiverType.isCollection()) {
      Type elemType = receiverType.getElementType();

      if (!elemType.isMetaclassType()) {
        errors.add(
            propertyNameToken,
            "Cannot navigate on non-object type",
            ErrorSeverity.ERROR,
            "type-checker");
        return Type.ERROR;
      }

      EClass eClass = elemType.getEClass();
      EStructuralFeature feature = eClass.getEStructuralFeature(propName);

      if (feature == null) {
        errors.add(
            propertyNameToken,
            "Unknown property: " + propName,
            ErrorSeverity.ERROR,
            "type-checker");
        return Type.ERROR;
      }

      Type featureType = mapFeatureToType(feature);
      return Type.set(featureType.getElementType()); // Flatten
    }

    // Singleton property access
    if (receiverType.isMetaclassType()) {
      EClass eClass = receiverType.getEClass();
      EStructuralFeature feature = eClass.getEStructuralFeature(propName);

      if (feature == null) {
        errors.add(
            propertyNameToken,
            "Unknown property: " + propName,
            ErrorSeverity.ERROR,
            "type-checker");
        return Type.ERROR;
      }

      return mapFeatureToType(feature);
    }

    errors.add(
        propertyNameToken,
        "Cannot access property on " + receiverType,
        ErrorSeverity.ERROR,
        "type-checker");
    return Type.ERROR;
  }

  /**
   * Maps an EMF structural feature to a MultiModelOCLInterface type.
   *
   * <p>Respects EMF ordering and uniqueness annotations:
   *
   * <ul>
   *   <li>ordered + unique → OrderedSet {@code <T>}
   *   <li>ordered + !unique → Sequence {@code [T]}
   *   <li>!ordered + unique → Set {@code {T}}
   *   <li>!ordered + !unique → Bag {@code {{T}}}
   *   <li>single-valued → Singleton {@code !T!}
   * </ul>
   *
   * @param feature The EMF structural feature
   * @return The corresponding MultiModelOCLInterface type
   */
  private Type mapFeatureToType(EStructuralFeature feature) {
    Type baseType = mapEClassifierToType(feature.getEType());

    if (feature.getUpperBound() > 1 || feature.getUpperBound() == -1) {
      // Multi-valued: respect EMF ordering and uniqueness annotations
      boolean ordered = feature.isOrdered();
      boolean unique = feature.isUnique();

      if (ordered && unique) {
        return Type.orderedSet(baseType); // <T>
      } else if (ordered) {
        return Type.sequence(baseType); // [T]
      } else if (unique) {
        return Type.set(baseType); // {T}
      } else {
        return Type.bag(baseType); // {{T}}
      }
    } else {
      // Single-valued: singleton !T!
      return Type.singleton(baseType);
    }
  }

  /**
   * Maps an EMF EClassifier to a MultiModelOCLInterface type.
   *
   * <p>Handles:
   *
   * <ul>
   *   <li>EString → Type.STRING
   *   <li>EInt → Type.INTEGER
   *   <li>EBoolean → Type.BOOLEAN
   *   <li>EClass → metaclass type
   * </ul>
   *
   * @param classifier The EMF classifier
   * @return The corresponding MultiModelOCLInterface type
   */
  private Type mapEClassifierToType(EClassifier classifier) {
    // Handle primitive types
    switch (classifier.getName()) {
      case "EString":
        return Type.STRING;
      case "EInt":
        return Type.INTEGER;
      case "EBoolean":
        return Type.BOOLEAN;
      case "EFloat":
        return Type.FLOAT;
      case "EDouble":
        return Type.DOUBLE;
      default:
        if (EcorePackage.Literals.ECLASS.equals(classifier.eClass())) {
          return Type.metaclassType((EClass) classifier);
        }
        return Type.ERROR;
    }
  }

  // ==================== Control Flow ====================

  /**
   * Type checks if-then-else expressions.
   *
   * <p>Validates:
   *
   * <ol>
   *   <li>Condition must be Boolean
   *   <li>Then and else branches must have compatible types
   * </ol>
   *
   * <p>Result type is the common supertype of both branches.
   *
   * <p><b>Example:</b>
   *
   * <pre>{@code
   * if age >= 18 then 'adult' else 'minor' endif  // → String
   * if condition then 1 else 2.0 endif            // → Real (common supertype)
   * }</pre>
   *
   * @param ctx The if-expression node
   * @return The common supertype of both branches
   */
  @Override
  public Type visitIfExpCS(OCLParser.IfExpCSContext ctx) {
    List<OCLParser.ExpCSContext> allExps = ctx.expCS();

    int thenIndex = -1;
    int elseIndex = -1;
    for (int i = 0; i < ctx.getChildCount(); i++) {
      String text = ctx.getChild(i).getText();
      if ("then".equals(text) && thenIndex == -1) {
        thenIndex = ctx.getChild(i).getSourceInterval().a;
      }
      if ("else".equals(text) && elseIndex == -1) {
        elseIndex = ctx.getChild(i).getSourceInterval().a;
      }
    }

    List<OCLParser.ExpCSContext> condExps = new ArrayList<>();
    List<OCLParser.ExpCSContext> thenExps = new ArrayList<>();
    List<OCLParser.ExpCSContext> elseExps = new ArrayList<>();

    for (OCLParser.ExpCSContext exp : allExps) {
      int expIndex = exp.getStart().getTokenIndex();
      if (expIndex < thenIndex) {
        condExps.add(exp);
      } else if (expIndex < elseIndex) {
        thenExps.add(exp);
      } else {
        elseExps.add(exp);
      }
    }

    // Type-check condition
    Type condType = null;
    for (OCLParser.ExpCSContext exp : condExps) {
      condType = visit(exp);
    }
    if (condType != null && condType != Type.ERROR) {
      Type condCheck = condType.isSingleton() ? condType.getElementType() : condType;
      if (condCheck.isCollection() || !condCheck.isConformantTo(Type.BOOLEAN)) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "If condition must be Boolean, got " + condType,
            ErrorSeverity.ERROR,
            "type-checker");
      }
    }

    // Type-check then-branch
    Type thenType = null;
    for (OCLParser.ExpCSContext exp : thenExps) {
      thenType = visit(exp);
    }

    // Type-check else-branch
    Type elseType = null;
    for (OCLParser.ExpCSContext exp : elseExps) {
      elseType = visit(exp);
    }

    if (thenType == null || elseType == null) {
      return Type.ERROR;
    }

    // Branches must have compatible types — mixing collection and scalar is an error
    boolean thenIsCollection = thenType.isCollection() && !thenType.isSingleton();
    boolean elseIsCollection = elseType.isCollection() && !elseType.isSingleton();
    if (thenIsCollection != elseIsCollection) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Incompatible branch types: " + thenType + " and " + elseType,
          ErrorSeverity.ERROR,
          "type-checker");
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    // Both collections must have same kind (ordered/unique)
    if (thenIsCollection && elseIsCollection) {
      if (thenType.isOrdered() != elseType.isOrdered()
          || thenType.isUnique() != elseType.isUnique()) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "Incompatible branch types: " + thenType + " and " + elseType,
            ErrorSeverity.ERROR,
            "type-checker");
        nodeTypes.put(ctx, Type.ERROR);
        return Type.ERROR;
      }
    }

    // Determine result type
    Type resultType;
    if (thenType.equals(elseType)) {
      resultType = thenType;
    } else if (thenType.isConformantTo(elseType)) {
      resultType = elseType;
    } else if (elseType.isConformantTo(thenType)) {
      resultType = thenType;
    } else {
      resultType = Type.commonSuperType(thenType, elseType);
      if (resultType == null || resultType == Type.ANY) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "Incompatible branch types: " + thenType + " and " + elseType,
            ErrorSeverity.ERROR,
            "type-checker");
        resultType = Type.ERROR;
      }
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Processes variable declarations (in let-expressions).
   *
   * @param ctx The variable declarations node
   * @return Type.ANY (not used)
   */
  @Override
  public Type visitVariableDeclarations(OCLParser.VariableDeclarationsContext ctx) {
    for (OCLParser.VariableDeclarationContext varDecl : ctx.variableDeclaration()) {
      visit(varDecl);
    }
    return Type.ANY; // Not used
  }

  /**
   * Type checks a single variable declaration.
   *
   * <p>Validates:
   *
   * <ul>
   *   <li>No duplicate variable names in current scope
   *   <li>Initializer type conforms to declared type (if present), using singleton-unwrapping rules
   *       via {@link #conformsWithUnwrapping}
   * </ul>
   *
   * <p>The variable is registered with the declared type (not the wrapped initType) so that
   * downstream navigation operates on the correct member type.
   *
   * @param ctx The variable declaration node
   * @return The variable type
   */
  @Override
  public Type visitVariableDeclaration(OCLParser.VariableDeclarationContext ctx) {
    String varName = ctx.varName.getText();

    // Variable already defined in Pass 1 - just look it up
    VariableSymbol symbol = symbolTable.resolveVariable(varName);
    if (symbol == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Variable '" + varName + "' not found (Pass 1 error?)",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    // Type-check initializer
    Type initType = visit(ctx.varInit);

    // Check explicit type if present
    Type declaredType = symbol.getType();
    if (ctx.varType != null) {
      Type explicitType = visit(ctx.varType);

      // use standard isConformantTo first, then singleton-unwrapping
      if (!initType.isConformantTo(explicitType)
          && !conformsWithUnwrapping(initType, explicitType)) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "Type mismatch: got " + initType + ", expected " + explicitType,
            ErrorSeverity.ERROR,
            "type-checker");
        return Type.ERROR;
      }

      // Always use the declared type for the variable so navigation on it uses
      // the correct (potentially more specific) type
      declaredType = explicitType;
      if (symbol.getType() == Type.ANY) {
        symbol.setType(declaredType);
      }
    } else {
      // No explicit type — refine symbol with inferred type from initializer
      if (declaredType == Type.ANY) {
        symbol.setType(initType);
        declaredType = initType;
      } else if (!initType.isConformantTo(declaredType)
          && !conformsWithUnwrapping(initType, declaredType)) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "Type mismatch: got " + initType + ", expected " + declaredType,
            ErrorSeverity.ERROR,
            "type-checker");
      }
    }

    nodeTypes.put(ctx, declaredType);
    return declaredType;
  }

  // ==================== Literal Values ====================

  /**
   * Type checks integer literals.
   *
   * @param ctx The number literal node
   * @return Type.INTEGER
   */
  @Override
  public Type visitNumberLit(OCLParser.NumberLitContext ctx) {
    String text = ctx.getText();
    Type type = text.contains(".") ? Type.DOUBLE : Type.INTEGER;
    nodeTypes.put(ctx, type);
    return type;
  }

  /**
   * Type checks string literals.
   *
   * @param ctx The string literal node
   * @return Type.STRING
   */
  @Override
  public Type visitStringLit(OCLParser.StringLitContext ctx) {
    nodeTypes.put(ctx, Type.STRING);
    return Type.STRING;
  }

  /**
   * Type checks boolean literals.
   *
   * @param ctx The boolean literal node
   * @return Type.BOOLEAN
   */
  @Override
  public Type visitBooleanLit(OCLParser.BooleanLitContext ctx) {
    nodeTypes.put(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  // ==================== Variable References ====================

  /**
   * Type checks variable references.
   *
   * <p>Handles the special {@code null} keyword, which is the empty optional {@code ?Any?} = {@code
   * []} rather than a variable. All other names are resolved from the symbol table.
   *
   * @param ctx The variable expression node
   * @return The variable's type, or {@code ?Any?} for {@code null}
   */
  @Override
  public Type visitVariableExpCS(OCLParser.VariableExpCSContext ctx) {
    String varName = ctx.varName.getText();

    // null is the empty optional ?Any? = []
    if (varName.equals("null")) {
      Type nullType = Type.optional(Type.ANY);
      nodeTypes.put(ctx, nullType);
      return nullType;
    }

    VariableSymbol symbol = symbolTable.resolveVariable(varName);
    if (symbol == null) {
      handleUndefinedSymbol(varName, ctx);
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    Type varType = symbol.getType();
    nodeTypes.put(ctx, varType);
    return varType;
  }

  /**
   * Type checks {@code self} references.
   *
   * <p>Returns the type of the special {@code self} variable from the current context.
   *
   * @param ctx The self expression node
   * @return The context type (type of self)
   */
  @Override
  public Type visitSelfExpCS(OCLParser.SelfExpCSContext ctx) {
    Symbol selfSymbol = symbolTable.resolveVariable("self");

    if (selfSymbol == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'self' not defined in current context",
          ErrorSeverity.ERROR,
          "type-checker");
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    Type selfType = selfSymbol.getType();
    nodeTypes.put(ctx, selfType);
    return selfType;
  }

  // ==================== Collection Literals ====================

  /**
   * Type checks collection literal expressions.
   *
   * <p>Infers element type from collection arguments and constructs appropriate collection type.
   *
   * <p><b>Examples:</b>
   *
   * <ul>
   *   <li>{@code Set{1,2,3}} → Set(Integer)
   *   <li>{@code Bag{'a','b'}} → Bag(String)
   *   <li>{@code Sequence{}} → Sequence(Any)
   * </ul>
   *
   * @param ctx The collection literal node
   * @return The inferred collection type
   */
  @Override
  public Type visitCollectionLiteralExpCS(OCLParser.CollectionLiteralExpCSContext ctx) {
    Type collectionType = visit(ctx.collectionKind);

    if (collectionType == Type.ERROR) {
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    // Empty collection: keep ANY as element type
    if (ctx.arguments == null) {
      nodeTypes.put(ctx, collectionType);
      return collectionType;
    }

    // Infer element type from arguments
    Type inferredElementType = visit(ctx.arguments);

    if (inferredElementType == Type.ERROR) {
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    // Create collection with inferred element type
    Type resultType = preserveCollectionKind(collectionType, inferredElementType);

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Preserves collection kind while changing element type.
   *
   * <p>Maintains the collection's unique/ordered properties when creating a new collection type
   * with a different element type.
   *
   * @param collectionType The original collection type
   * @param newElementType The new element type
   * @return A collection of the same kind with the new element type
   */
  private Type preserveCollectionKind(Type collectionType, Type newElementType) {
    if (collectionType.isUnique() && collectionType.isOrdered()) {
      return Type.orderedSet(newElementType);
    } else if (collectionType.isUnique()) {
      return Type.set(newElementType);
    } else if (collectionType.isOrdered()) {
      return Type.sequence(newElementType);
    } else {
      return Type.bag(newElementType);
    }
  }

  /**
   * Type checks collection arguments (elements in collection literal).
   *
   * <p>Computes the common supertype of all elements.
   *
   * @param ctx The collection arguments node
   * @return The common element type
   */
  @Override
  public Type visitCollectionArguments(OCLParser.CollectionArgumentsContext ctx) {
    Type commonType = null;

    for (OCLParser.CollectionLiteralPartCSContext part : ctx.collectionLiteralPartCS()) {
      Type partType = visit(part);

      if (partType == Type.ERROR) continue;

      if (commonType == null) {
        commonType = partType;
      } else if (!partType.equals(commonType)) {
        Type superType = Type.commonSuperType(commonType, partType);
        commonType = (superType != null) ? superType : Type.ANY;
      }
    }

    return (commonType != null) ? commonType : Type.ANY;
  }

  /**
   * Type checks a collection literal part (element or range).
   *
   * <p>Handles:
   *
   * <ul>
   *   <li>Single elements: {@code 42}
   *   <li>Ranges: {@code 1..10} (both bounds must be Integer)
   * </ul>
   *
   * @param ctx The collection literal part node
   * @return The element type (Integer for ranges)
   */
  @Override
  public Type visitCollectionLiteralPartCS(OCLParser.CollectionLiteralPartCSContext ctx) {
    Type firstType = visit(ctx.expCS(0));

    // Range: 1..10
    if (ctx.expCS().size() == 2) {
      Type secondType = visit(ctx.expCS(1));

      if (!firstType.isConformantTo(Type.INTEGER)) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "Range start must be Integer",
            ErrorSeverity.ERROR,
            "type-checker");
      }
      if (!secondType.isConformantTo(Type.INTEGER)) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "Range end must be Integer",
            ErrorSeverity.ERROR,
            "type-checker");
      }

      return Type.INTEGER;
    }

    return firstType;
  }

  // ==================== Collection Operations ====================

  /**
   * Type-checks the {@code first()} operation on a collection receiver.
   *
   * <p>Verifies that the receiver is a collection type, then registers and returns a singleton of
   * the receiver's element type as the result type.
   *
   * @param ctx the parse tree node for the {@code first()} operation
   * @return a singleton of the receiver's element type; or {@link Type#ERROR} if the receiver is
   *     not a collection type
   */
  @Override
  public Type visitFirstOp(OCLParser.FirstOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "first() requires collection receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type resultType = Type.singleton(receiverType.getElementType());
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type-checks the {@code last()} operation on a collection receiver.
   *
   * <p>Verifies that the receiver is a collection type, then registers and returns a singleton of
   * the receiver's element type as the result type.
   *
   * @param ctx the parse tree node for the {@code last()} operation
   * @return a singleton of the receiver's element type; or {@link Type#ERROR} if the receiver is
   *     not a collection type
   */
  @Override
  public Type visitLastOp(OCLParser.LastOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "last() requires collection receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type resultType = Type.singleton(receiverType.getElementType());
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code reverse()} operation.
   *
   * @param ctx The reverse operation node
   * @return Same collection type as receiver
   */
  @Override
  public Type visitReverseOp(OCLParser.ReverseOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "reverse() requires collection receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    nodeTypes.put(ctx, receiverType);
    return receiverType;
  }

  /**
   * Type checks the {@code isEmpty()} operation.
   *
   * @param ctx The isEmpty operation node
   * @return Type.BOOLEAN
   */
  @Override
  public Type visitIsEmptyOp(OCLParser.IsEmptyOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "isEmpty() requires collection receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type resultType = Type.singleton(Type.BOOLEAN);
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code notEmpty()} operation.
   *
   * @param ctx The notEmpty operation node
   * @return Type.BOOLEAN
   */
  @Override
  public Type visitNotEmptyOp(OCLParser.NotEmptyOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "notEmpty() requires collection receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Type resultType = Type.BOOLEAN;
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks collection modification operations (including, excluding, etc.).
   *
   * <p>These operations validate argument type compatibility and return the receiver collection
   * type.
   */
  @Override
  public Type visitIncludingOp(OCLParser.IncludingOpContext ctx) {
    Type receiverType = receiverStack.peek();
    Type argType = visit(ctx.arg);

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'including' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Type elemType = receiverType.getElementType();
    if (!argType.isConformantTo(elemType)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Incompatible argument type",
          ErrorSeverity.ERROR,
          "type-checker");
    }

    nodeTypes.put(ctx, receiverType);
    return receiverType;
  }

  /**
   * Visits an 'excluding' operation node in the AST.
   *
   * <p>This method performs type checking for the 'excluding' operation, which removes an element
   * from a collection.
   *
   * <ul>
   *   <li>The operation requires that the receiver type (top of {@code receiverStack}) is a
   *       collection.
   *   <li>If the receiver is not a collection, an error is added and {@link Type#ERROR} is
   *       returned.
   * </ul>
   *
   * The type of the receiver is stored in the {@code nodeTypes} map.
   *
   * @param ctx the context of the 'excluding' operation node in the AST
   * @return the type of the collection receiver if valid; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitExcludingOp(OCLParser.ExcludingOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'excluding' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    nodeTypes.put(ctx, receiverType);
    return receiverType;
  }

  /**
   * Visits an 'includes' operation node in the AST.
   *
   * <p>This method performs type checking for the 'includes' operation, which checks whether a
   * collection contains a given element.
   *
   * <ul>
   *   <li>The operation requires that the receiver type (top of {@code receiverStack}) is a
   *       collection.
   *   <li>If the receiver is not a collection, an error is added and {@link Type#ERROR} is
   *       returned.
   *   <li>If the type is valid, the resulting type of the operation is {@link Type#BOOLEAN}.
   * </ul>
   *
   * The resulting type is stored in the {@code nodeTypes} map.
   *
   * @param ctx the context of the 'includes' operation node in the AST
   * @return {@link Type#BOOLEAN} if the receiver is a collection; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitIncludesOp(OCLParser.IncludesOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'includes' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    nodeTypes.put(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Visits an 'excludes' operation node in the AST.
   *
   * <p>This method performs type checking for the 'excludes' operation, which checks whether a
   * collection does not contain a given element.
   *
   * <ul>
   *   <li>The operation requires that the receiver type (top of {@code receiverStack}) is a
   *       collection.
   *   <li>If the receiver is not a collection, an error is added and {@link Type#ERROR} is
   *       returned.
   *   <li>If the type is valid, the resulting type of the operation is {@link Type#BOOLEAN}.
   * </ul>
   *
   * The resulting type is stored in the {@code nodeTypes} map.
   *
   * @param ctx the context of the 'excludes' operation node in the AST
   * @return {@link Type#BOOLEAN} if the receiver is a collection; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitExcludesOp(OCLParser.ExcludesOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'excludes' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    nodeTypes.put(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Type checks the {@code flatten()} operation.
   *
   * <p>Requires Collection(Collection(T)), returns Collection(T).
   *
   * @param ctx The flatten operation node
   * @return Flattened collection type
   */
  @Override
  public Type visitFlattenOp(OCLParser.FlattenOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'flatten' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Type innerType = receiverType.getElementType();
    if (!innerType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Expected Collection(Collection(T))",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Type flatElementType = innerType.getElementType();
    Type resultType = preserveCollectionKind(receiverType, flatElementType);

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code union()} operation.
   *
   * <p>Combines two collections, computing result type based on collection properties.
   *
   * @param ctx The union operation node
   * @return Combined collection type
   */
  @Override
  public Type visitUnionOp(OCLParser.UnionOpContext ctx) {
    Type receiverType = receiverStack.peek();
    Type argType = visit(ctx.arg);

    if (!receiverType.isCollection() || !argType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'union' requires collection operands",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Type commonElemType =
        Type.commonSuperType(receiverType.getElementType(), argType.getElementType());

    boolean bothUnique = receiverType.isUnique() && argType.isUnique();
    boolean anyOrdered = receiverType.isOrdered() || argType.isOrdered();

    Type resultType;
    if (bothUnique && anyOrdered) {
      resultType = Type.orderedSet(commonElemType);
    } else if (bothUnique) {
      resultType = Type.set(commonElemType);
    } else if (anyOrdered) {
      resultType = Type.sequence(commonElemType);
    } else {
      resultType = Type.bag(commonElemType);
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Visits an 'append' operation node in the AST.
   *
   * <p>This method performs type checking and result type computation for the 'append' operation,
   * which combines two collections by adding all elements of the argument collection to the
   * receiver collection.
   *
   * <p>The steps are as follows:
   *
   * <ol>
   *   <li>Retrieve the receiver type from {@code receiverStack} and the argument type by visiting
   *       {@code ctx.arg}.
   *   <li>Check that both the receiver and argument are collections; if not, an error is added and
   *       {@link Type#ERROR} is returned.
   *   <li>Compute the common element type of the two collections using {@link
   *       Type#commonSuperType}.
   *   <li>Determine the resulting collection type based on uniqueness and ordering:
   *       <ul>
   *         <li>If both are unique and any is ordered → {@link Type#orderedSet}.
   *         <li>If both are unique → {@link Type#set}.
   *         <li>If any is ordered → {@link Type#sequence}.
   *         <li>Otherwise → {@link Type#bag}.
   *       </ul>
   *   <li>The computed result type is stored in {@code nodeTypes}.
   * </ol>
   *
   * @param ctx the context of the 'append' operation node in the AST
   * @return the resulting collection type if operands are valid; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitAppendOp(OCLParser.AppendOpContext ctx) {
    Type receiverType = receiverStack.peek();
    Type argType = visit(ctx.arg);

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'append' requires a collection receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    if (argType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'append' argument must be a singleton, not a collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    nodeTypes.put(ctx, receiverType);
    return receiverType;
  }

  // ==================== Aggregate Operations ====================

  /**
   * Type checks the {@code sum()} collection operation.
   *
   * <p>Requires a numeric collection receiver (elements must be INTEGER, FLOAT, or DOUBLE). The
   * result type is a singleton of the element type (preserves FLOAT for Float collections rather
   * than always returning INTEGER).
   *
   * @param ctx The sum operation node
   * @return Singleton of the element numeric type (INTEGER / FLOAT / DOUBLE)
   */
  @Override
  public Type visitSumOp(OCLParser.SumOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'sum' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Type elemType = receiverType.getElementType();
    if (elemType != Type.ANY && !TypeResolver.isNumeric(elemType)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'sum' requires numeric collection",
          ErrorSeverity.ERROR,
          "type-checker");
    }

    // Preserve the concrete numeric element type (FLOAT stays FLOAT, not INTEGER)
    Type resultType = Type.singleton(TypeResolver.isNumeric(elemType) ? elemType : Type.INTEGER);
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Visits a 'max' operation node in the AST.
   *
   * <p>This method delegates to {@link #visitAggregateOp} to perform type checking and result type
   * computation for the 'max' aggregate operation over a collection.
   *
   * @param ctx the context of the 'max' operation node in the AST
   * @return the resulting type of the aggregate operation
   */
  @Override
  public Type visitMaxOp(OCLParser.MaxOpContext ctx) {
    return visitAggregateOp(ctx, "max");
  }

  /**
   * Visits a 'min' operation node in the AST.
   *
   * <p>This method delegates to {@link #visitAggregateOp} to perform type checking and result type
   * computation for the 'min' aggregate operation over a collection.
   *
   * @param ctx the context of the 'min' operation node in the AST
   * @return the resulting type of the aggregate operation
   */
  @Override
  public Type visitMinOp(OCLParser.MinOpContext ctx) {
    return visitAggregateOp(ctx, "min");
  }

  /**
   * Visits an 'avg' operation node in the AST.
   *
   * <p>This method delegates to {@link #visitAggregateOp} to perform type checking and result type
   * computation for the 'avg' aggregate operation over a collection.
   *
   * @param ctx the context of the 'avg' operation node in the AST
   * @return the resulting type of the aggregate operation
   */
  @Override
  public Type visitAvgOp(OCLParser.AvgOpContext ctx) {
    return visitAggregateOp(ctx, "avg");
  }

  // ==================== Numeric Operations ====================

  /**
   * Type checks numeric operations (abs, floor, ceil, round).
   *
   * <p>All require numeric collection receivers and preserve collection type.
   */
  @Override
  public Type visitAbsOp(OCLParser.AbsOpContext ctx) {
    return visitNumericOp(ctx, "abs");
  }

  /**
   * Visits a 'floor' operation node in the AST.
   *
   * <p>This method delegates to {@link #visitNumericOp} to perform type checking and result type
   * computation for the 'floor' numeric operation.
   *
   * @param ctx the context of the 'floor' operation node in the AST
   * @return the resulting numeric type of the operation
   */
  @Override
  public Type visitFloorOp(OCLParser.FloorOpContext ctx) {
    return visitNumericOp(ctx, "floor");
  }

  /**
   * Visits a 'ceil' operation node in the AST.
   *
   * <p>This method delegates to {@link #visitNumericOp} to perform type checking and result type
   * computation for the 'ceil' numeric operation.
   *
   * @param ctx the context of the 'ceil' operation node in the AST
   * @return the resulting numeric type of the operation
   */
  @Override
  public Type visitCeilOp(OCLParser.CeilOpContext ctx) {
    return visitNumericOp(ctx, "ceil");
  }

  /**
   * Visits a 'round' operation node in the AST.
   *
   * <p>This method delegates to {@link #visitNumericOp} to perform type checking and result type
   * computation for the 'round' numeric operation.
   *
   * @param ctx the context of the 'round' operation node in the AST
   * @return the resulting numeric type of the operation
   */
  @Override
  public Type visitRoundOp(OCLParser.RoundOpContext ctx) {
    return visitNumericOp(ctx, "round");
  }

  /**
   * Helper for aggregate operation type checking ({@code min}, {@code max}, {@code avg}).
   *
   * <p>Accepts both multi-valued collections and singleton ¡T! receivers. A singleton is a
   * degenerate collection of one element, so min/max/avg are valid on it.
   *
   * @param ctx the operation node
   * @param opName operation name for error messages
   * @return singleton of the element type, or {@link Type#ERROR}
   */
  private Type visitAggregateOp(ParserRuleContext ctx, String opName) {
    Type receiverType = receiverStack.peek();

    // Reject singletons — max/min/avg require a collection, not a scalar
    if (receiverType.isSingleton() || !receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'" + opName + "' requires collection receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Type elemType = receiverType.getElementType();
    if (elemType.isSingleton()) {
      elemType = elemType.getElementType();
    }

    if (elemType != Type.ANY && !TypeResolver.isNumeric(elemType)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'" + opName + "' requires numeric collection",
          ErrorSeverity.ERROR,
          "type-checker");
    }

    Type resultType = Type.singleton(TypeResolver.isNumeric(elemType) ? elemType : Type.INTEGER);
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type-checks the {@code length()} operation on a String receiver.
   *
   * <p>Verifies that the receiver type is conformant to {@link Type#STRING}, then registers and
   * returns {@link Type#INTEGER} as the result type.
   *
   * @param ctx the parse tree node for the {@code length()} operation
   * @return {@link Type#INTEGER}; or {@link Type#ERROR} if the receiver is not conformant to {@code
   *     STRING}
   */
  @Override
  public Type visitLengthOp(OCLParser.LengthOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'length' requires String receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    nodeTypes.put(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  /**
   * Type-checks the {@code size()} operation on a collection receiver.
   *
   * <p>Verifies that the receiver is a collection type, then registers and returns {@link
   * Type#INTEGER} as the result type.
   *
   * @param ctx the parse tree node for the {@code size()} operation
   * @return {@link Type#INTEGER}; or {@link Type#ERROR} if the receiver is not a collection type
   */
  @Override
  public Type visitSizeOp(OCLParser.SizeOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "size() requires collection receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    nodeTypes.put(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  /**
   * Helper for numeric element operations ({@code abs}, {@code floor}, {@code ceil}, {@code
   * round}).
   *
   * <p>Accepts both collections and singletons ¡T! — a scalar Real/Float/Integer is ¡T! and numeric
   * operations on it are valid.
   *
   * @param ctx the operation node
   * @param opName operation name for error messages
   * @return the receiver's type unchanged, or {@link Type#ERROR}
   */
  private Type visitNumericOp(ParserRuleContext ctx, String opName) {
    Type receiverType = receiverStack.peek();

    Type checkType = receiverType.isSingleton() ? receiverType.getElementType() : receiverType;

    // Reject collections — abs/floor/ceil/round require scalar receiver
    if (checkType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'" + opName + "' requires scalar numeric receiver, not a collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    if (TypeResolver.isNumeric(checkType)) {
      nodeTypes.put(ctx, receiverType);
      return receiverType;
    }

    if (checkType != Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'" + opName + "' requires numeric receiver",
          ErrorSeverity.ERROR,
          "type-checker");
    }
    return Type.ERROR;
  }

  /**
   * Type checks the {@code lift()} operation.
   *
   * <p>Wraps collection in another collection: Collection(T) → Collection(Collection(T))
   *
   * @param ctx The lift operation node
   * @return Nested collection type
   */
  @Override
  public Type visitLiftOp(OCLParser.LiftOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'lift' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Type resultType = preserveCollectionKind(receiverType, receiverType);
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  // ==================== Iterator Operations ====================

  /**
   * Type checks the {@code select()} iterator.
   *
   * <p>Iterator variable receives type {@code ¡T!} (singleton — each element drawn from a
   * collection is a singleton value.
   *
   * @param ctx The select operation node
   * @return Same collection type as receiver
   */
  @Override
  public Type visitSelectOp(OCLParser.SelectOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'select' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.isEmpty()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "select requires at least one iterator variable",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    // each iterated element is a singleton ¡T! of the collection's element type
    Type elemType = receiverType.getElementType();
    Type iterVarType = normalizeToSingleton(elemType);

    Scope iterScope = scopeAnnotator.getScope(ctx);
    if (iterScope == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Internal error: No scope annotation from Pass 1",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    symbolTable.enterScope(iterScope);

    try {
      for (String iterVar : iterVars) {
        VariableSymbol symbol = symbolTable.resolveVariable(iterVar);
        if (symbol != null && symbol.getType() == Type.ANY) {
          symbol.setType(iterVarType);
        }
      }

      Type bodyType = visit(ctx.body);
      Type checkType = bodyType.isCollection() ? bodyType.getElementType() : bodyType;

      if (!checkType.isConformantTo(Type.BOOLEAN)) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "select body must return Boolean",
            ErrorSeverity.ERROR,
            "type-checker");
      }

      nodeTypes.put(ctx, receiverType);
      return receiverType;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Type checks the {@code reject()} iterator.
   *
   * <p>Iterator variable receives type {@code ¡T!} (singleton)
   *
   * @param ctx The reject operation node
   * @return Same collection type as receiver
   */
  @Override
  public Type visitRejectOp(OCLParser.RejectOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'reject' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.isEmpty()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "reject requires at least one iterator variable",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    // each iterated element is a singleton ¡T! of the collection's element type
    Type elemType = receiverType.getElementType();
    Type iterVarType = normalizeToSingleton(elemType);

    Scope iterScope = scopeAnnotator.getScope(ctx);
    if (iterScope == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Internal error: No scope annotation from Pass 1",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    symbolTable.enterScope(iterScope);

    try {
      for (String iterVar : iterVars) {
        VariableSymbol symbol = symbolTable.resolveVariable(iterVar);
        if (symbol != null && symbol.getType() == Type.ANY) {
          symbol.setType(iterVarType);
        }
      }

      Type bodyType = visit(ctx.body);

      if (!bodyType.getElementType().equals(Type.BOOLEAN)) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "reject predicate must return Boolean",
            ErrorSeverity.ERROR,
            "type-checker");
        return Type.ERROR;
      }

      nodeTypes.put(ctx, receiverType);
      return receiverType;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Type checks the {@code collect()} iterator.
   *
   * <p>Iterator variable receives type {@code ¡T!} (singleton)
   *
   * @param ctx The collect operation node
   * @return Collection of the body expression type
   */
  @Override
  public Type visitCollectOp(OCLParser.CollectOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'collect' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.isEmpty()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "collect requires at least one iterator variable",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    // each iterated element is a singleton ¡T! of the collection's element type
    Type elemType = receiverType.getElementType();
    Type iterVarType = normalizeToSingleton(elemType);

    Scope iterScope = scopeAnnotator.getScope(ctx);
    if (iterScope == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Internal error: No scope annotation from Pass 1",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    symbolTable.enterScope(iterScope);

    try {
      for (String iterVar : iterVars) {
        VariableSymbol symbol = symbolTable.resolveVariable(iterVar);
        if (symbol != null && symbol.getType() == Type.ANY) {
          symbol.setType(iterVarType);
        }
      }

      Type bodyType = visit(ctx.body);

      if (bodyType == Type.ERROR) {
        return Type.ERROR;
      }

      // collect result: preserve collection kind of receiver, element type from body
      // If body returns ¡T!, the collection element type is T (unwrap singleton)
      Type resultElemType = bodyType.isSingleton() ? bodyType.getElementType() : bodyType;
      Type resultType = preserveCollectionKind(receiverType, resultElemType);
      nodeTypes.put(ctx, resultType);
      return resultType;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Type checks the {@code forAll()} iterator.
   *
   * <p>Iterator variable receives type {@code ¡T!} (singleton) — each element drawn from a
   * collection is a singleton value.
   *
   * @param ctx the context of the 'forAll' operation node in the AST
   * @return {@link Type#BOOLEAN} if successful; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitForAllOp(OCLParser.ForAllOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'forAll' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    // each iterated element is a singleton ¡T! of the collection's element type
    Type elemType = receiverType.getElementType();
    Type iterVarType = normalizeToSingleton(elemType);

    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.isEmpty()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "forAll requires at least one iterator variable",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Scope iterScope = scopeAnnotator.getScope(ctx);
    if (iterScope == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Internal error: No scope annotation from Pass 1",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    symbolTable.enterScope(iterScope);

    try {
      for (String iterVar : iterVars) {
        VariableSymbol symbol = symbolTable.resolveVariable(iterVar);
        if (symbol != null && symbol.getType() == Type.ANY) {
          symbol.setType(iterVarType);
        }
      }

      Type bodyType = visit(ctx.body);

      if (!bodyType.getElementType().equals(Type.BOOLEAN)) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "forAll predicate must return Boolean",
            ErrorSeverity.ERROR,
            "type-checker");
        return Type.ERROR;
      }

      nodeTypes.put(ctx, Type.BOOLEAN);
      return Type.BOOLEAN;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Type checks the {@code exists()} iterator.
   *
   * <p>Iterator variable receives type {@code ¡T!} (singleton) per.
   *
   * @param ctx the context of the 'exists' operation node in the AST
   * @return {@link Type#BOOLEAN} if successful; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitExistsOp(OCLParser.ExistsOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'exists' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    // each iterated element is a singleton ¡T! of the collection's element type
    Type elemType = receiverType.getElementType();
    Type iterVarType = normalizeToSingleton(elemType);

    List<String> iterVars = new ArrayList<>();
    if (ctx.iteratorVars != null) {
      for (TerminalNode id : ctx.iteratorVarList().ID()) {
        iterVars.add(id.getText());
      }
    }

    if (iterVars.isEmpty()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "exists requires at least one iterator variable",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Scope iterScope = scopeAnnotator.getScope(ctx);
    if (iterScope == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Internal error: No scope annotation from Pass 1",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    symbolTable.enterScope(iterScope);

    try {
      for (String iterVar : iterVars) {
        VariableSymbol symbol = symbolTable.resolveVariable(iterVar);
        if (symbol != null && symbol.getType() == Type.ANY) {
          symbol.setType(iterVarType);
        }
      }

      Type bodyType = visit(ctx.body);

      if (!bodyType.getElementType().equals(Type.BOOLEAN)) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "exists predicate must return Boolean",
            ErrorSeverity.ERROR,
            "type-checker");
        return Type.ERROR;
      }

      nodeTypes.put(ctx, Type.BOOLEAN);
      return Type.BOOLEAN;
    } finally {
      symbolTable.exitScope();
    }
  }

  // ==================== String Operations ====================

  /**
   * Type checks string operations (concat, substring, toUpper, etc.).
   *
   * <p>All validate String receiver and appropriate argument types.
   */
  @Override
  public Type visitConcatOp(OCLParser.ConcatOpContext ctx) {
    Type receiverType = receiverStack.peek();
    Type argType = visit(ctx.arg);

    if (!receiverType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "concat requires String receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    if (!argType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "concat argument must be String",
          ErrorSeverity.ERROR,
          "type-checker");
    }

    nodeTypes.put(ctx, Type.STRING);
    return Type.STRING;
  }

  /**
   * Visits a 'substring' operation node in the AST.
   *
   * <p>This operation extracts a substring from a String receiver using start and end indices.
   *
   * <p>Steps:
   *
   * <ul>
   *   <li>Check that the receiver is of type {@link Type#STRING}.
   *   <li>Visit and check that both start and end indices are of type {@link Type#INTEGER}.
   *   <li>If type checks pass, the resulting type is {@link Type#STRING}.
   * </ul>
   *
   * @param ctx the context of the 'substring' operation node in the AST
   * @return {@link Type#STRING} if the receiver and indices are valid; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitSubstringOp(OCLParser.SubstringOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'substring' requires String",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Type startType = visit(ctx.start);
    Type endType = visit(ctx.end);

    if (!startType.isConformantTo(Type.INTEGER)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Start index must be Integer",
          ErrorSeverity.ERROR,
          "type-checker");
    }

    if (!endType.isConformantTo(Type.INTEGER)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "End index must be Integer",
          ErrorSeverity.ERROR,
          "type-checker");
    }

    nodeTypes.put(ctx, Type.STRING);
    return Type.STRING;
  }

  /**
   * Visits a 'toUpper' operation node in the AST.
   *
   * <p>Delegates to {@link #visitStringNoArgOp} for type checking and ensures the receiver is a
   * String. The result type is {@link Type#STRING}.
   *
   * @param ctx the context of the 'toUpper' operation node in the AST
   * @return {@link Type#STRING} if the receiver is a String; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitToUpperOp(OCLParser.ToUpperOpContext ctx) {
    return visitStringNoArgOp(ctx, "toUpper");
  }

  /**
   * Visits a 'toLower' operation node in the AST.
   *
   * <p>Delegates to {@link #visitStringNoArgOp} for type checking and ensures the receiver is a
   * String. The result type is {@link Type#STRING}.
   *
   * @param ctx the context of the 'toLower' operation node in the AST
   * @return {@link Type#STRING} if the receiver is a String; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitToLowerOp(OCLParser.ToLowerOpContext ctx) {
    return visitStringNoArgOp(ctx, "toLower");
  }

  /**
   * Visits an 'indexOf' operation node in the AST.
   *
   * <p>This operation returns the position of a substring within a String receiver.
   *
   * <ul>
   *   <li>Check that the receiver is {@link Type#STRING}.
   *   <li>Check that the argument is {@link Type#STRING}.
   *   <li>If valid, the resulting type is {@link Type#INTEGER}.
   * </ul>
   *
   * @param ctx the context of the 'indexOf' operation node in the AST
   * @return {@link Type#INTEGER} if the receiver and argument are valid; otherwise {@link
   *     Type#ERROR}
   */
  @Override
  public Type visitIndexOfOp(OCLParser.IndexOfOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'indexOf' requires String",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Type argType = visit(ctx.arg);
    if (!argType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Argument must be String",
          ErrorSeverity.ERROR,
          "type-checker");
    }

    nodeTypes.put(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  /**
   * Visits an 'equalsIgnoreCase' operation node in the AST.
   *
   * <p>This operation compares the receiver String with another String argument, ignoring case.
   *
   * <ul>
   *   <li>Check that the receiver is {@link Type#STRING}.
   *   <li>Check that the argument is {@link Type#STRING}.
   *   <li>If valid, the resulting type is {@link Type#BOOLEAN}.
   * </ul>
   *
   * @param ctx the context of the 'equalsIgnoreCase' operation node in the AST
   * @return {@link Type#BOOLEAN} if the receiver and argument are valid; otherwise {@link
   *     Type#ERROR}
   */
  @Override
  public Type visitEqualsIgnoreCaseOp(OCLParser.EqualsIgnoreCaseOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'equalsIgnoreCase' requires String",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Type argType = visit(ctx.arg);
    if (!argType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Argument must be String",
          ErrorSeverity.ERROR,
          "type-checker");
    }

    nodeTypes.put(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /** Helper for string operations with no arguments. */
  private Type visitStringNoArgOp(ParserRuleContext ctx, String opName) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'" + opName + "' requires String",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    nodeTypes.put(ctx, Type.STRING);
    return Type.STRING;
  }

  // ==================== Type Operations ====================

  /**
   * Type checks the {@code oclIsKindOf()} operation.
   *
   * <p>Returns collection of Boolean values, one per element.
   *
   * @param ctx The oclIsKindOf operation node
   * @return Collection(Boolean) with same kind as receiver
   */
  @Override
  public Type visitOclIsKindOfOp(OCLParser.OclIsKindOfOpContext ctx) {
    Type receiverType = receiverStack.peek();

    Type targetType = visit(ctx.type);
    if (targetType == null && ctx.type != null) {
      targetType = resolveTypeExpression(ctx.type);
    }

    if (!receiverType.isCollection()) {
      receiverType = Type.set(receiverType);
    }

    // Preserve collection kind from receiver
    Type resultType = preserveCollectionKind(receiverType, Type.BOOLEAN);
    nodeTypes.put(ctx, resultType);
    nodeTypes.put(ctx.type, targetType);
    return resultType;
  }

  /** Helper to resolve type expressions for type operations. */
  private Type resolveTypeExpression(OCLParser.TypeExpCSContext ctx) {
    String text = ctx.getText();
    return switch (text) {
      case "Integer" -> Type.INTEGER;
      case "String" -> Type.STRING;
      case "Boolean" -> Type.BOOLEAN;
      default -> Type.ANY;
    };
  }

  /**
   * Visits an 'oclIsTypeOf' operation node in the AST.
   *
   * <p>This operation checks whether each element of a collection is exactly of a given type.
   *
   * <ul>
   *   <li>Ensures that the receiver is a collection; otherwise reports an error and returns {@link
   *       Type#ERROR}.
   *   <li>The resulting type preserves the collection kind of the receiver, but with {@link
   *       Type#BOOLEAN} as element type.
   *   <li>The resulting type is stored in {@code nodeTypes}.
   * </ul>
   *
   * @param ctx the context of the 'oclIsTypeOf' operation node in the AST
   * @return the collection of Boolean type if receiver is valid; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitOclIsTypeOfOp(OCLParser.OclIsTypeOfOpContext ctx) {
    Type receiverType = receiverStack.peek();

    Type targetType = visit(ctx.type);
    if (targetType == null && ctx.type != null) {
      targetType = resolveTypeExpression(ctx.type);
    }

    // Wrap singleton/metaclass in collection for oclIsTypeOf
    if (!receiverType.isCollection()) {
      receiverType = Type.set(receiverType);
    }

    Type resultType = preserveCollectionKind(receiverType, Type.BOOLEAN);
    nodeTypes.put(ctx, resultType);
    nodeTypes.put(ctx.type, targetType);
    return resultType;
  }

  /**
   * Visits an 'oclAsType' operation node in the AST.
   *
   * <p>Casts the receiver to the target type while preserving the multiplicity. The receiver's
   * ctype multiplicity is preserved — only the member type τ is replaced by the target type:
   *
   * <ul>
   *   <li>{@code ¡Shape! .oclAsType(Box)} → {@code ¡Box!}
   *   <li>{@code {Shape} .oclAsType(Box)} → {@code {Box}}
   * </ul>
   *
   * @param ctx the context of the 'oclAsType' operation node in the AST
   * @return the cast type with preserved multiplicity, or {@link Type#ERROR}
   */
  @Override
  public Type visitOclAsTypeOp(OCLParser.OclAsTypeOpContext ctx) {
    Type receiverType = receiverStack.peek();

    Type targetType = visit(ctx.type);
    if (targetType == null && ctx.type != null) {
      targetType = resolveTypeExpression(ctx.type);
    }

    // preserve the receiver's multiplicity, replace only the
    // member type τ. Bare metaclass types are implicitly ¡T! (singleton).
    Type resultType;
    if (receiverType.isCollection() && !receiverType.isSingleton()) {
      // Multi-valued collection {T}, [T], etc. — preserve collection kind
      resultType = preserveCollectionKind(receiverType, targetType);
    } else {
      // Singleton ¡T! or bare metaclass T → result is ¡targetType!
      resultType = Type.singleton(targetType);
    }

    nodeTypes.put(ctx, resultType);
    nodeTypes.put(ctx.type, targetType);
    return resultType;
  }

  // ==================== Metamodel Operations ====================
  /**
   * Type checks the {@code allInstances()} operation.
   *
   * <p>Requires metaclass receiver, returns Set of that metaclass type.
   *
   * <p><b>Example:</b> {@code Person.allInstances()} → Set(Person)
   *
   * @param ctx The allInstances operation node
   * @return Set(MetaclassType)
   */
  @Override
  public Type visitAllInstancesOp(OCLParser.AllInstancesOpContext ctx) {
    Type receiverType = receiverStack.peek();

    if (!receiverType.isMetaclassType()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "allInstances() requires metaclass receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Type resultType = Type.set(receiverType);
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /** Placeholder for message operator (^). */
  @Override
  public Type visitMessage(OCLParser.MessageContext ctx) {
    Type leftType = visit(ctx.left);

    errors.add(
        ctx.getStart().getLine(),
        ctx.getStart().getCharPositionInLine(),
        "Message operator '^' not yet implemented",
        ErrorSeverity.WARNING,
        "type-checker");

    nodeTypes.put(ctx, leftType);
    return leftType;
  }

  // ==================== Delegation & Miscellaneous ====================
  @Override
  public Type visitPrefixedExpr(OCLParser.PrefixedExprContext ctx) {
    return visit(ctx.prefixedExpCS());
  }

  /**
   * Visits a literal node in the AST.
   *
   * <p>Delegates type checking to the underlying literal expression.
   *
   * @param ctx the context of the literal node in the AST
   * @return the type of the literal expression
   */
  @Override
  public Type visitLiteral(OCLParser.LiteralContext ctx) {
    return visit(ctx.literalExpCS());
  }

  /**
   * Visits a conditional (if-then-else) node in the AST.
   *
   * <p>Delegates type checking to the underlying if-expression.
   *
   * @param ctx the context of the conditional node in the AST
   * @return the type of the if-expression
   */
  @Override
  public Type visitConditional(OCLParser.ConditionalContext ctx) {
    return visit(ctx.ifExpCS());
  }

  /**
   * Visits a let-binding node in the AST.
   *
   * <p>Delegates type checking to the underlying let-expression.
   *
   * @param ctx the context of the let-binding node in the AST
   * @return the type of the let-expression
   */
  @Override
  public Type visitLetBinding(OCLParser.LetBindingContext ctx) {
    return visit(ctx.letExpCS());
  }

  /**
   * Type-checks a {@code let} expression.
   *
   * <p>Retrieves the {@link Scope} pre-annotated by Pass 1, enters it, then type-checks all
   * variable declarations in order before type-checking each body expression in sequence. The type
   * of the last body expression is registered as the result type of the {@code let} expression.
   *
   * @param ctx the parse tree node for the {@code let} expression, containing the variable
   *     declaration list and one or more body expressions
   * @return the type of the last body expression; or {@link Type#ERROR} if no scope annotation is
   *     found, or the body contains no expressions
   */
  @Override
  public Type visitLetExpCS(OCLParser.LetExpCSContext ctx) {
    Scope letScope = scopeAnnotator.getScope(ctx);
    if (letScope == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Internal error: No scope annotation from Pass 1",
          ErrorSeverity.ERROR,
          "type-checker");

      return Type.ERROR;
    }

    symbolTable.enterScope(letScope);

    try {
      visit(ctx.variableDeclarations());

      List<OCLParser.ExpCSContext> allExps = ctx.expCS();
      Type bodyType = null;
      for (OCLParser.ExpCSContext exp : allExps) {
        bodyType = visit(exp);
      }

      if (bodyType == null) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "Let body empty",
            ErrorSeverity.ERROR,
            "type-checker");
        bodyType = Type.ERROR;
      }

      nodeTypes.put(ctx, bodyType);
      return bodyType;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Visits a collection literal node in the AST.
   *
   * <p>Delegates type checking to the underlying collection literal expression.
   *
   * @param ctx the context of the collection literal node in the AST
   * @return the type of the collection literal expression
   */
  @Override
  public Type visitCollectionLiteral(OCLParser.CollectionLiteralContext ctx) {
    return visit(ctx.collectionLiteralExpCS());
  }

  /**
   * Visits a type literal node in the AST.
   *
   * <p>Delegates type checking to the underlying type literal expression.
   *
   * @param ctx the context of the type literal node in the AST
   * @return the type of the type literal expression
   */
  @Override
  public Type visitTypeLiteral(OCLParser.TypeLiteralContext ctx) {
    return visit(ctx.typeLiteralExpCS());
  }

  /**
   * Visits a nested expression node in the AST.
   *
   * <p>Delegates type checking to the underlying nested expression.
   *
   * @param ctx the context of the nested expression node in the AST
   * @return the type of the nested expression
   */
  @Override
  public Type visitNested(OCLParser.NestedContext ctx) {
    return visit(ctx.nestedExpCS());
  }

  /**
   * Visits a 'self' expression node in the AST.
   *
   * <p>Delegates type checking to the underlying self expression.
   *
   * @param ctx the context of the self expression node in the AST
   * @return the type of the self expression
   */
  @Override
  public Type visitSelf(OCLParser.SelfContext ctx) {
    return visit(ctx.selfExpCS());
  }

  /**
   * Visits a variable reference node in the AST.
   *
   * <p>Delegates type checking to the underlying variable expression.
   *
   * @param ctx the context of the variable reference node in the AST
   * @return the type of the variable expression
   */
  @Override
  public Type visitVariable(OCLParser.VariableContext ctx) {
    return visit(ctx.variableExpCS());
  }

  /**
   * Visits a nested expression node in the AST.
   *
   * <p>A nested expression may contain one or more sub-expressions. This method performs the
   * following steps:
   *
   * <ul>
   *   <li>Checks whether the nested expression contains any sub-expressions; if empty, an error is
   *       reported and {@link Type#ERROR} is returned.
   *   <li>Iterates over all sub-expressions, visiting each one and updating the resulting type to
   *       the type of the last sub-expression.
   *   <li>Stores the resulting type in {@code nodeTypes}.
   * </ul>
   *
   * @param ctx the context of the nested expression node in the AST
   * @return the type of the last sub-expression if any exist; otherwise {@link Type#ERROR}
   */
  @Override
  public Type visitNestedExpCS(OCLParser.NestedExpCSContext ctx) {
    List<OCLParser.ExpCSContext> exps = ctx.expCS();
    if (exps.isEmpty()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Empty nested expression",
          ErrorSeverity.ERROR,
          "type-checker");
      nodeTypes.put(ctx, Type.ERROR);
      return Type.ERROR;
    }

    Type resultType = null;
    for (OCLParser.ExpCSContext exp : exps) {
      resultType = visit(exp);
    }

    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Visits a 'onespace' lexical token in the AST.
   *
   * <p>This node represents a whitespace token and has type {@link Type#ANY}.
   *
   * @param ctx the context of the onespace token in the AST
   * @return {@link Type#ANY} since it represents a lexical token
   */
  @Override
  public Type visitOnespace(OCLParser.OnespaceContext ctx) {
    return Type.ANY; // Lexical token
  }

  // ==================== New Iterator Operations ====================

  /**
   * Type checks the {@code one()} iterator.
   *
   * <p>Returns true iff exactly one element satisfies the predicate.
   *
   * @param ctx The one operation node
   * @return Type.BOOLEAN
   */
  @Override
  public Type visitOneOp(OCLParser.OneOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'one' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }

    Type iterVarType = normalizeToSingleton(receiverType.getElementType());
    Scope iterScope = scopeAnnotator.getScope(ctx);
    if (iterScope == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Internal error: No scope annotation from Pass 1",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    symbolTable.enterScope(iterScope);
    try {
      for (String iterVar : iterVars) {
        VariableSymbol symbol = symbolTable.resolveVariable(iterVar);
        if (symbol != null && symbol.getType() == Type.ANY) symbol.setType(iterVarType);
      }
      Type bodyType = visit(ctx.body);
      Type checkType = bodyType.isCollection() ? bodyType.getElementType() : bodyType;
      if (!checkType.isConformantTo(Type.BOOLEAN)) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "one() predicate must return Boolean",
            ErrorSeverity.ERROR,
            "type-checker");
      }
      nodeTypes.put(ctx, Type.BOOLEAN);
      return Type.BOOLEAN;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Type checks the {@code any()} iterator.
   *
   * <p>Returns an arbitrary element satisfying the predicate — result is a singleton of the element
   * type.
   *
   * @param ctx The any operation node
   * @return Singleton of element type
   */
  @Override
  public Type visitAnyOp(OCLParser.AnyOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'any' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }

    Type iterVarType = normalizeToSingleton(receiverType.getElementType());
    Scope iterScope = scopeAnnotator.getScope(ctx);
    if (iterScope == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Internal error: No scope annotation from Pass 1",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    symbolTable.enterScope(iterScope);
    try {
      for (String iterVar : iterVars) {
        VariableSymbol symbol = symbolTable.resolveVariable(iterVar);
        if (symbol != null && symbol.getType() == Type.ANY) symbol.setType(iterVarType);
      }
      Type bodyType = visit(ctx.body);
      Type checkType = bodyType.isCollection() ? bodyType.getElementType() : bodyType;
      if (!checkType.isConformantTo(Type.BOOLEAN)) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "any() predicate must return Boolean",
            ErrorSeverity.ERROR,
            "type-checker");
      }
      // any() returns one element — singleton of the element type
      Type resultType = Type.singleton(receiverType.getElementType());
      nodeTypes.put(ctx, resultType);
      return resultType;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Type checks the {@code isUnique()} iterator.
   *
   * <p>Returns true iff all body results are distinct.
   *
   * @param ctx The isUnique operation node
   * @return Type.BOOLEAN
   */
  @Override
  public Type visitIsUniqueOp(OCLParser.IsUniqueOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'isUnique' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }

    Type iterVarType = normalizeToSingleton(receiverType.getElementType());
    Scope iterScope = scopeAnnotator.getScope(ctx);
    if (iterScope == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Internal error: No scope annotation from Pass 1",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    symbolTable.enterScope(iterScope);
    try {
      for (String iterVar : iterVars) {
        VariableSymbol symbol = symbolTable.resolveVariable(iterVar);
        if (symbol != null && symbol.getType() == Type.ANY) symbol.setType(iterVarType);
      }
      visit(ctx.body); // body type unconstrained — just check it parses
      nodeTypes.put(ctx, Type.BOOLEAN);
      return Type.BOOLEAN;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Type checks the {@code sortedBy()} iterator.
   *
   * <p>Body must return a comparable type. Result is an OrderedSet of the element type.
   *
   * @param ctx The sortedBy operation node
   * @return OrderedSet of element type
   */
  @Override
  public Type visitSortedByOp(OCLParser.SortedByOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'sortedBy' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }

    Type iterVarType = normalizeToSingleton(receiverType.getElementType());
    Scope iterScope = scopeAnnotator.getScope(ctx);
    if (iterScope == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Internal error: No scope annotation from Pass 1",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    symbolTable.enterScope(iterScope);
    try {
      for (String iterVar : iterVars) {
        VariableSymbol symbol = symbolTable.resolveVariable(iterVar);
        if (symbol != null && symbol.getType() == Type.ANY) symbol.setType(iterVarType);
      }
      Type bodyType = visit(ctx.body);
      // body should return something comparable (numeric or string)
      Type checkType = bodyType.isSingleton() ? bodyType.getElementType() : bodyType;
      if (checkType != Type.ANY
          && !TypeResolver.isNumeric(checkType)
          && !checkType.isConformantTo(Type.STRING)
          && !checkType.isEnumType()
          && checkType != Type.ERROR) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "sortedBy() body must return a comparable type (numeric or String), got: " + checkType,
            ErrorSeverity.ERROR,
            "type-checker");
      }
      // result is always OrderedSet of the original element type
      Type resultType = Type.orderedSet(receiverType.getElementType());
      nodeTypes.put(ctx, resultType);
      return resultType;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Type checks the {@code collectNested()} iterator.
   *
   * <p>Like collect() but does NOT flatten — returns a collection of collections.
   *
   * @param ctx The collectNested operation node
   * @return Collection of body types (not flattened)
   */
  @Override
  public Type visitCollectNestedOp(OCLParser.CollectNestedOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'collectNested' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    List<String> iterVars = new ArrayList<>();
    for (TerminalNode id : ctx.iteratorVarList().ID()) {
      iterVars.add(id.getText());
    }

    Type iterVarType = normalizeToSingleton(receiverType.getElementType());
    Scope iterScope = scopeAnnotator.getScope(ctx);
    if (iterScope == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Internal error: No scope annotation from Pass 1",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    symbolTable.enterScope(iterScope);
    try {
      for (String iterVar : iterVars) {
        VariableSymbol symbol = symbolTable.resolveVariable(iterVar);
        if (symbol != null && symbol.getType() == Type.ANY) symbol.setType(iterVarType);
      }
      Type bodyType = visit(ctx.body);
      if (bodyType == Type.ERROR) return Type.ERROR;
      // collectNested: preserve receiver kind, element type is the raw body type (no unwrap)
      Type resultType = preserveCollectionKind(receiverType, bodyType);
      nodeTypes.put(ctx, resultType);
      return resultType;
    } finally {
      symbolTable.exitScope();
    }
  }

  /**
   * Type checks the {@code iterate()} operation.
   *
   * <p>Syntax: {@code coll.iterate(elem; acc : T = init | body)} The accumulator type is the result
   * type.
   *
   * @param ctx The iterate operation node
   * @return The accumulator type
   */
  @Override
  public Type visitIterateOp(OCLParser.IterateOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'iterate' requires collection",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    Scope iterScope = scopeAnnotator.getScope(ctx);
    if (iterScope == null) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "Internal error: No scope annotation from Pass 1",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }

    symbolTable.enterScope(iterScope);
    try {
      OCLParser.IterateVarSpecContext spec = ctx.iterateVarSpec();

      // Iterator variable — gets element type of receiver
      String iterVarName = spec.iterVar.getText();
      Type iterVarType = normalizeToSingleton(receiverType.getElementType());
      VariableSymbol iterSym = symbolTable.resolveVariable(iterVarName);
      if (iterSym != null && iterSym.getType() == Type.ANY) iterSym.setType(iterVarType);

      // Accumulator type — either explicit or inferred from init expression
      Type accType;
      if (spec.accType != null) {
        accType = visit(spec.accType);
      } else {
        accType = visit(spec.accInit);
      }
      String accVarName = spec.accVar.getText();
      VariableSymbol accSym = symbolTable.resolveVariable(accVarName);
      if (accSym != null && accSym.getType() == Type.ANY) accSym.setType(accType);

      // Init expression must conform to accumulator type
      Type initType = visit(spec.accInit);
      if (!initType.isConformantTo(accType) && !conformsWithUnwrapping(initType, accType)) {
        errors.add(
            spec.getStart().getLine(),
            spec.getStart().getCharPositionInLine(),
            "iterate() accumulator init type "
                + initType
                + " does not conform to declared type "
                + accType,
            ErrorSeverity.ERROR,
            "type-checker");
      }

      // Body must conform to accumulator type
      Type bodyType = visit(ctx.body);
      if (!bodyType.isConformantTo(accType)
          && !conformsWithUnwrapping(bodyType, accType)
          && bodyType != Type.ERROR) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "iterate() body type " + bodyType + " does not conform to accumulator type " + accType,
            ErrorSeverity.ERROR,
            "type-checker");
      }

      nodeTypes.put(ctx, accType);
      return accType;
    } finally {
      symbolTable.exitScope();
    }
  }

  // ==================== New Collection Conversion Operations ====================

  /**
   * Type checks the {@code asSet()} operation.
   *
   * @param ctx The asSet operation node
   * @return Set of element type
   */
  @Override
  public Type visitAsSetOp(OCLParser.AsSetOpContext ctx) {
    return visitCollectionConversionOp(ctx, "asSet", Type::set);
  }

  /**
   * Type checks the {@code asBag()} operation.
   *
   * @param ctx The asBag operation node
   * @return Bag of element type
   */
  @Override
  public Type visitAsBagOp(OCLParser.AsBagOpContext ctx) {
    return visitCollectionConversionOp(ctx, "asBag", Type::bag);
  }

  /**
   * Type checks the {@code asSequence()} operation.
   *
   * @param ctx The asSequence operation node
   * @return Sequence of element type
   */
  @Override
  public Type visitAsSequenceOp(OCLParser.AsSequenceOpContext ctx) {
    return visitCollectionConversionOp(ctx, "asSequence", Type::sequence);
  }

  /**
   * Type checks the {@code asOrderedSet()} operation.
   *
   * @param ctx The asOrderedSet operation node
   * @return OrderedSet of element type
   */
  @Override
  public Type visitAsOrderedSetOp(OCLParser.AsOrderedSetOpContext ctx) {
    return visitCollectionConversionOp(ctx, "asOrderedSet", Type::orderedSet);
  }

  /** Helper for asSet/asBag/asSequence/asOrderedSet — all follow the same pattern. */
  private Type visitCollectionConversionOp(
      ParserRuleContext ctx, String opName, java.util.function.Function<Type, Type> factory) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'" + opName + "' requires collection receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type resultType = factory.apply(receiverType.getElementType());
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  // ==================== New Collection Operations ====================

  /**
   * Type checks the {@code includesAll()} operation.
   *
   * @param ctx The includesAll operation node
   * @return Type.BOOLEAN
   */
  @Override
  public Type visitIncludesAllOp(OCLParser.IncludesAllOpContext ctx) {
    Type receiverType = receiverStack.peek();
    Type argType = visit(ctx.arg);
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'includesAll' requires collection receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    if (!argType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'includesAll' argument must be a collection",
          ErrorSeverity.ERROR,
          "type-checker");
    }
    nodeTypes.put(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Type checks the {@code excludesAll()} operation.
   *
   * @param ctx The excludesAll operation node
   * @return Type.BOOLEAN
   */
  @Override
  public Type visitExcludesAllOp(OCLParser.ExcludesAllOpContext ctx) {
    Type receiverType = receiverStack.peek();
    Type argType = visit(ctx.arg);
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'excludesAll' requires collection receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    if (!argType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'excludesAll' argument must be a collection",
          ErrorSeverity.ERROR,
          "type-checker");
    }
    nodeTypes.put(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Type checks the {@code count()} operation.
   *
   * <p>Returns how many times an element occurs in the collection.
   *
   * @param ctx The count operation node
   * @return Type.INTEGER
   */
  @Override
  public Type visitCountOp(OCLParser.CountOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'count' requires collection receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type argType = visit(ctx.arg);
    Type elemType = receiverType.getElementType();
    if (!argType.isConformantTo(elemType)
        && !conformsWithUnwrapping(argType, elemType)
        && argType != Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "count() argument type "
              + argType
              + " incompatible with collection element type "
              + elemType,
          ErrorSeverity.WARNING,
          "type-checker");
    }
    nodeTypes.put(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  /**
   * Type checks the {@code intersection()} operation.
   *
   * <p>Both operands must be collections with compatible element types. Result preserves the more
   * restrictive kind (Set beats Bag, OrderedSet beats Sequence).
   *
   * @param ctx The intersection operation node
   * @return Collection of common element type
   */
  @Override
  public Type visitIntersectionOp(OCLParser.IntersectionOpContext ctx) {
    Type receiverType = receiverStack.peek();
    Type argType = visit(ctx.arg);
    if (!receiverType.isCollection() || !argType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'intersection' requires collection operands",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type commonElem = Type.commonSuperType(receiverType.getElementType(), argType.getElementType());
    // intersection result: unique if either operand is unique; ordered only if both are ordered
    boolean eitherUnique = receiverType.isUnique() || argType.isUnique();
    boolean bothOrdered = receiverType.isOrdered() && argType.isOrdered();
    Type resultType;
    if (eitherUnique && bothOrdered) {
      resultType = Type.orderedSet(commonElem);
    } else if (eitherUnique) {
      resultType = Type.set(commonElem);
    } else if (bothOrdered) {
      resultType = Type.sequence(commonElem);
    } else {
      resultType = Type.bag(commonElem);
    }
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code symmetricDifference()} operation.
   *
   * <p>Both operands must be Sets. Result is a Set of common element type.
   *
   * @param ctx The symmetricDifference operation node
   * @return Set of element type
   */
  @Override
  public Type visitSymmetricDifferenceOp(OCLParser.SymmetricDifferenceOpContext ctx) {
    Type receiverType = receiverStack.peek();
    Type argType = visit(ctx.arg);
    if (!receiverType.isCollection() || !argType.isCollection()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'symmetricDifference' requires collection operands",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    if (!receiverType.isUnique() || !argType.isUnique()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'symmetricDifference' requires Set operands (unique collections)",
          ErrorSeverity.ERROR, // WARNING → ERROR
          "type-checker");
      return Type.ERROR;
    }

    Type commonElem = Type.commonSuperType(receiverType.getElementType(), argType.getElementType());
    Type resultType = Type.set(commonElem);
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code prepend()} operation.
   *
   * <p>Requires Sequence or OrderedSet receiver. Returns same collection type.
   *
   * @param ctx The prepend operation node
   * @return Same ordered collection type
   */
  @Override
  public Type visitPrependOp(OCLParser.PrependOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection() || !receiverType.isOrdered()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'prepend' requires ordered collection (Sequence or OrderedSet)",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type argType = visit(ctx.arg);
    Type elemType = receiverType.getElementType();
    if (!argType.isConformantTo(elemType)
        && !conformsWithUnwrapping(argType, elemType)
        && argType != Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "prepend() argument type " + argType + " incompatible with element type " + elemType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    nodeTypes.put(ctx, receiverType);
    return receiverType;
  }

  /**
   * Type checks the {@code insertAt()} operation.
   *
   * <p>Requires ordered collection. Index must be Integer.
   *
   * @param ctx The insertAt operation node
   * @return Same ordered collection type
   */
  @Override
  public Type visitInsertAtOp(OCLParser.InsertAtOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection() || !receiverType.isOrdered()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'insertAt' requires ordered collection (Sequence or OrderedSet)",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type indexType = visit(ctx.index);
    if (!indexType.isConformantTo(Type.INTEGER)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "insertAt() index must be Integer, got " + indexType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    Type argType = visit(ctx.arg);
    Type elemType = receiverType.getElementType();
    if (!argType.isConformantTo(elemType)
        && !conformsWithUnwrapping(argType, elemType)
        && argType != Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "insertAt() element type "
              + argType
              + " incompatible with collection element type "
              + elemType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    nodeTypes.put(ctx, receiverType);
    return receiverType;
  }

  /**
   * Type checks the {@code subSequence()} operation.
   *
   * <p>Requires ordered collection. Both bounds must be Integer.
   *
   * @param ctx The subSequence operation node
   * @return Same ordered collection type
   */
  @Override
  public Type visitSubSequenceOp(OCLParser.SubSequenceOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isCollection() || !receiverType.isOrdered()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'subSequence' requires ordered collection (Sequence or OrderedSet)",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type startType = visit(ctx.start);
    Type endType = visit(ctx.end);
    if (!startType.isConformantTo(Type.INTEGER)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "subSequence() start index must be Integer, got " + startType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    if (!endType.isConformantTo(Type.INTEGER)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "subSequence() end index must be Integer, got " + endType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    nodeTypes.put(ctx, receiverType);
    return receiverType;
  }

  /**
   * Type checks the {@code at()} operation.
   *
   * <p>Requires ordered collection. Index must be Integer. Returns singleton of element type.
   *
   * @param ctx The at operation node
   * @return Singleton of element type
   */
  @Override
  public Type visitAtOp(OCLParser.AtOpContext ctx) {
    Type receiverType = receiverStack.peek();
    // at() also valid on String (returns a character as String)
    if (receiverType.isConformantTo(Type.STRING)) {
      Type indexType = visit(ctx.index);
      if (!indexType.isConformantTo(Type.INTEGER)) {
        errors.add(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            "at() index must be Integer, got " + indexType,
            ErrorSeverity.ERROR,
            "type-checker");
      }
      nodeTypes.put(ctx, Type.STRING);
      return Type.STRING;
    }
    if (!receiverType.isCollection() || !receiverType.isOrdered()) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'at' requires ordered collection or String receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type indexType = visit(ctx.index);
    if (!indexType.isConformantTo(Type.INTEGER)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "at() index must be Integer, got " + indexType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    Type resultType = Type.singleton(receiverType.getElementType());
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code ceiling()} operation (alias for ceil).
   *
   * @param ctx The ceiling operation node
   * @return Receiver type unchanged
   */
  @Override
  public Type visitCeilingOp(OCLParser.CeilingOpContext ctx) {
    return visitNumericOp(ctx, "ceiling");
  }

  /**
   * Type checks the {@code div()} operation (integer division).
   *
   * <p>Both receiver and argument must be Integer.
   *
   * @param ctx The div operation node
   * @return Type.INTEGER
   */
  @Override
  public Type visitDivOp(OCLParser.DivOpContext ctx) {
    Type receiverType = receiverStack.peek();
    Type argType = visit(ctx.arg);

    Type baseReceiver = receiverType.isSingleton() ? receiverType.getElementType() : receiverType;
    Type baseArg = argType.isSingleton() ? argType.getElementType() : argType;

    if (baseReceiver != Type.INTEGER && baseReceiver != Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "div() requires Integer receiver, got " + receiverType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    if (baseArg != Type.INTEGER && baseArg != Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "div() requires Integer argument, got " + argType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    nodeTypes.put(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  /**
   * Type checks the {@code mod()} operation (integer remainder).
   *
   * <p>Both receiver and argument must be Integer.
   *
   * @param ctx The mod operation node
   * @return Type.INTEGER
   */
  @Override
  public Type visitModOp(OCLParser.ModOpContext ctx) {
    Type receiverType = receiverStack.peek();
    Type argType = visit(ctx.arg);

    Type baseReceiver = receiverType.isSingleton() ? receiverType.getElementType() : receiverType;
    Type baseArg = argType.isSingleton() ? argType.getElementType() : argType;

    if (baseReceiver != Type.INTEGER && baseReceiver != Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "mod() requires Integer receiver, got " + receiverType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    if (baseArg != Type.INTEGER && baseArg != Type.ERROR) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "mod() requires Integer argument, got " + argType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    nodeTypes.put(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  // ==================== New String Operations ====================

  /**
   * Type checks the {@code toInteger()} operation.
   *
   * @param ctx The toInteger operation node
   * @return Type.INTEGER
   */
  @Override
  public Type visitToIntegerOp(OCLParser.ToIntegerOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'toInteger' requires String receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    nodeTypes.put(ctx, Type.INTEGER);
    return Type.INTEGER;
  }

  /**
   * Type checks the {@code toReal()} operation.
   *
   * @param ctx The toReal operation node
   * @return Type.DOUBLE
   */
  @Override
  public Type visitToRealOp(OCLParser.ToRealOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'toReal' requires String receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    nodeTypes.put(ctx, Type.DOUBLE);
    return Type.DOUBLE;
  }

  /**
   * Type checks the {@code characters()} operation.
   *
   * <p>Splits a String into a Sequence of single-character Strings.
   *
   * @param ctx The characters operation node
   * @return Sequence(String)
   */
  @Override
  public Type visitCharactersOp(OCLParser.CharactersOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'characters' requires String receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type resultType = Type.sequence(Type.STRING);
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Type checks the {@code matches()} operation.
   *
   * <p>Tests whether the receiver matches a regex pattern.
   *
   * @param ctx The matches operation node
   * @return Type.BOOLEAN
   */
  @Override
  public Type visitMatchesOp(OCLParser.MatchesOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'matches' requires String receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type argType = visit(ctx.arg);
    if (!argType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "matches() pattern argument must be String, got " + argType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    nodeTypes.put(ctx, Type.BOOLEAN);
    return Type.BOOLEAN;
  }

  /**
   * Type checks the {@code substituteAll()} operation.
   *
   * @param ctx The substituteAll operation node
   * @return Type.STRING
   */
  @Override
  public Type visitSubstituteAllOp(OCLParser.SubstituteAllOpContext ctx) {
    return visitSubstituteOp(ctx, ctx.pattern, ctx.replacement, "substituteAll");
  }

  /**
   * Type checks the {@code substituteFirst()} operation.
   *
   * @param ctx The substituteFirst operation node
   * @return Type.STRING
   */
  @Override
  public Type visitSubstituteFirstOp(OCLParser.SubstituteFirstOpContext ctx) {
    return visitSubstituteOp(ctx, ctx.pattern, ctx.replacement, "substituteFirst");
  }

  /** Helper for substituteAll / substituteFirst — identical type rules. */
  private Type visitSubstituteOp(
      ParserRuleContext ctx,
      OCLParser.ExpCSContext patternCtx,
      OCLParser.ExpCSContext replacementCtx,
      String opName) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'" + opName + "' requires String receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type patternType = visit(patternCtx);
    if (!patternType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          opName + "() pattern must be String, got " + patternType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    Type replacementType = visit(replacementCtx);
    if (!replacementType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          opName + "() replacement must be String, got " + replacementType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    nodeTypes.put(ctx, Type.STRING);
    return Type.STRING;
  }

  /**
   * Type checks the {@code tokenize()} operation.
   *
   * <p>Splits the receiver String by a delimiter, returning a Sequence of Strings.
   *
   * @param ctx The tokenize operation node
   * @return Sequence(String)
   */
  @Override
  public Type visitTokenizeOp(OCLParser.TokenizeOpContext ctx) {
    Type receiverType = receiverStack.peek();
    if (!receiverType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "'tokenize' requires String receiver",
          ErrorSeverity.ERROR,
          "type-checker");
      return Type.ERROR;
    }
    Type argType = visit(ctx.arg);
    if (!argType.isConformantTo(Type.STRING)) {
      errors.add(
          ctx.getStart().getLine(),
          ctx.getStart().getCharPositionInLine(),
          "tokenize() delimiter must be String, got " + argType,
          ErrorSeverity.ERROR,
          "type-checker");
    }
    Type resultType = Type.sequence(Type.STRING);
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Visits a type literal expression node in the AST.
   *
   * <p>This method delegates type checking to the underlying type literal node. The type literal
   * represents a type as an expression (e.g., a class or primitive type).
   *
   * @param ctx the context of the type literal expression node in the AST
   * @return the type of the underlying type literal
   */
  @Override
  public Type visitTypeLiteralExpCS(OCLParser.TypeLiteralExpCSContext ctx) {
    return visit(ctx.typeLiteralCS());
  }

  /**
   * Visits a property access node in the AST.
   *
   * <p>This method performs type checking for property access expressions, e.g., accessing a field
   * or attribute of an object or element of a collection.
   *
   * @param ctx the context of the property access node in the AST
   * @return the type of the accessed property
   */
  @Override
  public Type visitPropertyAccess(OCLParser.PropertyAccessContext ctx) {
    Type receiverType = receiverStack.peek();
    String propertyName = ctx.propertyName.getText();
    Type resultType = typeCheckPropertyAccess(receiverType, propertyName, ctx.propertyName);
    nodeTypes.put(ctx, resultType);
    return resultType;
  }

  /**
   * Visits a collection operation node in the AST.
   *
   * <p>Delegates type checking to the underlying collection operation expression.
   *
   * @param ctx the context of the collection operation node in the AST
   * @return the type of the collection operation expression
   */
  @Override
  public Type visitCollectionOperation(OCLParser.CollectionOperationContext ctx) {
    return visit(ctx.collectionOpCS());
  }

  /**
   * Visits a string operation node in the AST.
   *
   * <p>Delegates type checking to the underlying string operation expression.
   *
   * @param ctx the context of the string operation node in the AST
   * @return the type of the string operation expression
   */
  @Override
  public Type visitStringOperation(OCLParser.StringOperationContext ctx) {
    return visit(ctx.stringOpCS());
  }

  /**
   * Visits an iterator operation node in the AST.
   *
   * <p>Delegates type checking to the underlying iterator operation expression.
   *
   * @param ctx the context of the iterator operation node in the AST
   * @return the type of the iterator operation expression
   */
  @Override
  public Type visitIteratorOperation(OCLParser.IteratorOperationContext ctx) {
    return visit(ctx.iteratorOpCS());
  }

  /**
   * Visits a type operation node in the AST.
   *
   * <p>Delegates type checking to the underlying type operation expression.
   *
   * @param ctx the context of the type operation node in the AST
   * @return the type of the type operation expression
   */
  @Override
  public Type visitTypeOperation(OCLParser.TypeOperationContext ctx) {
    return visit(ctx.typeOpCS());
  }

  // ==================== Unknown / Catch-All Operations ====================

  /**
   * Dispatches to {@link #visitUnknownOpCS} for an unrecognised operation call.
   *
   * @param ctx the context for the unknown operation
   * @return {@link Type#ERROR}
   */
  @Override
  public Type visitUnknownOperation(OCLParser.UnknownOperationContext ctx) {
    return visit(ctx.unknownOpCS());
  }

  /**
   * Catches unknown binary operators (e.g. {@code expr IsNotImplies expr}) written between two
   * expressions.
   *
   * <p>All known operators are specific keyword/symbol tokens and appear as earlier alternatives in
   * {@code infixedExpCS}. An {@code ID} token in operator position therefore always means a typo
   * or unsupported operator, so we report a precise diagnostic on the name token and return
   * {@link Type#ERROR} so that downstream checks (e.g. "Invariant must be Boolean") are silenced.
   *
   * @param ctx the context for the unknown binary operator expression
   * @return {@link Type#ERROR}
   */
  @Override
  public Type visitUnknownBinaryOp(OCLParser.UnknownBinaryOpContext ctx) {
    // Visit both sides so errors inside them are still reported.
    visit(ctx.left);
    visit(ctx.right);

    String typed = ctx.op.getText();
    String typedLow = typed.toLowerCase(java.util.Locale.ROOT);

    String best =
        KNOWN_BINARY_OPS.stream()
            .min(
                java.util.Comparator.comparingInt(
                    k -> levenshtein(typedLow, k.toLowerCase(java.util.Locale.ROOT))))
            .orElse(null);
    int dist =
        best == null
            ? Integer.MAX_VALUE
            : levenshtein(typedLow, best.toLowerCase(java.util.Locale.ROOT));

    int threshold = editThreshold(typed.length());
    String message;
    String suggestion;
    if (dist <= threshold) {
      message = "Unknown operator '" + typed + ERR_DID_YOU_MEAN + best + "'?";
      suggestion = best;
    } else {
      message = "Unknown operator '" + typed + "' — this operator does not exist";
      suggestion = best; // still offer a Quick Fix to the closest keyword op
    }

    org.antlr.v4.runtime.Token tok = ctx.op;
    int endCol = tok.getCharPositionInLine() + tok.getText().length();
    errors.add(
        new CompileError(
            tok.getLine(),
            tok.getCharPositionInLine(),
            tok.getLine(),
            endCol,
            message,
            ErrorSeverity.ERROR,
            "type-checker",
            null,
            suggestion));
    nodeTypes.put(ctx, Type.ERROR);
    return Type.ERROR;
  }

  /**
   * Reports a precise "Unknown operation 'X'" diagnostic on the operation-name token so the user
   * sees a squiggle under the name rather than a cryptic ANTLR "mismatched input ')'" error.
   *
   * <p>Arguments are still visited so that any errors inside them (e.g. unknown properties) are
   * reported independently.
   *
   * @param ctx the context for the unknown operation call
   * @return {@link Type#ERROR}
   */
  @Override
  public Type visitUnknownOpCS(OCLParser.UnknownOpCSContext ctx) {
    // Type-check arguments so their own errors surface.
    for (OCLParser.ExpCSContext arg : ctx.args) {
      visit(arg);
    }

    String typed = ctx.opName.getText();
    java.util.Optional<String> close = suggestOperation(typed); // within threshold
    String best = bestOperation(typed); // unconditional best

    String message;
    String suggestion; // stored for Quick Fix — always the best candidate
    if (close.isPresent()) {
      message = ERR_UNKNOWN_OP + typed + ERR_DID_YOU_MEAN + close.get() + "'?";
      suggestion = close.get();
    } else if (best != null) {
      message = ERR_UNKNOWN_OP + typed + ERR_OP_DOES_NOT_EXIST;
      suggestion = best;
    } else {
      message = ERR_UNKNOWN_OP + typed + ERR_OP_DOES_NOT_EXIST;
      suggestion = null;
    }

    org.antlr.v4.runtime.Token tok = ctx.opName;
    int endCol = tok.getCharPositionInLine() + tok.getText().length();
    errors.add(
        new CompileError(
            tok.getLine(),
            tok.getCharPositionInLine(),
            tok.getLine(),
            endCol,
            message,
            ErrorSeverity.ERROR,
            "type-checker",
            null,
            suggestion));
    nodeTypes.put(ctx, Type.ERROR);
    return Type.ERROR;
  }

  /**
   * Checks that {@code ctx.body} is present (i.e., the user wrote {@code | body}) for iterator
   * keywords like {@code select}/{@code exists}. Without this fallback, e.g. {@code exists(A)}
   * (missing {@code | body}) is caught here with a precise message instead of a cryptic ANTLR
   * parse error.
   */
  @Override
  public Type visitIteratorMissingBody(OCLParser.IteratorMissingBodyContext ctx) {
    String opName = ctx.op.getText();
    org.antlr.v4.runtime.Token start = ctx.getStart();
    org.antlr.v4.runtime.Token stop = ctx.getStop();
    int endLine = stop != null ? stop.getLine() : start.getLine();
    int endCol =
        stop != null
            ? stop.getCharPositionInLine() + stop.getText().length()
            : start.getCharPositionInLine() + ctx.getText().length();
    errors.add(
        new CompileError(
            start.getLine(),
            start.getCharPositionInLine(),
            endLine,
            endCol,
            "'" + opName + "' requires '| <body>' after the iterator variable(s)",
            ErrorSeverity.ERROR,
            "type-checker",
            null));
    return Type.ERROR;
  }

  /**
   * Checks for a completely empty iterator call, e.g. {@code select()}. Without this fallback the
   * parser falls through to a raw ANTLR "mismatched input ')' expecting {...}" error dumping the
   * entire start-of-expression token set, which is not actionable for the user.
   */
  @Override
  public Type visitIteratorMissingVarAndBody(OCLParser.IteratorMissingVarAndBodyContext ctx) {
    String opName = ctx.op.getText();
    org.antlr.v4.runtime.Token start = ctx.getStart();
    org.antlr.v4.runtime.Token stop = ctx.getStop();
    int endLine = stop != null ? stop.getLine() : start.getLine();
    int endCol =
        stop != null
            ? stop.getCharPositionInLine() + stop.getText().length()
            : start.getCharPositionInLine() + ctx.getText().length();
    errors.add(
        new CompileError(
            start.getLine(),
            start.getCharPositionInLine(),
            endLine,
            endCol,
            "'" + opName + "' requires an iterator variable and a '| <body>', e.g. '" + opName
                + "(x | ...)'",
            ErrorSeverity.ERROR,
            "type-checker",
            null));
    return Type.ERROR;
  }

  /** Catches a no-arg operation called with arguments — e.g. {@code allInstances(B)}, {@code size(x)}. */
  @Override
  public Type visitNoArgOpWithArgs(OCLParser.NoArgOpWithArgsContext ctx) {
    for (OCLParser.ExpCSContext arg : ctx.args) {
      visit(arg);
    }
    String opName = ctx.op.getText();
    org.antlr.v4.runtime.Token start = ctx.getStart();
    org.antlr.v4.runtime.Token stop = ctx.getStop();
    int endLine = stop != null ? stop.getLine() : start.getLine();
    int endCol =
        stop != null
            ? stop.getCharPositionInLine() + stop.getText().length()
            : start.getCharPositionInLine() + ctx.getText().length();
    errors.add(
        new CompileError(
            start.getLine(),
            start.getCharPositionInLine(),
            endLine,
            endCol,
            "'" + opName + "' takes no arguments",
            ErrorSeverity.ERROR,
            "type-checker",
            null));
    return Type.ERROR;
  }

  /** Catches an operation keyword used without parentheses — e.g. {@code .notEmpty}, {@code .select}. */
  @Override
  public Type visitOpMissingParens(OCLParser.OpMissingParensContext ctx) {
    org.antlr.v4.runtime.Token op = ctx.op;
    String name = op.getText();
    String suggestion;
    String message;
    if (NO_ARG_OPS.contains(name)) {
      suggestion = name + "()";
      message = "'" + name + "' is an operation — add parentheses: '" + suggestion + "'";
    } else {
      suggestion = name + "(...)";
      message = "'" + name + "' is an operation that requires arguments — use '" + suggestion + "'";
    }
    errors.add(
        new CompileError(
            op.getLine(),
            op.getCharPositionInLine(),
            op.getLine(),
            op.getCharPositionInLine() + op.getText().length(),
            message,
            ErrorSeverity.ERROR,
            "type-checker",
            null,
            suggestion));
    return Type.ERROR;
  }

  /**
   * Invalid operator sequences (e.g. {@code <>}, {@code ><}, {@code +-}, {@code -+}) — two or more
   * arithmetic/comparison characters that don't form a valid operator. The operands are still
   * visited so their own errors are reported, but the result type is {@code ERROR}.
   */
  @Override
  public Type visitInvalidBinaryOp(OCLParser.InvalidBinaryOpContext ctx) {
    visit(ctx.left);
    visit(ctx.right);

    String op = ctx.op.getText();
    String hint =
        switch (op) {
          case "<>" -> " — did you mean `!=`? (OCL standard `<>` is not supported)";
          case "><" -> " — did you mean `!=` or a comparison?";
          case "+-" -> " — use `+` or `-` as separate operators";
          case "-+" -> " — use `-` or `+` as separate operators";
          default -> "";
        };

    org.antlr.v4.runtime.Token tok = ctx.op;
    int endCol = tok.getCharPositionInLine() + tok.getText().length();
    errors.add(
        new CompileError(
            tok.getLine(),
            tok.getCharPositionInLine(),
            tok.getLine(),
            endCol,
            "Invalid operator `" + op + "`" + hint,
            ErrorSeverity.ERROR,
            "type-checker",
            null));

    nodeTypes.put(ctx, Type.ERROR);
    return Type.ERROR;
  }

  /** Single {@code '='} used where OCL equality {@code '=='} was meant. */
  @Override
  public Type visitSingleEqualsOp(OCLParser.SingleEqualsOpContext ctx) {
    visit(ctx.left);
    visit(ctx.right);
    // Point at the '=' sign: it sits right after the left operand's last token.
    org.antlr.v4.runtime.Token eq = ctx.getStart(); // fallback
    for (int i = 0; i < ctx.getChildCount(); i++) {
      ParseTree child = ctx.getChild(i);
      if (child instanceof TerminalNode tn) {
        org.antlr.v4.runtime.Token t = tn.getSymbol();
        if ("=".equals(t.getText())) {
          eq = t;
          break;
        }
      }
    }
    errors.add(
        new CompileError(
            eq.getLine(),
            eq.getCharPositionInLine(),
            eq.getLine(),
            eq.getCharPositionInLine() + 1,
            "Use '==' for equality comparison, not '='",
            ErrorSeverity.ERROR,
            "type-checker",
            null,
            "=="));
    nodeTypes.put(ctx, Type.ERROR);
    return Type.ERROR;
  }

  /** Three or more consecutive '=' signs (e.g. {@code ===}, {@code ====}). */
  @Override
  public Type visitMultiEqualsOp(OCLParser.MultiEqualsOpContext ctx) {
    visit(ctx.left);
    visit(ctx.right);
    errors.add(
        new CompileError(
            ctx.op.getLine(),
            ctx.op.getCharPositionInLine(),
            ctx.op.getLine(),
            ctx.op.getCharPositionInLine() + ctx.op.getText().length(),
            "Invalid operator '" + ctx.op.getText() + "' — did you mean '=='?",
            ErrorSeverity.ERROR,
            "type-checker",
            null,
            "=="));
    nodeTypes.put(ctx, Type.ERROR);
    return Type.ERROR;
  }

  // ==================== Accessors ====================
  /**
   * Returns the computed type annotations for the parse tree.
   *
   * <p>This property is used by the evaluation phase to access pre-computed type information for
   *
   * <p>type-dependent operations.
   *
   * @return The parse tree property mapping nodes to types
   */
  public ParseTreeProperty<Type> getNodeTypes() {
    return nodeTypes;
  }

  /**
   * Sets the token stream for keyword-based parsing.
   *
   * <p>Required for {@link #visitIfExpCS} to determine expression partitioning.
   *
   * @param tokens The ANTLR token stream
   */
  public void setTokenStream(org.antlr.v4.runtime.TokenStream tokens) {
    this.tokens = tokens;
  }

  /**
   * Normalizes a bare type to it singleton ctype ¡T!.
   *
   * <p>every expression has a ctype χ = τ[l,r](μ,ω). Bare primitive types (INTEGER, STRING, etc.)
   * and bare metaclass types are implicitly ¡T![1,1]. This method makes that wrapping explicit so
   * all downstream operations can rely on it. Multi-valued collections ({T}, [T], etc.) are
   * returned unchanged since they are already proper ctypes.
   *
   * @param t the type to normalize
   * @return ¡t! if t is a bare scalar or metaclass, t unchanged if already a proper ctype
   */
  private Type normalizeToSingleton(Type t) {
    if (t == Type.ERROR || t == Type.ANY) {
      return t;
    }
    if (t.isCollection()) {
      return t;
    } // {T}, [T], <T>, {{T}} — already proper ctype
    if (t.isSingleton()) {
      return t;
    } // !T! — already wrapped
    if (t.isOptional()) {
      return t;
    } // ?T? — already wrapped
    return Type.singleton(t); // bare INTEGER, STRING, cad::Sphere → !T!
  }

  /**
   * Checks conformance with singleton-unwrapping rules at let-binding sites.
   *
   * <p>A singleton {@code !T!} can be bound to a variable declared as T, and vice versa. Also
   * handles optional {@code ?T?} binding to T.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code !cad::Box!} conforms to {@code cad::Box} (oclAsType result in let)
   *   <li>{@code cad::Box} conforms to {@code !cad::Box!} (bare type to singleton decl)
   *   <li>{@code ?Any?} conforms to {@code cad::Coordinate} (null comparison context)
   * </ul>
   *
   * @param initType the type of the initializer expression
   * @param declared the declared type of the variable
   * @return true if initType conforms to declared after unwrapping rules
   */
  private boolean conformsWithUnwrapping(Type initType, Type declared) {
    // !T! conforms to T  (e.g. oclAsType returns !Box!, declared as cad::Box)
    if (initType.isSingleton() && initType.getElementType().isConformantTo(declared)) {
      return true;
    }
    // T conforms to !T!  (bare type assigned to singleton-declared variable)
    if (declared.isSingleton() && initType.isConformantTo(declared.getElementType())) {
      return true;
    }
    // ?T? conforms to T  (optional binding, e.g. null comparison context)
    if (initType.isOptional() && initType.getElementType().isConformantTo(declared)) {
      return true;
    }
    return false;
  }

  /**
   * Returns {@code true} if the given parse (sub-)tree contains at least one {@link ErrorNode}.
   *
   * <p>ANTLR plants {@link ErrorNode} (and its subtype {@code MissingTokenNode}) whenever error
   * recovery inserts or deletes tokens. Detecting these lets us identify specifications whose parse
   * tree was corrupted by error recovery so that we can suppress unreliable type inferences.
   */
  private static boolean hasErrorNode(ParseTree tree) {
    if (tree instanceof ErrorNode) return true;
    for (int i = 0; i < tree.getChildCount(); i++) {
      if (hasErrorNode(tree.getChild(i))) return true;
    }
    return false;
  }
}
