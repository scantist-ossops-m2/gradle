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

package org.gradle.api.tasks.diagnostics

import org.gradle.integtests.fixtures.AbstractIntegrationSpec

class ArtifactTransformsReportTaskIntegrationTest extends AbstractIntegrationSpec {
    def setup() {
        settingsFile << """
            rootProject.name = "myLib"
        """
    }

    def "if no artifact transforms present in project, task reports complete absence"() {
        expect:
        succeeds ':artifactTransforms'
        reportsCompleteAbsenceOfArtifactTransforms()
    }

    private void reportsCompleteAbsenceOfArtifactTransforms(String projectName = "myLib") {
        outputContains("There are no Artifact Transforms registered in project '$projectName'.")
    }
}
