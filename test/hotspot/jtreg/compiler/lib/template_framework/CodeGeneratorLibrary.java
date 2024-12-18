/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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

package compiler.lib.template_framework;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Random;
import java.util.function.Consumer;

import jdk.test.lib.Utils;

/**
 * The {@link CodeGeneratorLibrary} provides a way to map {@link CodeGenerator} names to {@link CodeGenerator},
 * and provides the lookup facility required for recursive instantiation calls.
 */
public final class CodeGeneratorLibrary {
    private static final Random RANDOM = Utils.getRandomInstance();

    private CodeGeneratorLibrary parent;
    private HashMap<String,CodeGenerator> library;

    /**
     * Create a new {@link CodeGeneratorLibrary}.
     *
     * @param parent The parent library, or null. If a parent library is provided, that library is extended with
     *               the content of this library.
     * @param generators The set of generators for this library.
     */
    public CodeGeneratorLibrary(CodeGeneratorLibrary parent, HashSet<CodeGenerator> generators) {
        this.parent = parent;
        this.library = new HashMap<String,CodeGenerator>();
        for (CodeGenerator generator : generators) {
            if (findOrNull(generator.name) != null) {
                throw new TemplateFrameworkException("Code library already has a generator for name " + generator.name);
            }
            this.library.put(generator.name, generator);
        }
    }

    /**
     * Recursively find CodeGenerator with given name in this library or a parent library.
     *
     * @param name Name of the generator to find.
     * @param errorMessage Error message added in the exception if no generator is found for the name.
     * @return The generator from the library with the specified name.
     * @throws TemplateFrameworkException If no generator is found for the name.
     */
    public CodeGenerator find(String name, String errorMessage) {
        CodeGenerator codeGenerator = findOrNull(name);
        if (codeGenerator == null) {
            print();
            throw new TemplateFrameworkException("Code generator '" + name + "' not found" + errorMessage);
        }
        return codeGenerator;
    }

    /**
     * Recursively find CodeGenerator with given name in this library or a parent library.
     *
     * @param name Name of the generator to find.
     * @return The generator from the library with the specified name, or null if not found.
     */
    public CodeGenerator findOrNull(String name) {
        CodeGenerator codeGenerator = library.get(name);
        if (codeGenerator != null) {
            return codeGenerator;
        } else if (parent != null){
            return parent.findOrNull(name);
        } else {
            return null;
        }
    }

    /**
     * Print all generator names in the library.
     */
    public void print() {
        System.out.println("Library");
        for (Map.Entry<String,CodeGenerator> e : library.entrySet()) {
            System.out.println("  " + e.getKey() + ":   fuelCost=" + e.getValue().fuelCost);
        }
        if (parent != null) {
            parent.print();
        }
    }

    private static CodeGenerator factoryLoadStore(boolean mutable) {
        String generatorName = mutable ? "store" : "load";
        return new ProgrammaticCodeGenerator(generatorName, (Scope scope, Parameters parameters) -> {
            parameters.checkOnlyHas(scope, "type");
            String type = parameters.get("type", scope, " for generator call to load/store");
            String name = scope.sampleVariable(type, mutable);
            if (name == null) {
                scope.print();
                throw new TemplateFrameworkException("Generator call to load/store cannot find variable of type: " + type);
            }
            scope.stream.addCodeToLine(String.valueOf(name));
        }, 0);
    }

    private static CodeGenerator factoryDispatch() {
        return new ProgrammaticCodeGenerator("dispatch", (Scope scope, Parameters parameters) -> {
            String scopeKind = parameters.get("scope", scope, " for generator call to 'dispatch'");
            String generatorName = parameters.get("call", scope, " for generator call to 'dispatch'");
            CodeGenerator generator = scope.library().find(generatorName, " for dispatch in " + scopeKind + " scope");

            // Copy arguments, and remove the 2 args we just used. Forward the other args to the dispatch.
            HashMap<String,String> parameterMap = new HashMap<String,String>(parameters.getParameterMap());
            parameterMap.remove("scope");
            parameterMap.remove("call");

            switch(scopeKind) {
                case "class" -> {
                    ClassScope classScope = scope.classScope(" in dispatch for " + generatorName);
                    classScope.dispatch(generator, parameterMap);
                }
                case "method" -> {
                    MethodScope methodScope = scope.methodScope(" in dispatch for " + generatorName);
                    methodScope.dispatch(generator, parameterMap);
                }
                default -> {
                    scope.print();
                    throw new TemplateFrameworkException("Generator dispatch got: scope=" + scopeKind +
                                                         "but should be scope=class or scope=method");
                }
            }
        }, 0);
    }

