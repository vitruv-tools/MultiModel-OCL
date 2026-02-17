# MultiModelOCL

A domain-specific language for evaluating OCL constraints across multiple EMF metamodels with cross-model consistency checking.

## Overview

MultiModelOCL enables you to write and evaluate OCL constraints that span multiple metamodels in the same project. It implements OCL# semantics for null-safe, type-safe constraint evaluation with full EMF integration.

**Key Features:**
- **Cross-Metamodel Constraints**: Reference entities across different metamodels in a single constraint
- **OCL# Semantics**: Everything-is-a-collection approach eliminates null pointer exceptions
- **VSCode Extension**: Interactive development with syntax highlighting and inline evaluation
- **Type Safety**: Full static type checking before evaluation catches errors early
- **Smart Loading**: Automatically discovers and loads metamodels and instances from your project

Based on: Steinmann, F., Clarisó, R., Gogolla, M. (2025). ["Meet OCL{^\sharp}, a relational object constraint language"](https://link.springer.com/article/10.1007/s10270-025-01286-1).

## Quick Start

### Installation

**Prerequisites:**
- Java 17 or higher
- VSCode (for the extension)

**Download:**
1. Download the latest release from [Releases](https://github.com/vitruv-tools/MultiModel-OCL)
2. Extract the archive containing:
   - `multimodelocl.jar` - Standalone compiler/evaluator
   - `multimodelocl-X.X.X.vsix` - VSCode extension

### VSCode Extension Setup

1. **Install Extension:**
   - Open VSCode
   - Go to `Extensions` view (`Ctrl+Shift+X`)
   - Click `...` menu → `Install from VSIX...`
   - Select the downloaded `multimodelocl-X.X.X.vsix`
   - Reload VSCode when prompted

2. **Verify Installation:**
   - Open any `.ocl` file
   - You should see syntax highlighting
   - Run buttons (▶) should appear above constraints

### Project Structure

Organize your project following this convention:
```
your-project/
├── constraints.ocl          # Your constraint definitions
├── metamodels/              # Place .ecore files here
│   ├── model1.ecore
│   └── model2.ecore
└── instances/               # Place model instances here
    ├── instance1.xmi
    ├── instance2.model1
    └── instance3.model2
```

**Notes:**
- Instance files can have any extension (`.xmi`, `.model1`, custom extensions)
- The extension will automatically discover all `.ecore` files in `metamodels/`
- All files in `instances/` will be loaded as model instances

### Your First Constraint

Create `constraints.ocl`:
```ocl
-- Simple constraint: all spacecraft must have positive mass
context spaceMission::Spacecraft inv positiveM ass:
  self.mass > 0

-- Cross-metamodel constraint: total satellite mass must exceed spacecraft mass
context spaceMission::Spacecraft inv satelliteMassCheck:
  satelliteSystem::Satellite.allInstances()
    .collect(sat | sat.massKg)
    .sum() > self.mass
```

### Running Constraints

#### In VSCode (Recommended)

1. **Open** your `constraints.ocl` file
2. **Run single constraint:** Click the ▶ button next to any constraint
3. **Run all constraints:** Click the `▶ Run All` button at the top of the file
4. **View results:**
   - ✓ (green checkmark) = constraint passed
   - ✗ (red X) = constraint violated
   - Red squiggles = compilation errors
   - Output panel shows detailed violation messages

#### Command Line
```bash
# Evaluate all constraints in a project
java -jar multimodelocl.jar eval-batch constraints.ocl \
  --ecore metamodels/model1.ecore,metamodels/model2.ecore \
  --xmi instances/instance1.xmi,instances/instance2.model1

# Evaluate single constraint by name
java -jar multimodelocl.jar eval constraints.ocl \
  --constraint myConstraintName \
  --ecore metamodels/model1.ecore \
  --xmi instances/instance1.xmi
```

**JSON Output:**
```json
{
  "success": true,
  "constraints": [
    {
      "name": "positiveMass",
      "success": true,
      "satisfied": true
    },
    {
      "name": "satelliteMassCheck",
      "success": true,
      "satisfied": false,
      "warnings": ["Constraint violated for instances: [spacecraft1.spacemission]"]
    }
  ]
}
```

## Language Reference

### Constraint Syntax

**Components:**
- `context`: Defines which class the constraint applies to
- `MetamodelName::ClassName`: Fully qualified class name (required)
- `inv`: Invariant keyword
- `constraintName`: Unique identifier for this constraint
- `expression`: OCL expression that must evaluate to true

### Key Differences from Standard OCL

**1. Unified Dot Notation:**
```ocl
-- MultiModelOCL uses . for everything
collection.select(x | x > 5)

-- Standard OCL uses ->
collection->select(x | x > 5)
```

**2. Inequality Operator:**
```ocl
x != y    -- MultiModelOCL
x <> y    -- Standard OCL
```

**3. Everything is a Collection:**
```ocl
-- Single values are singletons
5         -- becomes [5]
"hello"   -- becomes ["hello"]

-- Null becomes empty collection
null      -- becomes []
```

**4. Fully Qualified Names Required:**
```ocl
context spaceMission::Spacecraft inv:  -- ✓ Correct
context Spacecraft inv:                 -- ✗ Error
```

### Supported Operations

#### Collection Operations
```ocl
-- Filtering
collection.select(x | x > 5)        -- Keep elements matching condition
collection.reject(x | x > 5)        -- Remove elements matching condition

-- Transformation
collection.collect(x | x * 2)       -- Transform each element

-- Quantifiers
collection.forAll(x | x > 0)        -- All elements must satisfy
collection.exists(x | x > 100)      -- At least one element must satisfy

-- Size/emptiness
collection.size()                    -- Number of elements
collection.isEmpty()                 -- True if empty
collection.notEmpty()                -- True if not empty

-- Membership
collection.includes(value)           -- True if contains value
collection.excludes(value)           -- True if doesn't contain value

-- Modification (returns new collection)
collection.including(value)          -- Add element
collection.excluding(value)          -- Remove element

-- Combination
collection1.union(collection2)       -- Union of collections
collection.append(value)             -- Add to end
collection.flatten()                 -- Flatten nested collections

-- Aggregation
collection.sum()                     -- Sum of numeric values
collection.avg()                     -- Average
collection.min()                     -- Minimum value
collection.max()                     -- Maximum value

-- Sequence operations
collection.first()                   -- First element
collection.last()                    -- Last element
collection.at(index)                 -- Element at index (1-based)
collection.reverse()                 -- Reversed collection

-- Special
Type.allInstances()                  -- All instances of a type
collection.lift()                    -- Lift operation
```

#### Arithmetic Operations
```ocl
x + y      -- Addition
x - y      -- Subtraction
x * y      -- Multiplication
x / y      -- Division (real division)
x % y      -- Modulo
-x         -- Unary minus
x.abs()    -- Absolute value
x.floor()  -- Round down
x.ceil()   -- Round up
x.round()  -- Round to nearest
```

#### Comparison Operations
```ocl
x < y      -- Less than
x <= y     -- Less than or equal
x > y      -- Greater than
x >= y     -- Greater than or equal
x == y     -- Equal
x != y     -- Not equal
```

#### Boolean Operations
```ocl
a and b      -- Logical AND
a or b       -- Logical OR
a xor b      -- Logical XOR
not a        -- Logical NOT
a implies b  -- Logical implication
```

#### String Operations
```ocl
str.concat("text")              -- Concatenate strings
str.size()                      -- Length of string
str.toUpper()                   -- Convert to uppercase
str.toLower()                   -- Convert to lowercase
str.substring(start, end)       -- Extract substring (1-based)
str.indexOf("sub")              -- Find substring position
str.equalsIgnoreCase("TEXT")    -- Case-insensitive comparison
```

#### Control Flow
```ocl
-- Conditional expression
if condition then
  expression1
else
  expression2
endif

-- Let binding (single variable)
let x = 10 in
  x * 2

-- Let binding (multiple variables)
let x = 10, y = 20 in
  x + y
```

#### Type Operations
```ocl
object.oclIsKindOf(Type)     -- Check if instance is kind of type
object.oclIsTypeOf(Type)     -- Check exact type match
object.oclAsType(Type)       -- Cast to type
```

### Cross-Metamodel Features

Access instances from other metamodels using fully qualified names:
```ocl
context spaceMission::Spacecraft inv:
  -- Get all satellites from different metamodel
  satelliteSystem::Satellite.allInstances()
    .collect(s | s.massKg)
    .sum() > self.mass
```
## Examples

See [examples/exampleproject](examples/exampleproject) for complete working examples including:
- Basic constraints on single metamodels
- Cross-metamodel constraints
- Collection operations
- Arithmetic and boolean logic
- Let bindings and conditionals

## VSCode Extension

### Features

- **Syntax Highlighting**: OCL-specific keyword and operator coloring
- **CodeLens Buttons**: `▶ Run All` at top, `▶` for each constraint
- **Live Feedback**: 
  - Green ✓ gutter icons for passing constraints
  - Red ✗ for failing constraints
  - Red squiggles for errors with hover tooltips
- **Output Panel**: Detailed results with violation messages
- **Project Discovery**: Automatically finds `.ecore` and instance files

### Building from Source
```bash
cd vscode-extension

# Install dependencies
npm install

# Compile TypeScript
npm run compile

# Package extension
npm run package
```

This creates `multimodelocl-X.X.X.vsix` which you can install via `Extensions → Install from VSIX...`

### Extension Settings

Configure in VSCode settings (`Ctrl+,`):
```json
{
  "multimodelocl.compilerPath": "/path/to/multimodelocl.jar"
}
```

Leave empty for automatic detection (extension includes bundled JAR).

## Building from Source

### Prerequisites

- Java 17+
- Maven 3.9+
- Node.js 20+ (for VSCode extension)

### Build Compiler/CLI
```bash
# Build JAR
mvn clean package

# JAR location
ls language/target/multimodelocl.jar

# Run tests
mvn test

# View coverage report
open language/target/site/jacoco/index.html
```

### Build VSCode Extension
```bash
cd vscode-extension

# First build the compiler JAR (it gets bundled)
cd ..
mvn clean package
cp language/target/multimodelocl.jar vscode-extension/lib/

# Then build extension
cd vscode-extension
npm install
npm run compile
npm run package
```

### Project Structure
```
MultiModel-OCL/
├── language/                   # Compiler implementation
│   ├── src/main/
│   │   ├── antlr4/            # Grammar definition
│   │   └── java/              # Compiler passes, evaluator
│   └── pom.xml
├── vscode-extension/           # VSCode extension
│   ├── src/extension.ts       # Extension logic
│   ├── syntaxes/              # Syntax highlighting
│   ├── lib/                   # Bundled JAR
│   └── package.json
├── examples/                   # Example projects
└── pom.xml                    # Parent POM
```

## Architecture

MultiModelOCL uses a three-pass compiler architecture:

### Pass 1: Symbol Table Construction
- Parses OCL file using ANTLR4 grammar
- Builds scope hierarchy for variable visibility
- Registers all variable declarations (`self`, let-bindings, iterators)

### Pass 2: Type Checking
- Validates all operations have compatible types
- Checks property and operation existence on EMF classes
- Produces type annotations for every expression
- Reports errors before evaluation begins

### Pass 3: Evaluation
- Executes constraints against EMF model instances
- Uses type annotations to safely navigate models
- Implements OCL# collection semantics
- Returns structured results (satisfied/violated/errors)

**Key Design Decisions:**
- **Everything is a Collection**: Eliminates null pointer exceptions, simplifies type rules
- **Lazy Loading**: Only loads metamodels that constraints actually reference
- **Immutable Collections**: All collection operations return new collections
- **1-Based Indexing**: Matches OCL specification and mathematical conventions

For detailed architecture documentation, see [ARCHITECTURE.md](ARCHITECTURE.md).

## API Documentation

The compiler can be used programmatically via the Java API (planned):
```java
import tools.vitruv.multimodelocl.pipeline.*;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        // Evaluate project with auto-discovery
        BatchValidationResult result = MultiModelOCL.evaluateProject(
            Path.of(".")
        );
        
        // Print summary
        System.out.println("Passed: " + result.getSatisfiedCount());
        System.out.println("Failed: " + result.getViolatedCount());
        
        // Iterate over results
        for (ConstraintResult cr : result.getResults()) {
            System.out.println(cr.getName() + ": " + 
                (cr.isSatisfied() ? "✓" : "✗"));
        }
    }
}
```

## License

This project is licensed under the Eclipse Public License 2.0 - see [LICENSE](LICENSE) for details.

### Third-Party Licenses

- ANTLR 4.13.2 - BSD 3-Clause License
- Eclipse EMF - Eclipse Public License 2.0
- Jackson - Apache License 2.0

See [NOTICE](NOTICE) for complete third-party license information.

## Acknowledgments

- Grammar derived from DeepOCL implementation by Ralph Gerbig and Arne Lange (University of Mannheim)
- OCL# semantics based on work by Gogolla, Clarisó, and Steinmann
