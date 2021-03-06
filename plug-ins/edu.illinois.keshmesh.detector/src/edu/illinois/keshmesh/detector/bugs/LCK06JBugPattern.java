/**
 * This file is licensed under the University of Illinois/NCSA Open Source License. See LICENSE.TXT for details.
 */
package edu.illinois.keshmesh.detector.bugs;

import edu.illinois.keshmesh.detector.BugPatternDetector;
import edu.illinois.keshmesh.detector.LCK06JBugDetector;

/**
 * 
 * @author Mohsen Vakilian
 * @author Stas Negara
 * 
 */
public class LCK06JBugPattern extends BugPattern {

	public LCK06JBugPattern() {
		super("LCK06J", "Do not use an instance lock to protect shared static data");
	}

	@Override
	public BugPatternDetector createBugPatternDetector() {
		bugPatternDetector = new LCK06JBugDetector();
		return bugPatternDetector;
	}

	public boolean hasFixer() {
		return false;
	}

}