    private static CodeGenerator factoryAddVariable() {
        return new ProgrammaticCodeGenerator("add_variable", (Scope scope, Parameters parameters) -> {
            parameters.checkOnlyHas(scope, "scope", "name", "type", "final");
            String scopeKind = parameters.get("scope", scope, " for generator call to 'add_variable'");
            String name = parameters.get("name", scope, " for generator call to 'add_variable'");
            String type = parameters.get("type", scope, " for generator call to 'add_variable'");
            String isFinal = parameters.getOrNull("final");

            if (isFinal != null && !isFinal.equals("true") && !isFinal.equals("false")) {
                scope.print();
                throw new TemplateFrameworkException("Generator 'add_variable' got: final=" + isFinal +
                                                     "but should be final=true or final=false");
            }
            boolean mutable = isFinal.equals("false");

            switch(scopeKind) {
                case "class" -> {
                    ClassScope classScope = scope.classScope(" in 'add_variable' for " + name);
                    classScope.addVariable(name, type, mutable);
                }
                case "method" -> {
                    MethodScope methodScope = scope.methodScope(" in 'add_variable' for " + name);
                    methodScope.addVariable(name, type, mutable);
                }
                default -> {
                    scope.print();
                    throw new TemplateFrameworkException("Generator dispatch got: scope=" + scopeKind +
                                                         "but should be scope=class or scope=method");
                }
            }
        }, 0);
    }

    private static CodeGenerator factoryRepeat() {
        return new ProgrammaticCodeGenerator("repeat", (Scope scope, Parameters parameters) -> {
            String generatorName = parameters.get("call", scope, " for generator call to 'repeat'");
            int repeat = parameters.getInt("repeat", scope, " In call to 'repeat'");

            if (repeat > 1000) {
                scope.print();
                throw new TemplateFrameworkException("Generator repeat should have repeat <= 1000, got: " + repeat);
            }

            CodeGenerator generator = scope.library().find(generatorName, " for repeat");

            // Copy arguments, and remove the 2 args we just used. Forward the other args to the repeat.
            HashMap<String,String> parameterMap = new HashMap<String,String>(parameters.getParameterMap());
            parameterMap.remove("call");
            parameterMap.remove("repeat");

            for (int i = 0; i < repeat; i++) {
                generator.where(parameterMap).instantiate(scope);
            }
        }, 0);
    }

