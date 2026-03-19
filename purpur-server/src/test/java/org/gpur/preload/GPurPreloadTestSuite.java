package org.gpur.preload;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite(failIfNoTests = false)
@SuiteDisplayName("GPur preload math tests")
@SelectClasses(GPurElytraPreloadMathTest.class)
public class GPurPreloadTestSuite {
}
