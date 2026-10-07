package org.gpur;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectPackages({"org.gpur.compute", "org.gpur.antixray", "org.gpur.waypoints"})
@org.junit.platform.suite.api.SelectClasses(GPurConfigTest.class)
public class GPurRuntimeTestSuite {}
