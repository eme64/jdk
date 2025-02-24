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

package compiler.lib.template_framework;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.List;

/**
 * {@link Template}s are used to generate code, based on {@link Token} which are rendered to {@link String}.
 *
 * <p>
 * A {@link Template} can have zero or more arguments, and for each number of arguments there is an implementation
 * (e.g. {@link ZeroArgs} for zero arguments and {@link TwoArgs} for two arguments). This allows the use of Generics
 * for the template argument types, i.e. the template arguments can be type checked. Ideally, we would have used
 * String Templates to inject these arguments into the strings. But since String Templates are not (yet) available,
 * the {@link Template}s provide <strong>hashtag replacements</strong> in the Strings: the {@link Template} argument
 * names are captured, and the argument values automatically replace any {@code "#name"} in the Strings. See the
 * different overloads of {@link make} for examples. Additional hashtag replacements can be defined with {@link let}.
 *
 * <p>
 * When using nested {@link Template}s, there can be collisions with identifiers (e.g. variable names and method names).
 * For this, {@link Template}s provide <strong>dollar replacements</strong>, which automaticall rename any
 * {@code "$name"} in the String with a {@code "name_ID"}, where the {@code "ID"} is unique for every use of
 * a {@link Template}. The dollar replacement can also be captured with {@link $}, and passed to nested
 * {@link Template}s, which allows sharing of these identifier names between {@link Template}s.
 *
 * <p>
 * To render a {@link Template} to a {@link String}, one first has to apply the arguments (e.g. with
 * {@link TwoArgs#withArgs}) and then the resulting {@link TemplateWithArgs} can either be used as a
 * {@link Token} inside another {@link Template}, or rendered to a {@link String} with {@link TemplateWithArgs#render}.
 *
 * <p>
 * A {@link TemplateWithArgs} can be used directly as a {@link Token} inside the {@link Template#body} to
 * nest the {@link Template}s. Alternatively, code can be {@link Hook#insert}ed to where a {@link Hook}
 * was {@link Hook#set} earlier (in some outer scope of the code). For example, while generating code in
 * a method, one can reach out to the scope of the class, and insert a new field, or define a utility method.
 *
 * <p>
 * A {@link TemplateBinding} allows the recurisve use of {@link Template}s. With the indirection of such a binding,
 * a {@link Template} can reference itself. To ensure the termination of recursion, the templates are rendered
 * with a certain amount of {@link fuel}, which is decreased at each {@link Template} nesting by a certain amount
 * (can be changed with {@link setFuelCost}). Recursive templates are supposed to terminate once the {@link fuel}
 * is depleated (i.e. reaches zero).
 *
 * <p>
 * Code generation often involves defining fields and variables, which are then available inside a defined
 * scope, and can be sampled in any nested scope. To allow the use of names for multiple applications (e.g.
 * fields, variables, methods, etc), we define a {@link Name}, which captures the {@link String} representation
 * to be used in code, as well as its type and if it is mutable. One can add such a {@link Name} to the
 * current code scope with {@link addName}, and sample from the current or outer scopes with {@link sampleName}.
 * When generating code, one might want to create {@link Name}s (variables, fields, etc) in local scope, or
 * in some outer scope with the use of {@link Hook}s.
 */
public interface Template {

    /**
     * Creates a {@link Template} with no arguments.
     * See {@link body} for more details about how to construct a {@link Template} with {@link Token}s.
     *
     * <p>
     * Example:
     * {@snippet lang=java :
     * var template = Template.make(() -> body(
     *     """
     *     Multi-line string or other tokens.
     *     """
     * ));
     * }
     *
     * @param body The {@link TemplateBody} created by {@link Template#body}.
     * @return A {@link Template} with zero arguments.
     */
    static ZeroArgs make(Supplier<TemplateBody> body) {
        return new ZeroArgs(body);
    }

    /**
     * Creates a {@link Template} with one argument.
     * See {@link body} for more details about how to construct a {@link Template} with {@link Token}s.
     *
     * <p>
     * Here an example with template argument {@code 'a'}, captured once as string name
     * for use in hashtag replacements, and captured once as lambda argument with the corresponding type
     * of the generic argument.
     * {@snippet lang=java :
     * var template = Template.make("a", (Integer a) -> body(
     *     """
     *     Multi-line string or other tokens.
     *     We can use the hashtag replacement #a to directly insert the String value of a.
     *     """,
     *     "We can also use the captured parameter of a: " + a
     * ));
     * }
     *
     * @param body The {@link TemplateBody} created by {@link Template#body}.
     * @param <A> Type of the (first) argument.
     * @param arg0Name The name of the (first) argument for hashtag replacement.
     * @return A {@link Template} with one argument.
     */
    static <A> OneArgs<A> make(String arg0Name, Function<A, TemplateBody> body) {
        return new OneArgs<>(arg0Name, body);
    }

