# VitruvOCL Architecture

## Overview
3-pass compiler: Parse → Type Check → Evaluate

## Directory Structure
- `grammar/` - ANTLR4 grammar (VitruvOCL.g4)
- `symboltable/` - Symbol table, scopes, variable bindings
- `typechecker/` - Pass 2: Type checking visitor
- `evaluator/` - Pass 3: Runtime evaluation, Value system
- `pipeline/` - Public API, metamodel loading, compilation orchestration
- `common/` - Shared types, error handling, visitor base classes

## Compilation Pipeline

### Pass 1: Symbol Table Construction
- Visits `let` expressions and iterator variables
- Builds scope hierarchy (GlobalScope, LocalScope)
- No type information yet

### Pass 2: Type Checking
- Input: Parse tree + symbol table
- Output: `nodeTypes` map (ParseTree → Type)
- Validates: operations, iterators, attribute access
- Reports type errors

### Pass 3: Evaluation
- Input: Parse tree + nodeTypes + model instances
- Output: `Value` (collection of `OCLElement`)
- Executes constraints against EMF models
- Uses metamodel wrapper for EObject access

## Key Design Patterns

**Visitor Pattern**: TypeCheckVisitor, EvaluationVisitor extend AbstractPhaseVisitor
**Type System**: Unified Type hierarchy (IntType, StringType, CollectionType, etc.)
**Value System**: Value wraps List<OCLElement>, everything is a collection
**Smart Loading**: Only loads metamodels referenced in constraints

## External Integration
- **ANTLR4**: Parser generation
- **EMF**: Metamodel and instance handling (EPackage, EObject)
- **Vitruvius**: VSUM integration (planned)

## Test Structure

Tests live in `src/test/java/tools/vitruv/multimodelocl/` and follow a layered structure that mirrors the compiler passes.

### Base Class: `DummyTestSpecification`

All expression-level tests extend `DummyTestSpecification`, which provides:

- **`compile(String)`** — Runs the full 3-pass pipeline and returns a `Value`
- **`parse(String)`** — Parses input to a parse tree (entry point: `prefixedExpCS`)
- **`buildDummySpec()`** — Returns a no-op `MetamodelWrapperInterface` stub for tests that don't need metamodels
- **Assertion helpers:**
  - `assertSize(Value, int)` — Collection size check
  - `assertSingleInt(Value, int)` — Singleton integer result
  - `assertSingleBool(Value, boolean)` — Singleton boolean result
  - `assertSingleString(Value, String)` — Singleton string result
  - `assertIncludes(Value, int)` — Element membership check
  - `assertExcludes(Value, int)` — Element exclusion check
  - `assertIntAt(Value, int, int, String)` — Indexed element check
  - `assertCollection(Value, int...)` — Order-independent collection content check

### Overriding for Different Entry Points

Not all tests use the default `prefixedExpCS` entry point. There are three patterns:

**Standard (no override needed):**
Extends `DummyTestSpecification`, uses inherited `compile()` and `parse()`.
Examples: `CollectionTest`, `IteratorTest`

**Custom parse entry point:**
Override `parse()` to use a different grammar rule.
```java
@Override
protected ParseTree parse(String input) {
    CommonTokenStream tokens = new CommonTokenStream(new OCLLexer(CharStreams.fromString(input)));
    return new OCLParser(tokens).expCS(); // or infixedExpCS(), etc.
}
```
Examples: `BooleanTest` (uses `expCS()`), `SimpleMathTest`, `StringTest`, `OCLIsKindOfTest` (all use `infixedExpCS()`)

**Custom compile pipeline:**
Override `compile()` completely when token stream injection is required (e.g. for `if-then-else` or `let` keyword detection).
```java
@Override
protected Value compile(String input) {
    OCLLexer lexer = new OCLLexer(CharStreams.fromString(input));
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    tokens.fill(); // required for let-expressions
    ParseTree tree = new OCLParser(tokens).infixedExpCS();
    // ... standard 3-pass pipeline with typeChecker.setTokenStream(tokens)
}
```
Examples: `IfThenElseTest`, `LetExpressionTest`, `StringOperationsTest`

### Test Classes

| Class | What it tests | Override |
|---|---|---|
| `BooleanTest` | Boolean literals, AND/OR/XOR/NOT/IMPLIES | `parse()` → `expCS()` |
| `SimpleMathTest` | Arithmetic, comparisons, aggregates | `parse()` → `infixedExpCS()` |
| `StringTest` | String literals, comparisons, collections | `parse()` → `infixedExpCS()` |
| `StringOperationsTest` | concat, substring, toUpper/toLower, indexOf | `compile()` (token stream) |
| `CollectionTest` | Set/Sequence/Bag operations, union, flatten | none |
| `IteratorTest` | select, reject, collect, forAll, exists | none |
| `LetExpressionTest` | let bindings, scoping, shadowing | `compile()` (token stream + `tokens.fill()`) |
| `IfThenElseTest` | Conditionals, nesting, type checking | `compile()` (token stream) |
| `OCLIsKindOfTest` | Runtime type checking, mixed collections | `parse()` → `infixedExpCS()` |
| `TypeCheckerTest` | Type inference for all expression forms | `parse()` → `infixedExpCS()` + custom `typeCheck()` infrastructure |
| `MetamodelIntegrationTest` | `MetamodelWrapper` loading, EClass resolution | No pipeline — tests wrapper directly |
| `SingleMetamodelConstraintTest` | End-to-end constraints on `spaceMission` metamodel | No pipeline — uses `MultiModelOCLInterface` |
| `CrossMetamodelConstraintTest` | End-to-end cross-metamodel constraints | No pipeline — uses `MultiModelOCLInterface` |

### Writing a New Test

For pure expression tests (no metamodel), extend `DummyTestSpecification`:

```java
public class MyFeatureTest extends DummyTestSpecification {

    @Test
    public void testMyFeature() {
        assertSingleInt(compile("Set{1,2,3}.size()"), 3);
        assertSingleBool(compile("5 > 3"), true);
        assertCollection(compile("Set{1,2,3}.select(x | x > 1)"), 2, 3);
    }
}
```

If your expressions use `if-then-else` or `let`, override `compile()` and inject the token stream:

```java
@Override
protected Value compile(String input) {
    OCLLexer lexer = new OCLLexer(CharStreams.fromString(input));
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    ParseTree tree = new OCLParser(tokens).infixedExpCS();
    // ... 3-pass pipeline, calling typeChecker.setTokenStream(tokens)
    //     and evaluator.setTokenStream(tokens)
}
```