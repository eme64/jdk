/*
 * Copyright (c) 2024, 2025, Oracle and/or its affiliates. All rights reserved.
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

#include "opto/convertnode.hpp"
#include "opto/rootnode.hpp"
#include "opto/vtransform.hpp"
#include "opto/vectornode.hpp"

void VTransformGraph::add_vtnode(VTransformNode* vtnode) {
  assert(vtnode->_idx == _vtnodes.length(), "position must match idx");
  _vtnodes.push(vtnode);
}

#define TRACE_OPTIMIZE(code)                          \
  NOT_PRODUCT(                                        \
    if (vtransform.vloop().is_trace_optimization()) { \
      code                                            \
    }                                                 \
  )

void VTransformGraph::optimize(VTransform& vtransform) {
  TRACE_OPTIMIZE( tty->print_cr("\nVTransformGraph::optimize"); )

  while (true) {
    bool progress = false;
    for (int i = 0; i < _vtnodes.length(); i++) {
      VTransformNode* vtn = _vtnodes.at(i);
      if (!vtn->is_alive()) { continue; }
      progress |= vtn->optimize(_vloop_analyzer, vtransform);
      if (vtn->outs() == 0 &&
          !(vtn->isa_Outer() != nullptr ||
            vtn->isa_LoopPhi() != nullptr ||
            vtn->is_load_or_store_in_loop())) {
        vtn->mark_dead();
        progress = true;
      }
    }
    if (!progress) { break; }
  }
}

// Compute a linearization of the graph. We do this with a reverse-post-order of a DFS.
// This only works if the graph is a directed acyclic graph (DAG). The C2 graph, and
// the VLoopDependencyGraph are both DAGs, but after introduction of vectors/packs, the
// graph has additional constraints which can introduce cycles. Example:
//
//                                                       +--------+
//  A -> X                                               |        v
//                     Pack [A,B] and [X,Y]             [A,B]    [X,Y]
//  Y -> B                                                 ^        |
//                                                         +--------+
//
// We return "true" IFF we find no cycle, i.e. if the linearization succeeds.
bool VTransformGraph::schedule() {
  assert(!is_scheduled(), "not yet scheduled");

#ifndef PRODUCT
  if (_trace._verbose) {
    print_vtnodes();
  }
#endif

  ResourceMark rm;
  GrowableArray<VTransformNode*> stack;
  VectorSet pre_visited;
  VectorSet post_visited;

  collect_nodes_without_req_or_dependency(stack);
  int num_alive_nodes = count_alive_vtnodes();

  // We create a reverse-post-visit order. This gives us a linearization, if there are
  // no cycles. Then, we simply reverse the order, and we have a schedule.
  int rpo_idx = num_alive_nodes - 1;
  while (!stack.is_empty()) {
    VTransformNode* vtn = stack.top();
    if (!pre_visited.test_set(vtn->_idx)) {
      // Forward arc in graph (pre-visit).
    } else if (!post_visited.test(vtn->_idx)) {
      // Forward arc in graph. Check if all uses were already visited:
      //   Yes -> post-visit.
      //   No  -> we are mid-visit.
      bool all_uses_already_visited = true;

      for (int i = 0; i < vtn->outs(); i++) {
        VTransformNode* use = vtn->out(i);

        // Skip dead nodes
        if (!use->is_alive()) { continue; }

        // Skip backedges
        const VTransformLoopPhiNode* use_loop_phi = use->isa_LoopPhi();
        if (use_loop_phi != nullptr &&
            use_loop_phi->in(2) == vtn) {
          continue;
        }

        if (post_visited.test(use->_idx)) { continue; }
        if (pre_visited.test(use->_idx)) {
          // Cycle detected!
          // The nodes that are pre_visited but not yet post_visited form a path from
          // the "root" to the current vtn. Now, we are looking at an edge (vtn, use),
          // and discover that use is also pre_visited but not post_visited. Thus, use
          // lies on that path from "root" to vtn, and the edge (vtn, use) closes a
          // cycle.
          NOT_PRODUCT(if (_trace._rejections) { trace_schedule_cycle(stack, pre_visited, post_visited); } )
          return false;
        }
        stack.push(use);
        all_uses_already_visited = false;
      }

      if (all_uses_already_visited) {
        stack.pop();
        post_visited.set(vtn->_idx);           // post-visit
        _schedule.at_put_grow(rpo_idx--, vtn); // assign rpo_idx
      }
    } else {
      stack.pop(); // Already post-visited. Ignore secondary edge.
    }
  }

#ifndef PRODUCT
  if (_trace._info) {
    print_schedule();
  }
#endif

  assert(rpo_idx == -1, "used up all rpo_idx, rpo_idx=%d", rpo_idx);
  return true;
}

// Find all nodes that in the loop, in a 2-phase process:
// - First, find all nodes that are not before the loop:
//   - loop-phis
//   - loads and stores that are in the loop
//   - and all their transitive uses.
// - Second, we find all nodes that are not after the loop:
//   - backedges
//   - loads and stores that are in the loop
//   - and all their transitive uses.
void VTransformGraph::mark_vtnodes_in_loop(VectorSet& in_loop) const {
  assert(is_scheduled(), "must already be scheduled");

  // Phase 1: find all nodes that are not before the loop.
  VectorSet is_not_before_loop;
  for (int i = 0; i < _schedule.length(); i++) {
    VTransformNode* vtn = _schedule.at(i);
    // Is vtn a loop-phi?
    if (vtn->isa_LoopPhi() != nullptr ||
        vtn->is_load_or_store_in_loop()) {
      is_not_before_loop.set(vtn->_idx);
      continue;
    }
    // Or one of its transitive uses?
    for (uint j = 0; j < vtn->req(); j++) {
      VTransformNode* def = vtn->in(j);
      if (def != nullptr && is_not_before_loop.test(def->_idx)) {
        is_not_before_loop.set(vtn->_idx);
        break;
      }
    }
  }

  // Phase 2: find all nodes that are not after the loop.
  for (int i = _schedule.length()-1; i >= 0; i--) {
    VTransformNode* vtn = _schedule.at(i);
    if (!is_not_before_loop.test(vtn->_idx)) { continue; }
    // Is load or store?
    if (vtn->is_load_or_store_in_loop()) {
        in_loop.set(vtn->_idx);
        continue;
    }
    for (int i = 0; i < vtn->outs(); i++) {
      VTransformNode* use = vtn->out(i);
      // Or is vtn a backedge or one of its transitive defs?
      if (in_loop.test(use->_idx) ||
          use->isa_LoopPhi() != nullptr) {
        in_loop.set(vtn->_idx);
        break;
      }
    }
  }
}

float VTransformGraph::cost() const {
  assert(is_scheduled(), "must already be scheduled");
#ifndef PRODUCT
  if (_vloop.is_trace_cost()) {
    tty->print_cr("\nVTransformGraph::cost:");
  }
#endif

  ResourceMark rm;
  VectorSet in_loop;
  mark_vtnodes_in_loop(in_loop);

  float sum = 0;
  for (int i = 0; i < _schedule.length(); i++) {
    VTransformNode* vtn = _schedule.at(i);
    if (!in_loop.test(vtn->_idx)) { continue; }
    float c = vtn->cost(_vloop_analyzer);
    sum += c;
#ifndef PRODUCT
    if (c != 0 && _vloop.is_trace_cost_verbose()) {
      tty->print("  -> cost = %.2f for ", c);
      vtn->print();
    }
#endif
  }

#ifndef PRODUCT
  if (_vloop.is_trace_cost()) {
    tty->print_cr("  total_cost = %.2f", sum);
  }
#endif
  return sum;
}

// Push all "root" nodes, i.e. those that have no inputs (req or dependency):
void VTransformGraph::collect_nodes_without_req_or_dependency(GrowableArray<VTransformNode*>& stack) const {
  for (int i = 0; i < _vtnodes.length(); i++) {
    VTransformNode* vtn = _vtnodes.at(i);
    if (vtn->is_alive() && !vtn->has_req_or_dependency()) {
      stack.push(vtn);
    }
  }
}

int VTransformGraph::count_alive_vtnodes() const {
  int count = 0;
  for (int i = 0; i < _vtnodes.length(); i++) {
    VTransformNode* vtn = _vtnodes.at(i);
    if (vtn->is_alive()) { count++; }
  }
  return count;
}

#ifndef PRODUCT
void VTransformGraph::trace_schedule_cycle(const GrowableArray<VTransformNode*>& stack,
                                           const VectorSet& pre_visited,
                                           const VectorSet& post_visited) const {
  tty->print_cr("\nVTransform::schedule found a cycle on path (P), vectorization attempt fails.");
  for (int j = 0; j < stack.length(); j++) {
    VTransformNode* n = stack.at(j);
    bool on_path = pre_visited.test(n->_idx) && !post_visited.test(n->_idx);
    tty->print("  %s ", on_path ? "P" : "_");
    n->print();
  }
}

void VTransformApplyResult::trace(VTransformNode* vtnode) const {
  tty->print("  apply: ");
  vtnode->print();
  tty->print("    ->   ");
  if (_node == nullptr) {
    tty->print_cr("nullptr");
  } else {
    _node->dump();
  }
}
#endif

// Helper-class for VTransformGraph::has_store_to_load_forwarding_failure.
// It wraps a VPointer. The VPointer has an iv_offset applied, which
// simulates a virtual unrolling. They represent the memory region:
//   [adr, adr + size)
//   adr = base + invar + iv_scale * (iv + iv_offset) + con
class VMemoryRegion : public ResourceObj {
private:
  // Note: VPointer has no default constructor, so we cannot use VMemoryRegion
  //       in-place in a GrowableArray. Hence, we make VMemoryRegion a resource
  //       allocated object, so the GrowableArray of VMemoryRegion* has a default
  //       nullptr element.
  const VPointer _vpointer;
  bool _is_load;      // load or store?
  uint _schedule_order;

public:
  VMemoryRegion(const VPointer& vpointer, bool is_load, uint schedule_order) :
    _vpointer(vpointer),
    _is_load(is_load),
    _schedule_order(schedule_order) {}

    const VPointer& vpointer() const { return _vpointer; }
    bool is_load()        const { return _is_load; }
    uint schedule_order() const { return _schedule_order; }

    static int cmp_for_sort_by_group(VMemoryRegion* r1, VMemoryRegion* r2) {
      // Sort by mem_pointer (base, invar, iv_scale), except for the con.
      return MemPointer::cmp_summands(r1->vpointer().mem_pointer(),
                                      r2->vpointer().mem_pointer());
    }

    static int cmp_for_sort(VMemoryRegion** r1, VMemoryRegion** r2) {
      int cmp_group = cmp_for_sort_by_group(*r1, *r2);
      if (cmp_group != 0) { return cmp_group; }

      // We use two comparisons, because a subtraction could underflow.
      jint con1 = (*r1)->vpointer().con();
      jint con2 = (*r2)->vpointer().con();
      if (con1 < con2) { return -1; }
      if (con1 > con2) { return  1; }
      return 0;
    }

    enum Aliasing { DIFFERENT_GROUP, BEFORE, EXACT_OVERLAP, PARTIAL_OVERLAP, AFTER };

    Aliasing aliasing(VMemoryRegion& other) {
      VMemoryRegion* p1 = this;
      VMemoryRegion* p2 = &other;
      if (cmp_for_sort_by_group(p1, p2) != 0) { return DIFFERENT_GROUP; }

      jlong con1 = p1->vpointer().con();
      jlong con2 = p2->vpointer().con();
      jlong size1 = p1->vpointer().size();
      jlong size2 = p2->vpointer().size();

      if (con1 >= con2 + size2) { return AFTER; }
      if (con2 >= con1 + size1) { return BEFORE; }
      if (con1 == con2 && size1 == size2) { return EXACT_OVERLAP; }
      return PARTIAL_OVERLAP;
    }

#ifndef PRODUCT
  void print() const {
    tty->print("VMemoryRegion[%s schedule_order(%4d), ",
               _is_load ? "load, " : "store,", _schedule_order);
    vpointer().print_on(tty, false);
    tty->print_cr("]");
  }
#endif
};

// Store-to-load-forwarding is a CPU memory optimization, where a load can directly fetch
// its value from the store-buffer, rather than from the L1 cache. This is many CPU cycles
// faster. However, this optimization comes with some restrictions, depending on the CPU.
// Generally, store-to-load-forwarding works if the load and store memory regions match
// exactly (same start and width). Generally problematic are partial overlaps - though
// some CPU's can handle even some subsets of these cases. We conservatively assume that
// all such partial overlaps lead to a store-to-load-forwarding failures, which means the
// load has to stall until the store goes from the store-buffer into the L1 cache, incurring
// a penalty of many CPU cycles.
//
// Example (with "iteration distance" 2):
//   for (int i = 10; i < SIZE; i++) {
//       aI[i] = aI[i - 2] + 1;
//   }
//
//   load_4_bytes( ptr +  -8)
//   store_4_bytes(ptr +   0)    *
//   load_4_bytes( ptr +  -4)    |
//   store_4_bytes(ptr +   4)    | *
//   load_4_bytes( ptr +   0)  <-+ |
//   store_4_bytes(ptr +   8)      |
//   load_4_bytes( ptr +   4)  <---+
//   store_4_bytes(ptr +  12)
//   ...
//
//   In the scalar loop, we can forward the stores from 2 iterations back.
//
// Assume we have 2-element vectors (2*4 = 8 bytes), with the "iteration distance" 2
// example. This gives us this machine code:
//   load_8_bytes( ptr +  -8)
//   store_8_bytes(ptr +   0) |
//   load_8_bytes( ptr +   0) v
//   store_8_bytes(ptr +   8)   |
//   load_8_bytes( ptr +   8)   v
//   store_8_bytes(ptr +  16)
//   ...
//
//   We packed 2 iterations, and the stores can perfectly forward to the loads of
//   the next 2 iterations.
//
// Example (with "iteration distance" 3):
//   for (int i = 10; i < SIZE; i++) {
//       aI[i] = aI[i - 3] + 1;
//   }
//
//   load_4_bytes( ptr + -12)
//   store_4_bytes(ptr +   0)    *
//   load_4_bytes( ptr +  -8)    |
//   store_4_bytes(ptr +   4)    |
//   load_4_bytes( ptr +  -4)    |
//   store_4_bytes(ptr +   8)    |
//   load_4_bytes( ptr +   0)  <-+
//   store_4_bytes(ptr +  12)
//   ...
//
//   In the scalar loop, we can forward the stores from 3 iterations back.
//
// Unfortunately, vectorization can introduce such store-to-load-forwarding failures.
// Assume we have 2-element vectors (2*4 = 8 bytes), with the "iteration distance" 3
// example. This gives us this machine code:
//   load_8_bytes( ptr + -12)
//   store_8_bytes(ptr +   0)  |   |
//   load_8_bytes( ptr +  -4)  x   |
//   store_8_bytes(ptr +   8)     ||
//   load_8_bytes( ptr +   4)     xx  <-- partial overlap with 2 stores
//   store_8_bytes(ptr +  16)
//   ...
//
// We see that eventually all loads are dependent on earlier stores, but the values cannot
// be forwarded because there is some partial overlap.
//
// Preferably, we would have some latency-based cost-model that accounts for such forwarding
// failures, and decide if vectorization with forwarding failures is still profitable. For
// now we go with a simpler heuristic: we simply forbid vectorization if we can PROVE that
// there will be a forwarding failure. This approach has at least 2 possible weaknesses:
//
//  (1) There may be forwarding failures in cases where we cannot prove it.
//      Example:
//        for (int i = 10; i < SIZE; i++) {
//            bI[i] = aI[i - 3] + 1;
//        }
//
//      We do not know if aI and bI refer to the same array or not. However, it is reasonable
//      to assume that if we have two different array references, that they most likely refer
//      to different arrays (i.e. no aliasing), where we would have no forwarding failures.
//  (2) There could be some loops where vectorization introduces forwarding failures, and thus
//      the latency of the loop body is high, but this does not matter because it is dominated
//      by other latency/throughput based costs in the loop body.
//
// Performance measurements with the JMH benchmark StoreToLoadForwarding.java have indicated
// that there is some iteration threshold: if the failure happens between a store and load that
// have an iteration distance below this threshold, the latency is the limiting factor, and we
// should not vectorize to avoid the latency penalty of store-to-load-forwarding failures. If
// the iteration distance is larger than this threshold, the throughput is the limiting factor,
// and we should vectorize in these cases to improve throughput.
//
bool VTransformGraph::has_store_to_load_forwarding_failure(const VLoopAnalyzer& vloop_analyzer) const {
  if (SuperWordStoreToLoadForwardingFailureDetection == 0) { return false; }

  // Collect all pointers for scalar and vector loads/stores.
  ResourceMark rm;
  // Use pointers because no default constructor for elements available.
  GrowableArray<VMemoryRegion*> memory_regions;

  // To detect store-to-load-forwarding failures at the iteration threshold or below, we
  // simulate a super-unrolling to reach SuperWordStoreToLoadForwardingFailureDetection
  // iterations at least. This is a heuristic, and we are not trying to be very precise
  // with the iteration distance. If we have already unrolled more than the iteration
  // threshold, i.e. if "SuperWordStoreToLoadForwardingFailureDetection < unrolled_count",
  // then we simply check if there are any store-to-load-forwarding failures in the unrolled
  // loop body, which may be at larger distance than the desired threshold. We cannot do any
  // more fine-grained analysis, because the unrolling has lost the information about the
  // iteration distance.
  int simulated_unrolling_count = SuperWordStoreToLoadForwardingFailureDetection;
  int unrolled_count = vloop_analyzer.vloop().cl()->unrolled_count();
  uint simulated_super_unrolling_count = MAX2(1, simulated_unrolling_count / unrolled_count);
  int iv_stride = vloop_analyzer.vloop().iv_stride();
  int schedule_order = 0;
  for (uint k = 0; k < simulated_super_unrolling_count; k++) {
    int iv_offset = k * iv_stride; // virtual super-unrolling
    for (int i = 0; i < _schedule.length(); i++) {
      VTransformNode* vtn = _schedule.at(i);
      if (vtn->is_load_or_store_in_loop()) {
        const VPointer& p = vtn->vpointer();
        if (p.is_valid()) {
          VTransformVectorNode* vector = vtn->isa_Vector();
          bool is_load = vtn->is_load_in_loop();
          const VPointer iv_offset_p(p.make_with_iv_offset(iv_offset));
          if (iv_offset_p.is_valid()) {
            // The iv_offset may lead to overflows. This is a heuristic, so we do not
            // care too much about those edge cases.
            memory_regions.push(new VMemoryRegion(iv_offset_p, is_load, schedule_order++));
          }
        }
      }
    }
  }

  // Sort the pointers by group (same base, invar and stride), and then by offset.
  memory_regions.sort(VMemoryRegion::cmp_for_sort);

#ifndef PRODUCT
  if (_trace._verbose) {
    tty->print_cr("VTransformGraph::has_store_to_load_forwarding_failure:");
    tty->print_cr("  simulated_unrolling_count = %d", simulated_unrolling_count);
    tty->print_cr("  simulated_super_unrolling_count = %d", simulated_super_unrolling_count);
    for (int i = 0; i < memory_regions.length(); i++) {
      VMemoryRegion& region = *memory_regions.at(i);
      region.print();
    }
  }
#endif

  // For all pairs of pointers in the same group, check if they have a partial overlap.
  for (int i = 0; i < memory_regions.length(); i++) {
    VMemoryRegion& region1 = *memory_regions.at(i);

    for (int j = i + 1; j < memory_regions.length(); j++) {
      VMemoryRegion& region2 = *memory_regions.at(j);

      const VMemoryRegion::Aliasing aliasing = region1.aliasing(region2);
      if (aliasing == VMemoryRegion::Aliasing::DIFFERENT_GROUP ||
          aliasing == VMemoryRegion::Aliasing::BEFORE) {
        break; // We have reached the next group or pointers that are always after.
      } else if (aliasing == VMemoryRegion::Aliasing::EXACT_OVERLAP) {
        continue;
      } else {
        assert(aliasing == VMemoryRegion::Aliasing::PARTIAL_OVERLAP, "no other case can happen");
        if ((region1.is_load() && !region2.is_load() && region1.schedule_order() > region2.schedule_order()) ||
            (!region1.is_load() && region2.is_load() && region1.schedule_order() < region2.schedule_order())) {
          // We predict that this leads to a store-to-load-forwarding failure penalty.
#ifndef PRODUCT
          if (_trace._rejections) {
            tty->print_cr("VTransformGraph::has_store_to_load_forwarding_failure:");
            tty->print_cr("  Partial overlap of store->load. We predict that this leads to");
            tty->print_cr("  a store-to-load-forwarding failure penalty which makes");
            tty->print_cr("  vectorization unprofitable. These are the two pointers:");
            region1.print();
            region2.print();
          }
#endif
          return true;
        }
      }
    }
  }

  return false;
}

void VTransformApplyState::set_transformed_node(VTransformNode* vtn, Node* n) {
  assert(_vtnode_idx_to_transformed_node.at(vtn->_idx) == nullptr, "only set once");
  _vtnode_idx_to_transformed_node.at_put(vtn->_idx, n);
}

Node* VTransformApplyState::transformed_node(const VTransformNode* vtn) const {
  Node* n = _vtnode_idx_to_transformed_node.at(vtn->_idx);
  assert(n != nullptr, "must find IR node for vtnode");
  return n;
}

void VTransformApplyState::init_memory_states() {
  const GrowableArray<Node*>& inputs = _vloop_analyzer.memory_slices().inputs();
  const GrowableArray<PhiNode*>& heads = _vloop_analyzer.memory_slices().heads();
  for (int i = 0; i < inputs.length(); i++) {
    PhiNode* head = heads.at(i);
    if (head != nullptr) {
      // Slice with Phi (i.e. with stores)
      _memory_states.at_put(i, head);

      // Remember uses outside the loop of the last memory state
      Node* old_backedge = head->in(2);
      assert(vloop().in_bb(old_backedge), "backedge should be in the loop");
      for (DUIterator_Fast jmax, j = old_backedge->fast_outs(jmax); j < jmax; j++) {
        Node* use = old_backedge->fast_out(j);
        if (!vloop().in_bb(use)) {
          for (uint k = 0; k < use->req(); k++) {
            if (use->in(k) == old_backedge) {
              _memory_state_uses_after_loop.push(MemoryStateUseAfterLoop(use, k, i));
            }
          }
        }
      }
    } else {
      // Slice without Phi (i.e. only loads)
      _memory_states.at_put(i, inputs.at(i));
    }
  }
}

// We may have reordered the scalar stores, or replaced them with vectors. Now
// the last memory state in the loop may have changed. Thus, we need to change
// the uses of the old last memory state the the new last memory state.
void VTransformApplyState::fix_memory_state_uses_after_loop() {
  for (int i = 0; i < _memory_state_uses_after_loop.length(); i++) {
    MemoryStateUseAfterLoop& use = _memory_state_uses_after_loop.at(i);
    Node* last_state = memory_state(use._alias_idx);
    phase()->igvn().replace_input_of(use._use, use._in_idx, last_state);
  }
}

float VTransformScalarNode::cost(const VLoopAnalyzer& vloop_analyzer) const {
  if (vloop_analyzer.has_zero_cost(_node)) {
    return 0;
  } else {
    return vloop_analyzer.cost_for_scalar(_node->Opcode());
  }
}

VTransformApplyResult VTransformScalarNode::apply(VTransformApplyState& apply_state) const {
  PhaseIdealLoop* phase = apply_state.phase();
  // Set all inputs that have a vtnode: they may have changed
  for (uint i = 0; i < req(); i++) {
    VTransformNode* vtn_def = in(i);
    if (vtn_def != nullptr) {
      Node* def = apply_state.transformed_node(vtn_def);
      phase->igvn().replace_input_of(_node, i, def);
    }
  }

  if (is_load_or_store_in_loop()) {
    Node* mem = apply_state.memory_state(adr_type());
    phase->igvn().replace_input_of(_node, 1, mem);
    if (_node->is_Store()) {
      apply_state.set_memory_state(adr_type(), _node);
    }
  }

  return VTransformApplyResult::make_scalar(_node);
}

VTransformApplyResult VTransformLoopPhiNode::apply(VTransformApplyState& apply_state) const {
  PhaseIdealLoop* phase = apply_state.phase();
  PhiNode* phi = node()->as_Phi();
  Node* in0 = apply_state.transformed_node(in(0));
  Node* in1 = apply_state.transformed_node(in(1));
  phase->igvn().replace_input_of(phi, 0, in0);
  phase->igvn().replace_input_of(phi, 1, in1);
  // Note: the backedge is hooked up later.

  // The Phi's inputs may have been modified, and the types changes, e.g. from
  // scalar to vector.
  const Type* t = in1->bottom_type();
  phi->as_Type()->set_type(t);
  phase->igvn().set_type(phi, t);

  return VTransformApplyResult::make_scalar(phi);
}

// Cleanup backedges. In the schedule, the backedges come after their phis. Hence,
// we only have the transformed backedges after the phis are already transformed.
// We hook the backedges into the phis now, during cleanup.
void VTransformLoopPhiNode::apply_cleanup(VTransformApplyState& apply_state) const {
  PhaseIdealLoop* phase = apply_state.phase();
  PhiNode* phi = node()->as_Phi();

  if (phi->is_memory_phi()) {
    // Memory phi/backedge
    // The last memory state of that slice is the backedge.
    Node* last_state = apply_state.memory_state(adr_type());
    phase->igvn().replace_input_of(phi, 2, last_state);
  } else {
    // Data phi/backedge
    Node* in2 = apply_state.transformed_node(in(2));
    phase->igvn().replace_input_of(phi, 2, in2);
  }
}

float VTransformReplicateNode::cost(const VLoopAnalyzer& vloop_analyzer) const {
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  return vloop_analyzer.cost_for_vector(Op_Replicate, vlen, bt);
}

VTransformApplyResult VTransformReplicateNode::apply(VTransformApplyState& apply_state) const {
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();

  Node* val = apply_state.transformed_node(in(1));
  VectorNode* vn = VectorNode::scalar2vector(val, vlen, bt);
  register_new_node_from_vectorization(apply_state, vn);
  return VTransformApplyResult::make_vector(vn, vlen, vn->length_in_bytes());
}

float VTransformConvI2LNode::cost(const VLoopAnalyzer& vloop_analyzer) const {
  return vloop_analyzer.cost_for_scalar(Op_ConvI2L);
}

VTransformApplyResult VTransformConvI2LNode::apply(VTransformApplyState& apply_state) const {
  Node* val = apply_state.transformed_node(in(1));
  Node* n = new ConvI2LNode(val);
  register_new_node_from_vectorization(apply_state, n);
  return VTransformApplyResult::make_scalar(n);
}

float VTransformShiftCountNode::cost(const VLoopAnalyzer& vloop_analyzer) const {
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  int shift_count_opc = VectorNode::shift_count_opcode(scalar_opcode());
  return vloop_analyzer.cost_for_scalar(Op_AndI) +
         vloop_analyzer.cost_for_vector(shift_count_opc, vlen, bt);
}

VTransformApplyResult VTransformShiftCountNode::apply(VTransformApplyState& apply_state) const {
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  PhaseIdealLoop* phase = apply_state.phase();
  Node* shift_count_in = apply_state.transformed_node(in(1));
  assert(shift_count_in->bottom_type()->isa_int(), "int type only for shift count");
  // The shift_count_in would be automatically truncated to the lowest _mask
  // bits in a scalar shift operation. But vector shift does not truncate, so
  // we must apply the mask now.
  Node* shift_count_masked = new AndINode(shift_count_in, phase->igvn().intcon(_mask));
  register_new_node_from_vectorization(apply_state, shift_count_masked);
  // Now that masked value is "boadcast" (some platforms only set the lowest element).
  VectorNode* vn = VectorNode::shift_count(scalar_opcode(), shift_count_masked, vlen, bt);
  register_new_node_from_vectorization(apply_state, vn);
  return VTransformApplyResult::make_vector(vn, vlen, vn->length_in_bytes());
}

float VTransformPopulateIndexNode::cost(const VLoopAnalyzer& vloop_analyzer) const {
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  return vloop_analyzer.cost_for_vector(Op_PopulateIndex, vlen, bt);
}

VTransformApplyResult VTransformPopulateIndexNode::apply(VTransformApplyState& apply_state) const {
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  PhaseIdealLoop* phase = apply_state.phase();
  Node* val = apply_state.transformed_node(in(1));
  assert(val->is_Phi(), "expected to be iv");
  assert(VectorNode::is_populate_index_supported(bt), "should support");
  const TypeVect* vt = TypeVect::make(bt, vlen);
  VectorNode* vn = new PopulateIndexNode(val, phase->igvn().intcon(1), vt);
  register_new_node_from_vectorization(apply_state, vn);
  return VTransformApplyResult::make_vector(vn, vlen, vn->length_in_bytes());
}

float VTransformElementWiseVectorNode::cost(const VLoopAnalyzer& vloop_analyzer) const {
  return vloop_analyzer.cost_for_vector(_vector_opcode, vector_length(), element_basic_type());
}

VTransformApplyResult VTransformElementWiseVectorNode::apply(VTransformApplyState& apply_state) const {
  int vopc     = _vector_opcode;
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  const TypeVect* vt = TypeVect::make(bt, vlen);

  assert(2 <= req() && req() <= 4, "Must have 1-3 inputs");
  Node* in1 =                apply_state.transformed_node(in(1));
  Node* in2 = (req() >= 3) ? apply_state.transformed_node(in(2)) : nullptr;
  Node* in3 = (req() >= 4) ? apply_state.transformed_node(in(3)) : nullptr;

  VectorNode* vn = nullptr;
  if (req() <= 3) {
    vn = VectorNode::make(vopc, in1, in2, vt); // unary and binary
  } else {
    vn = VectorNode::make(vopc, in1, in2, in3, vt); // ternary
  }

  register_new_node_from_vectorization(apply_state, vn);
  return VTransformApplyResult::make_vector(vn, vlen, vn->length_in_bytes());
}

// The scalar operation was a long -> int operation.
// However, the vector operation is long -> long.
// Hence, we lower the node to: long --long_op--> long --cast--> int
bool VTransformLongToIntVectorNode::optimize(const VLoopAnalyzer& vloop_analyzer, VTransform& vtransform) {
  int sopc     = scalar_opcode();
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  Node* origin = approximate_origin();
  assert(VectorNode::is_scalar_op_that_returns_int_but_vector_op_returns_long(sopc), "");

  // long --long_op--> long
  int long_vopc = VectorNode::opcode(sopc, T_LONG);
  VTransformNodePrototype long_prototype = VTransformNodePrototype(origin, sopc, vlen, T_LONG, nullptr);
  VTransformVectorNode* long_op = new (vtransform.arena()) VTransformElementWiseVectorNode(vtransform, long_prototype, req(), long_vopc);
  for (uint i = 1; i < req(); i++) {
    long_op->init_req(i, in(i));
  }

  // long --cast--> int
  VTransformNodePrototype cast_prototype = VTransformNodePrototype(origin, sopc, vlen, T_INT, nullptr);
  VTransformVectorNode* cast_op = new (vtransform.arena()) VTransformElementWiseVectorNode(vtransform, cast_prototype, req(), Op_VectorCastL2X);
  cast_op->init_req(1, long_op);

  TRACE_OPTIMIZE(
    tty->print_cr(" VTransformLongToIntVectorNode::optimize");
    tty->print_cr("  replace");
    this->print();
    tty->print_cr("  with");
    long_op->print();
    cast_op->print();
  )

  this->replace_by(cast_op);
  return true;
}

float VTransformBoolVectorNode::cost(const VLoopAnalyzer& vloop_analyzer) const {
  int sopc     = scalar_opcode();
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  assert(sopc == Op_Bool, "must be bool node");
  return vloop_analyzer.cost_for_vector(Op_VectorMaskCmp, vlen, bt);
}

VTransformApplyResult VTransformBoolVectorNode::apply(VTransformApplyState& apply_state) const {
  int sopc     = scalar_opcode();
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  assert(sopc == Op_Bool, "must be bool node");

  // Cmp + Bool -> VectorMaskCmp
  VTransformCmpVectorNode* vtn_cmp = in(1)->isa_CmpVector();
  assert(vtn_cmp != nullptr, "bool vtn expects cmp vtn as input");

  Node* cmp_in1 = apply_state.transformed_node(vtn_cmp->in(1));
  Node* cmp_in2 = apply_state.transformed_node(vtn_cmp->in(2));
  BoolTest::mask mask = test()._mask;

  PhaseIdealLoop* phase = apply_state.phase();
  ConINode* mask_node  = phase->igvn().intcon((int)mask);
  const TypeVect* vt = TypeVect::make(bt, vlen);
  VectorNode* vn = new VectorMaskCmpNode(mask, cmp_in1, cmp_in2, mask_node, vt);
  register_new_node_from_vectorization(apply_state, vn);
  return VTransformApplyResult::make_vector(vn, vlen, vn->vect_type()->length_in_bytes());
}

bool VTransformReductionVectorNode::optimize(const VLoopAnalyzer& vloop_analyzer, VTransform& vtransform) {
  return optimize_move_non_strict_order_reductions_out_of_loop(vloop_analyzer, vtransform);
}

int VTransformReductionVectorNode::vector_reduction_opcode() const {
  return ReductionNode::opcode(scalar_opcode(), element_basic_type());
}

bool VTransformReductionVectorNode::requires_strict_order() const {
  int vopc = vector_reduction_opcode();
  return ReductionNode::auto_vectorization_requires_strict_order(vopc);
}

bool VTransformReductionVectorNode::optimize_move_non_strict_order_reductions_out_of_loop(const VLoopAnalyzer& vloop_analyzer, VTransform& vtransform) {
  int sopc     = scalar_opcode();
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  int ropc     = vector_reduction_opcode();

  if (requires_strict_order()) {
    return false; // cannot move strict order reduction out of loop
  }

  const int vopc = VectorNode::opcode(sopc, bt);
  if (!Matcher::match_rule_supported_vector(vopc, vlen, bt)) {
    DEBUG_ONLY( this->print(); )
    assert(false, "do not have normal vector op for this reduction");
    return false; // not implemented
  }

  // We have a phi with a single use.
  VTransformLoopPhiNode* phi = in(1)->isa_LoopPhi();
  if (phi == nullptr || phi->outs() != 1) { return false; }

  // Traverse up the chain of non strict order reductions, checking that it loops
  // back to the phi. Check that all non strict order reductions only have a single
  // use, except for the last (last_red), which only has phi as a use in the loop,
  // and all other uses are outside the loop.
  VTransformReductionVectorNode* first_red   = this;
  VTransformReductionVectorNode* last_red    = phi->in(2)->isa_ReductionVector();
  VTransformReductionVectorNode* current_red = last_red;
  while (true) {
    if (current_red == nullptr ||
        current_red->vector_reduction_opcode() != ropc ||
        current_red->element_basic_type() != bt ||
        current_red->vector_length() != vlen) {
      return false; // not compatible
    }

    VTransformVectorNode* vector_input = current_red->in(2)->isa_Vector();
    if (vector_input == nullptr) {
      assert(false, "reduction has a bad vector input");
      return false;
    }

    // Expect single use of the non strict order reduction. Except for the last_red.
    if (current_red == last_red) {
      // All uses must be outside loop body, except for the phi.
      for (int i = 0; i < current_red->outs(); i++) {
        VTransformNode* use = current_red->out(i);
        if (use->isa_LoopPhi() == nullptr &&
            use->isa_Outer() == nullptr) {
          // Should not be allowed by SuperWord::mark_reductions
          assert(false, "reduction has use inside loop");
          return false;
        }
      }
    } else {
      if (current_red->outs() != 1) {
        return false; // Only single use allowed
      }
    }

    // If the scalar input is a phi, we passed all checks.
    VTransformNode* scalar_input = current_red->in(1);
    if (scalar_input == phi) {
      break;
    }

    // We expect another non strict reduction, verify it in the next iteration.
    current_red = scalar_input->isa_ReductionVector();
  }

  TRACE_OPTIMIZE(
    tty->print_cr("VTransformReductionVectorNode::optimize_move_non_strict_order_reductions_out_of_loop");
  )

  // All checks were successful. Edit the vtransform graph now.
  PhaseIdealLoop* phase = vloop_analyzer.vloop().phase();

  // Create a vector of identity values.
  Node* identity = ReductionNode::make_identity_con_scalar(phase->igvn(), sopc, bt);
  phase->set_ctrl(identity, phase->C->root());

  VTransformNodePrototype scalar_prototype = VTransformNodePrototype::make_from_scalar(identity, vloop_analyzer);
  VTransformNode* vtn_identity = new (vtransform.arena()) VTransformOuterNode(vtransform, scalar_prototype, identity);

  VTransformNodePrototype vector_prototype = VTransformNodePrototype(first_red->approximate_origin(), -1, vlen, bt, nullptr);
  VTransformNode* vtn_identity_vector = new (vtransform.arena()) VTransformReplicateNode(vtransform, vector_prototype);
  vtn_identity_vector->init_req(1, vtn_identity);

  // Turn the scalar phi into a vector phi.
  VTransformNode* init = phi->in(1);
  phi->set_req(1, vtn_identity_vector);

  // Traverse down the chain of reductions, and replace them with vector_accumulators.
  VTransformNode* current_vector_accumulator = phi;
  current_red = first_red;
  while (true) {
    VTransformNode* vector_input = current_red->in(2);
    VTransformVectorNode* vector_accumulator = new (vtransform.arena()) VTransformElementWiseVectorNode(vtransform, current_red->prototype(), 3, vopc);
    vector_accumulator->init_req(1, current_vector_accumulator);
    vector_accumulator->init_req(2, vector_input);
    TRACE_OPTIMIZE(
      tty->print("  replace    ");
      current_red->print();
      tty->print("  with       ");
      vector_accumulator->print();
    )
    current_vector_accumulator = vector_accumulator;
    if (current_red == last_red) { break; }
    current_red = current_red->unique_out()->isa_ReductionVector();
  }

  // Feed vector accumulator into the backedge.
  phi->set_req(2, current_vector_accumulator);

  // Create post-loop reduction. last_red keeps all uses outside the loop.
  last_red->set_req(1, init);
  last_red->set_req(2, current_vector_accumulator);

  TRACE_OPTIMIZE(
    tty->print("  phi        ");
    phi->print();
    tty->print("  after loop ");
    last_red->print();
  )
  return true; // success
}

float VTransformReductionVectorNode::cost(const VLoopAnalyzer& vloop_analyzer) const {
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  int vopc = vector_reduction_opcode();
  bool requires_strict_order = ReductionNode::auto_vectorization_requires_strict_order(vopc);
  return vloop_analyzer.cost_for_vector_reduction(vopc, vlen, bt, requires_strict_order);
}

VTransformApplyResult VTransformReductionVectorNode::apply(VTransformApplyState& apply_state) const {
  int sopc     = scalar_opcode();
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();

  Node* init = apply_state.transformed_node(in(1));
  Node* vec  = apply_state.transformed_node(in(2));

  ReductionNode* vn = ReductionNode::make(sopc, nullptr, init, vec, bt);
  register_new_node_from_vectorization(apply_state, vn);
  return VTransformApplyResult::make_vector(vn, vlen, vn->vect_type()->length_in_bytes());
}

float VTransformLoadVectorNode::cost(const VLoopAnalyzer& vloop_analyzer) const {
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  return vloop_analyzer.cost_for_vector(Op_LoadVector, vlen, bt);
}

VTransformApplyResult VTransformLoadVectorNode::apply(VTransformApplyState& apply_state) const {
  int sopc     = scalar_opcode();
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  const TypePtr* load_adr_type = adr_type();

  Node* ctrl = apply_state.vloop().cl();
  Node* mem  = apply_state.memory_state(load_adr_type);
  Node* adr  = apply_state.transformed_node(in(MemNode::Address));

  // Set the memory dependency of the LoadVector as early as possible.
  // Walk up the memory chain, and ignore any StoreVector that provably
  // does not have any memory dependency.
  const VPointer& load_p = vpointer();
  while (mem->is_StoreVector()) {
    VPointer store_p(mem->as_Mem(), apply_state.vloop());
    if (store_p.never_overlaps_with(load_p)) {
      mem = mem->in(MemNode::Memory);
    } else {
      break;
    }
  }

  LoadVectorNode* vn = LoadVectorNode::make(sopc, ctrl, mem, adr, load_adr_type, vlen, bt,
                                            _control_dependency);
  DEBUG_ONLY( if (VerifyAlignVector) { vn->set_must_verify_alignment(); } )
  register_new_node_from_vectorization(apply_state, vn);
  return VTransformApplyResult::make_vector(vn, vlen, vn->memory_size());
}

float VTransformStoreVectorNode::cost(const VLoopAnalyzer& vloop_analyzer) const {
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  return vloop_analyzer.cost_for_vector(Op_StoreVector, vlen, bt);
}

VTransformApplyResult VTransformStoreVectorNode::apply(VTransformApplyState& apply_state) const {
  int sopc     = scalar_opcode();
  uint vlen    = vector_length();
  BasicType bt = element_basic_type();
  const TypePtr* store_adr_type = adr_type();

  Node* ctrl = apply_state.vloop().cl();
  Node* mem  = apply_state.memory_state(store_adr_type);
  Node* adr  = apply_state.transformed_node(in(MemNode::Address));

  Node* value = apply_state.transformed_node(in(MemNode::ValueIn));
  StoreVectorNode* vn = StoreVectorNode::make(sopc, ctrl, mem, adr, store_adr_type, value, vlen);
  DEBUG_ONLY( if (VerifyAlignVector) { vn->set_must_verify_alignment(); } )
  register_new_node_from_vectorization(apply_state, vn);
  apply_state.set_memory_state(store_adr_type, vn);
  return VTransformApplyResult::make_vector(vn, vlen, vn->memory_size());
}

bool VTransformNode::is_load_in_loop() const {
  const VTransformMemopScalarNode* memop_scalar = isa_MemopScalar();
  if (memop_scalar != nullptr && memop_scalar->node()->is_Load()) { return true; }
  if (isa_LoadVector() != nullptr) { return true; }
  return false;
}

bool VTransformNode::is_load_or_store_in_loop() const {
  if (isa_MemopScalar() != nullptr) { return true; }
  if (isa_MemVector() != nullptr) { return true; }
  return false;
}

void VTransformNode::register_new_node_from_vectorization(VTransformApplyState& apply_state, Node* vn) const {
  PhaseIdealLoop* phase = apply_state.phase();
  phase->C->copy_node_notes_to(vn, approximate_origin());
  // The control is incorrect, but we set major_progress anyway.
  phase->register_new_node(vn, apply_state.vloop().cl());
  phase->igvn()._worklist.push(vn);
  VectorNode::trace_new_vector(vn, "AutoVectorization");
}

#ifndef PRODUCT
void VTransformGraph::print_vtnodes() const {
  tty->print_cr("\nVTransformGraph::print_vtnodes:");
  for (int i = 0; i < _vtnodes.length(); i++) {
    _vtnodes.at(i)->print();
  }
}

void VTransformGraph::print_schedule() const {
  tty->print_cr("\nVTransformGraph::print_schedule:");
  for (int i = 0; i < _schedule.length(); i++) {
    tty->print(" %3d: ", i);
    VTransformNode* vtn = _schedule.at(i);
    if (vtn == nullptr) {
      tty->print_cr("nullptr");
    } else {
      vtn->print();
    }
  }
}

void VTransformNode::print() const {
  tty->print("%3d %s (", _idx, name());
  for (uint i = 0; i < _req; i++) {
    print_node_idx(_in.at(i));
  }
  if ((uint)_in.length() > _req) {
    tty->print(" |");
    for (int i = _req; i < _in.length(); i++) {
      print_node_idx(_in.at(i));
    }
  }
  tty->print(") %s[", _is_alive ? "" : "dead ");
  for (int i = 0; i < _out.length(); i++) {
    print_node_idx(_out.at(i));
  }
  tty->print("] ");
  print_spec();
  tty->cr();
}

void VTransformNode::print_node_idx(const VTransformNode* vtn) {
  if (vtn == nullptr) {
    tty->print(" _");
  } else {
    tty->print(" %d", vtn->_idx);
  }
}

void VTransformScalarNode::print_spec() const {
  tty->print("node[%d %s]", _node->_idx, _node->Name());
}

void VTransformReplicateNode::print_spec() const {
  tty->print("vlen=%d bt=%s", vector_length(), type2name(element_basic_type()));
}

void VTransformShiftCountNode::print_spec() const {
  tty->print("vlen=%d bt=%s mask=%d opc=%s",
             vector_length(), type2name(element_basic_type()), _mask,
             NodeClassNames[scalar_opcode()]);
}

void VTransformPopulateIndexNode::print_spec() const {
  tty->print("vlen=%d bt=%s", vector_length(), type2name(element_basic_type()));
}

void VTransformVectorNode::print_spec() const {
  tty->print("vlen=%d bt=%s", vector_length(), type2name(element_basic_type()));
}

void VTransformElementWiseVectorNode::print_spec() const {
  tty->print("vlen=%d bt=%s vopc=%s",
             vector_length(),
             type2name(element_basic_type()),
             NodeClassNames[_vector_opcode]);
}
#endif
