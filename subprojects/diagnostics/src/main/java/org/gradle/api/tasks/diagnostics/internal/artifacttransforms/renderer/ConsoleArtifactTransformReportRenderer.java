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

package org.gradle.api.tasks.diagnostics.internal.artifacttransforms.renderer;

import org.gradle.api.tasks.diagnostics.internal.artifacttransforms.model.ArtifactTransformReportModel;
import org.gradle.api.tasks.diagnostics.internal.artifacttransforms.spec.ArtifactTransformReportSpec;
import org.gradle.internal.logging.text.StyledTextOutput;

import javax.annotation.Nullable;

public final class ConsoleArtifactTransformReportRenderer extends AbstractArtifactTransformReportRenderer<StyledTextOutput> {
    @Nullable
    private StyledTextOutput output;

    public ConsoleArtifactTransformReportRenderer(ArtifactTransformReportSpec spec) {
        super(spec);
    }

    @Override
    public void render(ArtifactTransformReportModel model, StyledTextOutput output) {
        this.output = output;

        final boolean hasAnyRelevantTransforms = !model.getTransforms().isEmpty(); // TODO: filter on spec here
        if (hasAnyRelevantTransforms) {
            // TODO: report on transforms
            message("Here are some ATs");
        } else {
            writeCompleteAbsenceOfResults(model);
        }
    }

    private void writeCompleteAbsenceOfResults(ArtifactTransformReportModel model) {
        message("There are no Artifact Transforms registered in project '" + model.getProjectName() + "'.");
    }

    private void message(String msg) {
        output.text(msg).println();
    }
}
