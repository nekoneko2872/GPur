package org.gpur;

import org.gpur.generation.GPurBackendGateTest;
import org.gpur.reload.GPurReloadRetentionPolicyTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite(failIfNoTests = false)
@SuiteDisplayName("GPur runtime tests")
@SelectClasses({GPurBackendGateTest.class, GPurReloadRetentionPolicyTest.class})
public class GPurRuntimeTestSuite {
}