    /**
     * Creates a {@link Template} with two arguments.
     * See {@link body} for more details about how to construct a {@link Template} with {@link Token}s.
     *
     * <p>
     * Here an example with template arguments {@code 'a'} and {@code 'b'}, captured once as string names
     * for use in hashtag replacements, and captured once as lambda arguments with the corresponding types
     * of the generic arguments.
     * {@snippet lang=java :
     * var template = Template.make("a", "b", (Integer a, String b) -> body(
     *     """
     *     Multi-line string or other tokens.
     *     We can use the hashtag replacement #a and #b to directly insert the String value of a and b.
     *     """,
     *     "We can also use the captured parameter of a and b: " + a + " and " + b
     * ));
     * }
     *
     * @param body The {@link TemplateBody} created by {@link Template#body}.
     * @param <A> Type of the first argument.
     * @param arg0Name The name of the first argument for hashtag replacement.
     * @param <B> Type of the second argument.
     * @param arg1Name The name of the second argument for hashtag replacement.
     * @return A {@link Template} with two arguments.
     */
    static <A, B> TwoArgs<A, B> make(String arg0Name, String arg1Name, BiFunction<A, B, TemplateBody> body) {
        return new TwoArgs<>(arg0Name, arg1Name, body);
    }

    /**
     * Interface for function with three arguments.
     *
     * @param <T> Type of the first argument.
     * @param <U> Type of the second argument.
     * @param <V> Type of the third argument.
     * @param <R> Type of the return value.
     */
    @FunctionalInterface
    public interface TriFunction<T, U, V, R> {

        /**
         * Function definition for the three argument functions.
         *
         * @param t The first argument.
         * @param u The second argument.
         * @param v The third argument.
         * @return Return value of the three argument function.
         */
        R apply(T t, U u, V v);
    }

    /**
     * Creates a {@link Template} with three arguments.
     * See {@link body} for more details about how to construct a {@link Template} with {@link Token}s.
     *
     * @param body The {@link TemplateBody} created by {@link Template#body}.
     * @param <A> Type of the first argument.
     * @param arg0Name The name of the first argument for hashtag replacement.
     * @param <B> Type of the second argument.
     * @param arg1Name The name of the second argument for hashtag replacement.
     * @param <C> Type of the third argument.
     * @param arg2Name The name of the third argument for hashtag replacement.
     * @return A {@link Template} with three arguments.
     */
    static <A, B, C> ThreeArgs<A, B, C> make(String arg0Name, String arg1Name, String arg2Name, TriFunction<A, B, C, TemplateBody> body) {
        return new ThreeArgs<>(arg0Name, arg1Name, arg2Name, body);
    }

    /**
     * A {@link Template} with no arguments.
     *
     * @param function The {@link Supplier} that creates the {@link TemplateBody}.
     */
    record ZeroArgs(Supplier<TemplateBody> function) implements Template {
        TemplateBody instantiate() {
            return function.get();
        }

        /**
         * Creates a {@link TemplateWithArgs} which can be used as a {@link Token} inside
         * a {@link Template} for nested code generation, and it can also be used with
         * {@link TemplateWithArgs#render} to render the template to a {@link String}
         * directly.
         *
         * @return The template all (zero) arguments applied.
         */
        public TemplateWithArgs withArgs() {
            return new TemplateWithArgs.ZeroArgsUse(this);
        }
    }


    /**
     * A {@link Template} with one argument.
     *
     * @param arg0Name The name of the (first) argument, used for hashtag replacements in the {@link Template}.
     * @param <A> The type of the (first) argument.
     * @param function The {@link Function} that creates the {@link TemplateBody} given the template argument.
     */
    record OneArgs<A>(String arg0Name, Function<A, TemplateBody> function) implements Template {
        TemplateBody instantiate(A a) {
            return function.apply(a);
        }

        /**
         * Creates a {@link TemplateWithArgs} which can be used as a {@link Token} inside
         * a {@link Template} for nested code generation, and it can also be used with
         * {@link TemplateWithArgs#render} to render the template to a {@link String}
         * directly.
         *
         * @param a The value for the (first) argument.
         * @return The template its argument applied.
         */
        public TemplateWithArgs withArgs(A a) {
            return new TemplateWithArgs.OneArgsUse<>(this, a);
        }
    }

