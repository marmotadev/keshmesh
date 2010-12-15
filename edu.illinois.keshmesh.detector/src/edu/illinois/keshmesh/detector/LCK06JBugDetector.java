/**
 * This file is licensed under the University of Illinois/NCSA Open Source License. See LICENSE.TXT for details.
 */
package edu.illinois.keshmesh.detector;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.dataflow.graph.BitVectorFramework;
import com.ibm.wala.dataflow.graph.BitVectorSolver;
import com.ibm.wala.fixpoint.BitVectorVariable;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAFieldAccessInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAMonitorInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.graph.impl.GraphInverter;
import com.ibm.wala.util.intset.BitVector;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableMapping;
import com.ibm.wala.util.intset.OrdinalSetMapping;

import edu.illinois.keshmesh.detector.bugs.BugInstance;
import edu.illinois.keshmesh.detector.bugs.BugInstances;
import edu.illinois.keshmesh.detector.bugs.BugPatterns;
import edu.illinois.keshmesh.detector.bugs.BugPosition;
import edu.illinois.keshmesh.detector.bugs.LCK06JFixInformation;
import edu.illinois.keshmesh.detector.util.AnalysisUtils;

/**
 * 
 * @author Mohsen Vakilian
 * @author Stas Negara
 * 
 */
public class LCK06JBugDetector extends BugPatternDetector {

	enum SynchronizedBlockKind {
		SAFE, UNSAFE
	}

	private static final String PRIMORDIAL_CLASSLOADER_NAME = "Primordial"; //$NON-NLS-1$

	private final Set<InstanceKey> instancesPointedByStaticFields = new HashSet<InstanceKey>();

	private final OrdinalSetMapping<InstructionInfo> globalValues = MutableMapping.make();

	private final Map<CGNode, CGNodeInfo> cgNodeInfoMap = new HashMap<CGNode, CGNodeInfo>();

	@Override
	public BugInstances performAnalysis(BasicAnalysisData analysisData) {
		basicAnalysisData = analysisData;
		populateAllInstancesPointedByStaticFields();
		BugInstances bugInstances = new BugInstances();
		Collection<InstructionInfo> unsafeSynchronizedBlocks = getUnsafeSynchronizedBlocks();
		if (unsafeSynchronizedBlocks.isEmpty()) {
			return bugInstances;
		}

		populateUnsafeModifyingStaticFieldsInstructionsMap();

		BitVectorSolver<CGNode> bitVectorSolver = propagateUnsafeModifyingStaticFieldsInstructions();

		Iterator<CGNode> cgNodesIterator = basicAnalysisData.callGraph.iterator();
		while (cgNodesIterator.hasNext()) {
			CGNode cgNode = cgNodesIterator.next();
			IntSet value = bitVectorSolver.getIn(cgNode).getValue();
			if (value != null) {
				IntIterator intIterator = value.intIterator();
				Logger.log("CGNode: " + cgNode.getMethod().getSignature());
				while (intIterator.hasNext()) {
					InstructionInfo instructionInfo = globalValues.getMappedObject(intIterator.next());
					Logger.log("\tPropagated instruction: " + instructionInfo);
				}
			}
		}

		for (InstructionInfo unsafeSynchronizedBlock : unsafeSynchronizedBlocks) {
			Collection<InstructionInfo> actuallyUnsafeInstructions = getActuallyUnsafeInstructions(bitVectorSolver, unsafeSynchronizedBlock);
			if (!actuallyUnsafeInstructions.isEmpty()) {
				TypeName enclosingClassName = unsafeSynchronizedBlock.getCGNode().getMethod().getDeclaringClass().getName();
				bugInstances.add(new BugInstance(BugPatterns.LCK06J, new BugPosition(unsafeSynchronizedBlock.getPosition(), AnalysisUtils.getEnclosingNonanonymousClassName(enclosingClassName)),
						new LCK06JFixInformation()));
			}
			Logger.log("Unsafe instructions of " + unsafeSynchronizedBlock + " are " + actuallyUnsafeInstructions.toString());
		}

		return bugInstances;
	}

	public void addSolverResults(Collection<InstructionInfo> results, BitVectorSolver<CGNode> bitVectorSolver, CGNode cgNode) {
		IntSet value = bitVectorSolver.getIn(cgNode).getValue();
		if (value != null) {
			IntIterator intIterator = value.intIterator();
			while (intIterator.hasNext()) {
				InstructionInfo instructionInfo = globalValues.getMappedObject(intIterator.next());
				results.add(instructionInfo);
			}
		}
	}

