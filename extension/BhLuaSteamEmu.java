package app.revanced.extension.gamehub;

import android.content.Context;

import com.luatools.javasteam.LuaToolsSteamEmu;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin BannerHub adapter around the portable {@link LuaToolsSteamEmu}.
 *
 * BannerHub-specific responsibilities only:
 *   - Locates BannerHub's Steam client component (usr/home/components/steam_9866233).
 *   - Scopes WINEDLLOVERRIDES=dwmapi,xinput1_4=n,b strictly to startSteam.bat (with backup/restore).
 *   - Resolves game install dirs from Steam/steamapps/appmanifest_<appId>.acf.
 *
 * Never touches drive_c/windows/system32, syswow64, or global user.reg DllOverrides.
 */
public final class BhLuaSteamEmu {

    private BhLuaSteamEmu() {}

    static final String PREFS = LuaToolsSteamEmu.PREFS;

    public static File steamClientDir(Context ctx) {
        return new File(ctx.getFilesDir(), "usr/home/components/steam_9866233");
    }

    public static LuaToolsSteamEmu.HostAdapter hostAdapter() {
        return new LuaToolsSteamEmu.HostAdapter() {
            @Override public File resolveSteamClientDir(Context ctx) {
                return steamClientDir(ctx);
            }
            @Override public String resolveSteamLuaPathWin(Context ctx) {
                return "Z:" + new File(ctx.getFilesDir(), "Steam/config/stplug-in").getAbsolutePath().replace('/', '\\');
            }
            @Override public File resolveGameInstallDir(Context ctx, int appId) {
                return findGameDir(ctx, appId);
            }
            @Override public void onSteamClientAttached(Context ctx, File dir, String mode) {
                patchStartSteamBat(dir);
            }
            @Override public void onSteamClientDetached(Context ctx, File dir) {
                restoreStartSteamBat(dir);
            }
        };
    }

    public static void patchStartSteamBat(File steamClientDir) {
        try {
            File bat = new File(steamClientDir, "startSteam.bat");
            if (!bat.isFile()) return;
            File bak = new File(steamClientDir, "startSteam.bat" + LuaToolsSteamEmu.BACKUP_SUFFIX);
            String content = new String(LuaToolsSteamEmu.readAll(bat), "UTF-8");
            if (!bak.isFile()) {
                LuaToolsSteamEmu.writeAll(bak, content.getBytes("UTF-8"));
            }
            if (!content.contains("WINEDLLOVERRIDES=")) {
                String patched = "set WINEDLLOVERRIDES=dwmapi,xinput1_4=n,b\r\n" + content;
                LuaToolsSteamEmu.writeAll(bat, patched.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {}
    }

    public static void restoreStartSteamBat(File steamClientDir) {
        try {
            File bat = new File(steamClientDir, "startSteam.bat");
            File bak = new File(steamClientDir, "startSteam.bat" + LuaToolsSteamEmu.BACKUP_SUFFIX);
            if (bak.isFile()) {
                if (bat.isFile()) bat.delete();
                bak.renameTo(bat);
            }
        } catch (Throwable ignored) {}
    }

    public static JSONObject attachForApp(Context ctx, int appId) {
        JSONObject out = new JSONObject();
        try {
            if (!LuaToolsSteamEmu.MODE_OFF.equals(LuaToolsSteamEmu.mode(ctx))) {
                out.put("steam_client", LuaToolsSteamEmu.attachSteamClient(ctx, hostAdapter()));
            }
            out.put("game", LuaToolsSteamEmu.attachGameRuntime(ctx, appId, findGameDir(ctx, appId)));
            out.put("ok", true);
        } catch (Throwable t) {
            try { out.put("ok", false); out.put("error", String.valueOf(t.getMessage())); } catch (Throwable ignored) {}
        }
        return out;
    }

    public static JSONArray attachAll(Context ctx) {
        JSONArray results = new JSONArray();
        if (!LuaToolsSteamEmu.MODE_OFF.equals(LuaToolsSteamEmu.mode(ctx))) {
            LuaToolsSteamEmu.attachSteamClient(ctx, hostAdapter());
        }
        File luaDir = new File(ctx.getFilesDir(), "Steam/config/stplug-in");
        String[] names = luaDir.list();
        if (names == null) return results;
        for (String n : names) {
            if (!n.endsWith(".lua")) continue;
            int appId;
            try { appId = Integer.parseInt(n.substring(0, n.length() - 4)); } catch (Throwable t) { continue; }
            File marker = new File(LuaToolsSteamEmu.stateRootDir(ctx), "state/" + appId + ".json");
            if (marker.isFile()) continue;
            results.put(LuaToolsSteamEmu.attachGameRuntime(ctx, appId, findGameDir(ctx, appId)));
        }
        return results;
    }

    public static JSONObject detach(Context ctx, int appId) {
        if (appId > 0) return LuaToolsSteamEmu.detachGame(ctx, appId);
        return LuaToolsSteamEmu.detachSteamClient(ctx, hostAdapter());
    }

    public static File findGameDir(Context ctx, int appId) {
        File common = new File(ctx.getFilesDir(), "Steam/steamapps/common");
        try {
            File acf = new File(ctx.getFilesDir(), "Steam/steamapps/appmanifest_" + appId + ".acf");
            if (acf.isFile()) {
                String txt = new String(LuaToolsSteamEmu.readAll(acf), "UTF-8");
                Matcher m = Pattern.compile("\"installdir\"\\s+\"([^\"]+)\"").matcher(txt);
                if (m.find()) {
                    File d = new File(common, m.group(1));
                    if (d.isDirectory()) return d;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
