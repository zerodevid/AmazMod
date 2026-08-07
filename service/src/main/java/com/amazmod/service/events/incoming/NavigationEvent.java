package com.amazmod.service.events.incoming;

import com.huami.watch.transport.DataBundle;

public class NavigationEvent {

    private DataBundle dataBundle;

    public NavigationEvent(DataBundle dataBundle) {
        this.dataBundle = dataBundle;
    }

    public DataBundle getDataBundle() {
        return dataBundle;
    }
}
