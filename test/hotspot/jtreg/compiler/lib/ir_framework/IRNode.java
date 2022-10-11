/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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

package compiler.lib.ir_framework;

import compiler.lib.ir_framework.driver.irmatching.mapping.*;
import compiler.lib.ir_framework.shared.CheckedTestFrameworkException;
import compiler.lib.ir_framework.shared.TestFormat;
import compiler.lib.ir_framework.shared.TestFormatException;
import jdk.test.lib.Platform;
import jdk.test.whitebox.WhiteBox;

import java.util.HashMap;
import java.util.Map;

/**
 * This class specifies IR node placeholder strings (also referred to as just "IR nodes") with mappings to regexes
 * depending on the selected compile phases. The mappings are stored in {@link #IR_NODE_MAPPINGS}. Each IR node
 * placeholder string is mapped to a {@link IRNodeMapEntry} instance defined in
 * {@link compiler.lib.ir_framework.driver.irmatching.mapping}.
 *
 * <p>
 * IR node placeholder strings can be used in {@link IR#failOn()} and/or {@link IR#counts()} attributes to define IR
 * constraint. They usually represent a single or a group of C2 IR nodes.
 *
 * <p>
 * Each IR node placeholder string is accompanied by a static block that defines an IR node placeholder to regex(es)
 * mapping. The IR framework will automatically replace each IR node placeholder string in user defined test with a
 * regex depending on the selected compile phases in {@link IR#phase} and the provided mapping.
 *
 * <p>
 * Each mapping must define a default compile phase which is applied when the user does not explicitly set the
 * {@link IR#phase()} attribute or when directly using {@link CompilePhase#DEFAULT}. In this case, the IR framework
 * falls back on the default compile phase of any {@link IRNodeMapEntry}.
 *
 * <p>
 * The IR framework reports a {@link TestFormatException} if:
 * <ul>
 *     <li><p> A user test specifies a compile phase for which no mapping is defined in this class.</li>
 *     <li><p> An IR node placeholder string is either missing a mapping or does not provide a regex for a specified
 *             compile phase in {@link IR#phase}.
 * </ul>
 *
 * <p>
 * There are two types of IR nodes:
 * <ul>
 *     <li><p>Normal IR nodes: The IR node placeholder string is directly replaced by a default regex.</li>
 *     <li><p>Composite IR nodes:  The IR node placeholder string contains an additional {@link #COMPOSITE_PREFIX}.
 *                                 Using this IR node expects another user provided string in the constraint list of
 *                                 {@link IR#failOn()} and {@link IR#counts()}. They cannot be use as standalone IR nodes.
 *                                 Trying to do so will result in a format violation error.</li>
 * </ul>
 */
public class IRNode {
    /**
     * Prefix for normal IR nodes.
     */
    private static final String PREFIX = "_#";
    /**
     * Prefix for composite IR nodes.
     */
    private static final String COMPOSITE_PREFIX = PREFIX + "C#";
    private static final String POSTFIX = "#_";

    private static final String START = "(\\d+(\\s){2}(";
    private static final String MID = ".*)+(\\s){2}===.*";
    private static final String END = ")";
    private static final String STORE_OF_CLASS_POSTFIX = "(:|\\+)\\S* \\*" + END;
    private static final String LOAD_OF_CLASS_POSTFIX = "(:|\\+)\\S* \\*" + END;

    public static final String IS_REPLACED = "#IS_REPLACED#"; // Is replaced by an additional user-defined string.


    /**
     * IR placeholder string to regex-for-compile-phase map.
     */
    private static final Map<String, IRNodeMapEntry> IR_NODE_MAPPINGS = new HashMap<>();

    /*
     * Start of IR placeholder string definitions followed by a static block defining the regex-for-compile-phase mapping.
     * An IR node placeholder string must start with PREFIX for normal IR nodes or COMPOSITE_PREFIX for composite IR
     * nodes (see class description above).
     *
     * An IR node definition looks like this:
     *
     * public static final String IR_NODE = [PREFIX|COMPOSITE_PREFIX] + "IR_NODE" + POSTFIX;
     * static {
     *    // Define IR_NODE to regex-for-compile-phase mapping. Create a new IRNodeMapEntry object and add it to
     *    // IR_NODE_MAPPINGS. This can be done by using the helper methods defined after all IR node placeholder string
     *    // definitions.
     * }
     */

    public static final String ABS_D = PREFIX + "ABS_D" + POSTFIX;
    static {
        idealIndependentNameRegex(ABS_D, "AbsD");
    }

    public static final String ABS_F = PREFIX + "ABS_F" + POSTFIX;
    static {
        idealIndependentNameRegex(ABS_F, "AbsF");
    }

    public static final String ABS_I = PREFIX + "ABS_I" + POSTFIX;
    static {
        idealIndependentNameRegex(ABS_I, "AbsI");
    }

    public static final String ABS_L = PREFIX + "ABS_L" + POSTFIX;
    static {
        idealIndependentNameRegex(ABS_L, "AbsL");
    }
    public static final String ADD = PREFIX + "ADD" + POSTFIX;
    static {
        idealIndependentNameRegex(ADD, "Add(I|L|F|D|P)");
    }

    public static final String ADD_I = PREFIX + "ADD_I" + POSTFIX;
    static {
        idealIndependentNameRegex(ADD_I, "AddI");
    }

    public static final String ADD_L = PREFIX + "ADD_L" + POSTFIX;
    static {
        idealIndependentNameRegex(ADD_L, "AddL");
    }

    public static final String ADD_VD = PREFIX + "ADD_VD" + POSTFIX;
    static {
        idealIndependentNameRegex(ADD_VD, "AddVD");
    }

    public static final String ADD_VI = PREFIX + "ADD_VI" + POSTFIX;
    static {
        idealIndependentNameRegex(ADD_VI, "AddVI");
    }

    public static final String ALLOC = PREFIX + "ALLOC" + POSTFIX;
    static {
        String idealIndependentRegex = START + "Allocate" + MID + END;
        String optoRegex = "(.*precise .*\\R((.*(?i:mov|xorl|nop|spill).*|\\s*|.*LGHI.*)\\R)*.*(?i:call,static).*wrapper for: _new_instance_Java" + END;
        allocNodes(ALLOC, idealIndependentRegex, optoRegex);
    }

