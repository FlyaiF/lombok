#!/usr/bin/env python3
"""JAVA_HOME=/path/to/jdk17 python3 run.py /path/to/lombok.jar"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def compile_source(jdk, jar, root, name, source, stack='512k'):
    path = root / (name + '.java')
    path.write_text(source)
    result = subprocess.run([str(jdk / 'bin' / 'javac'), '-J-Xss' + stack,
                             '-cp', jar, '-d', str(root), str(path)],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    return result.returncode, result.stdout


def fields(count):
    return ''.join('  private String f%d;\n' % i for i in range(count))


def main():
    jdk = Path(os.environ['JAVA_HOME'])
    jar = str(Path(sys.argv[1]).resolve())
    count = 1000
    cases = [
        ('WideToString', '@lombok.ToString class WideToString {\n' + fields(count) + '}\n',
         'WideToString', None),
        ('WideData', '@lombok.Data class WideData {\n' + fields(count) + '}\n',
         'WideData', None),
        ('Outer', 'package diagnostics;\nclass Innocent {}\nclass Outer {\n'
         '@lombok.ToString static class Huge {\n' + fields(count) + '}\n}\n',
         'diagnostics.Outer.Huge', None),
        # Force the initial AST-building path, before handlers can run.
        ('ExistingExpression', '@lombok.ToString class ExistingExpression {\n' + fields(count)
         + 'public String toString() { return ""' + ''.join(' + f%d' % i for i in range(count)) + '; }\n}\n',
         'ExistingExpression', "building Lombok's AST"),
    ]
    with tempfile.TemporaryDirectory(prefix='lombok-diagnostics-') as directory:
        root = Path(directory)
        for name, source, expected_class, phase in cases:
            status, output = compile_source(jdk, jar, root, name, source)
            (root / (name + '.log')).write_text(output)
            assert status != 0, name + ': expected a compiler stack overflow with -Xss512k'
            required = ["Lombok ran out of stack while", "class '" + expected_class + "'",
                        name + '.java', '1000 declared fields', '-Xss4m', 'MAVEN_OPTS',
                        'Caused by: java.lang.StackOverflowError']
            if phase:
                required.append(phase)
            for text in required:
                assert text in output, name + ': missing ' + repr(text) + '\n' + output[:3000]
            if name == 'Outer':
                assert "class 'diagnostics.Outer'" not in output, 'Wrong enclosing class reported'
                assert "class 'diagnostics.Innocent'" not in output, 'Wrong sibling class reported'
            print(name + ': diagnostic includes class, source, field count, phase, and original cause', flush=True)
        for name, source, _, _ in (cases[0], ('Small', '@lombok.Data class Small { int value; }', '', None)):
            status, output = compile_source(jdk, jar, root, name, source, stack='512k' if name == 'Small' else '4m')
            assert status == 0, name + ': expected successful compilation\n' + output
            assert 'Lombok ran out of stack' not in output, output
        print('All diagnostic checks passed; normal and increased-stack compilation succeeded')


if __name__ == '__main__':
    main()