    /**
     * A {@link Template} with two arguments.
     *
     * @param arg0Name The name of the first argument, used for hashtag replacements in the {@link Template}.
     * @param arg1Name The name of the second argument, used for hashtag replacements in the {@link Template}.
     * @param <A> The type of the first argument.
     * @param <B> The type of the second argument.
     * @param function The {@link BiFunction} that creates the {@link TemplateBody} given the template arguments.
     */
    record TwoArgs<A, B>(String arg0Name, String arg1Name,
                         BiFunction<A, B, TemplateBody> function) implements Template {
        TemplateBody instantiate(A a, B b) {
            return function.apply(a, b);
        }

        /**
         * Creates a {@link TemplateWithArgs} which can be used as a {@link Token} inside
         * a {@link Template} for nested code generation, and it can also be used with
         * {@link TemplateWithArgs#render} to render the template to a {@link String}
         * directly.
         *
         * @param a The value for the first argument.
         * @param b The value for the second argument.
         * @return The template all (two) arguments applied.
         */
        public TemplateWithArgs withArgs(A a, B b) {
            return new TemplateWithArgs.TwoArgsUse<>(this, a, b);
        }
    }

    /**
     * A {@link Template} with three arguments.
     *
     * @param arg0Name The name of the first argument, used for hashtag replacements in the {@link Template}.
     * @param arg1Name The name of the second argument, used for hashtag replacements in the {@link Template}.
     * @param arg2Name The name of the third argument, used for hashtag replacements in the {@link Template}.
     * @param <A> The type of the first argument.
     * @param <B> The type of the second argument.
     * @param <C> The type of the third argument.
     * @param function The function with three arguments that creates the {@link TemplateBody} given the template arguments.
     */
    record ThreeArgs<A, B, C>(String arg0Name, String arg1Name, String arg2Name,
                              TriFunction<A, B, C, TemplateBody> function) implements Template {
        TemplateBody instantiate(A a, B b, C c) {
            return function.apply(a, b, c);
        }

        /**
         * Creates a {@link TemplateWithArgs} which can be used as a {@link Token} inside
         * a {@link Template} for nested code generation, and it can also be used with
         * {@link TemplateWithArgs#render} to render the template to a {@link String}
         * directly.
         *
         * @param a The value for the first argument.
         * @param b The value for the second argument.
         * @param c The value for the third argument.
         * @return The template all (three) arguments applied.
         */
        public TemplateWithArgs withArgs(A a, B b, C c) {
            return new TemplateWithArgs.ThreeArgsUse<>(this, a, b, c);
        }
    }

    /**
     * Creates a {@link TemplateBody} from a list of tokens, which can be {@link String}s,
     * boxed primitive types (e.g. {@link Integer}), any {@link Token}, or {@link List}s
     * of any of these.
     *
     * {@snippet lang=java :
     * var template = Template.make(() -> body(
     *     """
     *     Multi-line string
     *     """,
     *     "normal string ", Integer.valueOf(3), Float.valueOf(1.5f),
     *     List.of("abc", "def"),
     *     nestedTemplate.withArgs(42)
     * ));
     * }
     *
     * @param tokens A list of tokens, which can be {@link String}s,boxed primitive types
     *               (e.g. {@link Integer}), any {@link Token}, or {@link List}s
     *               of any of these.
     * @return The {@link TemplateBody} which captures the list of validated {@link Token}s.
     * @throws IllegalArgumentException if the list of tokens contains an unexpected object.
     */
    static TemplateBody body(Object... tokens) {
        return new TemplateBody(Token.parse(tokens));
    }

    /**
     * Retrieves the dollar replacement of the {@code 'name'} for the
     * current {@link Template} that is being instanciated. It returns the same
     * dollar replacement as the string use {@code "$name"}.
     *
     * Here an example where a {@link Template} creates a local variable {@code 'var'},
     * with an implicit dollar replacement, and then captures that dollar replacement
     * using {@link $} for the use inside a nested template.
     * {@snippet lang=java :
     * var template = Template.make(() -> body(
     *     """
     *     int $var = 42;
     *     """,
     *     otherTemplate.withArgs($("var"))
     * ));
     * }
     *
     * @param name The {@link String} name of the name.
     * @return The dollar replacement for the {@code 'name'}.
     */
    static String $(String name) {
        return Renderer.getCurrent().$(name);
    }

    /**
     * Define a hashtag replacement for {@code "#key"}, with a specific value.
     *
     * {@snippet lang=java :
     * var template = Template.make("a", (Integer a) -> body(
     *     let("b", a * 5),
     *     """
     *     System.out.prinln("Use a and b with hashtag replacement: #a and #b");
     *     """
     * ));
     * }
     *
     * @param key Name for the hashtag replacement.
     * @param value The value that the hashtag is replaced with.
     * @return A token that does nothing, so that the {@link let} cal can easily be put in a list of tokens
     *         inside a {@link Template#body}.
     * @throws RendererException if there is a duplicate hashtag {@code key}.
     */
    static Token let(String key, Object value) {
        Renderer.getCurrent().addHashtagReplacement(key, value);
        return new NothingToken();
    }