    public static final String ALLOC_OF = COMPOSITE_PREFIX + "ALLOC_OF" + POSTFIX;
    static {
        String regex = "(.*precise .*" + IS_REPLACED + ":.*\\R((.*(?i:mov|xorl|nop|spill).*|\\s*|.*LGHI.*)\\R)*.*(?i:call,static).*wrapper for: _new_instance_Java" + END;
        optoOnly(ALLOC_OF, regex);
    }

    public static final String ALLOC_ARRAY = PREFIX + "ALLOC_ARRAY" + POSTFIX;
    static {
        String idealIndependentRegex = START + "AllocateArray" + MID + END;
        String optoRegex = "(.*precise \\[.*\\R((.*(?i:mov|xor|nop|spill).*|\\s*|.*LGHI.*)\\R)*.*(?i:call,static).*wrapper for: _new_array_Java" + END;
        allocNodes(ALLOC_ARRAY, idealIndependentRegex, optoRegex);
    }

    public static final String ALLOC_ARRAY_OF = COMPOSITE_PREFIX + "ALLOC_ARRAY_OF" + POSTFIX;
    static {
        String regex = "(.*precise \\[.*" + IS_REPLACED + ":.*\\R((.*(?i:mov|xorl|nop|spill).*|\\s*|.*LGHI.*)\\R)*.*(?i:call,static).*wrapper for: _new_array_Java" + END;
        optoOnly(ALLOC_ARRAY_OF, regex);
    }

    public static final String AND = PREFIX + "AND" + POSTFIX;
    static {
        idealIndependentNameRegex(AND, "And(I|L)");
    }

    public static final String AND_I = PREFIX + "AND_I" + POSTFIX;
    static {
        idealIndependentNameRegex(AND_I, "AndI");
    }

    public static final String AND_L = PREFIX + "AND_L" + POSTFIX;
    static {
        idealIndependentNameRegex(AND_L, "AndL");
    }

    public static final String AND_V = PREFIX + "AND_V" + POSTFIX;
    static {
        idealIndependentNameRegex(AND_V, "AndV");
    }

    public static final String AND_V_MASK = PREFIX + "AND_V_MASK" + POSTFIX;
    static {
        idealIndependentNameRegex(AND_V_MASK, "AndVMask");
    }

    public static final String CALL = PREFIX + "CALL" + POSTFIX;
    static {
        idealIndependentNameRegex(CALL, "Call.*Java");
    }

    public static final String CALL_OF_METHOD = COMPOSITE_PREFIX + "CALL_OF_METHOD" + POSTFIX;
    static {
        callOfNodes(CALL_OF_METHOD, "Call.*Java");
    }

    public static final String STATIC_CALL_OF_METHOD = COMPOSITE_PREFIX + "STATIC_CALL_OF_METHOD" + POSTFIX;
    static {
        callOfNodes(STATIC_CALL_OF_METHOD, "CallStaticJava");
    }

    public static final String CAST_II = PREFIX + "CAST_II" + POSTFIX;
    static {
        idealIndependentNameRegex(CAST_II, "CastII");
    }

    public static final String CAST_LL = PREFIX + "CAST_LL" + POSTFIX;
    static {
        idealIndependentNameRegex(CAST_LL, "CastLL");
    }

    public static final String CHECKCAST_ARRAY = PREFIX + "CHECKCAST_ARRAY" + POSTFIX;
    static {
        String regex = "(((?i:cmp|CLFI|CLR).*precise \\[.*:|.*(?i:mov|or).*precise \\[.*:.*\\R.*(cmp|CMP|CLR))" + END;
        optoOnly(CHECKCAST_ARRAY, regex);
    }

    public static final String CHECKCAST_ARRAY_OF = COMPOSITE_PREFIX + "CHECKCAST_ARRAY_OF" + POSTFIX;
    static {
        String regex = "(((?i:cmp|CLFI|CLR).*precise \\[.*" + IS_REPLACED + ":|.*(?i:mov|or).*precise \\[.*" + IS_REPLACED + ":.*\\R.*(cmp|CMP|CLR))" + END;
        optoOnly(CHECKCAST_ARRAY_OF, regex);
    }

    // Does not work on s390 (a rule containing this regex will be skipped on s390).
    public static final String CHECKCAST_ARRAYCOPY = PREFIX + "CHECKCAST_ARRAYCOPY" + POSTFIX;
    static {
        String regex = "(.*((?i:call_leaf_nofp,runtime)|CALL,\\s?runtime leaf nofp|BCTRL.*.leaf call).*checkcast_arraycopy.*" + END;
        optoOnly(CHECKCAST_ARRAYCOPY, regex);
    }

    public static final String CLASS_CHECK_TRAP = PREFIX + "CLASS_CHECK_TRAP" + POSTFIX;
    static {
        trapNodes(CLASS_CHECK_TRAP, "class_check");
    }

    public static final String CMOVE_I = PREFIX + "CMOVE_I" + POSTFIX;
    static {
        idealIndependentNameRegex(CMOVE_I, "CMoveI");
    }

    public static final String CMP_I = PREFIX + "CMP_I" + POSTFIX;
    static {
        idealIndependentNameRegex(CMP_I, "CmpI");
    }

    public static final String CMP_L = PREFIX + "CMP_L" + POSTFIX;
    static {
        idealIndependentNameRegex(CMP_L, "CmpL");
    }

    public static final String CMP_U = PREFIX + "CMP_U" + POSTFIX;
    static {
        idealIndependentNameRegex(CMP_U, "CmpU");
    }

    public static final String CMP_U3 = PREFIX + "CMP_U3" + POSTFIX;
    static {
        idealIndependentNameRegex(CMP_U3, "CmpU3");
    }

    public static final String CMP_UL = PREFIX + "CMP_UL" + POSTFIX;
    static {
        idealIndependentNameRegex(CMP_UL, "CmpUL");
    }

    public static final String CMP_UL3 = PREFIX + "CMP_UL3" + POSTFIX;
    static {
        idealIndependentNameRegex(CMP_UL3, "CmpUL3");
    }

