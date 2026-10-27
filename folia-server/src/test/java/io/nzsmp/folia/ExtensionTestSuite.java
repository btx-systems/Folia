package io.nzsmp.folia;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({AsyncWorkQueueTest.class, GenerationControlTest.class, SectionImporterTest.class, ManagedStoreTest.class, ChunkRecordCodecTest.class})
@ConfigurationParameter(key = "TestSuite", value = "Legacy")
public class ExtensionTestSuite {
}