	private Collection<InstructionInfo> getActuallyUnsafeInstructions(final BitVectorSolver<CGNode> bitVectorSolver, final InstructionInfo unsafeSynchronizedBlock) {
		Collection<InstructionInfo> unsafeInstructions = new HashSet<InstructionInfo>();
		CGNode cgNode = unsafeSynchronizedBlock.getCGNode();
		Collection<InstructionInfo> safeSynchronizedBlocks = new HashSet<InstructionInfo>();
		populateSynchronizedBlocksForNode(safeSynchronizedBlocks, cgNode, SynchronizedBlockKind.SAFE);
		IR ir = cgNode.getIR();
		if (ir == null) {
			return unsafeInstructions; //should not really be null here
		}
		DefUse defUse = new DefUse(ir);
		SSAInstruction[] instructions = ir.getInstructions();
		for (int instructionIndex = 0; instructionIndex < instructions.length; instructionIndex++) {
			SSAInstruction instruction = instructions[instructionIndex];
			if (instruction == null)
				continue;
			InstructionInfo instructionInfo = new InstructionInfo(cgNode, instructionIndex);
			if (instructionInfo.isInside(unsafeSynchronizedBlock) && !AnalysisUtils.isProtectedByAnySynchronizedBlock(safeSynchronizedBlocks, instructionInfo)) {
				if (canModifyStaticField(defUse, instruction)) {
					unsafeInstructions.add(instructionInfo);
				}
				if (instruction instanceof SSAAbstractInvokeInstruction) {
					SSAAbstractInvokeInstruction invokeInstruction = (SSAAbstractInvokeInstruction) instruction;
					Set<CGNode> possibleTargets = basicAnalysisData.callGraph.getPossibleTargets(cgNode, invokeInstruction.getCallSite());
					for (CGNode possibleTarget : possibleTargets) {
						// Add unsafe operations coming from callees.
						addSolverResults(unsafeInstructions, bitVectorSolver, possibleTarget);
					}
				}
			}
		}
		return unsafeInstructions;
	}

	private BitVectorSolver<CGNode> propagateUnsafeModifyingStaticFieldsInstructions() {
		LCK06JTransferFunctionProvider transferFunctions = new LCK06JTransferFunctionProvider(basicAnalysisData.callGraph, cgNodeInfoMap);

		BitVectorFramework<CGNode, InstructionInfo> bitVectorFramework = new BitVectorFramework<CGNode, InstructionInfo>(GraphInverter.invert(basicAnalysisData.callGraph), transferFunctions,
				globalValues);

		BitVectorSolver<CGNode> bitVectorSolver = new BitVectorSolver<CGNode>(bitVectorFramework) {
			@Override
			protected BitVectorVariable makeNodeVariable(CGNode cgNode, boolean IN) {
				BitVectorVariable nodeBitVectorVariable = new BitVectorVariable();
				nodeBitVectorVariable.addAll(cgNodeInfoMap.get(cgNode).getBitVector());
				return nodeBitVectorVariable;
			}
		};
		try {
			bitVectorSolver.solve(new NullProgressMonitor());
		} catch (CancelException ex) {
			//FIXME: Handle the exception (log or rethrow).
			ex.printStackTrace();
		}
		return bitVectorSolver;
	}

	/**
	 * Populates the initial sets for modifying static fields of all CGNodes.
	 * 
	 * @param cgNodeInfoMap
	 * @param globalValues
	 */
	private void populateUnsafeModifyingStaticFieldsInstructionsMap() {
		Iterator<CGNode> cgNodesIterator = basicAnalysisData.callGraph.iterator();
		while (cgNodesIterator.hasNext()) {
			CGNode cgNode = cgNodesIterator.next();
			BitVector bitVector = new BitVector();
			Collection<InstructionInfo> safeSynchronizedBlocks = new HashSet<InstructionInfo>();
			if (!isIgnoredClass(cgNode.getMethod().getDeclaringClass())) {
				Collection<InstructionInfo> modifyingStaticFieldsInstructions = getModifyingStaticFieldsInstructions(cgNode);
				populateSynchronizedBlocksForNode(safeSynchronizedBlocks, cgNode, SynchronizedBlockKind.SAFE);
				Collection<InstructionInfo> unsafeModifyingStaticFieldsInstructions = new HashSet<InstructionInfo>();
				for (InstructionInfo modifyingStaticFieldInstruction : modifyingStaticFieldsInstructions) {
					if (!AnalysisUtils.isProtectedByAnySynchronizedBlock(safeSynchronizedBlocks, modifyingStaticFieldInstruction)) {
						unsafeModifyingStaticFieldsInstructions.add(modifyingStaticFieldInstruction);
					}
				}
				for (InstructionInfo modifyInstruction : modifyingStaticFieldsInstructions) {
					Logger.log("MODIFY: " + modifyInstruction);
				}
				for (InstructionInfo unsafeModifyInstruction : unsafeModifyingStaticFieldsInstructions) {
					bitVector.set(globalValues.add(unsafeModifyInstruction));
					Logger.log("UNSAFE MODIFY: " + unsafeModifyInstruction);
				}
			}
			cgNodeInfoMap.put(cgNode, new CGNodeInfo(safeSynchronizedBlocks, bitVector));
		}
	}