    public static final String COMPRESS_BITS = PREFIX + "COMPRESS_BITS" + POSTFIX;
    static {
        idealIndependentNameRegex(COMPRESS_BITS, "CompressBits");
    }

    public static final String CONV_I2L = PREFIX + "CONV_I2L" + POSTFIX;
    static {
        idealIndependentNameRegex(CONV_I2L, "ConvI2L");
    }

    public static final String CONV_L2I = PREFIX + "CONV_L2I" + POSTFIX;
    static {
        idealIndependentNameRegex(CONV_L2I, "ConvL2I");
    }

    public static final String CON_I = PREFIX + "CON_I" + POSTFIX;
    static {
        idealIndependentNameRegex(CON_I, "ConI");
    }

    public static final String CON_L = PREFIX + "CON_L" + POSTFIX;
    static {
        idealIndependentNameRegex(CON_L, "ConL");
    }

    public static final String COUNTED_LOOP = PREFIX + "COUNTED_LOOP" + POSTFIX;
    static {
        String regex = START + "CountedLoop\\b" + MID + END;
        fromAfterCountedLoops(COUNTED_LOOP, regex);
    }

    public static final String COUNTED_LOOP_MAIN = PREFIX + "COUNTED_LOOP_MAIN" + POSTFIX;
    static {
        String regex = START + "CountedLoop\\b" + MID + "main" + END;
        fromAfterCountedLoops(COUNTED_LOOP_MAIN, regex);
    }

    public static final String DIV = PREFIX + "DIV" + POSTFIX;
    static {
        idealIndependentNameRegex(DIV, "Div(I|L|F|D)");
    }

    public static final String DIV_BY_ZERO_TRAP = PREFIX + "DIV_BY_ZERO_TRAP" + POSTFIX;
    static {
        trapNodes(DIV_BY_ZERO_TRAP, "div0_check");
    }

    public static final String DIV_L = PREFIX + "DIV_L" + POSTFIX;
    static {
        idealIndependentNameRegex(DIV_L, "DivL");
    }

    public static final String DYNAMIC_CALL_OF_METHOD = COMPOSITE_PREFIX + "DYNAMIC_CALL_OF_METHOD" + POSTFIX;
    static {
        callOfNodes(DYNAMIC_CALL_OF_METHOD, "CallDynamicJava");
    }

    public static final String EXPAND_BITS = PREFIX + "EXPAND_BITS" + POSTFIX;
    static {
        idealIndependentNameRegex(EXPAND_BITS, "ExpandBits");
    }

    public static final String FAST_LOCK = PREFIX + "FAST_LOCK" + POSTFIX;
    static {
        idealIndependentNameRegex(FAST_LOCK, "FastLock");
    }

    public static final String FAST_UNLOCK = PREFIX + "FAST_UNLOCK" + POSTFIX;
    static {
        String regex = START + "FastUnlock" + MID + END;
        fromMacroToBeforeMatching(FAST_UNLOCK, regex);
    }

    public static final String FIELD_ACCESS = PREFIX + "FIELD_ACCESS" + POSTFIX;
    static {
        String regex = "(.*Field: *" + END;
        optoOnly(FIELD_ACCESS, regex);
    }

    public static final String IF = PREFIX + "IF" + POSTFIX;
    static {
        idealIndependentNameRegex(IF, "If\\b");
    }

    // Does not work for VM builds without JVMCI like x86_32 (a rule containing this regex will be skipped without having JVMCI built).
    public static final String INTRINSIC_OR_TYPE_CHECKED_INLINING_TRAP = PREFIX + "INTRINSIC_OR_TYPE_CHECKED_INLINING_TRAP" + POSTFIX;
    static {
        trapNodes(INTRINSIC_OR_TYPE_CHECKED_INLINING_TRAP,"intrinsic_or_type_checked_inlining");
    }

    public static final String INTRINSIC_TRAP = PREFIX + "INTRINSIC_TRAP" + POSTFIX;
    static {
        trapNodes(INTRINSIC_TRAP,"intrinsic");
    }

    public static final String LOAD = PREFIX + "LOAD" + POSTFIX;
    static {
        idealIndependentNameRegex(LOAD, "Load(B|UB|S|US|I|L|F|D|P|N)");
    }

    public static final String LOAD_OF_CLASS = COMPOSITE_PREFIX + "LOAD_OF_CLASS" + POSTFIX;
    static {
        loadOfNodes(LOAD_OF_CLASS, "Load(B|UB|S|US|I|L|F|D|P|N)");
    }

    public static final String LOAD_B = PREFIX + "LOAD_B" + POSTFIX;
    static {
        idealIndependentNameRegex(LOAD_B, "LoadB");
    }

    public static final String LOAD_B_OF_CLASS = COMPOSITE_PREFIX + "LOAD_B_OF_CLASS" + POSTFIX;
    static {
        loadOfNodes(LOAD_B_OF_CLASS, "LoadB");
    }

    public static final String LOAD_D = PREFIX + "LOAD_D" + POSTFIX;
    static {
        idealIndependentNameRegex(LOAD_D, "LoadD");
    }

    public static final String LOAD_D_OF_CLASS = COMPOSITE_PREFIX + "LOAD_D_OF_CLASS" + POSTFIX;
    static {
        loadOfNodes(LOAD_D_OF_CLASS, "LoadD");
    }

    public static final String LOAD_F = PREFIX + "LOAD_F" + POSTFIX;
    static {
        idealIndependentNameRegex(LOAD_F, "LoadF");
    }

    public static final String LOAD_F_OF_CLASS = COMPOSITE_PREFIX + "LOAD_F_OF_CLASS" + POSTFIX;
    static {
        loadOfNodes(LOAD_F_OF_CLASS, "LoadF");
    }

    public static final String LOAD_I = PREFIX + "LOAD_I" + POSTFIX;
    static {
        idealIndependentNameRegex(LOAD_I, "LoadI");
    }

    public static final String LOAD_I_OF_CLASS = COMPOSITE_PREFIX + "LOAD_I_OF_CLASS" + POSTFIX;
    static {
        loadOfNodes(LOAD_I_OF_CLASS, "LoadI");
    }

