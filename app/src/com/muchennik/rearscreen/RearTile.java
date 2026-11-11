package com.muchennik.rearscreen;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: start/stop the rear-screen media. */
public class RearTile extends TileService {
    @Override public void onStartListening() { update(); }

    @Override public void onClick() {
        boolean on = new Prefs(this).running();
        Intent i = new Intent(this, RearService.class).setAction(on ? RearService.ACTION_STOP : RearService.ACTION_START);
        if (on) startService(i);
        else startForegroundService(i);
        Tile t = getQsTile();
        if (t != null) {
            t.setState(on ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
            t.updateTile();
        }
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(new Prefs(this).running() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }
}
