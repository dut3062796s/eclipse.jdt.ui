/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jdt.text.tests.performance;

import org.eclipse.test.performance.Dimension;
import org.eclipse.test.performance.Performance;

import org.eclipse.jface.text.BadLocationException;

import junit.framework.Test;
import junit.framework.TestSuite;

/**
 * Measures the time to type in one single method into a large text file
 * @since 3.1
 */
public class TextNonInitialTypingTest extends NonInitialTypingTest {

	private static final Class THIS= TextNonInitialTypingTest.class;
	
	public static Test suite() {
		return new PerformanceTestSetup(new TestSuite(THIS));
	}

	protected String getEditorId() {
		return "org.eclipse.ui.DefaultTextEditor";
	}
	
	public void testTypeAMethod() throws BadLocationException {
		Performance.getDefault().tagAsGlobalSummary(fMeter, "Typing speed in text editor", Dimension.ELAPSED_PROCESS); 
		super.testTypeAMethod();
	}
}
