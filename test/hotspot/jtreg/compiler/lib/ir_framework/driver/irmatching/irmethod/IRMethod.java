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

package compiler.lib.ir_framework.driver.irmatching.irmethod;

import compiler.lib.ir_framework.IR;
import compiler.lib.ir_framework.TestFramework;
import compiler.lib.ir_framework.driver.irmatching.irrule.IRRule;
import compiler.lib.ir_framework.driver.irmatching.irrule.IRRuleMatchResult;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper class to store information about a method that needs to be IR matched.
 */
public class IRMethod {
    private final Method method;
    private final List<IRRule> irRules;
    private String idealOutput;
    private String optoAssemblyOutput;
    private boolean hasOutput;

    public IRMethod(Method method, int[] ruleIds, IR[] irAnnos) {
        this.method = method;
        this.irRules = new ArrayList<>();
        for (int i : ruleIds) {
            irRules.add(new IRRule(this, i, irAnnos[i - 1]));
        }
        this.idealOutput = "";
        this.optoAssemblyOutput = "";
        this.hasOutput = false;
    }

    public Method getMethod() {
        return method;
    }


    public void setIdealOutput(String idealOutput) {
        this.idealOutput = "PrintIdeal:" + System.lineSeparator() + idealOutput;
        hasOutput = true;
    }

    public void setOptoAssemblyOutput(String optoAssemblyOutput) {
        this.optoAssemblyOutput = "PrintOptoAssembly:" + System.lineSeparator() + optoAssemblyOutput;
        hasOutput = true;
    }

    public String getOutput() {
        if (!hasOutput) {
            return "";
        }
        return getIdealOutput() + System.lineSeparator() + System.lineSeparator() + getOptoAssemblyOutput();
    }

    public String getIdealOutput() {
        return idealOutput;
    }

    public String getOptoAssemblyOutput() {
        return optoAssemblyOutput;
    }

    /**
     * Apply all IR rules of this IR method.
     */
    public IRMethodMatchResult applyIRRules() {
        TestFramework.check(!irRules.isEmpty(), "IRMethod cannot be created if there are no IR rules to apply");
        List<IRRuleMatchResult> results = new ArrayList<>();
        if (hasOutput) {
            return getNormalMatchResult(results);
        } else {
            return new MissingCompilationResult(this, irRules.size());
        }
    }

    private NormalMatchResult getNormalMatchResult(List<IRRuleMatchResult> results) {
        for (IRRule irRule : irRules) {
            IRRuleMatchResult result = irRule.applyCheckAttribute();
            if (result.fail()) {
                results.add(result);
            }
        }
        return new NormalMatchResult(this, results);
    }
}
