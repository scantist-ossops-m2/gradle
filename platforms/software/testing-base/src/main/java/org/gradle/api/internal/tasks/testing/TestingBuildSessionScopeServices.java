/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.testing;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.gradle.api.NonNullApi;
import org.gradle.api.internal.file.temp.TemporaryFileProvider;
import org.gradle.api.internal.tasks.testing.junit.result.AggregateTestResultsProvider;
import org.gradle.api.internal.tasks.testing.junit.result.BinaryResultBackedTestResultsProvider;
import org.gradle.api.internal.tasks.testing.junit.result.TestClassResult;
import org.gradle.api.internal.tasks.testing.junit.result.TestOutputStore;
import org.gradle.api.internal.tasks.testing.junit.result.TestReportDataCollector;
import org.gradle.api.internal.tasks.testing.junit.result.TestResultSerializer;
import org.gradle.api.internal.tasks.testing.junit.result.TestResultsProvider;
import org.gradle.api.internal.tasks.testing.operations.TestListenerBuildOperationAdapter;
import org.gradle.api.internal.tasks.testing.report.HtmlTestReport;
import org.gradle.api.internal.tasks.testing.results.TestListenerAdapter;
import org.gradle.api.internal.tasks.testing.results.TestListenerInternal;
import org.gradle.api.tasks.testing.TestEventReporterFactory;
import org.gradle.api.tasks.testing.TestOutputEvent;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.internal.logging.ConsoleRenderer;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationRunner;
import org.gradle.internal.service.Provides;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.ServiceRegistrationProvider;
import org.gradle.problems.buildtree.ProblemReporter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// TODO: Should this all be build tree scoped?

@NonNullApi
public class TestingBuildSessionScopeServices implements ServiceRegistrationProvider {

    void configure(ServiceRegistration serviceRegistration) {
        serviceRegistration.add(TestListenerBuildOperationAdapter.class);
        serviceRegistration.add(TestEventRecorder.class);
        serviceRegistration.add(TestEventReporter.class);
    }

    @Provides
    TestEventReporterFactory createTestEventService(
        TestListenerBuildOperationAdapter testListenerBuildOperationAdapter,
        TestEventRecorder testEventRecorder
    ) {
        TestListenerInternal compositeTestListener = new CompositeTestListener(ImmutableList.of(
            testListenerBuildOperationAdapter,
            testEventRecorder
        ));
        return new DefaultTestEventReporterFactory(compositeTestListener);
    }

    static class CompositeTestListener implements TestListenerInternal {

        private final ImmutableList<TestListenerInternal> listeners;

        public CompositeTestListener(ImmutableList<TestListenerInternal> listeners) {
            this.listeners = listeners;
        }

        @Override
        public void started(TestDescriptorInternal testDescriptor, TestStartEvent startEvent) {
            for (TestListenerInternal listener : listeners) {
                listener.started(testDescriptor, startEvent);
            }
        }

        @Override
        public void completed(TestDescriptorInternal testDescriptor, TestResult testResult, TestCompleteEvent completeEvent) {
            for (TestListenerInternal listener : listeners) {
                listener.completed(testDescriptor, testResult, completeEvent);
            }
        }

        @Override
        public void output(TestDescriptorInternal testDescriptor, TestOutputEvent event) {
            for (TestListenerInternal listener : listeners) {
                listener.output(testDescriptor, event);
            }
        }
    }

    public static class TestEventRecorder implements TestListenerInternal {

        private final TemporaryFileProvider temporaryFileProvider;

        private final Map<Object, SingleTestExecutionRecorder> executions = new ConcurrentHashMap<>();
        private final Map<Object, BinaryExecutionResults> resultsDirectories = new ConcurrentHashMap<>();

        public TestEventRecorder(TemporaryFileProvider temporaryFileProvider) {
            this.temporaryFileProvider = temporaryFileProvider;
        }

        @Override
        public void started(TestDescriptorInternal testDescriptor, TestStartEvent startEvent) {
            SingleTestExecutionRecorder recorder;
            if (testDescriptor.getParent() == null) {
                recorder = new SingleTestExecutionRecorder(testDescriptor, temporaryFileProvider);
                SingleTestExecutionRecorder previous = executions.put(testDescriptor.getId(), recorder);
                if (previous != null) {
                    throw new IllegalStateException("Root test execution with id " + testDescriptor.getId() + " already started");
                }
            } else {
                TestDescriptorInternal root = getRoot(testDescriptor);
                recorder = executions.get(root.getId());
                if (recorder == null) {
                    throw new IllegalStateException("Root test execution with id " + root.getId() + " not found");
                }
            }

            recorder.started(testDescriptor, startEvent);
        }

        @Override
        public void completed(TestDescriptorInternal testDescriptor, TestResult testResult, TestCompleteEvent completeEvent) {
            SingleTestExecutionRecorder recorder;
            if (testDescriptor.getParent() == null) {
                recorder = executions.remove(testDescriptor.getId());
                if (recorder == null) {
                    throw new IllegalStateException("Root test execution with id " + testDescriptor.getId() + " not found");
                }
            } else {
                TestDescriptorInternal root = getRoot(testDescriptor);
                recorder = executions.get(root.getId());
                if (recorder == null) {
                    throw new IllegalStateException("Root test execution with id " + root.getId() + " not found");
                }
            }

            recorder.completed(testDescriptor, testResult, completeEvent);

            if (testDescriptor.getParent() == null) {
                BinaryExecutionResults results = new BinaryExecutionResults(testDescriptor, recorder.getResultsDirectory());
                resultsDirectories.put(testDescriptor.getId(), results);
            }
        }

