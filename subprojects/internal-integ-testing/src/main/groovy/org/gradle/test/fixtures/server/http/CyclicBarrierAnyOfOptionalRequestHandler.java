/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.test.fixtures.server.http;

import java.util.Collection;
import java.util.concurrent.locks.Lock;

/**
 * A cyclic barrier for {@link BlockingHttpServer} where expectations are optional.
 */
public class CyclicBarrierAnyOfOptionalRequestHandler extends CyclicBarrierAnyOfRequestHandler {
    private final Lock lock;

    CyclicBarrierAnyOfOptionalRequestHandler(Lock lock, int testId, int timeoutMs, int maxConcurrent, WaitPrecondition previous, Collection<? extends ResourceExpectation> expectedRequests) {
        super(lock, testId, timeoutMs, maxConcurrent, previous, expectedRequests);
        this.lock = lock;
    }

    @Override
    public void assertComplete() {
        lock.lock();
        try {
            if (failure != null) {
                throw failure;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void releaseAll() {
        lock.lock();
        try {
            expected.clear();
        } finally {
            lock.unlock();
        }
    }
}