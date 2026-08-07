package com.amazmod.service.events;

/**
 * Posted on the local bus after NavigationStore has been updated, so the navigation screen and the
 * springboard widget can refresh. Carries no payload: readers pull the current state from
 * NavigationStore, which keeps a single source of truth.
 */
public class NavigationUpdateEvent {

    private final boolean navigating;

    public NavigationUpdateEvent(boolean navigating) {
        this.navigating = navigating;
    }

    public boolean isNavigating() {
        return navigating;
    }
}
