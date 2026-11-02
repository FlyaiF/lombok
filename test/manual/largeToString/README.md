# Stack overflow diagnostics for large classes

Run the regression with Python 3 and a JDK (tested with JDK 17.0.5):

```sh
JAVA_HOME=/path/to/jdk17 python3 test/manual/largeToString/run.py /path/to/lombok.jar
```

The test generates sources in a temporary directory and launches separate
compiler JVMs. It checks `@ToString`, `@Data`, a nested class alongside an
unrelated class, and a handwritten deep expression that overflows during
initial AST construction. A 512 KiB compiler stack stresses Lombok's paths;
a 4 MiB stack checks successful compilation of a large class, and a small
class is also compiled successfully at 512 KiB.
The exact failure threshold depends on the JDK, JIT compilation, and stack size.

## Automated regression

`lombok.javac.TestStackOverflowDiagnostics` is registered in the existing
`TestJavac` JUnit suite, run by the Ant `test.javac*` targets. Its four tests
inject an overflow during AST building or traversal and check inner-class
attribution, source/phase/field-count context, original-cause preservation,
types without symbols, and propagation of unrelated errors. They do not rely
on actually exhausting the process stack.

The tests passed on JDK 8 and JDK 17 using locally cached dependencies. The
test source also compiled at the test harness's Java 5 source/target level
using ECJ. An attempted `ant test.javac17` stopped at bootstrap dependency
download because `projectlombok.org` could not be resolved in this environment;
the full Ant suite still needs to run in a connected environment.

## What changes

Generated methods are unchanged. Instead, stack overflows receive context at:

* class boundaries while building or rebuilding Lombok's AST;
* class boundaries while traversing Lombok's AST;
* annotation-handler boundaries (including failures while generating methods).

The error identifies the source file, class, declared field count, and phase,
and suggests increasing the **compiler** JVM stack. The field count is all
fields declared in that class, not the number included in `toString()` or a
claim that those fields caused the overflow. Deep handwritten expressions can
also cause these failures.

The original `StackOverflowError` remains the cause, preserving its stack trace.
An already contextualized error is not wrapped again at enclosing class
boundaries, so an inner-class failure is not attributed to its outer class.
The build still fails; the error is not swallowed to continue with a broken AST.

For example, Maven 3.9.0 with the compiler plugin 3.11.0 and `MAVEN_OPTS=-Xss1m`
reported the following for a 1000-field entity:

```text
Fatal error compiling: lombok.javac.JavacAST$ASTStackOverflowError:
Lombok ran out of stack while building Lombok's AST for class 'ToString1000'
in SimpleFileObject[.../ToString1000.java] (1000 declared fields).
Deep expressions, including generated toString() concatenations, can cause this.
Increase the compiler JVM stack (for example, -Xss4m;
for in-process Maven compilation, use MAVEN_OPTS).
```

The real diagnostic is one line; it is wrapped above for readability.

## Scope and verification

Environment: macOS x86_64, GraalVM CE JDK 17.0.5, Maven 3.9.0,
Lombok 1.18.42. The changed classes were compiled with JDK 8 (as required by
Lombok's references to older javac APIs) against the installed 1.18.42 jar,
then substituted into a temporary copy for executable testing on JDK 17.
No generated-method changes were included. The diagnostic regression passed,
and fourteen existing delombok outputs matched the original jar (ignoring
generated timestamps). The full Ant suite was not run.
The normal Maven lifecycle had an uncached resources-plugin dependency;
the compiler goal was exercised directly, in-process.

This covers overflows inside Lombok. If Lombok finishes and javac later
runs out of stack in type attribution or bytecode generation, these boundaries
cannot add context to that later error. There is no fixed field-count threshold
that guarantees which component will overflow first.

## CI stack configuration

For Maven's in-process compiler:

```sh
MAVEN_OPTS="${MAVEN_OPTS:-} -Xss4m" mvn clean compile
```

Alternatively, put `-Xss4m` in the project's `.mvn/jvm.config`
([Maven JVM configuration](https://maven.apache.org/configure)).
For `<fork>true</fork>`, set `-J-Xss4m` in the compiler arguments instead
([compiler plugin arguments](https://maven.apache.org/plugins/maven-compiler-plugin/compile-mojo.html)).
A larger stack raises the failure threshold; it does not change the generated
expression's depth. One MiB is not a universal JVM default.
