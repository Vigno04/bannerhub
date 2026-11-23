package app.revanced.extension.gamehub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;

import com.luatools.javasteam.LuaToolsBridgeProvider;
import com.luatools.javasteam.LuaToolsJavaSteamHook;
import com.luatools.javasteam.LuaToolsSteamEmu;
import com.luatools.javasteam.LuaToolsStore;

import java.io.File;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BannerHub adapter extending the universal {@link LuaToolsBridgeProvider}.
 *
 * Universal streaming (openFile), .lua/.manifest storage (LuaToolsStore),
 * JavaSteam manifest decryption (LuaToolsJavaSteamHook), and CloudRedirect sync
 * (LuaToolsCloudSync) all come from com.luatools.javasteam.
 *
 * Authority: <packageName>.bh_lua_bridge
 */
public class BhLuaBridgeProvider extends LuaToolsBridgeProvider {

    @Override
    protected String getAppDisplayName(Context ctx) {
        return "BannerHub";
    }

    @Override
    protected void onFixDllOverrides(Context ctx, int appId, java.util.Set<String> dllStems) {
        applyWineDllOverridesForApp(ctx, appId, dllStems);
    }

    @Override
    protected File resolveSteamClientDir(Context ctx) {
        return BhLuaSteamEmu.steamClientDir(ctx);
    }

    @Override
    protected void onSteamClientAttached(Context ctx, File steamClientDir, String mode) {
        BhLuaSteamEmu.patchStartSteamBat(steamClientDir);
    }

    @Override
    protected void onSteamClientDetached(Context ctx, File steamClientDir) {
        BhLuaSteamEmu.restoreStartSteamBat(steamClientDir);
    }

    @Override
    protected File resolveGameInstallDir(Context ctx, int appId, String fallbackName) {
        File common = new File(ctx.getFilesDir(), "Steam/steamapps/common");
        try {
            File acf = new File(ctx.getFilesDir(), "Steam/steamapps/appmanifest_" + appId + ".acf");
            if (acf.isFile()) {
                String txt = new String(readAllBytes(acf), "UTF-8");
                Matcher m = Pattern.compile("\"installdir\"\\s+\"([^\"]+)\"").matcher(txt);
                if (m.find()) {
                    File d = new File(common, m.group(1));
                    if (d.isDirectory()) return d;
                }
            }
        } catch (Throwable ignored) {
        }
        return super.resolveGameInstallDir(ctx, appId, fallbackName);
    }

