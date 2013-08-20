/*
 * Copyright 2013 University of Chicago and Argonne National Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package exm.stc.ic.opt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.log4j.Logger;

import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.lang.Arg;
import exm.stc.common.lang.ExecContext;
import exm.stc.common.lang.PassedVar;
import exm.stc.common.lang.RefCounting;
import exm.stc.common.lang.Types;
import exm.stc.common.lang.Var;
import exm.stc.common.lang.Var.DefType;
import exm.stc.common.lang.Var.Alloc;
import exm.stc.common.util.HierarchicalSet;
import exm.stc.common.util.Pair;
import exm.stc.common.util.Sets;
import exm.stc.ic.opt.AliasTracker.AliasKey;
import exm.stc.ic.tree.ICContinuations.ContVarDefType;
import exm.stc.ic.tree.ICContinuations.Continuation;
import exm.stc.ic.tree.ICInstructions.Instruction;
import exm.stc.ic.tree.ICTree.Block;
import exm.stc.ic.tree.ICTree.CleanupAction;
import exm.stc.ic.tree.ICTree.Function;
import exm.stc.ic.tree.ICTree.Program;
import exm.stc.ic.tree.ICTree.Statement;

/**
 * Fix up passInVars and keepOpenVars in IC.  Perform validation
 * to make sure variables are visible.
 */
public class FixupVariables implements OptimizerPass {

  @Override
  public String getPassName() {
    return "Fixup variable passing";
  }

  @Override
  public String getConfigEnabledKey() {
    return null;
  }

  @Override
  public void optimize(Logger logger, Program program) {
    fixupProgram(logger, program, true);
  }

  /**
   * Fix up any variables missing from the usedVariables passed through
   * continuations. This is useful because it is easier to write other
   * optimizations if they are allowed to mess up the usedVariables
   * @param updateLists modify tree and update pass and keep open lists
   */
  public static void fixupProgram(Logger logger, Program prog,
                                  boolean updateLists) {
    Set<Var> referencedGlobals = new HashSet<Var>();
    for (Function fn : prog.getFunctions()) {
      fixupFunction(logger, prog, fn, referencedGlobals, updateLists);
    }
    
    if (updateLists)
      removeUnusedGlobals(prog, referencedGlobals);
  }

  public static void fixupFunction(Logger logger, Program prog,
          Function fn, Set<Var> referencedGlobals, boolean updateLists) {
    HierarchicalSet<Var> fnargs = new HierarchicalSet<Var>();
    for (Var v : fn.getInputList()) {
      fnargs.add(v);
    }
    for (Var v : fn.getOutputList()) {
      fnargs.add(v);
    }
    for (Entry<String, Arg> e : prog.getGlobalConsts().entrySet()) {
      Arg a = e.getValue();
      Var v = new Var(a.futureType(), e.getKey(),
          Alloc.GLOBAL_CONST, DefType.GLOBAL_CONST, null);
      fnargs.add(v);
    }
    
    AliasTracker aliases = new AliasTracker();
    
    Result res = fixupBlockRec(logger, fn, fn.mainBlock(),
                       ExecContext.CONTROL, fnargs,
                       referencedGlobals, aliases, updateLists);
    if (updateLists) {
      // Mark write-only outputs
      for (int i = 0; i < fn.getOutputList().size(); i++) {
        Var output = fn.getOutput(i);
        if (!res.read.contains(output) && !Types.hasReadableSideChannel(output.type())) {
          fn.makeOutputWriteOnly(i);
        }
      }
    }
    
    // Check that all variables referred to are available as args
    res.removeRead(fn.getInputList());
    res.removeReadWrite(fn.getOutputList());
    for (Var v: fn.getInputList()) {
      // TODO: should these be passed in through output list instead?
      if (Types.isScalarUpdateable(v.type())) {
        res.removeWritten(v);
      }
    }

    if (res.read.size() > 0) {
      throw new STCRuntimeError("Reference in IC function "
          + fn.getName() + " to undefined variables " + res.read.toString());
    }
    
    if (res.written.size() > 0) {
      throw new STCRuntimeError("Unexpected write IC function "
          + fn.getName() + " to variables " + res.written.toString());
    }
    
    if (res.aliasWritten.size() > 0) {
      throw new STCRuntimeError("Unexpected write IC function "
          + fn.getName() + " to variables " + res.aliasWritten.toString());
    }
  }

