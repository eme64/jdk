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
package org.openjdk.bench.vm.compiler;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.*;

import java.lang.foreign.*;
import java.util.concurrent.TimeUnit;
import java.util.Random;
import java.util.Arrays;

/**
 * This benchmark is here to measure vectorized peformance for some simple bulk operations:
 * - fill
 * - copy
 *
 * We may add more in the future, for example:
 * - comparison
 * - find index
 * - filter
 * - ...
 *
 * One important feature of this benchmark, is that we control for alignment and 4k-aliasing,
 * something almost no benchmarks have considered up to now. But it is important to get more
 * precise, clean and reliable results.
 *
 * Please also look at the companion benchmark:
 *   VectorBulkOperationsArray.java
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1)
public class VectorBulkOperationsMemorySegment {
    @Param({  "0",  "1",  "2",  "3",  "4",  "5",  "6",  "7",  "8",  "9",
             "10", "11", "12", "13", "14", "15", "16", "17", "18", "19",
             "20", "21", "22", "23", "24", "25", "26", "27", "28", "29",
             "30", "31", "32", "33", "34", "35", "36", "37", "38", "39",
             "40", "41", "42", "43", "44", "45", "46", "47", "48", "49",
             "50", "51", "52", "53", "54", "55", "56", "57", "58", "59",
             "60", "61", "62", "63", "64", "65", "66", "67", "68", "69",
             "70", "71", "72", "73", "74", "75", "76", "77", "78", "79",
             "80", "81", "82", "83", "84", "85", "86", "87", "88", "89",
             "90", "91", "92", "93", "94", "95", "96", "97", "98", "99",
            "100","101","102","103","104","105","106","107","108","109",
            "110","111","112","113","114","115","116","117","118","119",
            "120","121","122","123","124","125","126","127","128","129",
            "130","131","132","133","134","135","136","137","138","139",
            "140","141","142","143","144","145","146","147","148","149",
            "150","151","152","153","154","155","156","157","158","159",
            "160","161","162","163","164","165","166","167","168","169",
            "170","171","172","173","174","175","176","177","178","179",
            "180","181","182","183","184","185","186","187","188","189",
            "190","191","192","193","194","195","196","197","198","199",
            "200","201","202","203","204","205","206","207","208","209",
            "210","211","212","213","214","215","216","217","218","219",
            "220","221","222","223","224","225","226","227","228","229",
            "230","231","232","233","234","235","236","237","238","239",
            "240","241","242","243","244","245","246","247","248","249",
            "250","251","252","253","254","255","256","257","258","259",
            "260","261","262","263","264","265","266","267","268","269",
            "270","271","272","273","274","275","276","277","278","279",
            "280","281","282","283","284","285","286","287","288","289",
            "290","291","292","293","294","295","296","297","298","299",
            "300"})
    public static long NUM_ACCESS_ELEMENTS;

    // TODO: add more?
    @Param({"native", "array_byte"})
    public static String BACKING_TYPE;

    // Every array has two regions:
    // - read region
    // - write region
    // We should make sure that the region is a multiple of 4k, so that the
    // 4k-aliasing prevention trick can work.
    //
    // It would be ince to set REGION_SIZE statically, but we want to keep it rather small if possible,
    // to avoid running out of cache. But it might be quite large if NUM_ACCESS_ELEMENTS is large.
    public static long REGION_SIZE = -1024;
    public static final long REGION_2_BYTE_OFFSET   = 1024 * 2; // prevent 4k-aliasing
    public static final long REGION_2_SHORT_OFFSET  = REGION_2_BYTE_OFFSET / 2;
    public static final long REGION_2_CHAR_OFFSET   = REGION_2_BYTE_OFFSET / 2;
    public static final long REGION_2_INT_OFFSET    = REGION_2_BYTE_OFFSET / 4;
    public static final long REGION_2_LONG_OFFSET   = REGION_2_BYTE_OFFSET / 8;
    public static final long REGION_2_FLOAT_OFFSET  = REGION_2_BYTE_OFFSET / 4;
    public static final long REGION_2_DOUBLE_OFFSET = REGION_2_BYTE_OFFSET / 8;

    // TDOO: see what is still actie up here.

    // Just one MemorySegment for all cases, backed by whatever BACKING_TYPE.
    MemorySegment ms;

    // Used when we need variable values in fill.
    private byte varB = 42;
    private short varS = 42;
    private char varC = 42;
    private int varI = 42;
    private long varL = 42;
    private float varF = 42;
    private double varD = 42;

    // Number of repetitions, to randomize the offsets.
    public static final int REPETITIONS = 64 * 64;

    @CompilerControl(CompilerControl.Mode.INLINE)
    public static long offsetLoad(long i) { return i % 64; }

    @CompilerControl(CompilerControl.Mode.INLINE)
    public static long offsetStore(long i) { return (i / 64) % 64; }

    @Param("42")
    private int seed;
    private Random r = new Random(seed);

    public static long roundUp4k(long i) {
        return (i + 4*1024-1) & (-4*1024L);
    }

    @Setup
    public void init() {
        // Make sure we can fit the longs, and then some whiggle room for alignment.
        REGION_SIZE = roundUp4k(NUM_ACCESS_ELEMENTS * 8 + 1024 + REGION_2_BYTE_OFFSET);

        ms = switch(BACKING_TYPE) {
            case "native" -> Arena.ofAuto().allocate(2 * REGION_SIZE, 4 * 1024);
            case "array_byte" -> MemorySegment.ofArray(new byte[(int)(2 * REGION_SIZE)]);
            default -> throw new RuntimeException("not implemented: " + BACKING_TYPE);
        };
    }

    // -------------------------------- BYTE ------------------------------

    @Benchmark
    public void fill_zero_byte_loop() {
        for (int r = 0; r < REPETITIONS; r++) {
            long offset_store = offsetStore(r) + REGION_SIZE + REGION_2_BYTE_OFFSET;
            for (long i = 0; i < NUM_ACCESS_ELEMENTS; i++) {
                ms.set(ValueLayout.JAVA_BYTE, i + offset_store, (byte)0);
            }
        }
    }

    @Benchmark
    public void fill_var_byte_loop() {
        for (int r = 0; r < REPETITIONS; r++) {
            long offset_store = offsetStore(r) + REGION_SIZE + REGION_2_BYTE_OFFSET;
            for (long i = 0; i < NUM_ACCESS_ELEMENTS; i++) {
                ms.set(ValueLayout.JAVA_BYTE, i + offset_store, varB);
            }
        }
    }

    // TODO: fix or remove
    // @Benchmark
    // public void fill_zero_byte_arrays_fill() {
    //     for (int r = 0; r < REPETITIONS; r++) {
    //         int offset_store = offsetStore(r) + REGION_SIZE + REGION_2_BYTE_OFFSET;
    //         MemorySegment.fill();
    //         Arrays.fill(aB, offset_store, offset_store + NUM_ACCESS_ELEMENTS, (byte)0);
    //     }
    // }

    // @Benchmark
    // public void fill_var_byte_arrays_fill() {
    //     for (int r = 0; r < REPETITIONS; r++) {
    //         int offset_store = offsetStore(r) + REGION_SIZE + REGION_2_BYTE_OFFSET;
    //         Arrays.fill(aB, offset_store, offset_store + NUM_ACCESS_ELEMENTS, varB);
    //     }
    // }

    @Benchmark
    public void copy_byte_loop() {
        for (int r = 0; r < REPETITIONS; r++) {
            long offset_load = offsetLoad(r);
            long offset_store = offsetStore(r) + REGION_SIZE + REGION_2_BYTE_OFFSET;
            for (long i = 0; i < NUM_ACCESS_ELEMENTS; i++) {
                byte v = ms.get(ValueLayout.JAVA_BYTE, i + offset_load);
                ms.set(ValueLayout.JAVA_BYTE, i + offset_store, v);
            }
        }
    }

    @Benchmark
    public void copy_byte_MemorySegment_copy() {
        for (int r = 0; r < REPETITIONS; r++) {
            long offset_load = offsetLoad(r);
            long offset_store = offsetStore(r) + REGION_SIZE + REGION_2_BYTE_OFFSET;
            MemorySegment.copy(ms, offset_load, ms, offset_store, NUM_ACCESS_ELEMENTS);
        }
    }

    // TODO: more types?
}
