/*
 * Copyright (c) 2015, 2022, Oracle and/or its affiliates. All rights reserved.
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

#include "precompiled.hpp"
#include "asm/macroAssembler.hpp"
#include "classfile/javaClasses.hpp"
#include "gc/z/c2/zBarrierSetC2.hpp"
#include "gc/z/zBarrierSet.hpp"
#include "gc/z/zBarrierSetAssembler.hpp"
#include "gc/z/zBarrierSetRuntime.hpp"
#include "opto/arraycopynode.hpp"
#include "opto/addnode.hpp"
#include "opto/block.hpp"
#include "opto/compile.hpp"
#include "opto/graphKit.hpp"
#include "opto/machnode.hpp"
#include "opto/macro.hpp"
#include "opto/memnode.hpp"
#include "opto/node.hpp"
#include "opto/output.hpp"
#include "opto/regalloc.hpp"
#include "opto/rootnode.hpp"
#include "opto/runtime.hpp"
#include "opto/type.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/macros.hpp"

template<typename K, typename V, size_t _table_size>
class ZArenaHashtable : public ResourceObj {
  class ZArenaHashtableEntry : public ResourceObj {
  public:
    ZArenaHashtableEntry* _next;
    K _key;
    V _value;
  };

  static const size_t _table_mask = _table_size - 1;

  Arena* _arena;
  ZArenaHashtableEntry* _table[_table_size];

public:
  class Iterator {
    ZArenaHashtable* _table;
    ZArenaHashtableEntry* _current_entry;
    size_t _current_index;

  public:
    Iterator(ZArenaHashtable* table) :
      _table(table),
      _current_entry(table->_table[0]),
      _current_index(0) {
      if (_current_entry == NULL) {
        next();
      }
    }

    bool has_next() { return _current_entry != NULL; }
    K key()         { return _current_entry->_key; }
    V value()       { return _current_entry->_value; }

    void next() {
      if (_current_entry != NULL) {
        _current_entry = _current_entry->_next;
      }
      while (_current_entry == NULL && ++_current_index < _table_size) {
        _current_entry = _table->_table[_current_index];
      }
    }
  };

  ZArenaHashtable(Arena* arena) :
      _arena(arena),
      _table() {
    Copy::zero_to_bytes(&_table, sizeof(ZArenaHashtableEntry*) * _table_size);
  }

  void add(K key, V value) {
    ZArenaHashtableEntry* entry = new (_arena) ZArenaHashtableEntry();
    entry->_key = key;
    entry->_value = value;
    entry->_next = _table[key & _table_mask];
    _table[key & _table_mask] = entry;
  }

  V* get(K key) const {
    for (ZArenaHashtableEntry* e = _table[key & _table_mask]; e != NULL; e = e->_next) {
      if (e->_key == key) {
        return &(e->_value);
      }
    }
    return NULL;
  }

  Iterator iterator() {
    return Iterator(this);
  }
};

typedef ZArenaHashtable<intptr_t, bool, 4> ZOffsetTable;

class ZBarrierSetC2State : public ResourceObj {
private:
  GrowableArray<ZBarrierStubC2*>* _stubs;
  Node_Array                      _live;
  int                             _trampoline_stubs_count;
  int                             _stubs_start_offset;

public:
  ZBarrierSetC2State(Arena* arena) :
    _stubs(new (arena) GrowableArray<ZBarrierStubC2*>(arena, 8,  0, NULL)),
    _live(arena),
    _trampoline_stubs_count(0),
    _stubs_start_offset(0) {}

  GrowableArray<ZBarrierStubC2*>* stubs() {
    return _stubs;
  }

  RegMask* live(const Node* node) {
    if (!node->is_Mach()) {
      // Don't need liveness for non-MachNodes
      return NULL;
    }

    const MachNode* const mach = node->as_Mach();
    if (mach->has_barrier_flag(ZBarrierElided)) {
      // Don't need liveness data for nodes without barriers
      return NULL;
    }

    RegMask* live = (RegMask*)_live[node->_idx];
    if (live == NULL) {
      live = new (Compile::current()->comp_arena()->AmallocWords(sizeof(RegMask))) RegMask();
      _live.map(node->_idx, (Node*)live);
    }

    return live;
  }

  void inc_trampoline_stubs_count() {
    assert(_trampoline_stubs_count != INT_MAX, "Overflow");
    ++_trampoline_stubs_count;
  }

  int trampoline_stubs_count() {
    return _trampoline_stubs_count;
  }

  void set_stubs_start_offset(int offset) {
    _stubs_start_offset = offset;
  }

  int stubs_start_offset() {
    return _stubs_start_offset;
  }
};

static ZBarrierSetC2State* barrier_set_state() {
  return reinterpret_cast<ZBarrierSetC2State*>(Compile::current()->barrier_set_state());
}

void ZBarrierStubC2::register_stub(ZBarrierStubC2* stub) {
  if (!Compile::current()->output()->in_scratch_emit_size()) {
    barrier_set_state()->stubs()->append(stub);
  }
}

void ZBarrierStubC2::inc_trampoline_stubs_count() {
  if (!Compile::current()->output()->in_scratch_emit_size()) {
    barrier_set_state()->inc_trampoline_stubs_count();
  }
}

int ZBarrierStubC2::trampoline_stubs_count() {
  return barrier_set_state()->trampoline_stubs_count();
}

int ZBarrierStubC2::stubs_start_offset() {
  return barrier_set_state()->stubs_start_offset();
}

ZBarrierStubC2::ZBarrierStubC2(const MachNode* node) :
    _node(node),
    _entry(),
    _continuation() {}

Register ZBarrierStubC2::result() const {
  return noreg;
}

RegMask& ZBarrierStubC2::live() const {
  return *barrier_set_state()->live(_node);
}

Label* ZBarrierStubC2::entry() {
  // The _entry will never be bound when in_scratch_emit_size() is true.
  // However, we still need to return a label that is not bound now, but
  // will eventually be bound. Any eventually bound label will do, as it
  // will only act as a placeholder, so we return the _continuation label.
  return Compile::current()->output()->in_scratch_emit_size() ? &_continuation : &_entry;
}

Label* ZBarrierStubC2::continuation() {
  return &_continuation;
}

ZLoadBarrierStubC2* ZLoadBarrierStubC2::create(const MachNode* node, Address ref_addr, Register ref) {
  ZLoadBarrierStubC2* const stub = new (Compile::current()->comp_arena()) ZLoadBarrierStubC2(node, ref_addr, ref);
  register_stub(stub);

  return stub;
}

ZLoadBarrierStubC2::ZLoadBarrierStubC2(const MachNode* node, Address ref_addr, Register ref) :
    ZBarrierStubC2(node),
    _ref_addr(ref_addr),
    _ref(ref) {
  assert_different_registers(ref, ref_addr.base());
  assert_different_registers(ref, ref_addr.index());
}

Address ZLoadBarrierStubC2::ref_addr() const {
  return _ref_addr;
}

Register ZLoadBarrierStubC2::ref() const {
  return _ref;
}

Register ZLoadBarrierStubC2::result() const {
  return ref();
}

address ZLoadBarrierStubC2::slow_path() const {
  const uint16_t barrier_data = _node->barrier_data();
  DecoratorSet decorators = DECORATORS_NONE;
  if (barrier_data & ZBarrierStrong) {
    decorators |= ON_STRONG_OOP_REF;
  }
  if (barrier_data & ZBarrierWeak) {
    decorators |= ON_WEAK_OOP_REF;
  }
  if (barrier_data & ZBarrierPhantom) {
    decorators |= ON_PHANTOM_OOP_REF;
  }
  if (barrier_data & ZBarrierNoKeepalive) {
    decorators |= AS_NO_KEEPALIVE;
  }
  return ZBarrierSetRuntime::load_barrier_on_oop_field_preloaded_addr(decorators);
}

void ZLoadBarrierStubC2::emit_code(MacroAssembler& masm) {
  ZBarrierSet::assembler()->generate_c2_load_barrier_stub(&masm, static_cast<ZLoadBarrierStubC2*>(this));
}

ZStoreBarrierStubC2* ZStoreBarrierStubC2::create(const MachNode* node, Address ref_addr, Register new_zaddress, Register new_zpointer, bool is_native, bool is_atomic) {
  ZStoreBarrierStubC2* const stub = new (Compile::current()->comp_arena()) ZStoreBarrierStubC2(node, ref_addr, new_zaddress, new_zpointer, is_native, is_atomic);
  register_stub(stub);

  return stub;
}

ZStoreBarrierStubC2::ZStoreBarrierStubC2(const MachNode* node, Address ref_addr, Register new_zaddress, Register new_zpointer, bool is_native, bool is_atomic) :
    ZBarrierStubC2(node),
    _ref_addr(ref_addr),
    _new_zaddress(new_zaddress),
    _new_zpointer(new_zpointer),
    _is_native(is_native),
    _is_atomic(is_atomic) {
}

Address ZStoreBarrierStubC2::ref_addr() const {
  return _ref_addr;
}

Register ZStoreBarrierStubC2::new_zaddress() const {
  return _new_zaddress;
}

Register ZStoreBarrierStubC2::new_zpointer() const {
  return _new_zpointer;
}

bool ZStoreBarrierStubC2::is_native() const {
  return _is_native;
}

bool ZStoreBarrierStubC2::is_atomic() const {
  return _is_atomic;
}

Register ZStoreBarrierStubC2::result() const {
  return noreg;
}

void ZStoreBarrierStubC2::emit_code(MacroAssembler& masm) {
  ZBarrierSet::assembler()->generate_c2_store_barrier_stub(&masm, static_cast<ZStoreBarrierStubC2*>(this));
}

void* ZBarrierSetC2::create_barrier_state(Arena* comp_arena) const {
  return new (comp_arena) ZBarrierSetC2State(comp_arena);
}

void ZBarrierSetC2::late_barrier_analysis() const {
  compute_liveness_at_stubs();
  analyze_dominating_barriers();
}

void ZBarrierSetC2::emit_stubs(CodeBuffer& cb) const {
  MacroAssembler masm(&cb);
  GrowableArray<ZBarrierStubC2*>* const stubs = barrier_set_state()->stubs();
  barrier_set_state()->set_stubs_start_offset(masm.offset());

  for (int i = 0; i < stubs->length(); i++) {
    // Make sure there is enough space in the code buffer
    if (cb.insts()->maybe_expand_to_ensure_remaining(PhaseOutput::MAX_inst_size) && cb.blob() == NULL) {
      ciEnv::current()->record_failure("CodeCache is full");
      return;
    }

    stubs->at(i)->emit_code(masm);
  }

  masm.flush();
}

int ZBarrierSetC2::estimate_stub_size() const {
  Compile* const C = Compile::current();
  BufferBlob* const blob = C->output()->scratch_buffer_blob();
  GrowableArray<ZBarrierStubC2*>* const stubs = barrier_set_state()->stubs();
  int size = 0;

  for (int i = 0; i < stubs->length(); i++) {
    CodeBuffer cb(blob->content_begin(), (address)C->output()->scratch_locs_memory() - blob->content_begin());
    MacroAssembler masm(&cb);
    stubs->at(i)->emit_code(masm);
    size += cb.insts_size();
  }

  return size;
}

int ZBarrierSetC2::estimate_mach_node_size(MachNode* mach) const {
  if (ZVerifyElidedBarriers && ((mach->ideal_Opcode() == Op_StoreP) || (mach->ideal_Opcode() == Op_LoadP))) {
    return 64;
  }
  return 0;
}

static void set_barrier_data(C2Access& access) {
  if (!ZBarrierSet::barrier_needed(access.decorators(), access.type())) {
    return;
  }

  if (access.decorators() & C2_TIGHTLY_COUPLED_ALLOC) {
    access.add_barrier_data(ZBarrierElided);
    return;
  }

  uint16_t barrier_data = 0;

  if (access.decorators() & ON_PHANTOM_OOP_REF) {
    barrier_data |= ZBarrierPhantom;
  } else if (access.decorators() & ON_WEAK_OOP_REF) {
    barrier_data |= ZBarrierWeak;
  } else {
    barrier_data |= ZBarrierStrong;
  }

  if (access.decorators() & IN_NATIVE) {
    barrier_data |= ZBarrierNative;
  }

  if (access.decorators() & AS_NO_KEEPALIVE) {
    barrier_data |= ZBarrierNoKeepalive;
  }

  access.set_barrier_data(barrier_data);
}

Node* ZBarrierSetC2::store_at_resolved(C2Access& access, C2AccessValue& val) const {
  set_barrier_data(access);
  return BarrierSetC2::store_at_resolved(access, val);
}

Node* ZBarrierSetC2::load_at_resolved(C2Access& access, const Type* val_type) const {
  set_barrier_data(access);
  return BarrierSetC2::load_at_resolved(access, val_type);
}

Node* ZBarrierSetC2::atomic_cmpxchg_val_at_resolved(C2AtomicParseAccess& access, Node* expected_val,
                                                    Node* new_val, const Type* val_type) const {
  set_barrier_data(access);
  return BarrierSetC2::atomic_cmpxchg_val_at_resolved(access, expected_val, new_val, val_type);
}

Node* ZBarrierSetC2::atomic_cmpxchg_bool_at_resolved(C2AtomicParseAccess& access, Node* expected_val,
                                                     Node* new_val, const Type* value_type) const {
  set_barrier_data(access);
  return BarrierSetC2::atomic_cmpxchg_bool_at_resolved(access, expected_val, new_val, value_type);
}

Node* ZBarrierSetC2::atomic_xchg_at_resolved(C2AtomicParseAccess& access, Node* new_val, const Type* val_type) const {
  set_barrier_data(access);
  return BarrierSetC2::atomic_xchg_at_resolved(access, new_val, val_type);
}

bool ZBarrierSetC2::array_copy_requires_gc_barriers(bool tightly_coupled_alloc, BasicType type,
                                                    bool is_clone, bool is_clone_instance,
                                                    ArrayCopyPhase phase) const {
  if (phase == ArrayCopyPhase::Parsing) {
    return false;
  }
  if (phase == ArrayCopyPhase::Optimization) {
    return is_clone_instance;
  }
  // else ArrayCopyPhase::Expansion
  return type == T_OBJECT || type == T_ARRAY;
}

// This TypeFunc assumes a 64bit system
static const TypeFunc* clone_type() {
  // Create input type (domain)
  const Type** domain_fields = TypeTuple::fields(4);
  domain_fields[TypeFunc::Parms + 0] = TypeInstPtr::NOTNULL;  // src
  domain_fields[TypeFunc::Parms + 1] = TypeInstPtr::NOTNULL;  // dst
  domain_fields[TypeFunc::Parms + 2] = TypeLong::LONG;        // size lower
  domain_fields[TypeFunc::Parms + 3] = Type::HALF;            // size upper
  const TypeTuple* domain = TypeTuple::make(TypeFunc::Parms + 4, domain_fields);

  // Create result type (range)
  const Type** range_fields = TypeTuple::fields(0);
  const TypeTuple* range = TypeTuple::make(TypeFunc::Parms + 0, range_fields);

  return TypeFunc::make(domain, range);
}

#define XTOP LP64_ONLY(COMMA phase->top())

void ZBarrierSetC2::clone_at_expansion(PhaseMacroExpand* phase, ArrayCopyNode* ac) const {
  Node* const src = ac->in(ArrayCopyNode::Src);
  const TypeAryPtr* ary_ptr = src->get_ptr_type()->isa_aryptr();

  if (ac->is_clone_array() && ary_ptr != NULL) {
    BasicType bt = ary_ptr->elem()->array_element_basic_type();
    if (is_reference_type(bt)) {
      // Clone object array
      bt = T_OBJECT;
    } else {
      // Clone primitive array
      bt = T_LONG;
    }

    Node* ctrl = ac->in(TypeFunc::Control);
    Node* mem = ac->in(TypeFunc::Memory);
    Node* src = ac->in(ArrayCopyNode::Src);
    Node* src_offset = ac->in(ArrayCopyNode::SrcPos);
    Node* dest = ac->in(ArrayCopyNode::Dest);
    Node* dest_offset = ac->in(ArrayCopyNode::DestPos);
    Node* length = ac->in(ArrayCopyNode::Length);

    if (bt == T_OBJECT) {
      // BarrierSetC2::clone sets the offsets via BarrierSetC2::arraycopy_payload_base_offset
      // which 8-byte aligns them to allow for word size copies. Make sure the offsets point
      // to the first element in the array when cloning object arrays. Otherwise, load
      // barriers are applied to parts of the header. Also adjust the length accordingly.
      assert(src_offset == dest_offset, "should be equal");
      jlong offset = src_offset->get_long();
      if (offset != arrayOopDesc::base_offset_in_bytes(T_OBJECT)) {
        assert(!UseCompressedClassPointers, "should only happen without compressed class pointers");
        assert((arrayOopDesc::base_offset_in_bytes(T_OBJECT) - offset) == BytesPerLong, "unexpected offset");
        length = phase->transform_later(new SubLNode(length, phase->longcon(1))); // Size is in longs
        src_offset = phase->longcon(arrayOopDesc::base_offset_in_bytes(T_OBJECT));
        dest_offset = src_offset;
      }
    }
    Node* payload_src = phase->basic_plus_adr(src, src_offset);
    Node* payload_dst = phase->basic_plus_adr(dest, dest_offset);

    const char* copyfunc_name = "arraycopy";
    address     copyfunc_addr = phase->basictype2arraycopy(bt, NULL, NULL, true, copyfunc_name, true);

    const TypePtr* raw_adr_type = TypeRawPtr::BOTTOM;
    const TypeFunc* call_type = OptoRuntime::fast_arraycopy_Type();

    Node* call = phase->make_leaf_call(ctrl, mem, call_type, copyfunc_addr, copyfunc_name, raw_adr_type, payload_src, payload_dst, length XTOP);
    phase->transform_later(call);

    phase->igvn().replace_node(ac, call);
    return;
  }

  // Clone instance
  Node* const ctrl       = ac->in(TypeFunc::Control);
  Node* const mem        = ac->in(TypeFunc::Memory);
  Node* const dst        = ac->in(ArrayCopyNode::Dest);
  Node* const size       = ac->in(ArrayCopyNode::Length);

  assert(size->bottom_type()->is_long(), "Should be long");

  // The native clone we are calling here expects the instance size in words
  // Add header/offset size to payload size to get instance size.
  Node* const base_offset = phase->longcon(arraycopy_payload_base_offset(ac->is_clone_array()) >> LogBytesPerLong);
  Node* const full_size = phase->transform_later(new AddLNode(size, base_offset));

  Node* const call = phase->make_leaf_call(ctrl,
                                           mem,
                                           clone_type(),
                                           ZBarrierSetRuntime::clone_addr(),
                                           "ZBarrierSetRuntime::clone",
                                           TypeRawPtr::BOTTOM,
                                           src,
                                           dst,
                                           full_size,
                                           phase->top());
  phase->transform_later(call);
  phase->igvn().replace_node(ac, call);
}

#undef XTOP

static uint block_index(const Block* block, const Node* node) {
  for (uint j = 0; j < block->number_of_nodes(); ++j) {
    if (block->get_node(j) == node) {
      return j;
    }
  }
  ShouldNotReachHere();
  return 0;
}

// == Dominating barrier elision ==

static int block_register_safepoints(Block* block, Node* dom_access, uint from, uint to, const Node* mem, GrowableArray<SafepointAccessRecord*>& access_list) {
  Compile* const C = Compile::current();
  PhaseCFG* const cfg = C->cfg();
  Block* const dom_access_block = cfg->get_block_for_node(dom_access);
  uint dom_access_index = block_index(dom_access_block, dom_access);

  if (!dom_access_block->dominates(block)) {
    return 0; // the sfp is above the dominating access
  }

  if (dom_access_block == block) {
    if (dom_access_index > from) {
      from = dom_access_index;
    }
  }

  int count = 0;
  for (uint i = from; i < to; i++) {
    Node* node = block->get_node(i);
    if (node->is_MachSafePoint() && !node->is_MachCallLeaf()) {
      // Safepoint found
      access_list.push(new SafepointAccessRecord(node->as_MachSafePoint(), (Node*)mem));
      count++;
    }
  }
  return count;
}

// Look through various node aliases
// If look_through_spill is false - the first spill node will be returned
static const Node* look_through_node(const Node* node, bool look_through_spill = true) {
  while (node != NULL) {
    const Node* new_node = node;
    if (node->is_Mach()) {
      const MachNode* node_mach = node->as_Mach();
      if (node_mach->ideal_Opcode() == Op_CheckCastPP) {
        new_node = node->in(1);
      }
      if (node_mach->is_SpillCopy() && look_through_spill) {
        new_node = node->in(1);
      }
    }
    if (new_node == node || new_node == NULL) {
      break;
    } else {
      node = new_node;
    }
  }

  return node;
}

// Check the type if the checkcast node to determine the type of the allocation
static bool is_array_allocation(const Node* node) {
  precond(node->is_Phi());
  if (!node->raw_out(0)->isa_Mach()) {
    // Sometimes
    return true; // true -> skip this allocation
  }
  MachNode* cc = node->raw_out(0)->as_Mach();
  if (cc->ideal_Opcode() == Op_CheckCastPP) {
    if (cc->get_ptr_type()->isa_aryptr()) {
      // We must know the offset/index if an oop* array dominates an access
      return true;
    }
  }
  return false;
}

// Match the phi node that connects a TLAB allocation fast path with its slowpath
static bool is_allocation(const Node* node) {
  if (node->req() != 3) {
    return false;
  }
  const Node* fast_node = node->in(2);
  if (!fast_node->is_Mach()) {
    return false;
  }
  MachNode* fast_mach = fast_node->as_Mach();
  if (fast_mach->ideal_Opcode() != Op_LoadP) {
    return false;
  }
  intptr_t offset;
  const Node* base = look_through_node(fast_mach->get_base_and_offset(offset));
  if (base == NULL || !base->is_Mach()) {
    return false;
  }
  const MachNode* base_mach = base->as_Mach();
  if (base_mach->ideal_Opcode() != Op_ThreadLocal) {
    return false;
  }
  return offset == in_bytes(Thread::tlab_top_offset());
}

void ZBarrierSetC2::mark_mach_barrier_dom_elided(MachNode* mach) const{
  mach->add_barrier_data(ZBarrierElided | ZBarrierDomElided);
}

void ZBarrierSetC2::mark_mach_barrier_sab_elided(MachNode* mach) const {
  mach->add_barrier_data(ZBarrierElided | ZBarrierSABElided);
}

void ZBarrierSetC2::mark_mach_barrier_sab_bailout(MachNode* mach) const {
  assert(!mach->has_barrier_flag(ZBarrierElided), "must not have been marked sanity");
}

void ZBarrierSetC2::record_safepoint_attached_barrier(MachNode* const access, Node* mem, MachSafePointNode* sfp DEBUG_ONLY(COMMA Node* dom_access)) const {
  sfp->record_barrier(access, mem DEBUG_ONLY(COMMA dom_access));
}

bool access_is_spilled(MachNode* const access, const Node* const access_obj) {
  intptr_t mem_offset;
  // The access is spilled if you get different results with look_through true or false.
  return look_through_node(access->get_base_and_offset(mem_offset), false) != access_obj;
}

void ZBarrierSetC2::process_access(MachNode* const access, Node* dom_access, GrowableArray<SafepointAccessRecord*> &access_list, intptr_t access_offset) const {

  Compile* const C = Compile::current();
  PhaseCFG* const cfg = C->cfg();

  bool is_derived      = (access->in(2)->bottom_type()->is_ptr()->_offset != 0);
  bool offset_is_short = (access_offset >> 16) == 0;
  bool trace = C->directive()->TraceBarrierEliminationOption;

  if (access_list.length() == 0) {
    if (C->directive()->UseDomBarrierEliminationOption) {
      if (trace) {
        tty->print_cr("*** dom elided access %i for dom access %i", access->_idx, dom_access->_idx);
      }
      mark_mach_barrier_dom_elided(access);
    } else {
      if (trace) {
        tty->print_cr("*** SKIPPED dom elided access %i for dom access %i", access->_idx, dom_access->_idx);
      }
    }
    return;
  } else if (C->directive()->UseSafepointAttachedBarriersOption) {
    precond(access_list.length() > 0);
    if (offset_is_short && !is_derived) {
      mark_mach_barrier_sab_elided(access);
      while (access_list.length() > 0) {
        SafepointAccessRecord* sar = access_list.pop();
        MachSafePointNode* msfp = sar->_msfp;

#ifdef ASSERT
        if (ZVerifyElidedBarriers) {
          // Verify that the dominating access actually dominates all the SAB safepoints
          Block* dom_access_block = cfg->get_block_for_node(dom_access);
          Block* msfp_block = cfg->get_block_for_node(msfp);
          if (dom_access_block == msfp_block) {
            const uint dom_access_index = block_index(dom_access_block, dom_access);
            const uint msfp_index = block_index(msfp_block, msfp);
            assert(dom_access_index < msfp_index, "check");
          } else {
            assert(dom_access_block->dominates(msfp_block), "check");
          }
        }
#endif
        record_safepoint_attached_barrier(access, sar->_mem, msfp DEBUG_ONLY(COMMA dom_access));
      }
      postcond(access_list.length() == 0);
      return;
    } else { // can't elide accesses with an offset that doesn't fit in oopmap or is dervied
      assert(access->barrier_data() != 0, "check");
      if (trace) {
        tty->print_cr("*** BAILOUT dom elided access %i for dom access %i", access->_idx, dom_access->_idx);
      }
      mark_mach_barrier_sab_bailout(access);
    }
  }

  //empty list
  while (access_list.length() > 0) {
    access_list.pop();
  }
}

void ZBarrierSetC2::analyze_dominating_barriers_impl_inner(Block* dom_block, Node* dom_access, Node* const access, const Node* def_mem, GrowableArray<SafepointAccessRecord*> &access_list) const {
  Compile* const C = Compile::current();
  PhaseCFG* const cfg = C->cfg();

  Block* const access_block = cfg->get_block_for_node(access);
  Block* const def_block = cfg->get_block_for_node(def_mem);

  const uint access_index = block_index(access_block, access);
  Block* mem_block = cfg->get_block_for_node(def_mem);
  uint mem_index = block_index(def_block, def_mem);

  assert(dom_block->dominates(def_block), "sanity");
  assert(dom_block->dominates(access_block), "sanity");

  if (access_block == def_block) {
    // Earlier accesses in the same block
    assert(mem_index < access_index, "should already be handled");
    if (mem_index < access_index) {
      block_register_safepoints(mem_block, dom_access, mem_index + 1, access_index, def_mem, access_list);
    }
  } else if (mem_block->dominates(access_block)) {
    // Dominating block? Look around for safepoints
    Block_List stack;
    VectorSet visited;

    // Start processing first block - we might come back to it from below if in a loop
    block_register_safepoints(access_block, dom_access, 0, access_index, def_mem, access_list);
    // Push predecessor blocks
    for (uint p = 1; p < access_block->num_preds(); ++p) {
      Block* pred = cfg->get_block_for_node(access_block->pred(p));
      stack.push(pred);
    }

    while (stack.size() > 0) { // must traversal full stack and find all safepoints
      Block* block = stack.pop();
      if (visited.test_set(block->_pre_order)) {
        continue;
      }
      if (!dom_block->dominates(block)) {
        assert(0, "Should not reach here");
        continue;
      }
      if (block == mem_block) {
        block_register_safepoints(block, dom_access, mem_index, block->number_of_nodes(), def_mem, access_list);
        continue;
      }

      block_register_safepoints(block, dom_access, 0, block->number_of_nodes(), def_mem, access_list);

      // Push predecessor blocks
      for (uint p = 1; p < block->num_preds(); ++p) {
        Block* pred = cfg->get_block_for_node(block->pred(p));
        stack.push(pred);
      }
    }
  }
}

Node* next_def(const Node* node) {
  precond(node != NULL);
  if (node->is_Mach()) {
    const MachNode* node_mach = node->as_Mach();
    if (node_mach->ideal_Opcode() == Op_CheckCastPP) {
      return node->in(1);
    }
    if (node_mach->is_SpillCopy()) {
      return node->in(1);
    }
  }
  guarantee(0, "Shouldn't reach here.");
  return NULL;
}

void ZBarrierSetC2::analyze_dominating_barriers_impl(Node_List& accesses, Node_List& access_dominators) const {
  Block_List worklist;

  Compile* const C = Compile::current();
  PhaseCFG* const cfg = C->cfg();

  for (uint i = 0; i < accesses.size(); i++) {
    intptr_t access_offset;
    MachNode* const access    = accesses.at(i)->as_Mach();
    const Node*  access_mem   = look_through_node(access->get_base_and_offset(access_offset));
    Block* const access_block = cfg->get_block_for_node(access);
    const uint   access_index = block_index(access_block, access);

    if (access->has_barrier_flag(ZBarrierElided)) {
       continue; // already elided
    }

    if (access_mem == NULL) {
      // No information available
      continue;
    }

    for (uint j = 0; j < access_dominators.size(); j++) {
      const Node* dom_mem = nullptr;
      Node* dom = access_dominators.at(j);
      intptr_t mem_offset;
      if (dom == access) {
        continue;
      }
      if (dom->is_Phi()) {
        // Allocation node
        if (dom != access_mem) {
          continue;
        }
      } else {
        // Access node
        dom_mem = look_through_node(dom->as_Mach()->get_base_and_offset(mem_offset));

        if (dom_mem == NULL) {
          // No information available
          continue;
        }

        if (dom_mem != access_mem || mem_offset != access_offset) {
          // Not the same addresses, not a candidate
          continue;
        }
      }

      Block* dom_block = cfg->get_block_for_node(dom);
      if (!dom_block->dominates(access_block)) {
        continue;
      }

      if (access_block == dom_block) {
        const uint dom_index = block_index(dom_block, dom);
        if (access_index < dom_index) {
          continue;
        }
      }

      Block* dom_mem_block;
      if (dom_mem != NULL) {
        dom_mem_block = cfg->get_block_for_node(dom_mem);
      } else {
        dom_mem_block = dom_block; // Phis/Allocations don't have mem_obj
      }

      // Now we have established that we have an access than is dominated by another access or allocation.
      // Now walk the def chain up until the dominating access, recording any encountered safepoint
      // with the current def. Then lastly, process all access records.

      ResourceMark rm;
      GrowableArray<SafepointAccessRecord*> access_list;
      MachNode* node = access;
      const Node* node_def = access->get_base_and_offset(access_offset);

      int limit = 0;
      while (true) {
        analyze_dominating_barriers_impl_inner(dom_mem_block, dom, node, node_def, access_list);
        if (node_def->is_Phi()) {
          break; // allocation - we are done
        }
        if (node_def == dom_mem) {
          break; // reached the end - we are done
        }
        node = node_def->as_Mach();
        node_def = next_def(node_def);

        limit++;
        guarantee(limit < MaxNodeLimit, "guard against any unlimited searches instead of timing out");
      }

      process_access(access, dom, access_list, access_offset);
      assert(access_list.length() == 0, "check");
    }
  }
}

void ZBarrierSetC2::analyze_dominating_barriers() const {
  ResourceMark rm;
  Compile* const C = Compile::current();
  PhaseCFG* const cfg = C->cfg();

  Node_List loads;
  Node_List load_dominators;

  Node_List stores;
  Node_List store_dominators;

  Node_List atomics;
  Node_List atomic_dominators;

  // Step 1 - Find accesses and allocations, and track them in lists
  for (uint i = 0; i < cfg->number_of_blocks(); ++i) {
    const Block* const block = cfg->get_block(i);
    for (uint j = 0; j < block->number_of_nodes(); ++j) {
      Node* const node = block->get_node(j);
      if (node->is_Phi()) { // Change to CheckCastPP with InitalizeNode as ctrl
        if (is_allocation(node)) {
          if (is_array_allocation(node)) {
            // We must know the offset/index if an oop* array dominates an access
            continue;
          }

          load_dominators.push(node);
          store_dominators.push(node);
          // An allocation can't be considered to "dominate" an atomic operation.
          // For example a CAS requires the memory location to be store-good.
          // When you have a dominating store or atomic instruction, that is
          // indeed ensured to be the case. However, as for allocations, the
          // initialized memory location could be raw null, which isn't store-good.
        }
        continue;
      } else if (!node->is_Mach()) {
        continue;
      }

      MachNode* const mach = node->as_Mach();
      switch (mach->ideal_Opcode()) {
      case Op_LoadP:
        if (mach->has_barrier_flag(ZBarrierStrong) &&
            !mach->has_barrier_flag(ZBarrierNoKeepalive)) {
          loads.push(mach);
          load_dominators.push(mach);
        }
        break;
      case Op_StoreP:
        if (mach->has_barrier_flag(ZBarrierTypeMask)) {
          stores.push(mach);
          load_dominators.push(mach);
          store_dominators.push(mach);
          atomic_dominators.push(mach);
        }
        break;
      case Op_CompareAndExchangeP:
      case Op_CompareAndSwapP:
      case Op_GetAndSetP:
        if (mach->has_barrier_flag(ZBarrierTypeMask)) {
          atomics.push(mach);
          load_dominators.push(mach);
          store_dominators.push(mach);
          atomic_dominators.push(mach);
        }
        break;

      default:
        break;
      }
    }
  }

  // Step 2 - Find dominating accesses or allocations for each access
  analyze_dominating_barriers_impl(loads, load_dominators);
  analyze_dominating_barriers_impl(stores, store_dominators);
  analyze_dominating_barriers_impl(atomics, atomic_dominators);
}

// == Reduced spilling optimization ==

void ZBarrierSetC2::compute_liveness_at_stubs() const {
  ResourceMark rm;
  Compile* const C = Compile::current();
  Arena* const A = Thread::current()->resource_area();
  PhaseCFG* const cfg = C->cfg();
  PhaseRegAlloc* const regalloc = C->regalloc();
  RegMask* const live = NEW_ARENA_ARRAY(A, RegMask, cfg->number_of_blocks() * sizeof(RegMask));
  ZBarrierSetAssembler* const bs = ZBarrierSet::assembler();
  Block_List worklist;

  for (uint i = 0; i < cfg->number_of_blocks(); ++i) {
    new ((void*)(live + i)) RegMask();
    worklist.push(cfg->get_block(i));
  }

  while (worklist.size() > 0) {
    const Block* const block = worklist.pop();
    RegMask& old_live = live[block->_pre_order];
    RegMask new_live;

    // Initialize to union of successors
    for (uint i = 0; i < block->_num_succs; i++) {
      const uint succ_id = block->_succs[i]->_pre_order;
      new_live.OR(live[succ_id]);
    }

    // Walk block backwards, computing liveness
    for (int i = block->number_of_nodes() - 1; i >= 0; --i) {
      const Node* const node = block->get_node(i);

      // Remove def bits
      const OptoReg::Name first = bs->refine_register(node, regalloc->get_reg_first(node));
      const OptoReg::Name second = bs->refine_register(node, regalloc->get_reg_second(node));
      if (first != OptoReg::Bad) {
        new_live.Remove(first);
      }
      if (second != OptoReg::Bad) {
        new_live.Remove(second);
      }

      // Add use bits
      for (uint j = 1; j < node->req(); ++j) {
        const Node* const use = node->in(j);
        const OptoReg::Name first = bs->refine_register(use, regalloc->get_reg_first(use));
        const OptoReg::Name second = bs->refine_register(use, regalloc->get_reg_second(use));
        if (first != OptoReg::Bad) {
          new_live.Insert(first);
        }
        if (second != OptoReg::Bad) {
          new_live.Insert(second);
        }
      }

      // If this node tracks liveness, update it
      RegMask* const regs = barrier_set_state()->live(node);
      if (regs != NULL) {
        regs->OR(new_live);
      }
    }

    // Now at block top, see if we have any changes
    new_live.SUBTRACT(old_live);
    if (new_live.is_NotEmpty()) {
      // Liveness has refined, update and propagate to prior blocks
      old_live.OR(new_live);
      for (uint i = 1; i < block->num_preds(); ++i) {
        Block* const pred = cfg->get_block_for_node(block->pred(i));
        worklist.push(pred);
      }
    }
  }
}

void ZBarrierSetC2::eliminate_gc_barrier(PhaseMacroExpand* macro, Node* node) const {
  eliminate_gc_barrier_data(node);
}

void ZBarrierSetC2::eliminate_gc_barrier_data(Node* node) const {
  if (node->is_LoadStore()) {
    LoadStoreNode* loadstore = node->as_LoadStore();
    loadstore->add_barrier_data(ZBarrierElided);
  } else if (node->is_Mem()) {
    MemNode* mem = node->as_Mem();
    // Only set barrier bits on ops that can be elided
    if ((node->Opcode() == Op_StoreP) || (node->Opcode() == Op_LoadP)) {
      mem->add_barrier_data(ZBarrierElided);
    }
  }
}

enum {
  LOAD_COUNTER,
  STORE_COUNTER,
  ATOMIC_COUNTER,
  NO_COUNTER
};

const char* presentation_names[] = {
  "Loads",
  "Stores",
  "Atomics",
};

struct elision_counter_struct {
  unsigned int barrier_strong;
  unsigned int barrier_weak;
  unsigned int barrier_phantom;
  unsigned int barrier_nokeepalive;
  unsigned int barrier_native;
  unsigned int barrier_elided;
  unsigned int barrier_dom_elided;
  unsigned int barrier_sab_elided;
  unsigned int barrier_triv_elided;
};

static elision_counter_struct _elision_counter[3] = {};
static int _elided_zf = 0;

void ZBarrierSetC2::gather_stats() const {
  if (PrintBarrierSetStatistics) {
    Compile* const C = Compile::current();
    PhaseCFG* const cfg = C->cfg();

    for (uint i = 0; i < cfg->number_of_blocks(); ++i) {
      const Block* const block = cfg->get_block(i);
      for (uint j = 0; j < block->number_of_nodes(); ++j) {
        Node* const node = block->get_node(j);
        if (!node->is_Mach()) {
          continue;
        }
        MachNode* const mach = node->as_Mach();
        uint type;
        switch (mach->ideal_Opcode()) {
          case Op_LoadP:
            type = LOAD_COUNTER;
            if (mach->has_barrier_flag(ZBarrierNullCheckRemoval)) {
              _elided_zf++;
            }
            break;
          case Op_StoreP:
            type = STORE_COUNTER;
            break;
          case Op_CompareAndExchangeP:
          case Op_CompareAndSwapP:
          case Op_GetAndSetP:
            type = ATOMIC_COUNTER;
            break;
          default:
            type = NO_COUNTER;
            continue;
        }

        assert(type != NO_COUNTER, "check");
        uint16_t data = mach->barrier_data();
        if (data != 0) {
          if (data & ZBarrierStrong) {
            _elision_counter[type].barrier_strong++;
          }
          if (data & ZBarrierWeak) {
            _elision_counter[type].barrier_weak++;
          }
          if (data & ZBarrierPhantom) {
            _elision_counter[type].barrier_phantom++;
          }
          if (data & ZBarrierNoKeepalive) {
            _elision_counter[type].barrier_nokeepalive++;
          }
          if (data & ZBarrierNative) {
            _elision_counter[type].barrier_native++;
          }
          if (data & ZBarrierElided) {
            _elision_counter[type].barrier_elided++;
            if (data & ZBarrierDomElided) {
              _elision_counter[type].barrier_dom_elided++;
            } else if (data & ZBarrierSABElided) {
              _elision_counter[type].barrier_sab_elided++;
            } else {
              _elision_counter[type].barrier_triv_elided++;
            }
          } else {
            assert((data & ZBarrierDomElided) == 0, "must be inclusive with ZBarrierElided");
            assert((data & ZBarrierSABElided) == 0, "must be inclusive with ZBarrierElided");
          }
        }
      }
    }
  }
}

void ZBarrierSetC2::print_stats() const {
  for (int i = 0; i <= ATOMIC_COUNTER; i++) {
    tty->print_cr("%s -----------------------------------", presentation_names[i]);
    tty->print("strong: %i   ", _elision_counter[i].barrier_strong);
    tty->print("weak: %i   ", _elision_counter[i].barrier_weak);
    tty->print("phantom: %i   ", _elision_counter[i].barrier_phantom);
    tty->print("nokeepalive: %i", _elision_counter[i].barrier_nokeepalive);
    tty->print("native: %i", _elision_counter[i].barrier_native);
    tty->cr();
    tty->print_cr("total elided:   %4i (%2.1f%%)", _elision_counter[i].barrier_elided, ((float)_elision_counter[i].barrier_elided / (float)_elision_counter[i].barrier_strong * 100));
    unsigned int triv_elided = _elision_counter[i].barrier_elided - _elision_counter[i].barrier_dom_elided - _elision_counter[i].barrier_sab_elided;
    tty->print_cr("- triv. elided: %4i (%2.1f%%)", triv_elided, ((float)triv_elided / (float)_elision_counter[i].barrier_strong * 100));
    tty->print_cr("- dom elided:   %4i (%2.1f%%)", _elision_counter[i].barrier_dom_elided, ((float)_elision_counter[i].barrier_dom_elided / (float)_elision_counter[i].barrier_strong * 100));
    tty->print_cr("- sab elided:   %4i (%2.1f%%)", _elision_counter[i].barrier_sab_elided, ((float)_elision_counter[i].barrier_sab_elided / (float)_elision_counter[i].barrier_strong * 100));
    tty->cr();
  }
  tty->print_cr("Null checks -----------------------------------");
  tty->print_cr("Elided after load barrier: %i (%2.1f%%)", _elided_zf, ((float)_elided_zf / (float)_elision_counter[LOAD_COUNTER].barrier_strong * 100));
}

#ifndef PRODUCT
void ZBarrierSetC2::dump_barrier_data(const MachNode* mach, outputStream *st) const {
  if (mach->has_barrier_flag(ZBarrierStrong)) {
    st->print("strong ");
  }
  if (mach->has_barrier_flag(ZBarrierWeak)) {
    st->print("weak ");
  }
  if (mach->has_barrier_flag(ZBarrierPhantom)) {
    st->print("phantom ");
  }
  if (mach->has_barrier_flag(ZBarrierNoKeepalive)) {
    st->print("nokeepalive ");
  }
  if (mach->has_barrier_flag(ZBarrierNative)) {
    st->print("native ");
  }
  if (mach->has_barrier_flag(ZBarrierElided)) {
    st->print("elided ");
  }
  if (mach->has_barrier_flag(ZBarrierDomElided)) {
    st->print("dom ");
  }
  if (mach->has_barrier_flag(ZBarrierSABElided)) {
    st->print("sab ");
  }
  if (mach->has_barrier_flag(ZBarrierNullCheckRemoval)) {
    st->print("ncremoval ");
  }
}
void ZBarrierSetC2::dump_access_info(const Node* node, outputStream *st) const {
  if (node->is_MachSafePoint() && !node->is_MachCallLeaf()) {
    st->print("access(safepoint)");
    return;
  }
  // TODO: this is just copied from analyze_dominating_barriers(), extract into
  // a set of predicate functions (is_load(), is_store(), is_atomic(), etc.).
  if (node->is_Phi() && is_allocation(node) && ! is_array_allocation(node)) {
    st->print("access(allocation)");
    return;
  }
  if (!node->is_Mach()) {
    return;
  }
  MachNode* const mach = node->as_Mach();
  switch (mach->ideal_Opcode()) {
  case Op_LoadP:
    if (mach->has_barrier_flag(ZBarrierStrong) &&
        !mach->has_barrier_flag(ZBarrierNoKeepalive)) {
      st->print("access(load)");
    }
    break;
  case Op_StoreP:
    if (mach->has_barrier_flag(ZBarrierTypeMask)) {
      st->print("access(store)");
    }
    break;
  case Op_CompareAndExchangeP:
  case Op_CompareAndSwapP:
  case Op_GetAndSetP:
    if (mach->has_barrier_flag(ZBarrierTypeMask)) {
      st->print("access(atomic)");
    }
  default:
    break;
  }
}
#endif // !PRODUCT