    public static final String LOAD_KLASS = PREFIX + "LOAD_KLASS" + POSTFIX;
    static {
        idealIndependentNameRegex(LOAD_KLASS, "LoadKlass");
    }

    public static final String LOAD_L = PREFIX + "LOAD_L" + POSTFIX;
    static {
        idealIndependentNameRegex(LOAD_L, "LoadL");
    }

    public static final String LOAD_L_OF_CLASS = COMPOSITE_PREFIX + "LOAD_L_OF_CLASS" + POSTFIX;
    static {
        loadOfNodes(LOAD_L_OF_CLASS, "LoadL");
    }

    public static final String LOAD_N = PREFIX + "LOAD_N" + POSTFIX;
    static {
        idealIndependentNameRegex(LOAD_N, "LoadN");
    }

    public static final String LOAD_N_OF_CLASS = COMPOSITE_PREFIX + "LOAD_N_OF_CLASS" + POSTFIX;
    static {
        loadOfNodes(LOAD_N_OF_CLASS, "LoadN");
    }

    public static final String LOAD_OF_FIELD = COMPOSITE_PREFIX + "LOAD_OF_FIELD" + POSTFIX;
    static {
        String regex = START + "Load(B|C|S|I|L|F|D|P|N)" + MID + "@.*name=" + IS_REPLACED + ",.*" + END;
        idealIndependentOnly(LOAD_OF_FIELD, regex);
    }

    public static final String LOAD_P = PREFIX + "LOAD_P" + POSTFIX;
    static {
        idealIndependentNameRegex(LOAD_P, "LoadP");
    }

    public static final String LOAD_P_OF_CLASS = COMPOSITE_PREFIX + "LOAD_P_OF_CLASS" + POSTFIX;
    static {
        loadOfNodes(LOAD_P_OF_CLASS, "LoadP");
    }

    public static final String LOAD_S = PREFIX + "LOAD_S" + POSTFIX;
    static {
        idealIndependentNameRegex(LOAD_S, "LoadS");
    }

    public static final String LOAD_S_OF_CLASS = COMPOSITE_PREFIX + "LOAD_S_OF_CLASS" + POSTFIX;
    static {
        loadOfNodes(LOAD_S_OF_CLASS, "LoadS");
    }

    public static final String LOAD_UB = PREFIX + "LOAD_UB" + POSTFIX;
    static {
        idealIndependentNameRegex(LOAD_UB, "LoadUB");
    }

    public static final String LOAD_UB_OF_CLASS = COMPOSITE_PREFIX + "LOAD_UB_OF_CLASS" + POSTFIX;
    static {
        loadOfNodes(LOAD_UB_OF_CLASS, "LoadUB");
    }

    public static final String LOAD_US = PREFIX + "LOAD_US" + POSTFIX;
    static {
        idealIndependentNameRegex(LOAD_US, "LoadUS");
    }

    public static final String LOAD_US_OF_CLASS = COMPOSITE_PREFIX + "LOAD_US_OF_CLASS" + POSTFIX;
    static {
        loadOfNodes(LOAD_US_OF_CLASS, "LoadUS");
    }

    public static final String LOAD_VECTOR = PREFIX + "LOAD_VECTOR" + POSTFIX;
    static {
        idealIndependentNameRegex(LOAD_VECTOR, "LoadVector");
    }

    public static final String LONG_COUNTED_LOOP = PREFIX + "LONG_COUNTED_LOOP" + POSTFIX;
    static {
        String regex = START + "LongCountedLoop\\b" + MID + END;
        fromAfterCountedLoops(LONG_COUNTED_LOOP, regex);
    }

    public static final String LOOP = PREFIX + "LOOP" + POSTFIX;
    static {
        String regex = START + "Loop" + MID + END;
        fromBeforeCountedLoops(LOOP, regex);
    }

    public static final String LSHIFT = PREFIX + "LSHIFT" + POSTFIX;
    static {
        idealIndependentNameRegex(LSHIFT, "LShift(I|L)");
    }

    public static final String LSHIFT_I = PREFIX + "LSHIFT_I" + POSTFIX;
    static {
        idealIndependentNameRegex(LSHIFT_I, "LShiftI");
    }

    public static final String LSHIFT_L = PREFIX + "LSHIFT_L" + POSTFIX;
    static {
        idealIndependentNameRegex(LSHIFT_L, "LShiftL");
    }

    public static final String MAX_I = PREFIX + "MAX_I" + POSTFIX;
    static {
        idealIndependentNameRegex(MAX_I, "MaxI");
    }

    public static final String MAX_V = PREFIX + "MAX_V" + POSTFIX;
    static {
        idealIndependentNameRegex(MAX_V, "MaxV");
    }

    public static final String MEMBAR = PREFIX + "MEMBAR" + POSTFIX;
    static {
        idealIndependentNameRegex(MEMBAR, "MemBar");
    }

    public static final String MEMBAR_STORESTORE = PREFIX + "MEMBAR_STORESTORE" + POSTFIX;
    static {
        idealIndependentNameRegex(MEMBAR_STORESTORE, "MemBarStoreStore");
    }

    public static final String MIN_I = PREFIX + "MIN_I" + POSTFIX;
    static {
        idealIndependentNameRegex(MIN_I, "MinI");
    }

    public static final String MIN_V = PREFIX + "MIN_V" + POSTFIX;
    static {
        idealIndependentNameRegex(MIN_V, "MinV");
    }

    public static final String MUL = PREFIX + "MUL" + POSTFIX;
    static {
        idealIndependentNameRegex(MUL, "Mul(I|L|F|D)");
    }

    public static final String MUL_D = PREFIX + "MUL_D" + POSTFIX;
    static {
        idealIndependentNameRegex(MUL_D, "MulD");
    }

    public static final String MUL_F = PREFIX + "MUL_F" + POSTFIX;
    static {
        idealIndependentNameRegex(MUL_F, "MulF");
    }

    public static final String MUL_I = PREFIX + "MUL_I" + POSTFIX;
    static {
        idealIndependentNameRegex(MUL_I, "MulI");
    }

    public static final String MUL_L = PREFIX + "MUL_L" + POSTFIX;
    static {
        idealIndependentNameRegex(MUL_L, "MulL");
    }

