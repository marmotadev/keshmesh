/**
 * This file is licensed under the University of Illinois/NCSA Open Source License. See LICENSE.TXT for details.
 */
package edu.illinois.keshmesh.detector.tests.lck02j;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;

import edu.illinois.keshmesh.detector.bugs.BugInstance;
import edu.illinois.keshmesh.detector.bugs.BugPatterns;
import edu.illinois.keshmesh.detector.bugs.BugPosition;
import edu.illinois.keshmesh.detector.bugs.LCK02JFixInformation;
import edu.illinois.keshmesh.detector.util.SetUtils;

/**
 * 
 * @author Stas Negara
 * @author Mohsen Vakilian
 * 
 */
public class LCK02JTest5 extends LCK02JTest {

	@Before
	public void setup() throws Exception {
		setupProjectAndAnalyze("test-files/LCK02J/05/Test.java");
	}

	@Test
	public void shouldFindLCK02J() {
		Assert.assertEquals(1, bugInstances.size());
		Assert.assertTrue(bugInstances.contains(new BugInstance(BugPatterns.LCK02J, new BugPosition(23, 25, targetTestClassPath), new LCK02JFixInformation(SetUtils.asSet("p.Test.C.class",
				"p.Test.D.class")))));
	}
}
