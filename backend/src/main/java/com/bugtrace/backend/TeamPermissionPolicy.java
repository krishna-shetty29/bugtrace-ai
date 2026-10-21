package com.bugtrace.backend;
import java.util.*;
public final class TeamPermissionPolicy {
    private TeamPermissionPolicy() {}
    static boolean permitted(TeamAccessContext.Actor actor,String action) {
        if(actor==null||action==null)return false;
        if("WORKER".equals(actor.kind()))return "WORKER".equals(action);
        if(!"HUMAN".equals(actor.kind())||actor.role()==null)return false;
        return switch(action) {
            case "READ" -> Set.of("OWNER","INVESTIGATOR","REVIEWER","VIEWER").contains(actor.role());
            case "WRITE" -> Set.of("OWNER","INVESTIGATOR").contains(actor.role());
            case "REVIEW" -> Set.of("OWNER","REVIEWER").contains(actor.role());
            case "REVIEW_SUBMISSION" -> Set.of("OWNER","INVESTIGATOR","REVIEWER").contains(actor.role());
            case "OWNER" -> "OWNER".equals(actor.role());
            default -> false;
        };
    }
}
