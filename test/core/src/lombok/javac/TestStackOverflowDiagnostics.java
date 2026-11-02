/*
 * Copyright (C) 2026 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.javac;

import static org.junit.Assert.*;

import java.net.URI;
import java.util.Arrays;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCLiteral;
import com.sun.tools.javac.util.Context;

import lombok.core.CleanupRegistry;
import lombok.javac.apt.LombokProcessor;
import lombok.permit.Permit;

public class TestStackOverflowDiagnostics {
	private StandardJavaFileManager fileManager;
	private JavacTask task;
	private JCCompilationUnit unit;
	private Context context;

	@BeforeClass public static void openCompilerPackages() {
		LombokProcessor.addOpensForLombok();
	}

	@Before public void parseSource() throws Exception {
		JavaCompiler compiler = (JavaCompiler) Permit.invokeSneaky(
			Permit.getMethod(Class.forName("com.sun.tools.javac.api.JavacTool"), "create"), null);
		fileManager = compiler.getStandardFileManager(null, null, null);
		JavaFileObject source = new SimpleJavaFileObject(URI.create("string:///example/Outer.java"), JavaFileObject.Kind.SOURCE) {
			@Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return "package example; class Outer { static class Inner { int first; int second; int value() { return 1; } } }";
			}
		};
		task = (JavacTask) compiler.getTask(null, fileManager, null, Arrays.asList("-proc:none"), null, Arrays.asList(source));
		unit = (JCCompilationUnit) task.parse().iterator().next();
		context = (Context) Permit.invokeSneaky(Permit.getMethod(task.getClass(), "getContext"), task);
	}

	@After public void closeFileManager() throws Exception {
		if (fileManager != null) fileManager.close();
	}

	@Test public void buildingReportsTheInnerClass() throws Exception {
		task.analyze();
		final StackOverflowError original = new StackOverflowError("injected during AST building");
		try {
			new JavacAST(null, context, unit, new CleanupRegistry()) {
				@Override protected JavacNode buildTree(JCTree tree, Kind kind) {
					if (tree instanceof JCLiteral) throw original;
					return super.buildTree(tree, kind);
				}
			};
			fail("Expected the injected stack overflow");
		} catch (StackOverflowError error) {
			assertContext(error, original, "building Lombok's AST");
		}
	}

	@Test public void traversalReportsTheInnerClass() throws Exception {
		task.analyze();
		JavacAST ast = new JavacAST(null, context, unit, new CleanupRegistry());
		final StackOverflowError original = new StackOverflowError("injected during AST traversal");
		try {
			ast.traverse(new JavacASTAdapter() {
				@Override public void visitType(JavacNode node, JCClassDecl type) {
					if (type.name.contentEquals("Inner")) throw original;
				}
			});
			fail("Expected the injected stack overflow");
		} catch (StackOverflowError error) {
			assertContext(error, original, "traversing Lombok's AST");
			JCClassDecl outer = (JCClassDecl) ast.top().down().get(0).get();
			assertSame("Enclosing types must not overwrite the failing type", error,
				ast.augmentStackOverflow(error, outer, "another phase"));
		}
	}

	@Test public void reportsTypesWithoutSymbols() {
		JavacAST ast = new JavacAST(null, context, unit, new CleanupRegistry());
		JCClassDecl outer = (JCClassDecl) ast.top().down().get(0).get();
		assertNull(outer.sym);
		StackOverflowError original = new StackOverflowError();
		StackOverflowError error = ast.augmentStackOverflow(original, outer, "building Lombok's AST");
		assertTrue(error.getMessage(), error.getMessage().contains("class 'Outer'"));
		assertTrue(error.getMessage(), error.getMessage().contains("0 declared fields"));
		assertSame(original, error.getCause());
	}

	@Test public void unrelatedFailuresAreNotWrapped() {
		JavacAST ast = new JavacAST(null, context, unit, new CleanupRegistry());
		final AssertionError original = new AssertionError("unrelated failure");
		try {
			ast.traverse(new JavacASTAdapter() {
				@Override public void visitType(JavacNode node, JCClassDecl type) {
					throw original;
				}
			});
			fail("Expected the injected assertion error");
		} catch (AssertionError error) {
			assertSame(original, error);
		}
	}

	private static void assertContext(StackOverflowError error, StackOverflowError original, String phase) {
		assertNotSame(original, error);
		assertSame(original, error.getCause());
		String message = error.getMessage();
		assertTrue(message, message.contains("class 'example.Outer.Inner'"));
		assertTrue(message, message.contains("Outer.java"));
		assertTrue(message, message.contains("2 declared fields"));
		assertTrue(message, message.contains(phase));
		assertTrue(message, message.contains("compiler JVM stack"));
		assertTrue(message, message.contains("-Xss4m"));
		assertArrayEquals(original.getStackTrace(), error.getCause().getStackTrace());
	}
}
