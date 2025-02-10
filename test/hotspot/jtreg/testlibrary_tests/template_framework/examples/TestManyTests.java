/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @summary Test templates which creates many tests and runs them with the TestFramework.
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @compile ../../../compiler/lib/ir_framework/TestFramework.java
 * @run driver template_framework.examples.TestManyTests
 */

package template_framework.examples;

import java.util.List;

import compiler.lib.compile_framework.*;
import compiler.lib.template_framework.Template;
import compiler.lib.template_framework.TemplateWithArgs;
import static compiler.lib.template_framework.Template.body;
import static compiler.lib.template_framework.Template.let;
import static compiler.lib.template_framework.Library.CLASS_HOOK;
import static compiler.lib.template_framework.Library.METHOD_HOOK;
import static compiler.lib.template_framework.Library.TEST_CLASS;
import compiler.lib.template_framework.Library.TestClassInfo;

public class TestManyTests {

    public static void main(String[] args) {
        // Create a new CompileFramework instance.
        CompileFramework comp = new CompileFramework();

        // Add a java source file.
        comp.addJavaSourceCode("p.xyz.InnerTest", generate(comp));

        // Compile the source file.
        comp.compile();

        // Object ret = p.xyz.InnterTest.main();
        Object ret = comp.invoke("p.xyz.InnerTest", "main", new Object[] {});
    }

    // Generate a source Java file as String
    public static String generate(CompileFramework comp) {
        // Create the info required for the test class.
        TestClassInfo info = new TestClassInfo(comp.getEscapedClassPathOfCompiledClasses(),
                                               "p.xyz", "InnerTest", List.of());

        var template1 = Template.make(() -> body(
            """
            // --- $test start ---

            @Test
            public static Object $test() { return null; }

            // --- $test end   ---
            """
        ));

        List<TemplateWithArgs> templates = List.of(
            template1.withArgs(),
            template1.withArgs()
        );

        // Create the test class, which runs all templates.
        return TEST_CLASS.withArgs(info, templates).render();
    }
}
