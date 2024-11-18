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

package org.gradle.api.tasks.diagnostics.internal.artifacttransforms.model;

import org.gradle.api.Project;

import java.util.Collections;

/**
 * Factory for creating {@link ArtifactTransformReportModel} instances which represent the Artifact Transforms present in a project.
 */
public final class ArtifactTransformReportModelFactory {
    public ArtifactTransformReportModel buildForProject(Project project) {
        return new ArtifactTransformReportModel(project.getName(), Collections.emptyList());
    }
}
