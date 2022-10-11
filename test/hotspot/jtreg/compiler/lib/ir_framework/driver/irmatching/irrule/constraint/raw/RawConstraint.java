/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

package compiler.lib.ir_framework.driver.irmatching.irrule.constraint.raw;

import compiler.lib.ir_framework.CompilePhase;
import compiler.lib.ir_framework.IR;
import compiler.lib.ir_framework.IRNode;
import compiler.lib.ir_framework.driver.irmatching.irmethod.IRMethod;
import compiler.lib.ir_framework.driver.irmatching.irrule.constraint.Constraint;
import compiler.lib.ir_framework.shared.TestFormat;

/**
 * Base class to represent a single raw constraint as found in the {@link IR @IR} annotation(i.e. {@link IRNode}
 * placeholder strings are not replaced by default regexes, yet). A raw constraint can be parsed into a
 * {@link Constraint} by calling {@link #parse(CompilePhase, IRMethod)}. This replaces the IR node placeholder strings
 * by actual regexes and merge composite nodes together.
 *
 * @see Constraint
 */
abstract public class RawConstraint {
    protected final String rawNodeString;
    protected final int constraintIndex;
    private final String userPostfixString; // For composite IR nodes
    private final boolean compositeNode;

    public RawConstraint(String rawNodeString, String userPostfixString, int constraintIndex) {
        this.rawNodeString = rawNodeString;
        this.userPostfixString = userPostfixString;
        this.constraintIndex = constraintIndex;
        this.compositeNode = userPostfixString != null;
    }

    abstract public Constraint parse(CompilePhase compilePhase, IRMethod irMethod);

    protected CompilePhase getCompilePhase(CompilePhase compilePhase) {
        if (compilePhase == CompilePhase.DEFAULT) {
            compilePhase = getCompilePhaseForDefault();
        }
        return compilePhase;
    }

    public CompilePhase getCompilePhaseForDefault() {
        return IRNode.getDefaultPhaseForIRNode(rawNodeString);
    }

    protected String parseRegex(CompilePhase compilePhase) {
        if (IRNode.isIRNode(rawNodeString)) {
            String parsedNodeString = IRNode.getRegexForPhaseOfIRNode(rawNodeString, compilePhase);
            if (compositeNode) {
                TestFormat.checkNoReport(!userPostfixString.isEmpty(),
                                         "Provided empty string for composite node " + rawNodeString
                                         + " at constraint " + constraintIndex);
                parsedNodeString = parsedNodeString.replaceAll(IRNode.IS_REPLACED, userPostfixString);
            }
            return parsedNodeString;
        }
        return rawNodeString;
    }
}
