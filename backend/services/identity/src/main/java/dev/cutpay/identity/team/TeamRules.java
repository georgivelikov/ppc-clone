package dev.cutpay.identity.team;

import java.util.Set;

/**
 * Who may hand out, change and take away which roles. Owners run the business; admins run the team but cannot
 * create or touch other admins, so an admin account that is taken over cannot lock the owner out. The owner role
 * itself is never given or taken here.
 */
public final class TeamRules {

    public static final Set<String> ASSIGNABLE = Set.of("admin", "developer", "viewer", "support");

    private TeamRules() {
    }

    public static boolean canGrant(String actorRole, String role) {
        return switch (actorRole) {
            case "owner" -> ASSIGNABLE.contains(role);
            case "admin" -> ASSIGNABLE.contains(role) && !role.equals("admin");
            default -> false;
        };
    }

    /** Changing or removing a member needs authority over the role they have now. */
    public static boolean canManage(String actorRole, String targetRole) {
        return switch (actorRole) {
            case "owner" -> !targetRole.equals("owner");
            case "admin" -> !targetRole.equals("owner") && !targetRole.equals("admin");
            default -> false;
        };
    }
}