	private Collection<InstructionInfo> getModifyingStaticFieldsInstructions(CGNode cgNode) {
		Collection<InstructionInfo> modifyingStaticFieldsInstructions = new HashSet<InstructionInfo>();
		IR ir = cgNode.getIR();
		if (ir == null) {
			return modifyingStaticFieldsInstructions;
		}
		final DefUse defUse = new DefUse(ir);

		AnalysisUtils.filter(modifyingStaticFieldsInstructions, cgNode, new InstructionFilter() {

			@Override
			public boolean accept(SSAInstruction ssaInstruction) {
				return canModifyStaticField(defUse, ssaInstruction);
			}
		});
		return modifyingStaticFieldsInstructions;
	}

	private boolean canModifyStaticField(final DefUse defUse, SSAInstruction ssaInstruction) {
		if (ssaInstruction instanceof SSAPutInstruction) {
			return isStaticNonFinal((SSAFieldAccessInstruction) ssaInstruction);
		} else if (ssaInstruction instanceof SSAAbstractInvokeInstruction) {
			for (int i = 0; i < ssaInstruction.getNumberOfUses(); i++) {
				SSAInstruction defInstruction = defUse.getDef(ssaInstruction.getUse(i));
				if (defInstruction instanceof SSAGetInstruction && isStaticNonFinal((SSAFieldAccessInstruction) defInstruction)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean isStaticNonFinal(SSAFieldAccessInstruction fieldAccessInstruction) {
		IField accessedField = basicAnalysisData.classHierarchy.resolveField(fieldAccessInstruction.getDeclaredField());
		return fieldAccessInstruction.isStatic() && !accessedField.isFinal();
	}

	//TODO: A synchronized block that is nested inside a safe one is safe. So, this method should not return such synchronized blocks. 
	private Collection<InstructionInfo> getUnsafeSynchronizedBlocks() {
		Collection<InstructionInfo> unsafeSynchronizedBlocks = new HashSet<InstructionInfo>();
		Iterator<CGNode> cgNodesIterator = basicAnalysisData.callGraph.iterator();
		while (cgNodesIterator.hasNext()) {
			final CGNode cgNode = cgNodesIterator.next();
			if (!isIgnoredClass(cgNode.getMethod().getDeclaringClass())) {
				populateSynchronizedBlocksForNode(unsafeSynchronizedBlocks, cgNode, SynchronizedBlockKind.UNSAFE);
			}
		}
		return unsafeSynchronizedBlocks;
	}

	private void populateSynchronizedBlocksForNode(Collection<InstructionInfo> synchronizedBlocks, final CGNode cgNode, final SynchronizedBlockKind synchronizedBlockKind) {
		AnalysisUtils.filter(synchronizedBlocks, cgNode, new InstructionFilter() {

			@Override
			public boolean accept(SSAInstruction ssaInstruction) {
				if (ssaInstruction instanceof SSAMonitorInstruction) {
					SSAMonitorInstruction monitorInstruction = (SSAMonitorInstruction) ssaInstruction;
					if (monitorInstruction.isMonitorEnter()) {
						if (synchronizedBlockKind == SynchronizedBlockKind.SAFE) {
							return isSafe(cgNode, monitorInstruction);
						} else {
							return !isSafe(cgNode, monitorInstruction);
						}
					}
				}
				return false;
			}
		});
	}

	boolean isSafe(CGNode cgNode, SSAMonitorInstruction monitorInstruction) {
		assert (monitorInstruction.isMonitorEnter());
		PointerKey lockPointer = getPointerForValueNumber(cgNode, monitorInstruction.getRef());
		Collection<InstanceKey> lockPointedInstances = getPointedInstances(lockPointer);
		if (lockPointedInstances.isEmpty() || !instancesPointedByStaticFields.containsAll(lockPointedInstances)) {
			return false;
		}
		return true;
	}

	private void populateAllInstancesPointedByStaticFields() {
		for (IField staticField : getAllStaticFields()) {
			Logger.log("Static field: " + staticField);
			PointerKey staticFieldPointer = basicAnalysisData.heapModel.getPointerKeyForStaticField(staticField);
			Collection<InstanceKey> pointedInstances = getPointedInstances(staticFieldPointer);
			for (InstanceKey instance : pointedInstances) {
				Logger.log("Pointed instance: " + instance);
			}
			instancesPointedByStaticFields.addAll(pointedInstances);
		}
	}

	private Set<IField> getAllStaticFields() {
		Set<IField> staticFields = new HashSet<IField>();
		Iterator<IClass> classIterator = basicAnalysisData.classHierarchy.iterator();
		while (classIterator.hasNext()) {
			IClass klass = classIterator.next();
			if (!isIgnoredClass(klass)) {
				staticFields.addAll(klass.getAllStaticFields());
			}
		}
		return staticFields;
	}

	private boolean isIgnoredClass(IClass klass) {
		//TODO: Should we look for bugs in JDK usage as well?
		//TODO: !!!What about other bytecodes, e.g. from the libraries, which will not allow to get the source position?
		boolean isJDKClass = klass.getClassLoader().getName().toString().equals(PRIMORDIAL_CLASSLOADER_NAME);
		return isJDKClass;
	}

}