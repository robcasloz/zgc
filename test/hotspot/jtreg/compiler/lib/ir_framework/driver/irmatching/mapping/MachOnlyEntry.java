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

package compiler.lib.ir_framework.driver.irmatching.mapping;

import compiler.lib.ir_framework.CompilePhase;
import compiler.lib.ir_framework.RegexType;

/**
 * This class represents a mapping entry for an IR node that is only applied on mach graph phases (i.e. after matching
 * created a Mach Graph). All compile phases on the mach graph specify {@link RegexType#MACH} as regex type.
 */
public class MachOnlyEntry extends SingleRegexEntry {

    public MachOnlyEntry(CompilePhase defaultCompilePhase, String regex) {
        super(defaultCompilePhase, regex);
    }

    @Override
    public String getRegexForPhase(CompilePhase compilePhase) {
        if (compilePhase.getRegexType() == RegexType.MACH) {
            return regex;
        } else {
            return null;
        }
    }
}
