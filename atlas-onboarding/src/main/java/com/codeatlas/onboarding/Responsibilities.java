package com.codeatlas.onboarding;

import com.codeatlas.model.Entity;
import com.codeatlas.tools.Views;

import java.util.List;

/**
 * A small, deterministic role->responsibility label lookup shared by the onboarding
 * stages. This is a label mapping over already-extracted attributes, not analysis
 * logic - the responsibility it returns is always an inference and is labelled as
 * such by callers.
 */
final class Responsibilities {

    private Responsibilities() {
    }

    static String of(Views.EntityView c, List<Views.NeighborView> members) {
        String role = c.attributes().getOrDefault(Entity.Attributes.ROLE, "");
        switch (role) {
            case "controller":
                return "handles HTTP requests";
            case "service":
                return "business/service logic";
            case "repository":
                return "data access";
            case "mapper-interface":
                return "data mapping";
            case "dto-request":
                return "request data transfer object";
            case "dto-response":
                return "response data transfer object";
            default:
                break;
        }
        if ("true".equals(c.attributes().get(Entity.Attributes.JPA_ENTITY))) {
            return "persistent entity";
        }
        boolean hasTransformation = members.stream()
                .anyMatch(m -> "true".equals(m.entity().attributes().get(Entity.Attributes.TRANSFORMATION)));
        if (hasTransformation) {
            return "data transformation";
        }
        boolean hasNative = members.stream()
                .anyMatch(m -> "true".equals(m.entity().attributes().get(Entity.Attributes.NATIVE_METHOD)));
        if (hasNative) {
            return "native (JNI) boundary adapter";
        }
        boolean hasState = members.stream().anyMatch(m -> m.entity().kind().equals("VARIABLE"));
        if (hasState) {
            return "holds package state and its operations";
        }
        return "responsibility not determinable from structure";
    }
}
