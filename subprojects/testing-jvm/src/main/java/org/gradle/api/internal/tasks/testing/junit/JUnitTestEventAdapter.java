/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.api.internal.tasks.testing.junit;

import org.gradle.api.internal.tasks.testing.DefaultTestDescriptor;
import org.gradle.api.internal.tasks.testing.TestCompleteEvent;
import org.gradle.api.internal.tasks.testing.TestDescriptorInternal;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import org.gradle.api.internal.tasks.testing.TestStartEvent;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.internal.id.IdGenerator;
import org.gradle.internal.time.Clock;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JUnitTestEventAdapter extends RunListener {
    private static final Pattern DESCRIPTOR_PATTERN = Pattern.compile("(.*)\\((.*)\\)", Pattern.DOTALL);
    protected final IdGenerator<?> idGenerator;
    private final TestResultProcessor resultProcessor;
    private final Clock clock;
    private final Object lock = new Object();
    private final Map<Object, TestDescriptorInternal> executing = new HashMap<Object, TestDescriptorInternal>();
    private final Set<Object> assumptionFailed = new HashSet<Object>();

    public JUnitTestEventAdapter(TestResultProcessor resultProcessor, Clock clock,
                                 IdGenerator<?> idGenerator) {
        assert resultProcessor instanceof org.gradle.internal.concurrent.ThreadSafe;
        this.resultProcessor = resultProcessor;
        this.clock = clock;
        this.idGenerator = idGenerator;
    }

    @Override
    public void testStarted(Description description) throws Exception {
        testStarted(description, nullSafeDescriptor(idGenerator.generateId(), description));
    }

    protected void testStarted(Object identifier, TestDescriptorInternal descriptor) {
        synchronized (lock) {
            TestDescriptorInternal oldTest = executing.put(identifier, descriptor);
            assert oldTest == null : String.format("Unexpected start event for %s", identifier);
        }
        resultProcessor.started(descriptor, startEvent());
    }

    @Override
    public void testFailure(Failure failure) throws Exception {
        testFailure(failure.getDescription(), nullSafeDescriptor(idGenerator.generateId(), failure.getDescription()), failure.getException());
    }

    protected void testFailure(Object identifier, TestDescriptorInternal descriptor, Throwable exception) {
        if (descriptor == null) {
            return;
        }
        TestDescriptorInternal testInternal;
        synchronized (lock) {
            testInternal = executing.get(identifier);
        }
        boolean needEndEvent = false;
        if (testInternal == null) {
            // This can happen when, for example, a @BeforeClass or @AfterClass method fails
            needEndEvent = true;
            testInternal = descriptor;
            resultProcessor.started(testInternal, startEvent());
        }
        resultProcessor.failure(testInternal.getId(), exception);
        if (needEndEvent) {
            resultProcessor.completed(testInternal.getId(), new TestCompleteEvent(clock.getCurrentTime()));
        }
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        testAssumptionFailure(failure.getDescription());
    }

    protected void testAssumptionFailure(Object identifier){
        synchronized (lock) {
            assumptionFailed.add(identifier);
        }
    }

    @Override
    public void testIgnored(Description description) throws Exception {
        if (methodName(description) == null) {
            // An @Ignored class, ignore the event. We don't get testIgnored events for each method, so we have
            // generate them on our own
            processIgnoredClass(description);
        } else {
            testIgnored(descriptor(idGenerator.generateId(), description));
        }
    }

    protected void testIgnored(TestDescriptorInternal descriptor) {
        resultProcessor.started(descriptor, startEvent());
        resultProcessor.completed(descriptor.getId(), new TestCompleteEvent(clock.getCurrentTime(), TestResult.ResultType.SKIPPED));
    }

    private void processIgnoredClass(Description description) throws Exception {
        IgnoredTestDescriptorProvider provider = new IgnoredTestDescriptorProvider();
        String className = className(description);
        for (Description childDescription : provider.getAllDescriptions(description, className)) {
            testIgnored(childDescription);
        }
    }

    @Override
    public void testFinished(Description description) throws Exception {
        testFinished((Object) description);
    }

    protected void testFinished(Object identifier) {
        long endTime = clock.getCurrentTime();
        TestDescriptorInternal testInternal;
        TestResult.ResultType resultType;
        synchronized (lock) {
            testInternal = executing.remove(identifier);
            if (testInternal == null && executing.size() == 1) {
                // Assume that test has renamed itself (this can actually happen)
                testInternal = executing.values().iterator().next();
                executing.clear();
            }
            assert testInternal != null : String.format("Unexpected end event for %s", identifier);
            resultType = assumptionFailed.remove(identifier) ? TestResult.ResultType.SKIPPED : null;
        }
        resultProcessor.completed(testInternal.getId(), new TestCompleteEvent(endTime, resultType));
    }

    private TestStartEvent startEvent() {
        return new TestStartEvent(clock.getCurrentTime());
    }

    private TestDescriptorInternal descriptor(Object id, Description description) {
        return new DefaultTestDescriptor(id, className(description), methodName(description));
    }

    private TestDescriptorInternal nullSafeDescriptor(Object id, Description description) {
        String methodName = methodName(description);
        if (methodName != null) {
            return new DefaultTestDescriptor(id, className(description), methodName);
        } else {
            return new DefaultTestDescriptor(id, className(description), "classMethod");
        }
    }

    // Use this instead of Description.getMethodName(), it is not available in JUnit <= 4.5
    public static String methodName(Description description) {
        Matcher matcher = methodStringMatcher(description);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }

    // Use this instead of Description.getClassName(), it is not available in JUnit <= 4.5
    public static String className(Description description) {
        Matcher matcher = methodStringMatcher(description);
        return matcher.matches() ? matcher.group(2) : description.toString();
    }

    private static Matcher methodStringMatcher(Description description) {
        return DESCRIPTOR_PATTERN.matcher(description.toString());
    }

}

