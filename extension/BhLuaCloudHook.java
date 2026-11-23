package app.revanced.extension.gamehub;

import android.content.Context;

import com.luatools.javasteam.LuaToolsCloudSync;

import java.io.File;

/**
 * Thin BannerHub adapter delegating pre-launch / post-exit CloudRedirect sync
 * to the universal {@link LuaToolsCloudSync} addon.
 */
public final class BhLuaCloudHook {

    private BhLuaCloudHook() {}

    public static boolean isCompanionInstalled(Context ctx) {
        return LuaToolsCloudSync.isCompanionInstalled(ctx);
    }

    public static void onPreLaunch(Context ctx, int appId, String winePrefix) {
        if (winePrefix == null || winePrefix.isEmpty()) return;
        LuaToolsCloudSync.onPreLaunch(ctx, appId, getGseRemoteDir(winePrefix, appId));
    }

    public static void onPostExit(Context ctx, int appId, String winePrefix) {
        if (winePrefix == null || winePrefix.isEmpty()) return;
        LuaToolsCloudSync.onPostExit(ctx, appId, getGseRemoteDir(winePrefix, appId));
    }

    private static File getGseRemoteDir(String winePrefix, int appId) {
        return new File(winePrefix,
                "drive_c/users/steamuser/AppData/Roaming/GSE Saves/" + appId + "/remote");
    }
}