    public static final String NULL_ASSERT_TRAP = PREFIX + "NULL_ASSERT_TRAP" + POSTFIX;
    static {
        trapNodes(NULL_ASSERT_TRAP,"null_assert");
    }

    public static final String NULL_CHECK_TRAP = PREFIX + "NULL_CHECK_TRAP" + POSTFIX;
    static {
        trapNodes(NULL_CHECK_TRAP,"null_check");
    }

    public static final String OR_V = PREFIX + "OR_V" + POSTFIX;
    static {
        idealIndependentNameRegex(OR_V, "OrV");
    }

    public static final String OR_V_MASK = PREFIX + "OR_V_MASK" + POSTFIX;
    static {
        idealIndependentNameRegex(OR_V_MASK, "OrVMask");
    }

    public static final String OUTER_STRIP_MINED_LOOP = PREFIX + "OUTER_STRIP_MINED_LOOP" + POSTFIX;
    static {
        String regex = START + "OuterStripMinedLoop\\b" + MID + END;
        fromAfterCountedLoops(OUTER_STRIP_MINED_LOOP, regex);
    }

    public static final String PHI = PREFIX + "PHI" + POSTFIX;
    static {
        idealIndependentNameRegex(PHI, "Phi");
    }

    public static final String POPCOUNT_L = PREFIX + "POPCOUNT_L" + POSTFIX;
    static {
        idealIndependentNameRegex(POPCOUNT_L, "PopCountL");
    }

    public static final String POPULATE_INDEX = PREFIX + "POPULATE_INDEX" + POSTFIX;
    static {
        String regex = START + "PopulateIndex" + MID + END;
        IR_NODE_MAPPINGS.put(POPULATE_INDEX, new SinglePhaseRangeEntry(CompilePhase.PRINT_IDEAL, regex,
                                                                       CompilePhase.AFTER_CLOOPS,
                                                                       CompilePhase.BEFORE_MATCHING));
    }

    public static final String PREDICATE_TRAP = PREFIX + "PREDICATE_TRAP" + POSTFIX;
    static {
        trapNodes(PREDICATE_TRAP,"predicate");
    }

    public static final String RANGE_CHECK_TRAP = PREFIX + "RANGE_CHECK_TRAP" + POSTFIX;
    static {
        trapNodes(RANGE_CHECK_TRAP,"range_check");
    }

    public static final String REVERSE_BYTES_V = PREFIX + "REVERSE_BYTES_V" + POSTFIX;
    static {
        idealIndependentNameRegex(REVERSE_BYTES_V, "ReverseBytesV");
    }

    public static final String REVERSE_I = PREFIX + "REVERSE_I" + POSTFIX;
    static {
        idealIndependentNameRegex(REVERSE_I, "ReverseI");
    }

    public static final String REVERSE_L = PREFIX + "REVERSE_L" + POSTFIX;
    static {
        idealIndependentNameRegex(REVERSE_L, "ReverseL");
    }

    public static final String REVERSE_V = PREFIX + "REVERSE_V" + POSTFIX;
    static {
        idealIndependentNameRegex(REVERSE_V, "ReverseV");
    }

    public static final String ROUND_VD = PREFIX + "ROUND_VD" + POSTFIX;
    static {
        idealIndependentNameRegex(ROUND_VD, "RoundVD");
    }

    public static final String ROUND_VF = PREFIX + "ROUND_VF" + POSTFIX;
    static {
        idealIndependentNameRegex(ROUND_VF, "RoundVF");
    }

    public static final String RSHIFT = PREFIX + "RSHIFT" + POSTFIX;
    static {
        idealIndependentNameRegex(RSHIFT, "RShift(I|L)");
    }

    public static final String RSHIFT_I = PREFIX + "RSHIFT_I" + POSTFIX;
    static {
        idealIndependentNameRegex(RSHIFT_I, "RShiftI");
    }

    public static final String RSHIFT_L = PREFIX + "RSHIFT_L" + POSTFIX;
    static {
        idealIndependentNameRegex(RSHIFT_L, "RShiftL");
    }

    public static final String RSHIFT_VB = PREFIX + "RSHIFT_VB" + POSTFIX;
    static {
        idealIndependentNameRegex(RSHIFT_VB, "RShiftVB");
    }

    public static final String RSHIFT_VS = PREFIX + "RSHIFT_VS" + POSTFIX;
    static {
        idealIndependentNameRegex(RSHIFT_VS, "RShiftVS");
    }

    public static final String SAFEPOINT = PREFIX + "SAFEPOINT" + POSTFIX;
    static {
        idealIndependentNameRegex(SAFEPOINT, "SafePoint");
    }

    public static final String SCOPE_OBJECT = PREFIX + "SCOPE_OBJECT" + POSTFIX;
    static {
        String regex = "(.*# ScObj.*" + END;
        optoOnly(SCOPE_OBJECT, regex);
    }

    public static final String SIGNUM_VD = PREFIX + "SIGNUM_VD" + POSTFIX;
    static {
        idealIndependentNameRegex(SIGNUM_VD, "SignumVD");
    }

    public static final String SIGNUM_VF = PREFIX + "SIGNUM_VF" + POSTFIX;
    static {
        idealIndependentNameRegex(SIGNUM_VF, "SignumVF");
    }

    public static final String STORE = PREFIX + "STORE" + POSTFIX;
    static {
        idealIndependentNameRegex(STORE, "Store(B|C|S|I|L|F|D|P|N)");
    }

    public static final String STORE_B = PREFIX + "STORE_B" + POSTFIX;
    static {
        idealIndependentNameRegex(STORE_B, "StoreB");
    }

    public static final String STORE_B_OF_CLASS = COMPOSITE_PREFIX + "STORE_B_OF_CLASS" + POSTFIX;
    static {
        storeOfNodes(STORE_B_OF_CLASS, "StoreB");
    }

    public static final String STORE_C = PREFIX + "STORE_C" + POSTFIX;
    static {
        idealIndependentNameRegex(STORE_C, "StoreC");
    }

    public static final String STORE_C_OF_CLASS = COMPOSITE_PREFIX + "STORE_C_OF_CLASS" + POSTFIX;
    static {
        storeOfNodes(STORE_C_OF_CLASS, "StoreC");
    }