  private static class Result {
    final Set<Var> read; /** Variables that were read */
    final Set<Var> written; /** Variables that were written (de-aliased) */
    /** Original aliases for write variables, to make sure that redundant
     * aliases are passed correctly in case of suboptimal code */
    final Set<Var> aliasWritten;
    
    Result() {
      super();
      this.read = new HashSet<Var>();
      this.written = new HashSet<Var>();
      this.aliasWritten = new HashSet<Var>();
    }

    public List<Set<Var>> allSets() {
      List<Set<Var>> res = new ArrayList<Set<Var>>();
      res.add(read);
      res.add(written);
      res.add(aliasWritten);
      return res;
    }

    Set<Var> allNeeded() {
      return Sets.union(allSets());
    }
    
    /**
     * Add everything from another result
     */
    void add(Result other) {
      read.addAll(other.read);
      written.addAll(other.written);
      aliasWritten.addAll(other.aliasWritten);
    }
    
    /**
     * Add everything from another result with exclusions
     */
    void addExcluding(Result other, Collection<Var> exclusion) {
      List<Pair<Set<Var>, Set<Var>>> fromTos =
                      new ArrayList<Pair<Set<Var>, Set<Var>>>();
      fromTos.add(Pair.create(other.read, read));
      fromTos.add(Pair.create(other.written, written));
      fromTos.add(Pair.create(other.aliasWritten, aliasWritten));
                      
      for (Pair<Set<Var>, Set<Var>> fromTo: fromTos) {
        for (Var var: fromTo.val1) {
          if (!exclusion.contains(var)) {
            fromTo.val2.add(var);
          }
        }
      }
    }


    void addRead(Var var) {
      read.add(var);
    }

    void addRead(Collection<Var> vars) {
      for (Var var: vars) {
        addRead(var);
      }
    }

    void removeRead(Var var) {
      this.read.remove(var);
    }

    void removeRead(Collection<Var> vars) {
      for (Var var: vars) {
        removeRead(var);
      }
    }

    Var canonicalWriteVar(Var var, AliasTracker aliases) {
      AliasKey key = aliases.getCanonical(var);
      assert(key != null) : var;
      Var canonical = aliases.findVar(key);
      assert(canonical != null) : var + " " + key;
      return canonical;
    }

    /**
     * Mark variable as written
     * @param var
     * @param aliases
     */
    void addWritten(Var var, AliasTracker aliases) {
      /* Use the canonical variable for a struct field so that we can track
       * the write back to the original struct field in outer scopes where
       * there may be multiple aliases for that field.
       */
      Var key = canonicalWriteVar(var, aliases);
      this.written.add(key);
      if (!key.equals(var)) {
        this.aliasWritten.add(var);
      }
    }

    void addWritten(Collection<Var> vars, AliasTracker aliases) {
      for (Var var: vars) {
        addWritten(var, aliases);
      }
    }

    /**
     * Remove variable without canonicalizing it, e.g. if we're moving
     * up to an outer scope
     * @param var
     */
    void removeWritten(Var var) {
      // Remove the non-canonical var
      this.written.remove(var);
      this.aliasWritten.remove(var);
    }
    
    /**
     * Remove variable without canonicalizing them
     * @param vars
     */
    void removeReadWrite(Collection<Var> vars) {
      for (Var var: vars) {
        removeRead(var);
        removeWritten(var);
      }
    }
  }
  