    @Override
    public boolean onCreate() {
        final Context ctx = getContext();
        if (ctx != null) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() { watchLoop(ctx); }
            }, "bh-lua-watch");
            t.setDaemon(true);
            t.start();
        }
        return true;
    }

    // ---- Background watcher: auto fake-Steam mode for .lua games + cloud-save launch/exit hooks ----
    private static boolean watching;

    private static void watchLoop(Context ctx) {
        synchronized (BhLuaBridgeProvider.class) {
            if (watching) return;
            watching = true;
        }
        boolean wasRunning = false;
        int runningApp = 0;
        String runningPrefix = null;
        String runningExe = null;
        boolean sawGameExe = false;
        long exeGoneSince = 0L;
        while (true) {
            try {
                ensureFakeSteam(ctx);
                try { BhLuaSteamEmu.attachAll(ctx); } catch (Throwable ignored) {}
                int winePid = findWinePid(ctx);
                boolean running = winePid > 0;
                if (running && !wasRunning) {
                    sawGameExe = false;
                    exeGoneSince = 0L;
                    runningExe = null;
                    String[] info = latestLaunch(ctx);
                    if (info != null) {
                        runningApp = Integer.parseInt(info[0]);
                        runningPrefix = info[1];
                        runningExe = info.length > 2 ? info[2] : null;
                        if (LuaToolsJavaSteamHook.get(ctx).isLuaGame(runningApp)) {
                            BhLuaCloudHook.onPreLaunch(ctx, runningApp, runningPrefix);
                        } else {
                            runningApp = 0;
                        }
                    }
                } else if (running && LuaToolsSteamEmu.autoCloseEnabled(ctx) && runningExe != null) {
                    if (isExeRunningInProc(runningExe)) {
                        sawGameExe = true;
                        exeGoneSince = 0L;
                    } else if (sawGameExe) {
                        long now = System.currentTimeMillis();
                        if (exeGoneSince == 0L) {
                            exeGoneSince = now;
                        } else if (now - exeGoneSince >= 4000L) {
                            android.os.Process.killProcess(winePid);
                        }
                    }
                } else if (!running && wasRunning) {
                    if (runningApp > 0) {
                        BhLuaCloudHook.onPostExit(ctx, runningApp, runningPrefix);
                        runningApp = 0;
                    }
                    runningExe = null;
                    sawGameExe = false;
                    exeGoneSince = 0L;
                }
                wasRunning = running;
            } catch (Throwable ignored) {
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    private static int findWinePid(Context ctx) {
        android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> l = am.getRunningAppProcesses();
        if (l == null) return -1;
        for (android.app.ActivityManager.RunningAppProcessInfo p : l) {
            if (p.processName != null && p.processName.endsWith(":wine")) return p.pid;
        }
        return -1;
    }

    private static boolean isWineRunning(Context ctx) {
        return findWinePid(ctx) > 0;
    }

    private static boolean isExeRunningInProc(String exeName) {
        if (exeName == null || exeName.isEmpty()) return false;
        String needle = exeName.toLowerCase();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return false;
        for (File p : procs) {
            String n = p.getName();
            if (n.isEmpty() || n.charAt(0) < '0' || n.charAt(0) > '9') continue;
            File cmd = new File(p, "cmdline");
            if (!cmd.isFile()) continue;
            try {
                byte[] b = readAllBytes(cmd);
                if (b.length == 0) continue;
                String s = new String(b, "UTF-8").replace('\0', ' ').toLowerCase();
                if (s.contains(needle)) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** Returns {appId, winePrefixDir, exeBasename} for the most recently launched Steam game. */
    private static String[] latestLaunch(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "pcLaunchLog");
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        File best = null;
        for (File f : fs) if (best == null || f.lastModified() > best.lastModified()) best = f;
        if (best == null) return null;
        String txt = new String(readAllBytes(best), "UTF-8");
        Matcher m = Pattern.compile("SteamId\\s*=\\s*(\\d+)").matcher(txt);
        if (!m.find()) return null;
        String exeName = "";
        Matcher em = Pattern.compile("start path\\s*=\\s*([^\\r\\n]+)").matcher(txt);
        if (em.find()) {
            String p = em.group(1).trim().replace('\\', '/');
            int slash = p.lastIndexOf('/');
            exeName = (slash >= 0) ? p.substring(slash + 1) : p;
        }
        String gameId = best.getName().replace("launchLog", "").replace(".txt", "");
        return new String[]{m.group(1), new File(ctx.getFilesDir(), "usr/home/virtual_containers/" + gameId).getAbsolutePath(), exeName};
    }

    /** For every installed .lua game, make BannerHub use its fake-Steam launch (no real Steam ownership check). */
    private static void ensureFakeSteam(Context ctx) {
        File lua = new File(ctx.getFilesDir(), "Steam/config/stplug-in");
        String[] names = lua.list();
        File dbf = ctx.getDatabasePath("ux_db");
        if (names == null || !dbf.exists()) return;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbf.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            for (String n : names) {
                if (!n.endsWith(".lua")) continue;
                String appId = n.substring(0, n.length() - 4);
                Cursor c = db.rawQuery("SELECT local_game_id, game_id FROM t_game_library WHERE package_name=?", new String[]{"com.steam." + appId});
                try {
                    while (c.moveToNext()) {
                        setFake(ctx, c.getString(0));
                        setFake(ctx, c.getString(1));
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (db != null) db.close();
        }
    }

    private static void setFake(Context ctx, String gameId) {
        if (gameId == null || !gameId.startsWith("steam_")) return;
        android.content.SharedPreferences sp = ctx.getSharedPreferences("pc_g_setting" + gameId, Context.MODE_PRIVATE);
        String cur = sp.getString("pc_ls_steam_client", null);
        if (LuaToolsSteamEmu.useRealSteamClient(ctx) && LuaToolsSteamEmu.isSteamClientAttached(ctx)) {
            // Attached to a local Steam emulator: stop forcing the fake/minimal client so BannerHub
            // uses its normal (agent-driven) launch path again, including clean container shutdown.
            String orig = ctx.getSharedPreferences(BhLuaSteamEmu.PREFS, Context.MODE_PRIVATE).getString("orig_" + gameId, null);
            if (orig == null) {
                // A previous bridge build forced fake mode before this code existed, so no original
                // was captured. Fall back to BannerHub's own global Steam-client descriptor.
                orig = ctx.getSharedPreferences("pc_g_setting", Context.MODE_PRIVATE).getString("pc_ls_steam_client", null);
            }
            if (orig != null && orig.length() > 2 && !orig.equals(cur)) {
                sp.edit().putString("pc_ls_steam_client", orig).commit();
            }
            return;
        }
        if (cur != null && !cur.matches("(?s).*\"id\"\\s*:\\s*-1\\b.*")) {
            ctx.getSharedPreferences(BhLuaSteamEmu.PREFS, Context.MODE_PRIVATE).edit()
                    .putString("orig_" + gameId, cur).commit();
        }
        String fake = "{\"contentType\":-1,\"displayName\":\"\",\"fileMd5\":\"\",\"fileName\":\"\",\"framework\":\"\",\"id\":-1,\"name\":\"\",\"subData\":null,\"version\":\"\",\"versionCode\":0}";
        if (cur == null) {
            sp.edit().putString("pc_ls_steam_client", fake).commit();
        } else if (!cur.matches("(?s).*\"id\"\\s*:\\s*-1\\b.*")) {
            sp.edit().putString("pc_ls_steam_client", cur.replaceFirst("\"id\"\\s*:\\s*\\d+", "\"id\":-1")).commit();
        }
    }
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null || method == null) return null;

        Bundle res = new Bundle();
        switch (method) {
            case "ping": {
                res.putBoolean("ok", true);
                res.putInt("bridge_version", BRIDGE_VERSION);
                res.putString("app_name", "BannerHub");
                res.putString("package_name", ctx.getPackageName());
                res.putBoolean("supports_auto_sync", true);
                res.putBoolean("supports_mt_provider", true);
                res.putString(
                        "mt_authority",
                        ctx.getPackageName() + ".app.revanced.extension.gamehub.filemanager.MTDataFilesProvider"
                );
                return res;
            }
            case "list_prefixes": {
                ArrayList<String> prefixes = new ArrayList<>();
                File filesDir = ctx.getFilesDir();
                if (filesDir != null) {
                    collectPrefixes(filesDir, prefixes, 0);
                }
                res.putBoolean("ok", true);
                res.putStringArrayList("prefixes", prefixes);
                return res;
            }
            case "add_steam_game": {
                int appId = extras != null ? extras.getInt("app_id", 0) : 0;
                String name = extras != null ? extras.getString("name", "") : "";
                String luaContent = extras != null ? extras.getString("lua_content", "") : "";
                if (appId <= 0 || luaContent == null || luaContent.isEmpty()) {
                    res.putBoolean("ok", false);
                    res.putString("error", "Missing app_id or lua_content");
                    return res;
                }
                try {
                    File filesDir = ctx.getFilesDir();
                    File stPlugIn = new File(filesDir, "Steam/config/stplug-in");
                    if (!stPlugIn.exists()) stPlugIn.mkdirs();
                    File luaFile = new File(stPlugIn, appId + ".lua");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(luaFile);
                    fos.write(luaContent.getBytes("UTF-8"));
                    fos.close();

                    String resolvedName = resolveGameName(appId, name, luaContent);
                    injectIntoSteamDatabases(ctx, appId, resolvedName, luaContent);
                    manifestFileNames.clear();
                    java.util.Map<String, long[]> manifestSizes = new java.util.HashMap<>();
                    fixComponentUrls(ctx);
                    int manifestsCached = installJavaSteamManifests(ctx, appId, luaContent, extras, manifestSizes);
                    String exe = detectExecutable(ctx, resolvedName, manifestFileNames);
                    writeAppInfoVdf(ctx, appId, resolvedName, luaContent, manifestSizes, exe);
                    if (exe != null) patchExecutable(ctx, appId, exe);
                    try {
                        res.putString("steam_attach", String.valueOf(BhLuaSteamEmu.attachForApp(ctx, appId)));
                    } catch (Throwable ignored) {
                    }

                    res.putBoolean("ok", true);
                    res.putInt("app_id", appId);
                    res.putInt("manifests_cached", manifestsCached);
                    res.putString("name", resolvedName);
                    res.putString("path", luaFile.getAbsolutePath());
                } catch (Exception e) {
                    res.putBoolean("ok", false);
                    res.putString("error", e.getMessage());
                }
                return res;
            }
            case "set_steam_emu":
            case "steam_emu_attach":
            case "steam_emu_detach":
            case "steam_emu_status":
                return super.call(method, arg, extras);
            case "bridge_status": {
                res = super.call(method, arg, extras);
                res.putBoolean("wine_running", isWineRunning(ctx));
                try {
                    String[] info = latestLaunch(ctx);
                    if (info != null) {
                        res.putString("last_app_id", info[0]);
                        res.putString("last_prefix", info[1]);
                    }
                } catch (Throwable ignored) {
                }
                return res;
            }
            case "apply_fix":
                return super.call(method, arg, extras);
            default:
                return super.call(method, arg, extras);
        }
    }

    private static final ArrayList<String> manifestFileNames = new ArrayList<>();

    private static void applyWineDllOverridesForApp(Context ctx, int appId, java.util.Set<String> dlls) {
        File dbf = ctx.getDatabasePath("ux_db");
        if (!dbf.exists()) return;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbf.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            Cursor c = db.rawQuery("SELECT local_game_id, game_id FROM t_game_library WHERE package_name=?",
                    new String[]{"com.steam." + appId});
            try {
                while (c.moveToNext()) {
                    String gameId = c.getString(0);
                    File reg = new File(ctx.getFilesDir(), "usr/home/virtual_containers/" + gameId + "/user.reg");
                    if (!reg.isFile()) continue;
                    String content = new String(readAllBytes(reg), "UTF-8");
                    int idx = content.indexOf("[Software\\\\Wine\\\\DllOverrides]");
                    if (idx < 0) continue;
                    int nl = content.indexOf('\n', idx);
                    if (nl < 0) continue;
                    int insertPos = nl + 1;
                    if (content.startsWith("#time=", insertPos)) {
                        int nl2 = content.indexOf('\n', insertPos);
                        if (nl2 >= 0) insertPos = nl2 + 1;
                    }
                    StringBuilder add = new StringBuilder();
                    for (String dll : dlls) {
                        String line = "\"" + dll + "\"=\"native,builtin\"";
                        if (!content.contains("\"" + dll + "\"=")) {
                            add.append(line).append('\n');
                        }
                    }
                    if (add.length() > 0) {
                        String updated = content.substring(0, insertPos) + add + content.substring(insertPos);
                        writeAllBytes(reg, updated.getBytes("UTF-8"));
                    }
                }
            } finally {
                c.close();
            }
        } catch (Throwable ignored) {
        } finally {
            if (db != null) db.close();
        }
    }

    private static String resolveGameName(int appId, String providedName, String luaContent) {
        if (providedName != null && !providedName.isEmpty() && !providedName.startsWith("AppID ")) {
            return providedName;
        }
        Pattern headerPat = Pattern.compile("^--\\s*" + appId + "\\s*-\\s*(.+)$", Pattern.MULTILINE);
        Matcher m = headerPat.matcher(luaContent);
        if (m.find()) {
            String found = m.group(1);
            if (found != null && !found.trim().isEmpty()) return found.trim();
        }
        return (providedName != null && !providedName.isEmpty()) ? providedName : ("AppID " + appId);
    }

    private static void injectIntoSteamDatabases(Context ctx, int appId, String name, String luaContent) {
        long nowMs = System.currentTimeMillis();
        long userId = 1L;

        File steamDbFile = ctx.getDatabasePath("xj_steam_db");
        if (steamDbFile != null && steamDbFile.exists()) {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    steamDbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            try {
                Cursor c = db.rawQuery("SELECT id FROM steam_account WHERE is_current_user = 1 LIMIT 1", null);
                if (c != null) {
                    if (c.moveToFirst()) userId = c.getLong(0);
                    c.close();
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS t_luatools_keep (app_id INTEGER PRIMARY KEY)");
                db.execSQL("DELETE FROM t_luatools_keep WHERE app_id = ?", new Object[]{appId});
                db.execSQL(
                        "DELETE FROM t_steam_user_games WHERE user_id = ? AND appid = ?",
                        new Object[]{userId, appId}
                );
                ContentValues cv = new ContentValues();
                cv.put("user_id", userId);
                cv.put("appid", appId);
                cv.put("name", name);
                cv.put("last_update_time", nowMs);
                cv.put("extra_maps", "");
                db.insertOrThrow("t_steam_user_games", null, cv);

                db.execSQL("INSERT OR REPLACE INTO t_luatools_keep (app_id) VALUES (?)", new Object[]{appId});
                db.execSQL(
                        "CREATE TRIGGER IF NOT EXISTS trg_lt_keep_user_games " +
                        "BEFORE DELETE ON t_steam_user_games " +
                        "WHEN OLD.appid IN (SELECT app_id FROM t_luatools_keep) " +
                        "BEGIN SELECT RAISE(IGNORE); END;"
                );
            } finally {
                db.close();
            }
        }

        File picsDbFile = ctx.getDatabasePath("xj_steam_pics_v5");
        if (picsDbFile != null && picsDbFile.exists()) {
            SQLiteDatabase pdb = SQLiteDatabase.openDatabase(
                    picsDbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            try {
                pdb.beginTransaction();
                long pkgId = appId;

                pdb.execSQL("CREATE TABLE IF NOT EXISTS t_luatools_keep (app_id INTEGER PRIMARY KEY, package_id INTEGER)");
                pdb.execSQL("DELETE FROM t_luatools_keep WHERE app_id = ?", new Object[]{appId});

                // 1. User PICS app entry
                ContentValues userApp = new ContentValues();
                userApp.put("user_id", userId);
                userApp.put("app_id", appId);
                userApp.put("change_number", 40000000);
                userApp.put("access_token", 0L);
                pdb.insertWithOnConflict("t_steam_user_pics_apps", null, userApp, SQLiteDatabase.CONFLICT_REPLACE);

                // 2. App info
                String safeInstallDir = name.replaceAll("[^a-zA-Z0-9_\\- ]", "").trim();
                if (safeInstallDir.isEmpty()) safeInstallDir = "App_" + appId;
                ContentValues appInfo = new ContentValues();
                appInfo.put("app_id", appId);
                appInfo.put("name", name);
                appInfo.put("type", "game");
                appInfo.put("icon", "");
                appInfo.put("clienticon", "");
                appInfo.put("last_update_time", nowMs);
                appInfo.put(
                        "extra_maps",
                        "{\"buildId\":\"1000\",\"installdir\":\"" + safeInstallDir +
                        "\",\"buildIdTimeUpdated\":\"1700000000\",\"isfreeapp\":\"false\",\"developer\":\"\",\"publisher\":\"\",\"releaseDate\":\"0\"}"
                );
                pdb.insertWithOnConflict("t_steam_pics_app_info", null, appInfo, SQLiteDatabase.CONFLICT_REPLACE);

                // 3. Localized assets (exact schema columns: lang, name, library_*_path)
                ContentValues locAssets = new ContentValues();
                locAssets.put("app_id", appId);
                locAssets.put("lang", "en");
                locAssets.put("name", name);
                locAssets.put("extra_maps", "{}");
                locAssets.put("library_hero_path", "library_hero.jpg");
                locAssets.put("library_logo_path", "logo.png");
                locAssets.put("library_logo_path_2x", "logo_2x.png");
                locAssets.put("library_capsule_path", "library_600x900.jpg");
                locAssets.put("library_capsule_path_2x", "library_600x900_2x.jpg");
                pdb.insertWithOnConflict("t_steam_user_pics_app_info_loc_assets", null, locAssets, SQLiteDatabase.CONFLICT_REPLACE);

                // 4. Synthetic package license & package->app/depot mapping
                pdb.execSQL(
                        "DELETE FROM t_steam_user_package_license WHERE user_id = ? AND package_id = ?",
                        new Object[]{userId, pkgId}
                );
                ContentValues lic = new ContentValues();
                lic.put("user_id", userId);
                lic.put("package_id", pkgId);
                lic.put("time_created", nowMs);
                lic.put("time_next_process", 0L);
                lic.put("minute_limit", 0);
                lic.put("minutes_used", 0);
                lic.put("payment_method", 1024);
                lic.put("flags", 512);
                lic.put("purchase_country_code", "IT");
                lic.put("license_type", 1);
                lic.put("territory_code", 0);
                lic.put("change_number", 40000000);
                lic.put("owner_id", 0);
                lic.put("access_token", 0L);
                lic.put("master_package_id", 0);
                pdb.insertOrThrow("t_steam_user_package_license", null, lic);

                ContentValues pkgApp = new ContentValues();
                pkgApp.put("package_id", pkgId);
                pkgApp.put("app_id", appId);
                pdb.insertWithOnConflict("t_steam_user_pics_package_info_apps", null, pkgApp, SQLiteDatabase.CONFLICT_REPLACE);

                ContentValues userPkg = new ContentValues();
                userPkg.put("user_id", userId);
                userPkg.put("package_id", pkgId);
                userPkg.put("change_number", 40000000);
                userPkg.put("access_token", 0L);
                pdb.insertWithOnConflict("t_steam_user_pics_packages", null, userPkg, SQLiteDatabase.CONFLICT_REPLACE);

                ContentValues pkgInfo = new ContentValues();
                pkgInfo.put("user_id", userId);
                pkgInfo.put("package_id", pkgId);
                pkgInfo.put("last_update_time", nowMs);
                pkgInfo.put("extra_maps", "{\"billingType\":\"10\",\"startTime\":\"0\",\"expiryTime\":\"0\",\"freePromotion\":\"false\"}");
                pdb.insertWithOnConflict("t_steam_user_pics_package_info", null, pkgInfo, SQLiteDatabase.CONFLICT_REPLACE);

                // 5. Parse addappid(depotId, 1, "hexKey") from luaContent
                Pattern keyPat = Pattern.compile("addappid\\(\\s*(\\d+)\\s*,\\s*\\d+\\s*,\\s*\"([0-9a-fA-F]+)\"");
                Matcher km = keyPat.matcher(luaContent);
                while (km.find()) {
                    long depotId = Long.parseLong(km.group(1));
                    String keyHex = km.group(2);
                    if (depotId != appId) {
                        ContentValues pkgDepot = new ContentValues();
                        pkgDepot.put("package_id", pkgId);
                        pkgDepot.put("depot_id", depotId);
                        pdb.insertWithOnConflict("t_steam_user_pics_package_info_depots", null, pkgDepot, SQLiteDatabase.CONFLICT_REPLACE);

                        pdb.execSQL(
                                "DELETE FROM t_steam_depot_decryption_key WHERE app_id = ? AND depot_id = ?",
                                new Object[]{appId, depotId}
                        );
                        ContentValues dk = new ContentValues();
                        dk.put("app_id", appId);
                        dk.put("depot_id", depotId);
                        dk.put("depot_decryption_key", keyHex);
                        dk.put("modify_time", nowMs);
                        dk.put("extra_maps", "");
                        pdb.insertOrThrow("t_steam_depot_decryption_key", null, dk);
                    }
                }

                // 6. Protect injected rows from being wiped by BannerHub's Steam license sync
                pdb.execSQL(
                        "INSERT OR REPLACE INTO t_luatools_keep (app_id, package_id) VALUES (?, ?)",
                        new Object[]{appId, pkgId}
                );
                pdb.execSQL(
                        "CREATE TRIGGER IF NOT EXISTS trg_lt_keep_lic " +
                        "BEFORE DELETE ON t_steam_user_package_license " +
                        "WHEN OLD.package_id IN (SELECT package_id FROM t_luatools_keep) " +
                        "BEGIN SELECT RAISE(IGNORE); END;"
                );
                pdb.execSQL(
                        "CREATE TRIGGER IF NOT EXISTS trg_lt_keep_apps " +
                        "BEFORE DELETE ON t_steam_user_pics_apps " +
                        "WHEN OLD.app_id IN (SELECT app_id FROM t_luatools_keep) " +
                        "BEGIN SELECT RAISE(IGNORE); END;"
                );
                pdb.execSQL(
                        "CREATE TRIGGER IF NOT EXISTS trg_lt_keep_pkgs " +
                        "BEFORE DELETE ON t_steam_user_pics_packages " +
                        "WHEN OLD.package_id IN (SELECT package_id FROM t_luatools_keep) " +
                        "BEGIN SELECT RAISE(IGNORE); END;"
                );
                pdb.execSQL(
                        "CREATE TRIGGER IF NOT EXISTS trg_lt_keep_pkginfo " +
                        "BEFORE DELETE ON t_steam_user_pics_package_info " +
                        "WHEN OLD.package_id IN (SELECT package_id FROM t_luatools_keep) " +
                        "BEGIN SELECT RAISE(IGNORE); END;"
                );

                pdb.setTransactionSuccessful();
            } finally {
                if (pdb.inTransaction()) pdb.endTransaction();
                pdb.close();
            }
        }
    }

    private static int installJavaSteamManifests(
            Context ctx,
            int appId,
            String luaContent,
            Bundle extras,
            java.util.Map<String, long[]> manifestSizesOut
    ) {
        int cachedCount = 0;
        try {
            // Map depotId -> depotKeyHex from addappid(depotId, 1, "hexKey")
            java.util.Map<String, String> depotKeys = new java.util.HashMap<>();
            Pattern keyPat = Pattern.compile("addappid\\(\\s*(\\d+)\\s*,\\s*\\d+\\s*,\\s*\"([0-9a-fA-F]+)\"");
            Matcher km = keyPat.matcher(luaContent);
            while (km.find()) {
                depotKeys.put(km.group(1), km.group(2));
            }

            // Collect (depotId, manifestId) from setManifestid(depotId, "manifestId", ...)
            java.util.Map<String, String> depotManifests = new java.util.LinkedHashMap<>();
            Pattern manPat = Pattern.compile("setManifestid\\(\\s*(\\d+)\\s*,\\s*\"(\\d+)\"");
            Matcher mm = manPat.matcher(luaContent);
            while (mm.find()) {
                depotManifests.put(mm.group(1), mm.group(2));
            }

            if (depotManifests.isEmpty()) return 0;

            File bhCacheDir = new File(ctx.getFilesDir(), "steam_download_cache/manifest");
            if (!bhCacheDir.exists()) bhCacheDir.mkdirs();
            File steamDepotCacheDir = new File(ctx.getFilesDir(), "Steam/depotcache");
            if (!steamDepotCacheDir.exists()) steamDepotCacheDir.mkdirs();

            Class<?> dmClass = null;
            java.lang.reflect.Method deserializeMethod = null;
            java.lang.reflect.Method decryptMethod = null;
            java.lang.reflect.Method saveToFileMethod = null;
            java.lang.reflect.Method getUncompressedSizeMethod = null;
            java.lang.reflect.Method getCompressedSizeMethod = null;
            try {
                dmClass = Class.forName("in.dragonbra.javasteam.types.DepotManifest");
                deserializeMethod = dmClass.getMethod("deserialize", byte[].class);
                decryptMethod = dmClass.getMethod("decryptFilenames", byte[].class);
                saveToFileMethod = dmClass.getMethod("saveToFile", String.class);
                getUncompressedSizeMethod = dmClass.getMethod("getTotalUncompressedSize");
                getCompressedSizeMethod = dmClass.getMethod("getTotalCompressedSize");
            } catch (Throwable ignored) {
            }

            for (java.util.Map.Entry<String, String> entry : depotManifests.entrySet()) {
                String depotId = entry.getKey();
                String manifestId = entry.getValue();
                byte[] rawBytes = null;

                if (extras != null) {
                    rawBytes = extras.getByteArray("manifest_bytes_" + depotId + "_" + manifestId);
                }
                File stdFile = new File(steamDepotCacheDir, depotId + "_" + manifestId + ".manifest");
                if ((rawBytes == null || rawBytes.length == 0) && stdFile.isFile() && stdFile.length() > 0) {
                    rawBytes = readAllBytes(stdFile);
                }
                if (rawBytes == null || rawBytes.length == 0) {
                    File extFile = new File("/sdcard/Games/LuaTools/depotcache/" + depotId + "_" + manifestId + ".manifest");
                    if (extFile.isFile() && extFile.length() > 0) {
                        rawBytes = readAllBytes(extFile);
                    }
                }
                if (rawBytes == null || rawBytes.length == 0) continue;

                // Always ensure raw .manifest is in Steam/depotcache/<depotId>_<manifestId>.manifest
                if (!stdFile.isFile() || stdFile.length() != rawBytes.length) {
                    writeAllBytes(stdFile, rawBytes);
                }

                // Target file read by JavaSteam / BannerHub DepotManifestFetcher:
                // files/steam_download_cache/manifest/<manifestId> (no extension)
                File cacheFile = new File(bhCacheDir, manifestId);

                if (dmClass != null && deserializeMethod != null && saveToFileMethod != null) {
                    try {
                        Object dm = deserializeMethod.invoke(null, (Object) rawBytes);
                        String keyHex = depotKeys.get(depotId);
                        if (keyHex != null && keyHex.length() == 64 && decryptMethod != null) {
                            byte[] keyBytes = hexToBytes(keyHex);
                            decryptMethod.invoke(dm, (Object) keyBytes);
                        }
                        try {
                            Object files = dmClass.getMethod("getFiles").invoke(dm);
                            for (Object fd : (java.util.List<?>) files) {
                                Object fn = fd.getClass().getMethod("getFileName").invoke(fd);
                                if (fn != null) manifestFileNames.add(fn.toString().replace('\\', '/'));
                            }
                        } catch (Throwable ignored2) {
                        }
                        saveToFileMethod.invoke(dm, cacheFile.getAbsolutePath());
                        if (getUncompressedSizeMethod != null && getCompressedSizeMethod != null) {
                            long uncomp = ((Number) getUncompressedSizeMethod.invoke(dm)).longValue();
                            long comp = ((Number) getCompressedSizeMethod.invoke(dm)).longValue();
                            if (uncomp > 0) {
                                manifestSizesOut.put(depotId, new long[]{uncomp, comp > 0 ? comp : uncomp});
                            }
                        }
                        cachedCount++;
                        continue;
                    } catch (Throwable ignored) {
                    }
                }

                // Fallback if reflection fails: write raw bytes directly
                writeAllBytes(cacheFile, rawBytes);
                cachedCount++;
            }
        } catch (Exception ignored) {
        }
        return cachedCount;
    }


    private static String depotOs(String lua, String depotId) {
        Matcher m = Pattern.compile("addappid\\(\\s*" + depotId + "\\b[^\\n]*?--\\s*\\(([^)]*)\\)").matcher(lua);
        if (m.find()) {
            String t = m.group(1).toLowerCase();
            if (!t.contains("windows")) {
                if (t.contains("linux")) return "linux";
                if (t.contains("mac")) return "macos";
            }
        }
        return "windows";
    }

    private static void fixComponentUrls(Context ctx) {
        try {
            File f = new File(ctx.getApplicationInfo().dataDir, "shared_prefs/sp_winemu_all_components12.xml");
            if (!f.isFile()) return;
            String x = new String(readAllBytes(f), "UTF-8");
            String y = x.replace("https://qua-zone-cdn-download.bigeyes.com/", "https://uxdl.mac520.com/");
            if (!y.equals(x)) writeAllBytes(f, y.getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    private static boolean badExe(String n) {
        String l = n.toLowerCase();
        String[] bad = {"unins", "crashhandler", "crashpad", "vc_redist", "vcredist", "dxsetup", "dotnet",
                "ue4prereq", "directx", "unitycrashhandler", "setup", "redist", "launcher_helper"};
        for (String b : bad) if (l.contains(b)) return true;
        return false;
    }

    private static String detectExecutable(Context ctx, String name, java.util.List<String> names) {
        ArrayList<String> exes = new ArrayList<>();
        for (String n : names) if (n.toLowerCase().endsWith(".exe") && !badExe(n)) exes.add(n);
        if (exes.isEmpty()) {
            try {
                String dir = name.replaceAll("[^a-zA-Z0-9_\\- ]", "").trim();
                File root = new File(ctx.getFilesDir(), "Steam/steamapps/common/" + dir);
                collectExes(root, root, exes, 0);
            } catch (Throwable ignored) {
            }
        }
        if (exes.isEmpty()) return null;
        String key = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String e : exes) {
            String base = e.substring(e.lastIndexOf('/') + 1).toLowerCase().replace(".exe", "").replaceAll("[^a-z0-9]", "");
            int score = -e.split("/").length * 10;
            if (!base.isEmpty() && (key.contains(base) || base.contains(key))) score += 50;
            if (base.contains("64")) score += 1;
            if (score > bestScore) { bestScore = score; best = e; }
        }
        return best;
    }

    private static void collectExes(File root, File dir, ArrayList<String> out, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null || depth > 3) return;
        for (File f : fs) {
            if (f.isDirectory()) collectExes(root, f, out, depth + 1);
            else if (f.getName().toLowerCase().endsWith(".exe") && !badExe(f.getName())) {
                out.add(f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1).replace('\\', '/'));
            }
        }
    }

    private static void patchExecutable(Context ctx, int appId, String exe) {
        String appStr = String.valueOf(appId);
        for (String db : new String[]{"xj_steam_db", "ux_db"}) {
            try {
                File f = ctx.getDatabasePath(db);
                if (!f.exists()) continue;
                android.database.sqlite.SQLiteDatabase d = android.database.sqlite.SQLiteDatabase.openDatabase(
                        f.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
                try {
                    d.execSQL(
                            "UPDATE steam_download_entity SET extend = replace(extend, '\"executable\":\"game.exe\"', ?) WHERE steam_appid = ? AND extend LIKE '%game.exe%'",
                            new Object[]{"\"executable\":\"" + exe + "\"", appId}
                    );
                } finally {
                    d.close();
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("gs_steam_install_games_sp", Context.MODE_PRIVATE);
            String v = sp.getString(appStr, null);
            if (v != null && v.contains("game.exe")) {
                String nv = v.replace("executable\":\"game.exe", "executable\":\"" + exe)
                        .replace("executable\\\":\\\"game.exe", "executable\\\":\\\"" + exe);
                sp.edit().putString(appStr, nv).commit();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Real Steam product info (PICS) as JSON, so the game looks exactly like a normal Steam game. */
    private static org.json.JSONObject fetchPics(int appId) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL("https://api.steamcmd.net/v1/info/" + appId).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            org.json.JSONObject root = new org.json.JSONObject(bos.toString("UTF-8"));
            org.json.JSONObject data = root.optJSONObject("data");
            org.json.JSONObject app = data != null ? data.optJSONObject(String.valueOf(appId)) : null;
            if (app == null || app.optJSONObject("config") == null || app.optJSONObject("depots") == null) return null;
            return app;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] buildVdfFromPics(int appId, org.json.JSONObject app, String lua,
                                           java.util.Map<String, long[]> manifestSizes) {
        try {
            // depots we hold keys for + manifest ids from the lua
            java.util.Set<String> keyed = new java.util.HashSet<>();
            Matcher km = Pattern.compile("addappid\\(\\s*(\\d+)\\s*,\\s*\\d+\\s*,\\s*\"([0-9a-fA-F]+)\"").matcher(lua);
            while (km.find()) keyed.add(km.group(1));
            java.util.Map<String, String> gids = new java.util.HashMap<>();
            Matcher mm = Pattern.compile("setManifestid\\(\\s*(\\d+)\\s*,\\s*\"(\\d+)\"").matcher(lua);
            while (mm.find()) gids.put(mm.group(1), mm.group(2));

            org.json.JSONObject depots = app.getJSONObject("depots");
            org.json.JSONObject outDepots = new org.json.JSONObject();
            java.util.Iterator<String> it = depots.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = depots.get(k);
                if (k.matches("\\d+")) {
                    if (!keyed.isEmpty() && !keyed.contains(k)) continue;
                    if (!(v instanceof org.json.JSONObject)) continue;
                    org.json.JSONObject d = new org.json.JSONObject(v.toString());
                    org.json.JSONObject mans = d.optJSONObject("manifests");
                    org.json.JSONObject pub = mans != null ? mans.optJSONObject("public") : null;
                    String g = gids.get(k);
                    if (g != null) {
                        if (mans == null) { mans = new org.json.JSONObject(); d.put("manifests", mans); }
                        if (pub == null) { pub = new org.json.JSONObject(); mans.put("public", pub); }
                        pub.put("gid", g);
                        long[] sz = manifestSizes != null ? manifestSizes.get(k) : null;
                        if (sz != null && sz[0] > 0) {
                            pub.put("size", String.valueOf(sz[0]));
                            pub.put("download", String.valueOf(sz[1] > 0 ? sz[1] : sz[0]));
                        }
                    }
                    outDepots.put(k, d);
                } else {
                    outDepots.put(k, v);
                }
            }

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            vdfBegin(out, "appinfo");
            vdfStr(out, "appid", String.valueOf(appId));
            String[] sections = {"common", "extended", "config"};
            for (String sec : sections) {
                org.json.JSONObject o = app.optJSONObject(sec);
                if (o != null) writeJsonVdf(out, sec, o);
            }
            writeJsonVdf(out, "depots", outDepots);
            vdfEnd(out);
            vdfEnd(out);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeJsonVdf(java.io.ByteArrayOutputStream out, String key, org.json.JSONObject o) throws Exception {
        vdfBegin(out, key);
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.get(k);
            if (v instanceof org.json.JSONObject) writeJsonVdf(out, k, (org.json.JSONObject) v);
            else if (v instanceof org.json.JSONArray || v == org.json.JSONObject.NULL) continue;
            else vdfStr(out, k, String.valueOf(v));
        }
        vdfEnd(out);
    }
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static byte[] readAllBytes(File f) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        } finally {
            fis.close();
        }
    }

    private static void writeAllBytes(File f, byte[] data) throws Exception {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
    }

    private static void writeAppInfoVdf(
            Context ctx,
            int appId,
            String name,
            String luaContent,
            java.util.Map<String, long[]> manifestSizes,
            String exe
    ) {
        try {
            File appInfoDir = new File(ctx.getFilesDir(), "Steam/steamapps/app_info");
            if (!appInfoDir.exists()) appInfoDir.mkdirs();
            File vdfFile = new File(appInfoDir, "app_" + appId + ".vdf");

            org.json.JSONObject pics = fetchPics(appId);
            if (pics != null) {
                byte[] real = buildVdfFromPics(appId, pics, luaContent, manifestSizes);
                if (real != null) {
                    writeAllBytes(vdfFile, real);
                    return;
                }
            }

            String safeInstallDir = name.replaceAll("[^a-zA-Z0-9_\\- ]", "").trim();
            if (safeInstallDir.isEmpty()) safeInstallDir = "App_" + appId;

            // Collect setManifestid(depotId, "manifestId", size) (even if commented with --)
            java.util.LinkedHashMap<String, String[]> manifests = new java.util.LinkedHashMap<>();
            Pattern manPat = Pattern.compile("setManifestid\\(\\s*(\\d+)\\s*,\\s*\"(\\d+)\"(?:\\s*,\\s*(\\d+))?");
            Matcher mm = manPat.matcher(luaContent);
            while (mm.find()) {
                String depotId = mm.group(1);
                String gid = mm.group(2);
                String size = mm.group(3);
                String downloadSize = size;
                long[] actualSizes = manifestSizes != null ? manifestSizes.get(depotId) : null;
                if (actualSizes != null && actualSizes[0] > 0) {
                    if (size == null || size.isEmpty()) size = String.valueOf(actualSizes[0]);
                    downloadSize = String.valueOf(actualSizes[1] > 0 ? actualSizes[1] : actualSizes[0]);
                } else {
                    if (size == null || size.isEmpty()) size = "1073741824";
                    downloadSize = size;
                }
                manifests.put(depotId, new String[]{gid, size, downloadSize});
            }

            // Fallback: if no setManifestid found, add any depot from addappid(depotId, 1, "key")
            if (manifests.isEmpty()) {
                Pattern keyPat = Pattern.compile("addappid\\(\\s*(\\d+)\\s*,\\s*\\d+\\s*,\\s*\"([0-9a-fA-F]+)\"");
                Matcher km = keyPat.matcher(luaContent);
                while (km.find()) {
                    String depotId = km.group(1);
                    if (!depotId.equals(String.valueOf(appId))) {
                        manifests.put(depotId, new String[]{"0", "1073741824", "1073741824"});
                    }
                }
            }

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            vdfBegin(out, "appinfo");
            vdfStr(out, "appid", String.valueOf(appId));

            vdfBegin(out, "common");
            vdfStr(out, "name", name);
            vdfStr(out, "type", "Game");
            vdfStr(out, "oslist", "windows");
            vdfStr(out, "releasestate", "released");
            vdfEnd(out); // common

            vdfBegin(out, "config");
            vdfStr(out, "contenttype", "3");
            vdfStr(out, "installdir", safeInstallDir);
            vdfBegin(out, "launch");
            vdfBegin(out, "0");
            vdfStr(out, "executable", exe != null ? exe : "game.exe");
            vdfStr(out, "description", "Launch");
            vdfEnd(out); // 0
            vdfEnd(out); // launch
            vdfEnd(out); // config

            vdfBegin(out, "depots");
            vdfStr(out, "baselanguages", "english");
            for (java.util.Map.Entry<String, String[]> entry : manifests.entrySet()) {
                String depotId = entry.getKey();
                String gid = entry.getValue()[0];
                String size = entry.getValue()[1];
                String dlSize = entry.getValue().length > 2 ? entry.getValue()[2] : size;

                vdfBegin(out, depotId);
                vdfBegin(out, "config");
                vdfStr(out, "oslist", depotOs(luaContent, depotId));
                vdfEnd(out); // config
                vdfBegin(out, "manifests");
                vdfBegin(out, "public");
                vdfStr(out, "gid", gid);
                vdfStr(out, "size", size);
                vdfStr(out, "download", dlSize);
                vdfEnd(out); // public
                vdfEnd(out); // manifests
                vdfEnd(out); // depotId
            }
            vdfBegin(out, "branches");
            vdfBegin(out, "public");
            vdfStr(out, "buildid", "1000");
            vdfStr(out, "timeupdated", "1700000000");
            vdfEnd(out); // public
            vdfEnd(out); // branches
            vdfEnd(out); // depots

            vdfEnd(out); // appinfo
            vdfEnd(out); // root terminator

            java.io.FileOutputStream fos = new java.io.FileOutputStream(vdfFile);
            fos.write(out.toByteArray());
            fos.close();
        } catch (Exception ignored) {
        }
    }

    private static void vdfBegin(java.io.ByteArrayOutputStream out, String name) throws Exception {
        out.write(0x00);
        out.write(name.getBytes("UTF-8"));
        out.write(0x00);
    }

    private static void vdfStr(java.io.ByteArrayOutputStream out, String key, String val) throws Exception {
        out.write(0x01);
        out.write(key.getBytes("UTF-8"));
        out.write(0x00);
        out.write((val != null ? val : "").getBytes("UTF-8"));
        out.write(0x00);
    }

    private static void vdfEnd(java.io.ByteArrayOutputStream out) {
        out.write(0x08);
    }

    private static void collectPrefixes(File dir, ArrayList<String> out, int depth) {
        if (depth > 3 || dir == null || !dir.isDirectory()) return;
        File driveC = new File(dir, "drive_c");
        if (driveC.isDirectory()) {
            out.add(dir.getAbsolutePath());
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectPrefixes(child, out, depth + 1);
            }
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