    public static final String STORE_D = PREFIX + "STORE_D" + POSTFIX;
    static {
        idealIndependentNameRegex(STORE_D, "StoreD");
    }

    public static final String STORE_D_OF_CLASS = COMPOSITE_PREFIX + "STORE_D_OF_CLASS" + POSTFIX;
    static {
        storeOfNodes(STORE_D_OF_CLASS, "StoreD");
    }

    public static final String STORE_F = PREFIX + "STORE_F" + POSTFIX;
    static {
        idealIndependentNameRegex(STORE_F, "StoreF");
    }

    public static final String STORE_F_OF_CLASS = COMPOSITE_PREFIX + "STORE_F_OF_CLASS" + POSTFIX;
    static {
        storeOfNodes(STORE_F_OF_CLASS, "StoreF");
    }

    public static final String STORE_I = PREFIX + "STORE_I" + POSTFIX;
    static {
        idealIndependentNameRegex(STORE_I, "StoreI");
    }

    public static final String STORE_I_OF_CLASS = COMPOSITE_PREFIX + "STORE_I_OF_CLASS" + POSTFIX;
    static {
        storeOfNodes(STORE_I_OF_CLASS, "StoreI");
    }

    public static final String STORE_L = PREFIX + "STORE_L" + POSTFIX;
    static {
        idealIndependentNameRegex(STORE_L, "StoreL");
    }

    public static final String STORE_L_OF_CLASS = COMPOSITE_PREFIX + "STORE_L_OF_CLASS" + POSTFIX;
    static {
        storeOfNodes(STORE_L_OF_CLASS, "StoreL");
    }

    public static final String STORE_N = PREFIX + "STORE_N" + POSTFIX;
    static {
        idealIndependentNameRegex(STORE_N, "StoreN");
    }

    public static final String STORE_N_OF_CLASS = COMPOSITE_PREFIX + "STORE_N_OF_CLASS" + POSTFIX;
    static {
        storeOfNodes(STORE_N_OF_CLASS, "StoreN");
    }

    public static final String STORE_OF_CLASS = COMPOSITE_PREFIX + "STORE_OF_CLASS" + POSTFIX;
    static {
        storeOfNodes(STORE_OF_CLASS, "Store(B|C|S|I|L|F|D|P|N)");
    }

    public static final String STORE_OF_FIELD = COMPOSITE_PREFIX + "STORE_OF_FIELD" + POSTFIX;
    static {
        String regex = START + "Store(B|C|S|I|L|F|D|P|N)" + MID + "@.*name=" + IS_REPLACED + ",.*" + END;
        idealIndependentOnly(STORE_OF_FIELD, regex);
    }

    public static final String STORE_P = PREFIX + "STORE_P" + POSTFIX;
    static {
        idealIndependentNameRegex(STORE_P, "StoreP");
    }

    public static final String STORE_P_OF_CLASS = COMPOSITE_PREFIX + "STORE_P_OF_CLASS" + POSTFIX;
    static {
        storeOfNodes(STORE_P_OF_CLASS, "StoreP");
    }

    public static final String STORE_VECTOR = PREFIX + "STORE_VECTOR" + POSTFIX;
    static {
        idealIndependentNameRegex(STORE_VECTOR, "StoreVector");
    }

    public static final String SUB = PREFIX + "SUB" + POSTFIX;
    static {
        idealIndependentNameRegex(SUB, "Sub(I|L|F|D)");
    }

    public static final String SUB_D = PREFIX + "SUB_D" + POSTFIX;
    static {
        idealIndependentNameRegex(SUB_D, "SubD");
    }

    public static final String SUB_F = PREFIX + "SUB_F" + POSTFIX;
    static {
        idealIndependentNameRegex(SUB_F, "SubF");
    }

    public static final String SUB_I = PREFIX + "SUB_I" + POSTFIX;
    static {
        idealIndependentNameRegex(SUB_I, "SubI");
    }

    public static final String SUB_L = PREFIX + "SUB_L" + POSTFIX;
    static {
        idealIndependentNameRegex(SUB_L, "SubL");
    }

    public static final String TRAP = PREFIX + "TRAP" + POSTFIX;
    static {
        trapNodes(TRAP,"reason");
    }

    public static final String UDIV_I = PREFIX + "UDIV_I" + POSTFIX;
    static {
        idealIndependentNameRegex(UDIV_I, "UDivI");
    }

    public static final String UDIV_L = PREFIX + "UDIV_L" + POSTFIX;
    static {
        idealIndependentNameRegex(UDIV_L, "UDivL");
    }

    public static final String UDIV_MOD_I = PREFIX + "UDIV_MOD_I" + POSTFIX;
    static {
        idealIndependentNameRegex(UDIV_MOD_I, "UDivModI");
    }

    public static final String UDIV_MOD_L = PREFIX + "UDIV_MOD_L" + POSTFIX;
    static {
        idealIndependentNameRegex(UDIV_MOD_L, "UDivModL");
    }

    public static final String UMOD_I = PREFIX + "UMOD_I" + POSTFIX;
    static {
        idealIndependentNameRegex(UMOD_I, "UModI");
    }

    public static final String UMOD_L = PREFIX + "UMOD_L" + POSTFIX;
    static {
        idealIndependentNameRegex(UMOD_L, "UModL");
    }

    public static final String UNHANDLED_TRAP = PREFIX + "UNHANDLED_TRAP" + POSTFIX;
    static {
        trapNodes(UNHANDLED_TRAP,"unhandled");
    }

    public static final String UNSTABLE_IF_TRAP = PREFIX + "UNSTABLE_IF_TRAP" + POSTFIX;
    static {
        trapNodes(UNSTABLE_IF_TRAP,"unstable_if");
    }

    public static final String URSHIFT = PREFIX + "URSHIFT" + POSTFIX;
    static {
        idealIndependentNameRegex(URSHIFT, "URShift(B|S|I|L)");
    }

    public static final String URSHIFT_B = PREFIX + "URSHIFT_B" + POSTFIX;
    static {
        idealIndependentNameRegex(URSHIFT_B, "URShiftB");
    }

