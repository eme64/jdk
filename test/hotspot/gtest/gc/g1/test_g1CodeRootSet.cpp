/*
 * Copyright (c) 2014, 2025, Oracle and/or its affiliates. All rights reserved.
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

#include "gc/g1/g1CodeRootSet.hpp"
#include "unittest.hpp"

TEST_VM(G1CodeRootSet, g1_code_cache_rem_set) {
  G1CodeRootSet root_set;

  ASSERT_TRUE(root_set.is_empty()) << "Code root set must be initially empty "
          "but is not.";

  root_set.add((nmethod*) 1);
  ASSERT_EQ(root_set.length(), (size_t) 1) << "Added exactly one element, but"
          " set contains " << root_set.length() << " elements";

  const size_t num_to_add = 1000;

  for (size_t i = 1; i <= num_to_add; i++) {
    root_set.add((nmethod*) 1);
  }
  ASSERT_EQ(root_set.length(), (size_t) 1)
          << "Duplicate detection should not have increased the set size but "
          << "is " << root_set.length();

  for (size_t i = 2; i <= num_to_add; i++) {
    root_set.add((nmethod*) (uintptr_t) (i));
  }

  ASSERT_EQ(root_set.length(), num_to_add)
          << "After adding in total " << num_to_add << " distinct code roots, "
          "they need to be in the set, but there are only " << root_set.length();

  size_t num_popped = 0;
  for (size_t i = 1; i <= num_to_add; i++) {
    bool removed = root_set.remove((nmethod*) i);
    if (removed) {
      num_popped += 1;
    } else {
      break;
    }
  }
  ASSERT_EQ(num_popped, num_to_add)
          << "Managed to pop " << num_popped << " code roots, but only "
          << num_to_add << " were added";
  ASSERT_EQ(root_set.length(), 0u)
          << "should be empty";
}