        @Override
        public void output(TestDescriptorInternal testDescriptor, TestOutputEvent event) {
            TestDescriptorInternal root = getRoot(testDescriptor);
            SingleTestExecutionRecorder recorder = executions.get(root.getId());
            if (recorder == null) {
                throw new IllegalStateException("Root test execution with id " + root.getId() + " not found");
            }
            recorder.output(testDescriptor, event);
        }

        public ImmutableMap<Object, BinaryExecutionResults> getBinaryResults() {
            if (!executions.isEmpty()) {
                throw new IllegalStateException("Cannot get binary results until all test executions are completed");
            }
            return ImmutableMap.copyOf(resultsDirectories);
        }

        private static TestDescriptorInternal getRoot(TestDescriptorInternal event) {
            TestDescriptorInternal current = event;
            while (current.getParent() != null) {
                current = current.getParent();
            }
            return current;
        }

        public static class BinaryExecutionResults {
            private final TestDescriptorInternal root;
            private final File resultsDirectory;

            public BinaryExecutionResults(TestDescriptorInternal root, File resultsDirectory) {
                this.root = root;
                this.resultsDirectory = resultsDirectory;
            }

            public TestDescriptorInternal getRoot() {
                return root;
            }

            public File getResultsDirectory() {
                return resultsDirectory;
            }
        }
    }

    private static class SingleTestExecutionRecorder implements TestListenerInternal {

        private final File resultsDirectory;
        private final TestOutputStore.Writer outputWriter;
        private final Map<String, TestClassResult> results;
        private final TestListenerInternal delegate;

        private boolean completed = false;

        public SingleTestExecutionRecorder(TestDescriptorInternal root, TemporaryFileProvider temporaryFileProvider) {
            this.resultsDirectory = temporaryFileProvider.createTemporaryDirectory("binary-test-results", root.getId().toString());

            this.outputWriter = new TestOutputStore(resultsDirectory).writer();
            this.results = new ConcurrentHashMap<>();

            TestReportDataCollector testReportDataCollector = new TestReportDataCollector(results, outputWriter);
            this.delegate = new TestListenerAdapter(testReportDataCollector, testReportDataCollector);
        }

        @Override
        public void started(TestDescriptorInternal testDescriptor, TestStartEvent startEvent) {
            delegate.started(testDescriptor, startEvent);
        }

        @Override
        public void completed(TestDescriptorInternal testDescriptor, TestResult testResult, TestCompleteEvent completeEvent) {
            delegate.completed(testDescriptor, testResult, completeEvent);
            if (testDescriptor.getParent() == null) {
                outputWriter.close();
                new TestResultSerializer(resultsDirectory).write(results.values());
                completed = true;
            }
        }

        @Override
        public void output(TestDescriptorInternal testDescriptor, TestOutputEvent event) {
            delegate.output(testDescriptor, event);
        }

        public File getResultsDirectory() {
            if (!completed) {
                throw new IllegalStateException("Test execution not completed yet");
            }
            return resultsDirectory;
        }
    }

    public static class TestEventReporter implements ProblemReporter {

        private final TestEventRecorder recorder;
        private final BuildOperationRunner buildOperationRunner;
        private final BuildOperationExecutor buildOperationExecutor;

        public TestEventReporter(TestEventRecorder recorder, BuildOperationRunner buildOperationRunner, BuildOperationExecutor buildOperationExecutor) {
            this.recorder = recorder;
            this.buildOperationRunner = buildOperationRunner;
            this.buildOperationExecutor = buildOperationExecutor;
        }

        @Override
        public String getId() {
            return "test events";
        }

        @Override
        public void report(File reportDir, ProblemConsumer validationFailures) {
            Map<Object, TestEventRecorder.BinaryExecutionResults> allResults = recorder.getBinaryResults();

            List<TestResultsProvider> allProviders = new ArrayList<>(allResults.size());

            // Generate per-execution reports
            HtmlTestReport htmlReport = new HtmlTestReport(buildOperationRunner, buildOperationExecutor);
            for (Map.Entry<Object, TestEventRecorder.BinaryExecutionResults> entry : allResults.entrySet()) {
                TestEventRecorder.BinaryExecutionResults binaryResults = entry.getValue();
                File resultsDirectory = binaryResults.getResultsDirectory();

                BinaryResultBackedTestResultsProvider resultsProvider = new BinaryResultBackedTestResultsProvider(resultsDirectory);
                allProviders.add(resultsProvider);

                File reportLocation = new File(reportDir, entry.getKey().toString());
                htmlReport.generateReport(resultsProvider, reportLocation);

                String url = new ConsoleRenderer().asClickableFileUrl(reportLocation.toPath().resolve("index.html").toFile());
                System.out.println("Test results for " + entry.getKey() + ": " + url);
            }

            // Generate aggregate report
            File reportLocation = new File(reportDir, "aggregate-test-results");
            String url = new ConsoleRenderer().asClickableFileUrl(reportLocation.toPath().resolve("index.html").toFile());
            System.out.println("Aggregate test results: " + url);
            htmlReport.generateReport(new AggregateTestResultsProvider(allProviders), reportLocation);
        }
    }

}
