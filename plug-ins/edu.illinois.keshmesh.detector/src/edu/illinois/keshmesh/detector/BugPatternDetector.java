/**
 * This file is licensed under the University of Illinois/NCSA Open Source License. See LICENSE.TXT for details.
 */
package edu.illinois.keshmesh.detector;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;

import org.eclipse.jdt.core.IJavaProject;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;

import edu.illinois.keshmesh.detector.bugs.BugInstances;

/**
 * 
 * @author Mohsen Vakilian
 * @author Stas Negara
 * 
 */
public abstract class BugPatternDetector {

	protected IJavaProject javaProject = null;

	protected BasicAnalysisData basicAnalysisData = null;

	/**
	 * Should assign basicAnalysisData as its first action in order to make
	 * other methods valid (e.g. getPointerForValueNumber(CGNode,int) )
	 * 
	 * @param basicAnalysisData
	 * @return
	 */
	public abstract BugInstances performAnalysis(IJavaProject javaProject, BasicAnalysisData basicAnalysisData);

	/**
	 * Tests can perform more rigorous checks if the detectors collect more fine
	 * grained information along their way.
	 * 
	 * @return an object that contains the results while analyzing the code for
	 *         a bug pattern.
	 */
	public abstract IntermediateResults getIntermediateResults();

	protected PointerKey getPointerForValueNumber(CGNode cgNode, int valueNumber) {
		return basicAnalysisData.heapModel.getPointerKeyForLocal(cgNode, valueNumber);
	}

	protected Collection<InstanceKey> getPointedInstances(PointerKey pointer) {
		Collection<InstanceKey> pointedInstances = new HashSet<InstanceKey>();
		Iterator<InstanceKey> pointedInstancesIterator = basicAnalysisData.pointerAnalysis.getPointsToSet(pointer).iterator();
		while (pointedInstancesIterator.hasNext()) {
			pointedInstances.add(pointedInstancesIterator.next());
		}
		return pointedInstances;
	}

}