    public static final String URSHIFT_I = PREFIX + "URSHIFT_I" + POSTFIX;
    static {
        idealIndependentNameRegex(URSHIFT_I, "URShiftI");
    }

    public static final String URSHIFT_L = PREFIX + "URSHIFT_L" + POSTFIX;
    static {
        idealIndependentNameRegex(URSHIFT_L, "URShiftL");
    }

    public static final String URSHIFT_S = PREFIX + "URSHIFT_S" + POSTFIX;
    static {
        idealIndependentNameRegex(URSHIFT_S, "URShiftS");
    }

    public static final String VECTOR_BLEND = PREFIX + "VECTOR_BLEND" + POSTFIX;
    static {
        idealIndependentNameRegex(VECTOR_BLEND, "VectorBlend");
    }

    public static final String VECTOR_CAST_B2X = PREFIX + "VECTOR_CAST_B2X" + POSTFIX;
    static {
        idealIndependentNameRegex(VECTOR_CAST_B2X, "VectorCastB2X");
    }

    public static final String VECTOR_CAST_D2X = PREFIX + "VECTOR_CAST_D2X" + POSTFIX;
    static {
        idealIndependentNameRegex(VECTOR_CAST_D2X, "VectorCastD2X");
    }

    public static final String VECTOR_CAST_F2X = PREFIX + "VECTOR_CAST_F2X" + POSTFIX;
    static {
        idealIndependentNameRegex(VECTOR_CAST_F2X, "VectorCastF2X");
    }

    public static final String VECTOR_CAST_I2X = PREFIX + "VECTOR_CAST_I2X" + POSTFIX;
    static {
        idealIndependentNameRegex(VECTOR_CAST_I2X, "VectorCastI2X");
    }

    public static final String VECTOR_CAST_L2X = PREFIX + "VECTOR_CAST_L2X" + POSTFIX;
    static {
        idealIndependentNameRegex(VECTOR_CAST_L2X, "VectorCastL2X");
    }

    public static final String VECTOR_CAST_S2X = PREFIX + "VECTOR_CAST_S2X" + POSTFIX;
    static {
        idealIndependentNameRegex(VECTOR_CAST_S2X, "VectorCastS2X");
    }

    public static final String VECTOR_REINTERPRET = PREFIX + "VECTOR_REINTERPRET" + POSTFIX;
    static {
        idealIndependentNameRegex(VECTOR_REINTERPRET, "VectorReinterpret");
    }

    public static final String VECTOR_UCAST_B2X = PREFIX + "VECTOR_UCAST_B2X" + POSTFIX;
    static {
        idealIndependentNameRegex(VECTOR_UCAST_B2X, "VectorUCastB2X");
    }

    public static final String VECTOR_UCAST_I2X = PREFIX + "VECTOR_UCAST_I2X" + POSTFIX;
    static {
        idealIndependentNameRegex(VECTOR_UCAST_I2X, "VectorUCastI2X");
    }

    public static final String VECTOR_UCAST_S2X = PREFIX + "VECTOR_UCAST_S2X" + POSTFIX;
    static {
        idealIndependentNameRegex(VECTOR_UCAST_S2X, "VectorUCastS2X");
    }

    public static final String XOR_I = PREFIX + "XOR_I" + POSTFIX;
    static {
        idealIndependentNameRegex(XOR_I, "XorI");
    }

    public static final String XOR_L = PREFIX + "XOR_L" + POSTFIX;
    static {
        idealIndependentNameRegex(XOR_L, "XorL");
    }

    public static final String XOR_V = PREFIX + "XOR_V" + POSTFIX;
    static {
        idealIndependentNameRegex(XOR_V, "XorV");
    }

    public static final String XOR_V_MASK = PREFIX + "XOR_V_MASK" + POSTFIX;
    static {
        idealIndependentNameRegex(XOR_V_MASK, "XorVMask");
    }

    public static final String ZLOADP_WITH_BARRIER_FLAG = COMPOSITE_PREFIX + "ZLOADP_WITH_BARRIER_FLAG" + POSTFIX;
    static {
        String regex = START + "zLoadP" + MID + "barrier\\(\\s*" + IS_REPLACED + "\\s*\\)" + END;
        machOnly(ZLOADP_WITH_BARRIER_FLAG, regex);
    }

    public static final String ZSTOREP_WITH_BARRIER_FLAG = COMPOSITE_PREFIX + "ZSTOREP_WITH_BARRIER_FLAG" + POSTFIX;
    static {
        String regex = START + "zStoreP" + MID + "barrier\\(\\s*" + IS_REPLACED + "\\s*\\)" + END;
        machOnly(ZSTOREP_WITH_BARRIER_FLAG, regex);
    }

    /*
     * Utility methods to set up IR_NODE_MAPPINGS.
     */

    static void allocNodes(String irNode, String idealRegex, String optoRegex) {
        Map<PhaseInterval, String> intervalToRegexMap = new HashMap<>();
        intervalToRegexMap.put(new PhaseInterval(CompilePhase.BEFORE_REMOVEUSELESS, CompilePhase.PHASEIDEALLOOP_ITERATIONS),
                               idealRegex);
        intervalToRegexMap.put(new PhaseSingletonSet(CompilePhase.PRINT_OPTO_ASSEMBLY), optoRegex);
        MultiPhaseRangeEntry entry = new MultiPhaseRangeEntry(CompilePhase.PRINT_OPTO_ASSEMBLY, intervalToRegexMap);
        IR_NODE_MAPPINGS.put(irNode, entry);
    }

    static void idealIndependentNameRegex(String irNodePlaceholder, String irNodeRegex) {
        String regex = START + irNodeRegex + MID + END;
        IR_NODE_MAPPINGS.put(irNodePlaceholder, new IdealIndependentEntry(CompilePhase.PRINT_IDEAL, regex));
    }

    static void callOfNodes(String irNodePlaceholder, String callRegex) {
        String regex = START + callRegex + MID + IS_REPLACED + " " +  END;
        IR_NODE_MAPPINGS.put(irNodePlaceholder, new IdealIndependentEntry(CompilePhase.PRINT_IDEAL, regex));
    }

