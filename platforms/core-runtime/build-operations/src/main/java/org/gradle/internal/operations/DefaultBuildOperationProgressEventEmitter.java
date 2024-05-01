/*
 * Copyright 2022 the original author or authors.
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

package org.gradle.internal.operations;

import javax.annotation.Nullable;

public class DefaultBuildOperationProgressEventEmitter implements BuildOperationProgressEventEmitter {

    private final BuildOperationTimeSupplier clock;
    private final CurrentBuildOperationRef current;
    private final BuildOperationListener listener;

    public DefaultBuildOperationProgressEventEmitter(BuildOperationTimeSupplier clock, CurrentBuildOperationRef current, BuildOperationListener listener) {
        this.clock = clock;
        this.current = current;
        this.listener = listener;
    }

    @Override
    public void emit(@Nullable OperationIdentifier operationIdentifier, long timestamp, long monotonicTimestamp, @Nullable Object details) {
        // Explicit check in case of unsafe CurrentBuildOperationRef usage
        if (operationIdentifier == null) {
            throw new IllegalArgumentException("operationIdentifier is null");
        }
        doEmit(operationIdentifier, timestamp, monotonicTimestamp, details);
    }

    @Override
    public void emitNow(@Nullable OperationIdentifier operationIdentifier, @Nullable Object details) {
        emit(operationIdentifier, clock.getCurrentTime(), System.nanoTime(), details);
    }

    @Override
    public void emitNowIfCurrent(Object details) {
        emitIfCurrent(clock.getCurrentTime(), System.nanoTime(), details);
    }

    @Override
    public void emitIfCurrent(long time, long monotonicTime, Object details) {
        OperationIdentifier currentOperationIdentifier = current.getId();
        if (currentOperationIdentifier != null) {
            doEmit(currentOperationIdentifier, time, monotonicTime, details);
        }
    }

    @Override
    public void emitNowForCurrent(Object details) {
        emitForCurrent(clock.getCurrentTime(), System.nanoTime(), details);
    }

    private void emitForCurrent(long time, long monotonicTimestamp, Object details) {
        OperationIdentifier currentOperationIdentifier = current.getId();
        if (currentOperationIdentifier == null) {
            throw new IllegalStateException("No current build operation");
        } else {
            doEmit(currentOperationIdentifier, time, monotonicTimestamp, details);
        }
    }

    private void doEmit(OperationIdentifier operationIdentifier, long timestamp, long monotonicTimestamp, @Nullable Object details) {
        listener.progress(operationIdentifier, new OperationProgressEvent(timestamp, monotonicTimestamp, details));
    }
}
