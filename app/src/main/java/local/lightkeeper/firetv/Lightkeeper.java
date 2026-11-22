package local.lightkeeper.firetv;

import android.content.Context;

/** The one store, app list and server of the process. */
final class Lightkeeper {
    private static Lightkeeper one;
    final Store store;
    final Apps apps;
    final Server server;

    private Lightkeeper(Context ctx) {
        store = new Store(ctx);
        apps = new Apps(ctx);
        server = new Server(store, apps);
    }

    static synchronized Lightkeeper get(Context ctx) {
        if (one == null) one = new Lightkeeper(ctx.getApplicationContext());
        return one;
    }
}