  /**
   * 
   * @param logger
   * @param block 
   * @param visible all
   *          variables logically visible in this block. will be modified in fn
   * @param referencedGlobals updated with names of any globals used
   * @param aliases 
   * @param updateLists 
   * @return
   */
  private static Result fixupBlockRec(Logger logger,
      Function function, Block block, ExecContext execCx, 
      HierarchicalSet<Var> visible, Set<Var> referencedGlobals,
      AliasTracker aliases, boolean updateLists) {

    if (updateLists)
      // Remove global imports to be readded later if needed
      removeGlobalImports(block);
    
    // blockVars: variables defined in this block
    Set<Var> blockVars = new HashSet<Var>();
    
    // update block variables and visible variables
    for (Var v: block.getVariables()) {
      blockVars.add(v);
      visible.add(v);
    }

    // Work out which variables are read/writte which aren't locally declared
    Result result = new Result();
    findBlockNeeded(block, result, aliases);
    
    for (Continuation c : block.allComplexStatements()) {
      fixupContinuationRec(logger, function, execCx, c,
              visible, referencedGlobals, aliases,
              blockVars, result, updateLists);
    }

    // Outer scopes don't have anything to do with vars declared here
    result.removeReadWrite(blockVars);
    
    if (execCx == ExecContext.CONTROL) {
      // Global constants can be imported in control blocks only
      Set<Var> globals = addGlobalImports(block, visible, updateLists,
                                          result.allSets());
  
      referencedGlobals.addAll(globals);
      result.removeReadWrite(globals);
    }
    return result;
  }

  /**
   * Find all referenced vars in scope
   * @param block
   * @param result accumulate needed vars
   * @param aliases 
   */
  private static void findBlockNeeded(Block block, Result result,
                                      AliasTracker aliases) {
    for (Var v: block.getVariables()) {
      if (v.mapping() != null) {
        result.addRead(v.mapping());
      }
    }
    
    for (Statement stmt: block.getStatements()) {
      switch (stmt.type()) {
        case INSTRUCTION: {
          Instruction i = stmt.instruction();

          // This line keeps aliases up to data for e.g. struct insertions
          aliases.update(i);
          
          for (Arg in: i.getInputs()) {
            if (in.isVar()) {
              result.addRead(in.getVar());
            }
          }
          for (Var read: i.getReadOutputs()) {
            result.addRead(read);
          }
          result.addWritten(i.getOutputs(), aliases);
          break;
        }
        case CONDITIONAL: {
          result.addRead(stmt.conditional().requiredVars(false));
          break;
        }
        default:
          throw new STCRuntimeError("Unknown statement type " + stmt.type());
      }
    }
    
    for (Continuation cont: block.getContinuations()) {
      result.addRead(cont.requiredVars(false));
    }
    
    for (CleanupAction cleanup: block.getCleanups()) {
      // ignore outputs - the cleaned up vars should already be in scope
      for (Arg in: cleanup.action().getInputs()) {
        if (in.isVar()) {
          result.addRead(in.getVar());
        }
      }
    }
  }

  /**
   * Update variable passing for nested continuation
   * @param logger
   * @param function
   * @param outerCx exec context outside of continuation
   * @param continuation
   * @param visible
   * @param referencedGlobals
   * @param aliases 
   * @param outerBlockVars
   * @param neededVars
   * @param updateLists 
   */
  private static void fixupContinuationRec(Logger logger, Function function,
          ExecContext outerCx,
          Continuation continuation, HierarchicalSet<Var> visible,
          Set<Var> referencedGlobals, AliasTracker outerAliases, Set<Var> outerBlockVars,
          Result result, boolean updateLists) {
    // First see what variables the continuation defines inside itself
    List<Var> constructVars = continuation.constructDefinedVars(ContVarDefType.NEW_DEF);
    ExecContext innerCx = continuation.childContext(outerCx);
    AliasTracker contAliases = outerAliases.makeChild();
    
    for (Block innerBlock : continuation.getBlocks()) {
      HierarchicalSet<Var> childVisible = visible.makeChild();
      for (Var v : constructVars) {
        childVisible.add(v);
      }
      AliasTracker blockAliases = contAliases.makeChild();
      Result inner = fixupBlockRec(logger,
          function, innerBlock, innerCx, childVisible,
          referencedGlobals, blockAliases, updateLists);
      
      // construct will provide some vars
      if (!constructVars.isEmpty()) {
        inner.removeReadWrite(constructVars);
      }

      if (continuation.inheritsParentVars()) {
        // Might be some variables not yet defined in this scope
        inner.removeReadWrite(outerBlockVars);
        result.add(inner);
      } else if (updateLists) {
        // Update the passed in vars
        rebuildContinuationPassedVars(function, continuation, visible,
              outerBlockVars, outerAliases, result, inner);
        rebuildContinuationKeepOpenVars(function, continuation,
                  visible, outerBlockVars, outerAliases, result, inner);
      }
    }
  }