    /**
     * Define a hashtag replacement for {@code "#key"}, with a specific value, which is also captured
     * by the provided {@code 'function'} with type {@code <T>}.
     *
     * {@snippet lang=java :
     * var template = Template.make("a", (Integer a) -> let("b", a * 2, (Integer b) -> body(
     *     """
     *     System.out.prinln("Use a and b with hashtag replacement: #a and #b");
     *     """,
     *     "System.out.println(\"Use a and b as capture variables:\" + a + " and " + b + ");\n"
     * )));
     * }
     *
     * @param key Name for the hashtag replacement.
     * @param value The value that the hashtag is replaced with.
     * @param <T> The type of the value.
     * @param function The function that is applied with the provided {@code 'value'}.
     * @return A token that does nothing, so that the {@link let} cal can easily be put in a list of tokens
     *         inside a {@link Template#body}.
     * @throws RendererException if there is a duplicate hashtag {@code key}.
     */
    static <T> TemplateBody let(String key, T value, Function<T, TemplateBody> function) {
        Renderer.getCurrent().addHashtagReplacement(key, value);
        return function.apply(value);
    }

    /**
     * Default amount of fuel for {@link TemplateWithArgs#render}. It guides the nesting depth of {@link Template}s.
     */
    public final static float DEFAULT_FUEL = 100.0f;

    /**
     * The default amount of fuel spent per {@link Template}. It is suptracted from the current {@link fuel} at every
     * nesting level, and once the {@link fuel} reaches zero, the nesting is supposed to terminate.
     */
    public final static float DEFAULT_FUEL_COST = 10.0f;

    /**
     * The current remaining fuel for nested {@link Template}s. Every level of {@link Template} nestig
     * subtracts a certain amount of fuel, and when it reaches zero, {@link Template}s are supposed to
     * stop nesting, if possible. This is not a hard rule, but a guide, and a mechanism to ensure
     * termination in recursive {@link Template} instantiations.
     *
     * <p>
     * Example of a recursive {@link Template}, which checks the remaining {@link fuel} at every level,
     * and terminates if it reaches zero. It also demonstrates the use of {@link TemplateBinding} for
     * the recursive use of {@link Template}s. We {@link TemplateWithArgs#render} with {@code 30} total fuel, and spending {@code 5} fuel at each recursion level.
     * {@snippet lang=java :
     * var binding = new TemplateBinding<Template.OneArgs<Integer>>();
     * var template = Template.make("depth", (Integer depth) -> body(
     *     setFuelCost(5.0f),
     *     let("fuel", fuel()),
     *     """
     *     System.out.println("Currently at depth #depth with fuel #fuel");
     *     """
     *     (fuel() > 0) ? binding.get().withArgs(depth + 1)
     *                    "// terminate\n"
     * ));
     * binding.bind(template);
     * String code = template.withArgs(0).render(30.0f);
     * }
     *
     * @return The amount of fuel left for nested {@link Template} use.
     */
    static float fuel() {
        return Renderer.getCurrent().fuel();
    }

    /**
     * Changes the amount of fuel used for the current {@link Template}, where the default is
     * {@link Template#DEFAULT_FUEL_COST}.
     *
     * @param fuelCost The amount of fuel used for the current {@link Template}.
     * @return A token for convenient use in {@link Template#body}.
     */
    static Token setFuelCost(float fuelCost) {
        Renderer.getCurrent().setFuelCost(fuelCost);
        return new NothingToken();
    }

    /**
     * Add a {@link Name} in the current code frame.
     * Note that there can be duplicate definitions, and they simply increase
     * the {@link weighNames} weight, and increase the probability of sampling
     * the name with {@link sampleName}.
     *
     * @param name The {@link Name} to be added to the current code frame.
     * @return The token that performs the defining action.
     */
    static Token addName(Name name) {
        return new AddNameToken(name);
    }

    /**
     * Weight the {@link Name}s for the specified {@link Name.Type}.
     *
     * @param type The type of the names to weigh.
     * @param onlyMutable Determines if we weigh the mutable names or all.
     * @return The weight of names for the specified parameters.
     */
    static long weighNames(Name.Type type, boolean onlyMutable) {
        return Renderer.getCurrent().weighNames(type, onlyMutable);
    }

    /**
     * Sample a random name for the specified type.
     *
     * @param type The type of the names to sample from.
     * @param onlyMutable Determines if we sample from the mutable names or all.
     * @return The sampled name.
     */
    static Name sampleName(Name.Type type, boolean onlyMutable) {
        return Renderer.getCurrent().sampleName(type, onlyMutable);
    }
}
