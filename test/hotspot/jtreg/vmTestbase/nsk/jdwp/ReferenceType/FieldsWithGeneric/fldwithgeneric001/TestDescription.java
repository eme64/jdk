/*
 * Copyright (c) 2017, 2024, Oracle and/or its affiliates. All rights reserved.
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
 *
 * @summary converted from VM Testbase nsk/jdwp/ReferenceType/FieldsWithGeneric/fldwithgeneric001.
 * VM Testbase keywords: [quick, jpda, jdwp]
 * VM Testbase readme:
 * DESCRIPTION
 *     This test performs checking for
 *         JDWP command set: ReferenceType
 *         JDWP command: FieldsWithGeneric
 *     It checks that the command returns generic signature information
 *     properly.
 *     Debuggee part of the test contains several tested fields. Some of them
 *     are generic including the inherited fields. Debugger part obtains
 *     information for each field in a reference type of a tested class.
 *     Proper generic signature should be returned for the generic fields, or
 *     NULL for non-generic ones. Information for the inherited fields should
 *     not be returned.
 * COMMENTS
 *
 * @library /vmTestbase /test/hotspot/jtreg/vmTestbase
 *          /test/lib
 * @build nsk.jdwp.ReferenceType.FieldsWithGeneric.fldwithgeneric001t
 * @run driver
 *      nsk.jdwp.ReferenceType.FieldsWithGeneric.fldwithgeneric001
 *      -arch=${os.family}-${os.simpleArch}
 *      -verbose
 *      -waittime=5
 *      -debugee.vmkind=java
 *      -transport.address=dynamic
 *      -debugee.vmkeys="${test.vm.opts} ${test.java.opts}"
 */

