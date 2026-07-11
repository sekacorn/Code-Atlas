package com.codeatlas.analysis;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.SoftwareModel;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes {@link RepositoryMetrics} from the model.
 *
 * <p>Line counts come from {@code FILE} entities (populated by the pipeline);
 * entity counts come from the merged, de-duplicated model, so a package split
 * across many files is counted once.
 */
public final class MetricsEngine {

    public RepositoryMetrics compute(SoftwareModel model) {
        int totalFiles = 0;
        long totalLines = 0;
        long commentLines = 0;
        long blankLines = 0;
        Map<String, Integer> filesByLanguage = new TreeMap<>();

        for (Entity file : model.entitiesOfKind(EntityKind.FILE)) {
            totalFiles++;
            totalLines += file.intAttribute(Entity.Attributes.LINES_OF_CODE, 0);
            commentLines += file.intAttribute(Entity.Attributes.COMMENT_LINES, 0);
            blankLines += file.intAttribute(Entity.Attributes.BLANK_LINES, 0);
            filesByLanguage.merge(file.language(), 1, Integer::sum);
        }
        long codeLines = Math.max(0, totalLines - commentLines - blankLines);

        Map<EntityKind, Integer> counts = new EnumMap<>(EntityKind.class);
        for (Entity e : model.entities()) {
            counts.merge(e.kind(), 1, Integer::sum);
        }

        return new RepositoryMetrics(totalFiles, totalLines, codeLines, commentLines, blankLines,
                filesByLanguage, counts);
    }
}