    static void idealIndependentOnly(String irNodePlaceholder, String regex) {
        IR_NODE_MAPPINGS.put(irNodePlaceholder, new IdealIndependentEntry(CompilePhase.PRINT_IDEAL, regex));
    }

    static void optoOnly(String irNodePlaceholder, String regex) {
        IR_NODE_MAPPINGS.put(irNodePlaceholder, new SinglePhaseEntry(CompilePhase.PRINT_OPTO_ASSEMBLY, regex));
    }

    static void machOnly(String irNodePlaceholder, String regex) {
        IR_NODE_MAPPINGS.put(irNodePlaceholder, new MachOnlyEntry(CompilePhase.FINAL_CODE, regex));
    }

    static void fromAfterCountedLoops(String irNodePlaceholder, String regex) {
        IR_NODE_MAPPINGS.put(irNodePlaceholder, new SinglePhaseRangeEntry(CompilePhase.PRINT_IDEAL, regex,
                                                                          CompilePhase.AFTER_CLOOPS,
                                                                          CompilePhase.FINAL_CODE));
    }

    static void fromBeforeCountedLoops(String irNodePlaceholder, String regex) {
        IR_NODE_MAPPINGS.put(irNodePlaceholder, new SinglePhaseRangeEntry(CompilePhase.PRINT_IDEAL, regex,
                                                                          CompilePhase.BEFORE_CLOOPS,
                                                                          CompilePhase.FINAL_CODE));
    }

    static void fromMacroToBeforeMatching(String irNodePlaceholder, String regex) {
        IR_NODE_MAPPINGS.put(irNodePlaceholder, new SinglePhaseRangeEntry(CompilePhase.PRINT_IDEAL, regex,
                                                                          CompilePhase.MACRO_EXPANSION,
                                                                          CompilePhase.BEFORE_MATCHING));
    }

    static void trapNodes(String irNodePlaceholder, String trapReason) {
        String regex = START + "CallStaticJava" + MID + "uncommon_trap.*" + trapReason + END;
        idealIndependentOnly(irNodePlaceholder, regex);
    }

    static void loadOfNodes(String irNodePlaceholder, String irNodeRegex) {
        String regex = START + irNodeRegex + MID + "@\\S*" + IS_REPLACED + LOAD_OF_CLASS_POSTFIX;
        idealIndependentOnly(irNodePlaceholder, regex);
    }

    static void storeOfNodes(String irNodePlaceholder, String irNodeRegex) {
        String regex = START + irNodeRegex + MID + "@\\S*" + IS_REPLACED + STORE_OF_CLASS_POSTFIX;
        idealIndependentOnly(irNodePlaceholder, regex);
    }


    /*
     * Methods used internally by the IR framework.
     */

    public static boolean isCompositeIRNode(String node) {
        return node.startsWith(COMPOSITE_PREFIX);
    }

    public static boolean isIRNode(String node) {
        return node.startsWith(PREFIX);
    }

    public static String getCompositeNodeName(String irNodeString) {
        TestFramework.check(irNodeString.length() > COMPOSITE_PREFIX.length() + POSTFIX.length(),
                            "Invalid composite node placeholder: " + irNodeString);
        return irNodeString.substring(COMPOSITE_PREFIX.length(), irNodeString.length() - POSTFIX.length());
    }

    /**
     * Is this IR node supported on current platform, used VM build, etc.?
     * Throws a {@link CheckedTestFrameworkException} if the default regex is unsupported.
     */
    public static void checkDefaultIRNodeSupported(String node) throws CheckedTestFrameworkException {
        switch (node) {
            case INTRINSIC_OR_TYPE_CHECKED_INLINING_TRAP -> {
                if (!WhiteBox.getWhiteBox().isJVMCISupportedByGC()) {
                    throw new CheckedTestFrameworkException("INTRINSIC_OR_TYPE_CHECKED_INLINING_TRAP is unsupported " +
                                                            "in builds without JVMCI.");
                }
            }
            case CHECKCAST_ARRAYCOPY -> {
                if (Platform.isS390x()) {
                    throw new CheckedTestFrameworkException("CHECKCAST_ARRAYCOPY is unsupported on s390.");
                }
            }
            // default: do nothing -> IR node is supported and can be used by the user.
        }
    }

    public static String getRegexForPhaseOfIRNode(String irNode, CompilePhase compilePhase) {
        IRNodeMapEntry entry = IR_NODE_MAPPINGS.get(irNode);
        String failMsg = "IR Node \"" + irNode + "\" defined in class IRNode has no mapping in class IRNodeMappings " +
                         "(i.e. an entry in the constructor of class IRNodeMappings)." + System.lineSeparator() +
                         "   Have you just created the entry \"" + irNode + "\" in class IRNode and forgot to add a " +
                         "mapping in class IRNodeMappings?" + System.lineSeparator() +
                         "   Violation";
        TestFormat.checkNoReport(entry != null, failMsg);
        String regex = entry.getRegexForPhase(compilePhase);
        failMsg = "IR Node \"" + irNode + "\" defined in class IRNode has no regex defined for compile phase "
                  + compilePhase + "." + System.lineSeparator() +
                  "   If you think this compile phase should be " +
                  "supported, add a mapping in class IRNodeMappings (i.e an entry in the constructor of class " +
                  "IRNodeMappings)." + System.lineSeparator() +
                  "   Violation";
        TestFormat.checkNoReport(regex != null, failMsg);
        return regex;
    }

    public static CompilePhase getDefaultPhaseForIRNode(String irNode) {
        IRNodeMapEntry entry = IR_NODE_MAPPINGS.get(irNode);
        String failMsg = "\"" + irNode + "\" is not an IR node defined in class IRNode and " +
                         "has therefore no default compile phase specified." + System.lineSeparator() +
                         "   If your regex represents a C2 IR node, consider adding an entry to class IRNode with a " +
                         "mapping in class IRNodeMappings." + System.lineSeparator() +
                         "   Otherwise, set the @IR \"phase\" attribute to a compile phase different from " +
                         "CompilePhase.DEFAULT to explicitly tell the IR framework on which compile phase your rule" +
                         " should be applied on." + System.lineSeparator() +
                         "   Violation";
        TestFormat.checkNoReport(entry != null, failMsg);
        return entry.getDefaultCompilePhase();
    }
}