    /**
     * The standard library populated with a large set of {@link CodeGenerator}s.
     *
     * @return The standard library.
     */
    public static CodeGeneratorLibrary standard() {
        HashSet<CodeGenerator> codeGenerators = new HashSet<CodeGenerator>();

        // Random Constants.
        codeGenerators.add(new ProgrammaticCodeGenerator("int_con",
            (Scope scope, Parameters parameters) -> {
                parameters.checkOnlyHas(scope, "lo", "hi");
                String lo = parameters.getOrNull("lo");
                String hi = parameters.getOrNull("hi");

                // TODO biased sampling?

                if (lo == null && hi == null) {
                    // Full int range
                    int v = RANDOM.nextInt();
                    scope.stream.addCodeToLine(String.valueOf(v));
                } else if (lo == null) {
                    // Bounded: [min_int, hi)
                    int hiVal = parameters.getInt("hi", scope, " In int_con.");
                    if (hiVal == Integer.MIN_VALUE) {
                        scope.print();
                        throw new TemplateFrameworkException("Generator int_con must have min_int < hi");
                    }
                    int v = RANDOM.nextInt(Integer.MIN_VALUE, hiVal);
                    scope.stream.addCodeToLine(String.valueOf(v));
                } else if (hi == null) {
                    // Bounded: [lo, max_int]
                    int loVal = parameters.getInt("lo", scope, " In int_con.");
                    if (loVal == Integer.MIN_VALUE) {
                        // Full int range
                        int v = RANDOM.nextInt();
                        scope.stream.addCodeToLine(String.valueOf(v));
                    } else {
                        // We have to shift things to make sure max_int can be generated.
                        int v = RANDOM.nextInt(loVal-1, Integer.MAX_VALUE) + 1;
                        scope.stream.addCodeToLine(String.valueOf(v));
                    }
                } else {
                    // Bounded: [lo, hi)
                    int loVal = parameters.getInt("lo", scope, " In int_con.");
                    int hiVal = parameters.getInt("hi", scope, " In int_con.");
                    if (loVal >= hiVal) {
                        scope.print();
                        throw new TemplateFrameworkException("Generator int_con must have lo < hi.");
                    }
                    int v = RANDOM.nextInt(loVal, hiVal);
                    scope.stream.addCodeToLine(String.valueOf(v));
                }
        }, 0));

        // Random choice from a list "aaa|bbb|ccc" -> return either "aaa", "bbb" or "ccc"
        codeGenerators.add(new ProgrammaticCodeGenerator("choose",
            (Scope scope, Parameters parameters) -> {
                parameters.checkOnlyHas(scope, "from");
                String list = parameters.get("from", scope, " for generator call to 'choose'");
                String[] elements = list.split("\\|");
                int r = RANDOM.nextInt(elements.length);
                scope.stream.addCodeToLine(elements[r]);
        }, 0));

        // Dispatch generator call to a ClassScope or MethodScope
        codeGenerators.add(factoryDispatch());

        // Control flow.
        codeGenerators.add(factoryRepeat());

        // Variable load/store.
        codeGenerators.add(factoryLoadStore(false));
        codeGenerators.add(factoryLoadStore(true));

        // Add variable to ClassScope or MethodScope.
        codeGenerators.add(factoryAddVariable());

        // ClassScope generators.
        codeGenerators.add(new Template("new_field_in_class",
            """
            // start $new_field_in_class
            public static int #{name} = #{:int_con};
            #{:add_variable(scope=class,type=int,name=#name,final=#final):}
            // end   $new_field_in_class
            """
        ));

        // MethodScope generators.
        codeGenerators.add(new Template("new_var_in_method",
            """
            // start $new_var_in_method
            int #{name} = #{:int_con};
            #{:add_variable(scope=method,type=int,name=#name,final=#final):}
            // end   $new_var_in_method
            """
        ));

        addRandomCode(codeGenerators);
        return new CodeGeneratorLibrary(null, codeGenerators);
    }

    private static void addRandomCode(HashSet<CodeGenerator> codeGenerators) {
        // empty: as default and generally last generator in recursive generation.
        codeGenerators.add(new Template("empty","/* empty */", 0));

        codeGenerators.add(new Template("code_split",
            """
            #{:code}
            #{:code}
            """
        ));
        // codeGenerators.add(new Template("prefix",
        //     """
        //     // start $prefix
        //     // ... prefix code ...
        //         #{:code}
        //     // end   $prefix
        //     """
        // ));
        // codeGenerators.add(new Template("foo",
        //     """
        //     // start $foo
        //     {
        //         #{v1:store(type=int)} = #{v11:load(type=int)};
        //         #{v2:store(type=int)} = #{v12:load(type=int)};
        //         #{v3:store(type=int)} = #{v13:load(type=int)};
        //         #{v4:store(type=int)} = #{v14:load(type=int)};
        //         #{v5:store(type=int)} = #{v15:load(type=int)};
        //     }
        //     // end   $foo
        //     """
        // ));
        // codeGenerators.add(new Template("bar",
        //     """
        //     // start $bar
        //     {
        //         ${fieldI} += 42;
        //         #{:dispatch(scope=class,call=new_field_in_class,name=$fieldI,final=false)}
        //         ${varI} += 42;
        //         #{:dispatch(scope=method,call=new_var_in_method,name=$varI,final=false)}
        //     }
        //     // end   $bar
        //     """
        // ));

        // Selector for code blocks.
        SelectorCodeGenerator selectorForCode = new SelectorCodeGenerator("code", "empty");
        selectorForCode.add("code_split",  100);
        //selectorForCode.add("prefix", 100);
        //selectorForCode.add("foo", 100);
        //selectorForCode.add("bar", 100);
        codeGenerators.add(selectorForCode);

    }
}