  private static void rebuildContinuationPassedVars(Function function,
          Continuation continuation, HierarchicalSet<Var> visibleVars,
          Set<Var> outerBlockVars, AliasTracker outerAliases,
          Result outer, Result inner) {
    // Rebuild passed in vars
    List<PassedVar> passedIn = new ArrayList<PassedVar>();
    for (Var needed: inner.allNeeded()) {
      if (!visibleVars.contains(needed)) {
        throw new STCRuntimeError("Variable " + needed
            + " should have been " + "visible but wasn't in "
            + function.getName());
      }

      boolean writeOnly = !inner.read.contains(needed);
      passedIn.add(new PassedVar(needed, writeOnly));
    }
    
    // Copy out any additional variables
    outer.addExcluding(inner, outerBlockVars);
    
    // Handle any additional variables that need to be passed in,
    // for example if a variable is waited on but not otherwise passed
    for (PassedVar addtl: continuation.getMustPassVars()) {
      // Check
      boolean mustAdd = true;
      ListIterator<PassedVar> it = passedIn.listIterator();
      while (it.hasNext()) {
        PassedVar existing = it.next();
        if (existing.var.equals(addtl.var)) {
          mustAdd = false;
          if (existing.writeOnly && !addtl.writeOnly) {
            // Must be readable too
            it.set(addtl);
          }
        }
      }
      if (mustAdd) {
        passedIn.add(addtl);
      }
    }
    
    continuation.setPassedVars(passedIn);
  }

  private static void rebuildContinuationKeepOpenVars(Function function,
      Continuation continuation, HierarchicalSet<Var> visible,
      Set<Var> outerBlockVars, AliasTracker outerAliases,
      Result outer, Result inner) {
    List<Var> keepOpen = new ArrayList<Var>();
    for (Var v: inner.written) {
      // If not declared in this scope
      if (!outerBlockVars.contains(v)) {
        outer.addWritten(v, outerAliases);
      }
      if (!visible.contains(v)) {
        throw new STCRuntimeError("Variable " + v
            + " should have been " + "visible but wasn't in "
            + function.getName());
      }
      if (RefCounting.hasWriteRefCount(v)) {
        keepOpen.add(v);
      }
    }
    continuation.setKeepOpenVars(keepOpen);
  }

  private static void removeGlobalImports(Block block) {
    ListIterator<Var> varIt = block.variableIterator();
    while (varIt.hasNext()) {
      Var v = varIt.next();
      if (v.defType() == DefType.GLOBAL_CONST) {
        varIt.remove();
      }
    }
  }

  /**
   * 
   * @param block
   * @param visible
   * @param neededSets sets of vars needed from outside bock
   * @return set of global vars
   */
  private static Set<Var> addGlobalImports(Block block,
          HierarchicalSet<Var> visible,
          boolean updateLists, List<Set<Var>> neededSets) {
    // if global constant missing, just add it
    Set<Var> addedGlobals = new HashSet<Var>();
    for (Set<Var> neededSet: neededSets) {
      for (Var var: neededSet) {
        if (visible.contains(var)) {
          if (var.storage() == Alloc.GLOBAL_CONST) {
            // Add at top in case used as mapping var
            if (updateLists && !addedGlobals.contains(var))
              block.addVariable(var, true);
            addedGlobals.add(var);
          }
        }
      }
    }
    return addedGlobals;
  }

  private static void removeUnusedGlobals(Program prog,
       Set<Var> referencedGlobals) {
    Set<String> globNames = new HashSet<String>(prog.getGlobalConsts().keySet());
    Set<String> referencedGlobNames = Var.nameSet(referencedGlobals);
    globNames.removeAll(referencedGlobNames);
    for (String unused: globNames) {
      prog.removeGlobalConst(unused);
    }
  }
}
